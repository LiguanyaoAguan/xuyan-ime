package dev.dsh.voiceime;

import android.content.Context;
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
 * AI 润色：把语音识别的原始结果交给大模型整理一遍。
 *
 * 走的是 OpenAI 兼容的 chat/completions（和小米 MiMo 同一套地址与 api-key），
 * 但这里是**纯文本**请求，不上传音频。
 *
 * 只做三件事：修错别字、补标点、去口癖。不改变原意、不添加内容。
 * 失败时返回 null，调用方保持原文不动 —— 润色属于"锦上添花"，绝不能影响正常输入。
 */
public class CloudPolish {

    private static final String TAG = "VimPolish";

    private static final String PROMPT =
            "你是语音输入的文字整理助手。把下面这段语音识别结果整理成通顺的中文："
            + "修正明显的错别字、补全标点、去掉口头禅和重复词。"
            + "不要改变原意，不要添加原文没有的内容，不要加解释。只输出整理后的正文。";

    /** 返回润色后的文字；失败或未启用时返回 null */
    public static String polish(Context ctx, String raw) throws Exception {
        if (raw == null || raw.trim().isEmpty()) return null;
        String key = AsrSettings.apiKey(ctx);
        if (key.isEmpty()) throw new IllegalStateException("没填 API Key");

        String base = AsrSettings.baseUrl(ctx);
        String url = base.endsWith("/chat/completions") ? base
                : (base.endsWith("/") ? base + "chat/completions" : base + "/chat/completions");

        JSONObject sys = new JSONObject();
        sys.put("role", "system");
        sys.put("content", PROMPT);
        JSONObject usr = new JSONObject();
        usr.put("role", "user");
        usr.put("content", raw);

        JSONObject body = new JSONObject();
        body.put("model", AsrSettings.polishModel(ctx));
        body.put("messages", new JSONArray().put(sys).put(usr));
        body.put("temperature", 0.2);

        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(12000);
            c.setReadTimeout(30000);
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(payload.length);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setRequestProperty("api-key", key);
            c.setRequestProperty("Authorization", "Bearer " + key);
            try (OutputStream os = c.getOutputStream()) { os.write(payload); }

            int code = c.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? c.getInputStream() : c.getErrorStream();
            String resp = readAll(is);
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("HTTP " + code + "：" + brief(resp));
            }
            JSONObject o = new JSONObject(resp);
            JSONArray choices = o.optJSONArray("choices");
            if (choices == null || choices.length() == 0) throw new IllegalStateException("返回无 choices");
            JSONObject msg = choices.getJSONObject(0).optJSONObject("message");
            Object content = msg == null ? null : msg.opt("content");
            String out = content instanceof String ? ((String) content).trim() : "";
            if (out.isEmpty()) return null;
            Log.i(TAG, "润色完成：" + raw.length() + " → " + out.length() + " 字");
            return out;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** 拉取 /v1/models，用来确认这个 key 到底能用哪些模型名（避免猜） */
    public static String listModels(Context ctx) throws Exception {
        String key = AsrSettings.apiKey(ctx);
        if (key.isEmpty()) throw new IllegalStateException("没填 API Key");
        String base = AsrSettings.baseUrl(ctx);
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        if (base.endsWith("/chat/completions")) base = base.substring(0, base.length() - 17);
        String url = base + "/models";

        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(12000);
            c.setReadTimeout(20000);
            c.setRequestProperty("api-key", key);
            c.setRequestProperty("Authorization", "Bearer " + key);
            int code = c.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? c.getInputStream() : c.getErrorStream();
            String resp = readAll(is);
            if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code + "：" + brief(resp));
            return resp;
        } finally {
            if (c != null) c.disconnect();
        }
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

    private static String brief(String s) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').trim();
        return t.length() > 160 ? t.substring(0, 160) + "…" : t;
    }
}
