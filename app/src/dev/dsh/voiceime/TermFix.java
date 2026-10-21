package dev.dsh.voiceime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 本地术语纠正：把语音识别里常见的误识别替换成规范写法。
 *
 * 完全离线、纯字符串规则，不调用云端、不调用大模型。
 * 为什么需要：SenseVoice 对专名会给出「同音替换」而不是乱码，
 * 例如 DeepSeek Harness → 「dP 哈尼斯」、Harness → 「哈尼斯」，
 * 句子仍然通顺，肉眼很难发现，下游使用会照错的词推理。
 *
 * 匹配方式：先把识别文本和变体都去掉空白与标点、转小写，
 * 在「紧凑文本」上查找变体，再把命中的原文区间整体替换成规范写法。
 * 这样「dP。哈尼斯」「dP 哈尼斯」「dP哈尼斯」都能命中同一条规则。
 *
 * 变体按长度降序匹配，保证「dP哈尼斯」先于「哈尼斯」生效。
 */
public final class TermFix {

    /** 内置词表：规范写法 \\t 变体1|变体2|…，每行一条，# 开头为注释 */
    private static final String BUILTIN =
            "# —— AI / DSH 相关 ——\n"
            + "DeepSeek Harness\tDeepSeek Harness|dP哈尼斯|dP 哈尼斯|dP。哈尼斯|迪普希克哈尼斯|迪普西克哈尼斯|deep seek harness|深度求索哈尼斯|深度求索 harness\n"
            + "DeepSeek\tDeepSeek|迪普希克|迪普西克|地普希克|的普希克|深度求索|dP希克|deep seek\n"
            + "Harness\tHarness|哈尼斯|哈内斯|哈呢斯|哈耐斯|哈尼丝\n"
            + "DSH\tDSH|d s h|地埃斯艾尺\n"
            + "DeepSeek V4.1 Flash\tDeepSeek V4.1 Flash|deepsickv4.1flash|deepepsickv4.1flash|迪普希克v4.1flash|迪普西克v4.1flash\n"
            + "MiMo V2.5 Pro\tMiMo V2.5 Pro|mimov2.5pro|mov2.5pro|米莫v2.5pro|米莫v2.5 pro|mi mov2.5pro\n"
            + "小米 MiMo V2.5 Pro\t小米 MiMo V2.5 Pro|小米mov2.5pro|小米mimov2.5pro|小米米莫v2.5pro|小米mimov2.5 pro\n"
            + "GPT\tGPT|gpt|g p t|计皮提|吉皮提\n"
            + "# —— 本项目专名 ——\n"
            + "序言输入法\t序言输入法|许言输入法|序岩输入法|序言书法|续言输入法\n"
            + "sherpa-onnx\tsherpa-onnx|夏尔巴onnx|夏尔巴 onnx|沙尔帕onnx|沙尔帕 onnx\n"
            + "SenseVoice\tSenseVoice|森斯沃伊斯|深思沃伊斯|sense voice|sensevoice\n"
            + "zipformer\tzipformer|zip former|兹普former\n"
            + "# —— 常见技术词（只在明确误写时替换，不影响正常措辞）——\n"
            + "剪贴板\t剪贴板|剪贴版|减贴板\n"
            + "悬浮窗\t悬浮窗|悬乎窗\n"
            + "无障碍\t无障碍|无帐碍|无障爱\n";

    private static final int MAX_ROUNDS = 24;

    /**
     * DeepSeek 的常见叫法（含中文音译与缩写）。
     * 识别结果里它经常变成 "DP" / "迪普希克"。
     */
    private static final String[] DS_PREFIX = {
            "DeepSeek", "deepseek", "DP", "dP", "dp",
            "deep sick", "deepsick", "deep' sick", "deepep sick", "deepepsick", "deepp sick",
            "迪普希克", "迪普西克", "地普西克", "地普希克", "迪普", "地普"
    };

    /**
     * Harness 的常见中文音译。
     * "号历史 / 好历史 / 号力史" 是实测出现过的错法（hao-li-shi 对应 har-nes）。
     */
    private static final String[] HARNESS_SUFFIX = {
            "Harness", "harness",
            "哈尼斯", "哈内斯", "哈呢斯", "哈耐斯", "哈纳斯", "哈妮丝", "哈尼丝",
            "哈丽斯", "哈里斯", "哈莉丝", "哈尼史", "哈尼士", "哈尼司", "哈尼思",
            "哈里士", "哈历斯", "哈尼逊", "哈尼森", "哈妮斯", "哈你斯",
            "号历史", "号力史", "好历史", "豪历史", "号莉史"
    };

