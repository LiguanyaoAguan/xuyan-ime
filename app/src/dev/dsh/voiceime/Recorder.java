package dev.dsh.voiceime;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 16kHz 单声道录音，输出 float[]（范围 -1..1），直接喂给 AsrEngine。
 * 同时把实时音量回调出去，给 UI 做波形/脉冲动画。
 */
public class Recorder {

    private static final String TAG = "VimRec";
    private static final int SR = 16000;
    /** 单次最长录音，防呆 */
    private static final int MAX_MS = 120_000;

    public interface Level {
        void onLevel(float rms01, int ms);
    }

    /** 原始音频分片回调（给流式识别用） */
    public interface Pcm {
        void onPcm(float[] samples);
    }

    private volatile Pcm pcmCb;

    public void setPcmListener(Pcm l) { this.pcmCb = l; }

    private AudioRecord ar;
    private Thread worker;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ByteArrayOutputStream buf = new ByteArrayOutputStream(1 << 20);
    private Level levelCb;

    public boolean isRecording() { return running.get(); }

    @SuppressLint("MissingPermission")
    public boolean start(Level cb) {
        if (running.get()) return true;
        levelCb = cb;
        int min = AudioRecord.getMinBufferSize(SR,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) min = SR;                 // 兜底
        int bufSize = Math.max(min * 4, SR * 2);

        try {
            ar = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SR, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, bufSize);
        } catch (Throwable e) {
            Log.e(TAG, "AudioRecord 创建失败", e);
            return false;
        }
        if (ar.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord 未初始化");
            safeRelease();
            return false;
        }

        buf.reset();
        running.set(true);
        worker = new Thread(this::loop, "vim-record");
        worker.start();
        return true;
    }

    private void loop() {
        short[] chunk = new short[1600];        // 100ms
        long t0 = System.currentTimeMillis();
        try {
            ar.startRecording();
        } catch (Throwable e) {
            Log.e(TAG, "startRecording 失败", e);
            running.set(false);
            return;
        }
        while (running.get()) {
            int n = ar.read(chunk, 0, chunk.length);
            if (n > 0) {
                for (int i = 0; i < n; i++) {
                    buf.write(chunk[i] & 0xFF);
                    buf.write((chunk[i] >> 8) & 0xFF);
                }
                if (levelCb != null) {
                    float rms = 0f;
                    for (int i = 0; i < n; i++) { float v = chunk[i] / 32768f; rms += v * v; }
                    rms = (float) Math.sqrt(rms / n);
                    levelCb.onLevel(Math.min(1f, rms * 4f),
                            (int) (System.currentTimeMillis() - t0));
                }
                if (pcmCb != null) {
                    float[] f = new float[n];
                    for (int i = 0; i < n; i++) f[i] = chunk[i] / 32768f;
                    pcmCb.onPcm(f);
                }
            }
            if (System.currentTimeMillis() - t0 > MAX_MS) {
                Log.w(TAG, "达到最大录音时长，自动停止");
                break;
            }
        }
        try { ar.stop(); } catch (Throwable ignored) { }
        running.set(false);
    }

    /** 停止并返回 float PCM（可能为空数组） */
    public float[] stop() {
        running.set(false);
        Thread w = worker;
        if (w != null) {
            try { w.join(1500); } catch (InterruptedException ignored) { }
        }
        worker = null;
        safeRelease();

        byte[] pcm = buf.toByteArray();
        buf.reset();
        float[] out = new float[pcm.length / 2];
        for (int i = 0; i < out.length; i++) {
            int lo = pcm[i * 2] & 0xFF;
            int hi = pcm[i * 2 + 1];
            short s = (short) ((hi << 8) | lo);
            out[i] = s / 32768f;
        }
        return out;
    }

    public void cancel() {
        running.set(false);
        Thread w = worker;
        if (w != null) try { w.join(800); } catch (InterruptedException ignored) { }
        worker = null;
        safeRelease();
        buf.reset();
    }

    private void safeRelease() {
        try { if (ar != null) ar.release(); } catch (Throwable ignored) { }
        ar = null;
    }
}
