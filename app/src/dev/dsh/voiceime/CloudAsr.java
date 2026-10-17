package dev.dsh.voiceime;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 云端语音识别（OpenAI 兼容的 chat/completions + input_audio）。
 *
 * 当前对接小米 MiMo 的语音识别模型 mimo-v2.5-asr，协议实测要点：
 *   POST {baseUrl}/chat/completions
 *   header  api-key: &lt;sk-...&gt;           ← 注意：不是 Authorization: Bearer
 *   body    {"model":"mimo-v2.5-asr",
 *            "messages":[{"role":"user","content":[
 *                {"type":"input_audio",
 *                 "input_audio":{"data":"data:audio/wav;base64,...."}}]}],
 *            "asr_options":{"language":"zh"}}
 *   return  choices[0].message.content
 *
 * 只用 JDK 自带 HttpURLConnection，不引第三方库——本项目是免 Gradle 的 javac 构建。
 */
public class CloudAsr {

    private static final String TAG = "VimCloud";
    /** 接口要求 base64 后 ≤ 10MB */
    private static final int MAX_B64 = 10 * 1024 * 1024;

    /** 整段识别（不分句：云端模型自带 VAD 和标点，切碎反而更差） */
    public static String transcribe(Context ctx, float[] pcm, int sr) throws Exception {
        return call(ctx, toWav(pcm, sr));
    }

    /** 连通性测试：发 0.3 秒静音，只验证地址/密钥/模型是否通 */
    public static String test(Context ctx) throws Exception {
        float[] silence = new float[(int) (16000 * 0.3f)];
        return call(ctx, toWav(silence, 16000));
    }

    // ------------------------------------------------------------------ HTTP

    private static String call(Context ctx, byte[] wav) throws Exception {
        String key = AsrSettings.apiKey(ctx);
        if (key.isEmpty()) throw new IllegalStateException("还没填 API Key");

        String url = endpoint(AsrSettings.baseUrl(ctx));
        String b64 = Base64.encodeToString(wav, Base64.NO_WRAP);
        if (b64.length() > MAX_B64) {
            throw new IllegalStateException("音频太大（base64 " + (b64.length() / 1024) + "KB > 10MB），请说短一点");
        }

        JSONObject audio = new JSONObject();
        audio.put("data", "data:audio/wav;base64," + b64);

        JSONObject part = new JSONObject();
        part.put("type", "input_audio");
        part.put("input_audio", audio);

        JSONObject msg = new JSONObject();
        msg.put("role", "user");
        msg.put("content", new JSONArray().put(part));

        JSONObject asr = new JSONObject();
        asr.put("language", AsrSettings.language(ctx));

        JSONObject body = new JSONObject();
        body.put("model", AsrSettings.model(ctx));
        body.put("messages", new JSONArray().put(msg));
        body.put("asr_options", asr);

        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);

        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(15000);
            c.setReadTimeout(120000);
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(payload.length);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setRequestProperty("api-key", key);
            // 部分 OpenAI 兼容网关只认这个头；小米侧会忽略，留着不影响
            c.setRequestProperty("Authorization", "Bearer " + key);

            try (OutputStream os = c.getOutputStream()) {
                os.write(payload);
            }

            int code = c.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? c.getInputStream() : c.getErrorStream();
            String resp = readAll(is);
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("HTTP " + code + "：" + brief(resp));
            }
            return parseText(resp);
        } catch (java.net.SocketTimeoutException e) {
            throw new IllegalStateException("请求超时（网络慢或音频太长）");
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** baseUrl 可以是 .../v1，也可以是完整 .../v1/chat/completions */
    private static String endpoint(String base) {
        String u = base == null ? "" : base.trim();
        if (u.isEmpty()) u = AsrSettings.DEF_BASE;
        if (u.endsWith("/chat/completions")) return u;
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u + "/chat/completions";
    }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
        try { is.close(); } catch (Throwable ignored) { }
        return new String(bo.toByteArray(), StandardCharsets.UTF_8);
    }

    /** 只留一段便于塞进状态栏的错误摘要 */
    private static String brief(String s) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').replace('\r', ' ').trim();
        return t.length() > 200 ? t.substring(0, 200) + "…" : t;
    }

    /**
     * 解析 choices[0].message.content。
     * 兼容两种形态：content 是字符串，或 [{type:"text",text:"..."}] 数组。
     */
    private static String parseText(String json) throws Exception {
        JSONObject o = new JSONObject(json);
        JSONArray choices = o.optJSONArray("choices");
        if (choices == null || choices.length() == 0) {
            throw new IllegalStateException("返回里没有 choices：" + brief(json));
        }
        JSONObject message = choices.getJSONObject(0).optJSONObject("message");
        if (message == null) throw new IllegalStateException("返回里没有 message：" + brief(json));

        Object content = message.opt("content");
        if (content instanceof String) return ((String) content).trim();

        StringBuilder sb = new StringBuilder();
        if (content instanceof JSONArray) {
            JSONArray arr = (JSONArray) content;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject seg = arr.optJSONObject(i);
                if (seg == null) continue;
                String t = seg.optString("text", "");
                if (!t.isEmpty()) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(t);
                }
            }
        }
        if (sb.length() == 0) Log.w(TAG, "识别结果为空：" + brief(json));
        return sb.toString().trim();
    }

    // ------------------------------------------------------------------ WAV

    /** float PCM(-1..1, 单声道) → 标准 16bit PCM 的 WAV 字节流 */
    static byte[] toWav(float[] pcm, int sr) {
        int n = pcm == null ? 0 : pcm.length;
        int dataLen = n * 2;
        ByteArrayOutputStream b = new ByteArrayOutputStream(44 + dataLen);
        ascii(b, "RIFF");
        le32(b, 36 + dataLen);
        ascii(b, "WAVE");
        ascii(b, "fmt ");
        le32(b, 16);          // fmt 块长度
        le16(b, 1);           // PCM
        le16(b, 1);           // 单声道
        le32(b, sr);          // 采样率
        le32(b, sr * 2);      // 每秒字节数
        le16(b, 2);           // 块对齐
        le16(b, 16);          // 位深
        ascii(b, "data");
        le32(b, dataLen);
        for (int i = 0; i < n; i++) {
            float v = pcm[i];
            if (v > 1f) v = 1f;
            if (v < -1f) v = -1f;
            int s = (int) (v * 32767f);
            b.write(s & 0xFF);
            b.write((s >> 8) & 0xFF);
        }
        return b.toByteArray();
    }

    private static void ascii(ByteArrayOutputStream b, String s) {
        for (int i = 0; i < s.length(); i++) b.write(s.charAt(i) & 0xFF);
    }

    private static void le16(ByteArrayOutputStream b, int v) {
        b.write(v & 0xFF);
        b.write((v >> 8) & 0xFF);
    }

    private static void le32(ByteArrayOutputStream b, int v) {
        b.write(v & 0xFF);
        b.write((v >> 8) & 0xFF);
        b.write((v >> 16) & 0xFF);
        b.write((v >> 24) & 0xFF);
    }
}