    /** DeepSeek 版本后缀：v4.1 Flash 实测会被识别成 "v4.1flash" */
    private static final String[] FLASH_SUFFIX = {
            "V4.1 Flash", "V4.1Flash", "v4.1flash", "v4.1 flash", "v4.1 闪", "v41flash"
    };

    private static final class Rule {
        String canonical;
        String variant;   // 已去标点空格、转小写
        int len;
    }

    private final List<Rule> rules = new ArrayList<>();

    public TermFix(String source) {
        String text = (source == null || source.trim().isEmpty()) ? BUILTIN : source;
        parse(text);
        buildCombos();
        sortRules();
    }

    /**
     * 组合生成：DeepSeek 前缀 × Harness 音译后缀。
     * 这样「DP号历史」「DP哈尼斯」「DeepSeek好历史」都不用逐条维护，
     * 也不会把单独出现的「好历史」误改成 Harness（组合只在成对时命中）。
     */
    private void buildCombos() {
        for (String p : DS_PREFIX) {
            for (String s : HARNESS_SUFFIX) {
                addRule("DeepSeek Harness", compact(p + s));
            }
            for (String f : FLASH_SUFFIX) {
                addRule("DeepSeek V4.1 Flash", compact(p + f));
            }
        }
    }

    private void addRule(String canonical, String variant) {
        if (variant == null || variant.isEmpty()) return;
        // 保护一：规范写法自身就包含这个变体时（例如 "MiMo V2.5 Pro" 含 "mov2.5pro"），
        // 说明它本来就是正确写法的一部分，替换只会自我套娃（MiMiMiMo…），直接丢弃。
        if (compact(canonical).contains(variant)) return;
        Rule r = new Rule();
        r.canonical = canonical;
        r.variant = variant;
        r.len = variant.length();
        rules.add(r);
    }

    private void sortRules() {
        Collections.sort(rules, new Comparator<Rule>() {
            @Override public int compare(Rule a, Rule b) {
                if (a.len != b.len) return b.len - a.len;     // 长变体优先
                return a.canonical.compareTo(b.canonical);     // 结果稳定
            }
        });
    }

    private void parse(String src) {
        String[] lines = src.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] parts = line.split("\t");
            if (parts.length < 2) continue;
            String canonical = parts[0].trim();
            if (canonical.isEmpty()) continue;
            String[] variants = parts[1].split("\\|");
            for (String v : variants) {
                addRule(canonical, compact(v));
            }
        }
    }

    /** 判断是否「可忽略字符」：空白与常见中英文标点 */
    private static boolean skip(char c) {
        if (Character.isWhitespace(c)) return true;
        return "，。！？、；：,.!?;:·…—～~\"'“”‘’（）()【】[]《》<>「」『』".indexOf(c) >= 0;
    }

    private static String compact(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!skip(c)) sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    /**
     * 对识别文本做术语纠正。
     * 返回新字符串；没有命中任何规则时原样返回。
     */
    public String apply(String text) {
        if (text == null || text.isEmpty() || rules.isEmpty()) return text;
        String cur = text;
        for (int guard = 0; guard < MAX_ROUNDS; guard++) {
            String next = fixOnce(cur);
            if (next.equals(cur)) return cur;
            cur = next;
        }
        return cur;
    }

    /** 每轮只替换一个最长命中，替换后重新建映射，直到没有变化 */
    private String fixOnce(String text) {
        StringBuilder compact = new StringBuilder(text.length());
        List<Integer> map = new ArrayList<>(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!skip(c)) {
                compact.append(Character.toLowerCase(c));
                map.add(i);
            }
        }
        int n = compact.length();
        if (n == 0) return text;
        String ctext = compact.toString();

        for (Rule r : rules) {
            if (r.len > n) continue;
            int idx = ctext.indexOf(r.variant);
            while (idx >= 0) {
                int start = map.get(idx);
                int end = map.get(idx + r.len - 1) + 1;
                String replaced = text.substring(0, start) + r.canonical + text.substring(end);
                if (!replaced.equals(text)) {
                    // 保护二：替换后长度异常膨胀时中止，避免规则互相套娃。
                    if (replaced.length() > text.length() * 3 + 16) return text;
                    return replaced;
                }
                idx = ctext.indexOf(r.variant, idx + 1);
            }
        }
        return text;
    }

    /** 调试/自检用 */
    public int ruleCount() { return rules.size(); }
}
