package dev.dsh.voiceime;

import android.content.Context;
import android.util.Log;

import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.HomophoneReplacerConfig;
import com.k2fsa.sherpa.onnx.OfflineCanaryModelConfig;
import com.k2fsa.sherpa.onnx.OfflineCohereTranscribeModelConfig;
import com.k2fsa.sherpa.onnx.OfflineDolphinModelConfig;
import com.k2fsa.sherpa.onnx.OfflineFireRedAsrCtcModelConfig;
import com.k2fsa.sherpa.onnx.OfflineFireRedAsrModelConfig;
import com.k2fsa.sherpa.onnx.OfflineFunAsrNanoModelConfig;
import com.k2fsa.sherpa.onnx.OfflineMedAsrCtcModelConfig;
import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig;
import com.k2fsa.sherpa.onnx.OfflineOmnilingualAsrCtcModelConfig;
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig;
import com.k2fsa.sherpa.onnx.OfflineQwen3AsrModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig;
import com.k2fsa.sherpa.onnx.OfflineStream;
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig;
import com.k2fsa.sherpa.onnx.OfflineWenetCtcModelConfig;
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig;
import com.k2fsa.sherpa.onnx.OfflineZipformerCtcModelConfig;
import com.k2fsa.sherpa.onnx.QnnConfig;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 本机离线语音识别引擎（sherpa-onnx + SenseVoice int8）。
 *
 * 完全离线：模型放在本 App 的应用专属外部目录，不联网、不上传。
 * 预热：切到本输入法时由 VoiceImeService 在后台线程调用 warmUp()，
 *       用户第一次按麦克风时模型已就绪，无需等待加载。
 */
public class AsrEngine {

    private static final String TAG = "VimAsr";
    public static final int SAMPLE_RATE = 16000;
    /** 能量分段阈值（归一化幅度的比例），低于它算静音 */
    private static final float SILENCE_RATIO = 0.06f;
    private static final int MIN_SILENCE_MS = 350;
    private static final int MIN_SEG_MS = 250;

    private static AsrEngine INSTANCE;

    private final Context ctx;
    private volatile OfflineRecognizer rec;
    private volatile boolean loading;
    private volatile String loadError;
    private volatile boolean ready;

    private AsrEngine(Context c) {
        this.ctx = c.getApplicationContext();
    }

    public static synchronized AsrEngine get(Context c) {
        if (INSTANCE == null) INSTANCE = new AsrEngine(c);
        return INSTANCE;
    }

    /**
     * 模型目录。
     *
     * 优先用**应用内部存储**（getFilesDir）。
     * 原因（实测踩坑）：外部目录 /sdcard/Android/data/<pkg>/files 走 FUSE，
     * 归属校验会拒绝读取「非本 App 创建」的文件；用 adb/shell 预置的模型
     * 在 App 看来是「存在但不可读」。内部存储是普通文件系统，没有这个问题。
     */
    public File modelDir() {
        File in = new File(ctx.getFilesDir(), "models");
        if (new File(in, "model.int8.onnx").isFile()) return in;
        File ex = new File(ctx.getExternalFilesDir(null), "models");
        if (new File(ex, "model.int8.onnx").isFile()) return ex;
        return in;                       // 都没有时默认指向内部，便于导错提示
    }

    public boolean modelPresent() {
        File d = modelDir();
        return new File(d, "model.int8.onnx").isFile() && new File(d, "tokens.txt").isFile();
    }

    public boolean isReady() { return ready; }
    public boolean isLoading() { return loading; }
    public String loadError() { return loadError; }

    /** 后台预热；可在任意线程重复调用，只会加载一次 */
    public synchronized void warmUp() {
        if (ready || loading) return;
        loading = true;
        new Thread(() -> {
            try {
                if (!modelPresent()) throw new IllegalStateException("模型文件缺失：" + modelDir());
                long t0 = System.currentTimeMillis();
                OfflineRecognizer r = build();
                rec = r;
                ready = true;
                loadError = null;
                Log.i(TAG, "模型就绪，用时 " + (System.currentTimeMillis() - t0) + " ms");
            } catch (Throwable e) {
                loadError = e.getClass().getSimpleName() + ": " + e.getMessage();
                Log.e(TAG, "模型加载失败", e);
            } finally {
                loading = false;
            }
        }, "asr-warmup").start();
    }

