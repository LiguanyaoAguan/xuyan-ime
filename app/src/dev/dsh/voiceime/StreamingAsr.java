package dev.dsh.voiceime;

import android.content.Context;
import android.util.Log;

import com.k2fsa.sherpa.onnx.EndpointConfig;
import com.k2fsa.sherpa.onnx.EndpointRule;
import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.HomophoneReplacerConfig;
import com.k2fsa.sherpa.onnx.OnlineCtcFstDecoderConfig;
import com.k2fsa.sherpa.onnx.OnlineLMConfig;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineNeMoCtcModelConfig;
import com.k2fsa.sherpa.onnx.OnlineParaformerModelConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizer;
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineToneCtcModelConfig;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;
import com.k2fsa.sherpa.onnx.OnlineZipformer2CtcModelConfig;
import com.k2fsa.sherpa.onnx.QnnConfig;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 流式（边说边出字）识别引擎。
 *
 * 关键设计：**解码跑在自己的线程里**。
 * 之前解码写在录音回调里，解码一慢就把 AudioRecord 的读取堵住，音频越积越多，
 * 表现出来就是"不跟手"。现在录音线程只负责入队，解码线程自己取。
 *
 * 自动识别两种模型结构：
 *   · transducer：encoder*.onnx + decoder*.onnx + joiner*.onnx（大模型，更准）
 *   · ctc：       model.int8.onnx（小模型，更快）
 */
public class StreamingAsr {

    private static final String TAG = "VimStream";
    public static final int SAMPLE_RATE = 16000;

    private static StreamingAsr INSTANCE;

    private final Context ctx;
    private volatile OnlineRecognizer rec;
    private volatile OnlineStream stream;
    private volatile boolean ready;
    private volatile boolean loading;
    private volatile String loadError;
    private volatile String latest = "";

    private Thread worker;
    private volatile boolean running;
    private final List<float[]> queue = new ArrayList<>();

    private StreamingAsr(Context c) { this.ctx = c.getApplicationContext(); }

    public static synchronized StreamingAsr get(Context c) {
        if (INSTANCE == null) INSTANCE = new StreamingAsr(c);
        return INSTANCE;
    }

    public File modelDir() {
        return new File(ctx.getFilesDir(), "models-stream");
    }

    public boolean modelPresent() {
        File d = modelDir();
        boolean ctc = new File(d, "model.int8.onnx").isFile();
        boolean trans = new File(d, "encoder.int8.onnx").isFile();
        return (ctc || trans) && new File(d, "tokens.txt").isFile();
    }

    public boolean isReady() { return ready; }
    public String loadError() { return loadError; }
    public String latest() { return latest; }

    // ---------------------------------------------------------------- 加载

    public synchronized void warmUp() {
        if (ready || loading) return;
        loading = true;
        new Thread(() -> {
            try {
                File d = modelDir();
                if (!modelPresent()) throw new IllegalStateException("流式模型缺失：" + d);
                long t0 = System.currentTimeMillis();

                FeatureConfig feat = new FeatureConfig(SAMPLE_RATE, 80, 0f);
                File enc = new File(d, "encoder.int8.onnx");
                boolean transducer = enc.isFile();
                String tokens = new File(d, "tokens.txt").getAbsolutePath();

                OnlineModelConfig mc;
                if (transducer) {
                    mc = new OnlineModelConfig(
                            new OnlineTransducerModelConfig(
                                    enc.getAbsolutePath(),
                                    new File(d, "decoder.onnx").getAbsolutePath(),
                                    new File(d, "joiner.int8.onnx").getAbsolutePath(),
                                    new QnnConfig("", "", "")),
                            new OnlineParaformerModelConfig(),
                            new OnlineZipformer2CtcModelConfig(""),
                            new OnlineNeMoCtcModelConfig(),
                            new OnlineToneCtcModelConfig(),
                            tokens, 6, false, "cpu", "", "", "");
                } else {
                    mc = new OnlineModelConfig(
                            new OnlineTransducerModelConfig(),
                            new OnlineParaformerModelConfig(),
                            new OnlineZipformer2CtcModelConfig(
                                    new File(d, "model.int8.onnx").getAbsolutePath()),
                            new OnlineNeMoCtcModelConfig(),
                            new OnlineToneCtcModelConfig(),
                            tokens, 6, false, "cpu", "", "", "");
                }

                EndpointConfig ep = new EndpointConfig(
                        new EndpointRule(false, 2.4f, 0f),
                        new EndpointRule(true, 1.2f, 0f),
                        new EndpointRule(false, 0f, 20f));

                OnlineRecognizerConfig rc = new OnlineRecognizerConfig(
                        feat, mc,
                        new OnlineLMConfig("", 0f),
                        new OnlineCtcFstDecoderConfig("", 0),
                        new HomophoneReplacerConfig("", "", ""),
                        ep,
                        false,                    // 断句交给用户松手
                        "greedy_search",
                        4,
                        "", 1.5f,
                        "", "",
                        0f);

                rec = new OnlineRecognizer(null, rc);
                ready = true;
                loadError = null;
                Log.i(TAG, (transducer ? "transducer" : "ctc") + " 流式模型就绪，用时 "
                        + (System.currentTimeMillis() - t0) + " ms");
            } catch (Throwable e) {
                loadError = e.getClass().getSimpleName() + ": " + e.getMessage();
                Log.e(TAG, "流式模型加载失败", e);
            } finally {
                loading = false;
            }
        }, "asr-stream-warmup").start();
    }

