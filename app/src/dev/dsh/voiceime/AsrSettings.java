package dev.dsh.voiceime;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 识别引擎配置（本机离线 / 云端 API 二选一）。
 *
 * 刻意用独立 SharedPreferences（"asr_cfg"），与 VoiceImeService 的 "vim" 分开，
 * 避免键名撞车。API Key 只存本机私有目录，不进日志、不进导出。
 */
public class AsrSettings {

    public static final String PREFS = "asr_cfg";

    public static final String K_ENGINE = "engine";      // local | cloud
    public static final String K_BASE   = "base_url";
    public static final String K_KEY    = "api_key";
    public static final String K_MODEL  = "model";
    public static final String K_LANG   = "language";
    public static final String K_POLISH = "polish";          // "1" = 识别后调 AI 润色
    public static final String K_POLISH_MODEL = "polish_model";

    /** 默认对接小米 MiMo 的语音识别（OpenAI 兼容） */
    public static final String DEF_BASE  = "https://api.xiaomimimo.com/v1";
    public static final String DEF_MODEL = "mimo-v2.5-asr";
    public static final String DEF_LANG  = "zh";
    public static final String DEF_POLISH_MODEL = "mimo-v2.6";

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isCloud(Context c) {
        return "cloud".equals(sp(c).getString(K_ENGINE, "local"));
    }

    public static void setEngine(Context c, String engine) {
        sp(c).edit().putString(K_ENGINE, engine).apply();
    }

    public static String engineLabel(Context c) {
        return isCloud(c) ? "云端 API" : "本机离线";
    }

    public static String baseUrl(Context c) {
        String v = sp(c).getString(K_BASE, DEF_BASE);
        return (v == null || v.trim().isEmpty()) ? DEF_BASE : v.trim();
    }

    public static String apiKey(Context c) {
        String v = sp(c).getString(K_KEY, "");
        return v == null ? "" : v.trim();
    }

    public static String model(Context c) {
        String v = sp(c).getString(K_MODEL, DEF_MODEL);
        return (v == null || v.trim().isEmpty()) ? DEF_MODEL : v.trim();
    }

    public static String language(Context c) {
        String v = sp(c).getString(K_LANG, DEF_LANG);
        return (v == null || v.trim().isEmpty()) ? DEF_LANG : v.trim();
    }

    /**
     * 本机离线引擎的语言模式（与云端接口的 language 是两件事）。
     * auto 让 SenseVoice 自己判断；zh/en/ja/ko/yue 强制指定。
     * 强制 zh 时英文专名容易被音译（DeepSeek → dP），这是可调项。
     */
    public static final String K_LOCAL_LANG = "local_language";

    public static String localLanguage(Context c) {
        String v = sp(c).getString(K_LOCAL_LANG, "zh");
        return (v == null || v.trim().isEmpty()) ? "zh" : v.trim();
    }

    public static void setLocalLanguage(Context c, String v) {
        sp(c).edit().putString(K_LOCAL_LANG, v == null ? "zh" : v.trim()).apply();
    }

    public static boolean polishEnabled(Context c) {
        return "1".equals(sp(c).getString(K_POLISH, "0"));
    }

    public static void setPolishEnabled(Context c, boolean on) {
        sp(c).edit().putString(K_POLISH, on ? "1" : "0").apply();
    }

    public static String polishModel(Context c) {
        String v = sp(c).getString(K_POLISH_MODEL, DEF_POLISH_MODEL);
        return (v == null || v.trim().isEmpty()) ? DEF_POLISH_MODEL : v.trim();
    }

    public static void setPolishModel(Context c, String v) {
        sp(c).edit().putString(K_POLISH_MODEL, v == null ? "" : v.trim()).apply();
    }

    public static void save(Context c, String base, String key, String model, String lang) {
        sp(c).edit()
                .putString(K_BASE, base == null ? "" : base.trim())
                .putString(K_KEY, key == null ? "" : key.trim())
                .putString(K_MODEL, model == null ? "" : model.trim())
                .putString(K_LANG, lang == null ? "" : lang.trim())
                .apply();
    }
}