    private OfflineRecognizer build() {
        File d = modelDir();
        String model = new File(d, "model.int8.onnx").getAbsolutePath();
        String tokens = new File(d, "tokens.txt").getAbsolutePath();

        FeatureConfig feat = new FeatureConfig(SAMPLE_RATE, 80, 0f);

        OfflineSenseVoiceModelConfig sv = new OfflineSenseVoiceModelConfig(
                model,          // model
                "zh",           // language：auto 也可，zh 更稳
                true,           // useInverseTextNormalization：把「二零二六」规整成「2026」
                new QnnConfig("", "", ""));

        // 注意：OfflineModelConfig 共 25 个参数，顺序已从字节码 putfield 逐个核对。
        // 未使用的模型槽位必须传**空对象**：Kotlin 侧这些参数声明为非空，
        // 传 null 会在构造期抛 NullPointerException（parameter transducer），
        // 不是模型文件的问题，也不是参数顺序的问题。
        OfflineModelConfig mc = new OfflineModelConfig(
                new OfflineTransducerModelConfig(),      // 1  transducer
                new OfflineParaformerModelConfig(),      // 2  paraformer
                new OfflineWhisperModelConfig(),         // 3  whisper
                new OfflineFireRedAsrModelConfig(),      // 4  fireRedAsr
                new OfflineMoonshineModelConfig(),       // 5  moonshine
                new OfflineNemoEncDecCtcModelConfig(),   // 6  nemo
                sv,                                      // 7  senseVoice  ← 本引擎用这个
                new OfflineDolphinModelConfig(),         // 8  dolphin
                new OfflineZipformerCtcModelConfig(),    // 9  zipformerCtc
                new OfflineWenetCtcModelConfig(),        // 10 wenetCtc
                new OfflineOmnilingualAsrCtcModelConfig(), // 11 omnilingual
                new OfflineMedAsrCtcModelConfig(),       // 12 medasr
                new OfflineFunAsrNanoModelConfig(),      // 13 funasrNano
                new OfflineQwen3AsrModelConfig(),        // 14 qwen3Asr
                new OfflineFireRedAsrCtcModelConfig(),   // 15 fireRedAsrCtc
                new OfflineCanaryModelConfig(),          // 16 canary
                new OfflineCohereTranscribeModelConfig(),// 17 cohereTranscribe
                "",     // 18 teleSpeech
                2,      // 19 numThreads
                false,  // 20 debug
                "cpu",  // 21 provider
                "",     // 22 modelType
                tokens, // 23 tokens  ← 词表路径
                "",     // 24 modelingUnit
                "");    // 25 bpeVocab

        OfflineRecognizerConfig rc = new OfflineRecognizerConfig(
                feat,
                mc,
                new HomophoneReplacerConfig("", "", ""),
                "greedy_search",   // decodingMethod
                4,                 // maxActivePaths
                "",                // hotwordsFile
                1.0f,              // hotwordsScore
                "",                // ruleFsts
                "",                // ruleFars
                0.0f);             // blankPenalty

        // 关键修复（实测 2026-10-18）：必须传 null，不能传 ctx.getAssets()。
        // sherpa-onnx 的 ReadFile 只要看到 assetManager 非 null，就会优先走「从 assets 读」，
        // 而这里的模型是绝对路径的普通文件 → 读不到 → 打 F 级日志并直接终止进程。
        // 表现：输入法一被选中，进程 die，系统重启，再 die，循环 —— 输入视图永远不显示。
        // 对应上游 issue：k2-fsa/sherpa-onnx#2562
        return new OfflineRecognizer(null, rc);
    }

    /**
     * 识别一段 16kHz 单声道 PCM。
     * 长音频先按静音切成小段分别识别再拼接——SenseVoice 对超长音频会掉准确率。
     */
    public String transcribe(float[] samples, int sampleRate) {
        if (samples == null || samples.length == 0) return "";
        OfflineRecognizer r = rec;
        if (r == null) return "";

        float[] pcm = preprocess(samples);

        // 关键改动：30 秒以内**整段识别**，不再按音量切句。
        // 之前每遇到 350ms 静音就切一刀，一句话被切成好几段分别识别，
        // SenseVoice 很依赖上下文，切碎后准确率和标点都会变差。
        List<float[]> segs;
        if (pcm.length <= 30 * sampleRate) {
            segs = new ArrayList<>(1);
            segs.add(pcm);
        } else {
            segs = split(pcm, sampleRate);
        }

        StringBuilder sb = new StringBuilder();
        for (float[] seg : segs) {
            String t = one(r, seg, sampleRate);
            if (t == null) continue;
            t = t.trim();
            if (t.isEmpty()) continue;
            if (sb.length() > 0 && !endsWithPunct(sb)) sb.append('，');
            sb.append(t);
        }
        return cleanPunct(sb.toString());
    }