    // ---------------------------------------------------------------- 识别

    /** 开始一次识别（录音开始时调用）：建流 + 启动解码线程 */
    public void start() {
        if (!ready || rec == null) return;
        releaseStream();
        try {
            stream = rec.createStream("");
        } catch (Throwable e) {
            Log.e(TAG, "创建流失败", e);
            return;
        }
        latest = "";
        synchronized (queue) { queue.clear(); }
        running = true;
        worker = new Thread(this::loop, "asr-stream-decode");
        worker.start();
    }

    /** 录音线程调：只入队，绝不阻塞 */
    public void push(float[] chunk) {
        if (!running || chunk == null || chunk.length == 0) return;
        synchronized (queue) {
            if (queue.size() > 200) queue.remove(0);
            queue.add(chunk);
        }
    }

    /** 解码线程 */
    private void loop() {
        while (running) {
            float[] chunk = null;
            synchronized (queue) {
                if (!queue.isEmpty()) chunk = queue.remove(0);
            }
            if (chunk == null) {
                try { Thread.sleep(5); } catch (InterruptedException e) { break; }
                continue;
            }
            OnlineStream s = stream;
            if (s == null || rec == null) break;
            try {
                s.acceptWaveform(chunk, SAMPLE_RATE);
                while (rec.isReady(s)) rec.decode(s);
                String t = rec.getResult(s).getText();
                if (t != null && !t.isEmpty()) latest = t;
            } catch (Throwable e) {
                Log.e(TAG, "流式解码失败", e);
                break;
            }
        }
    }

    /** 结束：把剩余音频解完，返回最终草稿 */
    public String finish() {
        running = false;
        Thread w = worker;
        if (w != null) {
            try { w.join(1500); } catch (InterruptedException ignored) { }
        }
        worker = null;

        OnlineStream s = stream;
        String out = latest;
        if (s != null && rec != null) {
            try {
                while (true) {
                    float[] chunk = null;
                    synchronized (queue) {
                        if (!queue.isEmpty()) chunk = queue.remove(0);
                    }
                    if (chunk == null) break;
                    s.acceptWaveform(chunk, SAMPLE_RATE);
                    while (rec.isReady(s)) rec.decode(s);
                }
                s.inputFinished();
                while (rec.isReady(s)) rec.decode(s);
                String t = rec.getResult(s).getText();
                if (t != null && !t.isEmpty()) out = t;
            } catch (Throwable e) {
                Log.e(TAG, "流式收尾失败", e);
            }
        }
        releaseStream();
        latest = "";
        return out == null ? "" : out;
    }

    private void releaseStream() {
        try {
            if (stream != null) stream.release();
        } catch (Throwable ignored) { }
        stream = null;
    }

    public synchronized void release() {
        running = false;
        releaseStream();
        try { if (rec != null) rec.release(); } catch (Throwable ignored) { }
        rec = null;
        ready = false;
    }
}