    private static boolean endsWithPunct(StringBuilder sb) {
        if (sb.length() == 0) return false;
        char c = sb.charAt(sb.length() - 1);
        return "，。！？、；：,.!?;:".indexOf(c) >= 0;
    }

    /**
     * 音频预处理：去直流 + 峰值归一化。
     * 小声说话时电平偏低，int8 量化模型会明显退化；抬到 0.85 峰值能改善。
     * 太安静（峰值 < 0.01）不动，避免把底噪放大。
     */
    private static float[] preprocess(float[] x) {
        if (x == null || x.length == 0) return x;
        double sum = 0;
        for (float v : x) sum += v;
        float dc = (float) (sum / x.length);

        float peak = 0f;
        for (float v : x) {
            float a = Math.abs(v - dc);
            if (a > peak) peak = a;
        }
        float gain = 1f;
        if (peak > 0.01f && peak < 0.7f) gain = 0.85f / peak;

        float[] y = new float[x.length];
        for (int i = 0; i < x.length; i++) {
            float v = (x[i] - dc) * gain;
            if (v > 1f) v = 1f;
            if (v < -1f) v = -1f;
            y[i] = v;
        }
        if (gain != 1f) Log.i(TAG, String.format("音量归一化 peak=%.3f gain=%.2f", peak, gain));
        return y;
    }

    /** 清理模型输出的重复/错位标点，例如「。，」「，，」「，。」 */
    static String cleanPunct(String s) {
        if (s == null) return "";
        String t = s;
        t = t.replaceAll("([，。！？、；：,.!?;:])\\1+", "$1");   // 同一标点重复
        t = t.replaceAll("[，、；：,;:]+([。！？.!?])", "$1");       // 逗号紧跟句末 → 只留句末
        t = t.replaceAll("([。！？.!?])[，、；：,;:]+", "$1");       // 句末后多余的逗号
        t = t.replaceAll("^[，。！？、；：,.!?;:\\s]+", "");       // 开头标点
        t = t.replaceAll("[，、；：,;:]+$", "");                     // 结尾多余逗号
        t = t.replaceAll("\\s{2,}", " ");
        return t.trim();
    }

    private String one(OfflineRecognizer r, float[] seg, int sampleRate) {
        OfflineStream s = null;
        try {
            s = r.createStream();
            s.acceptWaveform(seg, sampleRate);
            r.decode(s);
            return r.getResult(s).getText();
        } catch (Throwable e) {
            Log.e(TAG, "识别失败", e);
            return null;
        } finally {
            if (s != null) try { s.release(); } catch (Throwable ignored) { }
        }
    }

    /** 极简能量分段：静音超过 MIN_SILENCE_MS 且累计长度够，就切一刀 */
    private List<float[]> split(float[] x, int sr) {
        List<float[]> out = new ArrayList<>();
        int minSil = sr * MIN_SILENCE_MS / 1000;
        int minSeg = sr * MIN_SEG_MS / 1000;
        int win = sr / 100;                 // 10ms 窗
        if (win < 1) win = 1;

        float peak = 0f;
        for (float v : x) { float a = Math.abs(v); if (a > peak) peak = a; }
        if (peak <= 0f) return out;
        float thr = peak * SILENCE_RATIO;

        int start = 0, i = 0, sil = 0;
        while (i < x.length) {
            int end = Math.min(i + win, x.length);
            float m = 0f;
            for (int k = i; k < end; k++) { float a = Math.abs(x[k]); if (a > m) m = a; }
            if (m < thr) {
                sil += end - i;
                if (sil >= minSil && (i - start) >= minSeg) {
                    out.add(slice(x, start, i - sil + win));
                    start = end;
                    sil = 0;
                }
            } else {
                sil = 0;
            }
            i = end;
        }
        if (x.length - start >= minSeg) out.add(slice(x, start, x.length));

        // 兜底：一段都没切出来（全程连续说话）就整体识别
        if (out.isEmpty()) out.add(x);
        return out;
    }

    private static float[] slice(float[] x, int a, int b) {
        if (a < 0) a = 0;
        if (b > x.length) b = x.length;
        if (b <= a) return new float[0];
        float[] y = new float[b - a];
        System.arraycopy(x, a, y, 0, b - a);
        return y;
    }

    public synchronized void release() {
        try { if (rec != null) rec.release(); } catch (Throwable ignored) { }
        rec = null;
        ready = false;
    }
}
