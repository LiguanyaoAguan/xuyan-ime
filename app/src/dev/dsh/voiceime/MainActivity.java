package dev.dsh.voiceime;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 序言 · 语音控制中心（v1.0）
 *
 * 三栏：导航 / 内容 / 胶囊预览。内容区限宽居中，避免平板上一行拉太长。
 * 视觉：深色分层 + 品牌蓝；胶囊本身样式完全不改。
 *
 * 说明：右侧预览只是「画」一个 VoicePadView，**不设置 callback**，
 * 所以点它不会触发录音、删除或往任何 App 输入文字。
 */
public class MainActivity extends Activity {

    private static final int REQ_AUDIO = 1001;

    private static final String[] PAGES = {
            "总览", "语音识别", "输入与外观", "识别实验室", "权限与系统", "开发者工具"
    };

    // ---- 视觉 token ----
    private static final int BG        = 0xFF101419;
    private static final int NAV       = 0xFF171D26;
    private static final int CARD      = 0xFF1D2530;
    private static final int CARD2     = 0xFF252F3D;
    private static final int BRAND     = 0xFF60A5FA;
    private static final int TEXT      = 0xFFF1F5F9;
    private static final int SUB       = 0xFF94A3B8;
    private static final int GREEN     = 0xFF22C55E;
    private static final int AMBER     = 0xFFF59E0B;
    private static final int RED       = 0xFFEF4444;
    private static final int MAX_CONTENT_DP = 840;

    private LinearLayout navBox, contentBox, contentInner, previewBox;
    private TextView[] navItems;
    private int page = 0;
    private boolean wide;

    private TextView tvStatus, tvCmd, tvEngine, tvPolish;
    private EditText etBase, etKey, etModel, etLang, etPolishModel;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(build());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    // ---------------------------------------------------------------- 骨架

    private View build() {
        float dpw = getResources().getDisplayMetrics().widthPixels
                / getResources().getDisplayMetrics().density;
        wide = dpw >= 900;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(BG);

        navBox = new LinearLayout(this);
        navBox.setOrientation(LinearLayout.VERTICAL);
        navBox.setBackgroundColor(NAV);
        root.addView(navBox, new LinearLayout.LayoutParams(dp(wide ? 200 : 118),
                ViewGroup.LayoutParams.MATCH_PARENT));
        buildNav();

        View div = new View(this);
        div.setBackgroundColor(0xFF232C38);
        root.addView(div, new LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT));

        // 内容：ScrollView 里再套一层限宽容器，居中
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        sv.setFillViewport(true);

        FrameLayout wrap = new FrameLayout(this);
        contentInner = new LinearLayout(this);
        contentInner.setOrientation(LinearLayout.VERTICAL);
        contentInner.setPadding(dp(28), dp(26), dp(28), dp(48));

        FrameLayout.LayoutParams innerLp = new FrameLayout.LayoutParams(
                Math.min(dp(MAX_CONTENT_DP), ViewGroup.LayoutParams.MATCH_PARENT),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        innerLp.gravity = Gravity.CENTER_HORIZONTAL;
        wrap.addView(contentInner, innerLp);
        sv.addView(wrap, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(sv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        contentBox = contentInner;

        if (wide) {
            View vd = new View(this);
            vd.setBackgroundColor(0xFF232C38);
            root.addView(vd, new LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT));

            previewBox = new LinearLayout(this);
            previewBox.setOrientation(LinearLayout.VERTICAL);
            previewBox.setBackgroundColor(NAV);
            previewBox.setPadding(dp(22), dp(28), dp(22), dp(24));
            previewBox.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            root.addView(previewBox, new LinearLayout.LayoutParams(dp(272),
                    ViewGroup.LayoutParams.MATCH_PARENT));
            buildPreview();
        }

        select(0);
        return root;
    }

    private void buildNav() {
        TextView logo = new TextView(this);
        logo.setText("序言");
        logo.setTextColor(TEXT);
        logo.setTextSize(wide ? 22 : 17);
        logo.setTypeface(Typeface.DEFAULT_BOLD);
        logo.setLetterSpacing(0.06f);
        logo.setPadding(dp(22), dp(26), dp(12), dp(2));
        navBox.addView(logo);

        TextView ver = new TextView(this);
        ver.setText("Xuyan · v1.0");
        ver.setTextColor(SUB);
        ver.setTextSize(11);
        ver.setPadding(dp(22), 0, dp(12), dp(24));
        navBox.addView(ver);

        navItems = new TextView[PAGES.length];
        for (int i = 0; i < PAGES.length; i++) {
            final int idx = i;
            TextView t = new TextView(this);
            t.setText(PAGES[i]);
            t.setTextSize(wide ? 15 : 13);
            t.setPadding(dp(22), dp(14), dp(10), dp(14));
            t.setOnClickListener(v -> select(idx));
            navBox.addView(t, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            navItems[i] = t;
        }

        View filler = new View(this);
        navBox.addView(filler, new LinearLayout.LayoutParams(1, 0, 1f));

        TextView foot = new TextView(this);
        foot.setText("本地识别 · 音频不出设备");
        foot.setTextColor(0xFF5B6779);
        foot.setTextSize(10);
        foot.setPadding(dp(22), dp(12), dp(12), dp(22));
        navBox.addView(foot);
    }

    /** 右侧预览：只展示胶囊本身与状态样张，参数不在这里 */
    private void buildPreview() {
        TextView h = new TextView(this);
        h.setText("胶囊");
        h.setTextColor(TEXT);
        h.setTextSize(13);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        previewBox.addView(h);

        TextView hint = new TextView(this);
        hint.setText("可在屏幕上任意拖动");
        hint.setTextColor(SUB);
        hint.setTextSize(10);
        hint.setPadding(0, dp(4), 0, dp(22));
        previewBox.addView(hint);

        FrameLayout holder = new FrameLayout(this);
        int w = (int) (VoicePadView.W * getResources().getDisplayMetrics().density);
        int hh = (int) (VoicePadView.H * getResources().getDisplayMetrics().density);
        holder.addView(new VoicePadView(this), new FrameLayout.LayoutParams(w, hh));
        previewBox.addView(holder, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView states = new TextView(this);
        states.setText("\n\n状态");
        states.setTextColor(SUB);
        states.setTextSize(10);
        previewBox.addView(states);

        String[][] rows = {
                {"空闲", "蓝色麦克风"},
                {"录音", "红色 + 呼吸光"},
                {"识别", "灰色"},
                {"完成", "描边闪绿"},
                {"异常", "琥珀色"},
        };
        for (String[] r : rows) {
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setPadding(0, dp(7), 0, dp(7));

            View dot = new View(this);
            dot.setBackgroundColor(colorOfState(r[0]));
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(6), dp(6));
            dlp.gravity = Gravity.CENTER_VERTICAL;
            dlp.rightMargin = dp(10);
            line.addView(dot, dlp);

            TextView t = new TextView(this);
            t.setText(r[0] + "  ·  " + r[1]);
            t.setTextColor(SUB);
            t.setTextSize(11);
            line.addView(t);
            previewBox.addView(line);
        }
    }

    private int colorOfState(String name) {
        switch (name) {
            case "录音": return RED;
            case "识别": return 0xFFA2ADBD;
            case "完成": return GREEN;
            case "异常": return AMBER;
            default: return BRAND;
        }
    }

    private void select(int idx) {
        page = idx;
        for (int i = 0; i < navItems.length; i++) {
            boolean on = i == idx;
            navItems[i].setTextColor(on ? TEXT : SUB);
            navItems[i].setBackground(on ? roundBg(0xFF2A3546, 10) : null);
            navItems[i].setTypeface(Typeface.create(Typeface.DEFAULT,
                    on ? Typeface.BOLD : Typeface.NORMAL));
        }
        contentBox.removeAllViews();
        tvStatus = tvCmd = tvEngine = tvPolish = null;
        etBase = etKey = etModel = etLang = etPolishModel = null;

        switch (idx) {
            case 0: pageOverview(); break;
            case 1: pageAsr(); break;
            case 2: pageInput(); break;
            case 3: pageLab(); break;
            case 4: pagePermissions(); break;
            default: pageDev(); break;
        }
        refresh();
    }

    // ---------------------------------------------------------------- 页面

    private void pageOverview() {
        bigTitle("运行状态", "一眼看清现在能不能用");

        LinearLayout two = new LinearLayout(this);
        two.setOrientation(LinearLayout.HORIZONTAL);
        two.setPadding(0, dp(4), 0, 0);

        // 左栏：四项状态
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        buildStatusGridInto(left);

        View gap = new View(this);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(dp(16), 1);
        two.addView(left, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1.25f));
        two.addView(gap, glp);

        // 右栏：面板预览（用 VoicePadView 真实绘制面板态）
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setBackground(roundBg(CARD, 16));
        right.setPadding(dp(18), dp(16), dp(18), dp(16));

        TextView rt = new TextView(this);
        rt.setText("展开面板");
        rt.setTextColor(TEXT);
        rt.setTextSize(13);
        rt.setTypeface(Typeface.DEFAULT_BOLD);
        right.addView(rt);

        TextView rd = new TextView(this);
        rd.setText("点胶囊上的 ⌃ 展开");
        rd.setTextColor(SUB);
        rd.setTextSize(10);
        rd.setPadding(0, dp(4), 0, dp(12));
        right.addView(rd);

        FrameLayout ph = new FrameLayout(this);
        int pw = (int) (VoicePadView.PW * getResources().getDisplayMetrics().density);
        int phh = (int) (VoicePadView.PH * getResources().getDisplayMetrics().density);
        VoicePadView panelPreview = new VoicePadView(this);
        ph.addView(panelPreview, new FrameLayout.LayoutParams(pw, phh));
        right.addView(ph);

        two.addView(right, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        contentBox.addView(two);

        sub("快捷操作");
        contentBox.addView(primaryButton("切换到本输入法", v -> {
            try {
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.showInputMethodPicker();
            } catch (Throwable e) { toast("切换失败：" + e.getMessage()); }
        }));
        note("切到本输入法后，点任意输入框就会出现胶囊。");
    }

    /** 2×2 状态卡片，区分「文件存在 / 已加载 / 已启用 / 正在运行」 */
    private void buildStatusGridInto(LinearLayout host) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        AsrEngine e = AsrEngine.get(this);
        StreamingAsr st = StreamingAsr.get(this);
        boolean perm = Build.VERSION.SDK_INT < 23
                || checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        boolean ov = false;
        try { ov = Settings.canDrawOverlays(this); } catch (Throwable ignored) { }

        LinearLayout c1 = statusCard("麦克风权限", perm ? "已授权" : "未授权", perm, "语音输入必需");
        LinearLayout c2 = statusCard("悬浮窗", ov ? "已授权" : "未授权", ov,
                ov ? "胶囊可任意摆放" : "胶囊只能待底部");
        LinearLayout c3 = statusCard("输入法", imeEnabled() ? "已启用" : "未启用", imeEnabled(),
                imeEnabled() ? "可在系统里选择" : "需到系统设置打开");
        String engineLabel = AsrSettings.isCloud(this) ? "云端 API"
                : (e.isReady() ? "本地 · 已加载" : (e.modelPresent() ? "本地 · 待加载" : "本地 · 模型缺失"));
        LinearLayout c4 = statusCard("识别引擎", engineLabel, !AsrSettings.isCloud(this) && !e.modelPresent() ? false : true,
                AsrSettings.polishEnabled(this) ? "AI 润色已开" : "AI 润色已关");

        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp1.rightMargin = dp(12);
        lp1.topMargin = dp(6);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp2.topMargin = dp(6);
        row.addView(c1, lp1);
        row.addView(c2, new LinearLayout.LayoutParams(lp2));
        host.addView(row);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp3 = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp3.rightMargin = dp(12);
        lp3.topMargin = dp(10);
        LinearLayout.LayoutParams lp4 = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp4.topMargin = dp(10);
        row2.addView(c3, lp3);
        row2.addView(c4, lp4);
        host.addView(row2);

        // 流式草稿单独一行（它最容易误读成"已就绪"）
        String flow;
        boolean flowOk = st.modelPresent();
        if (!flowOk) flow = "未安装 · 不会边说边出字";
        else flow = st.isReady() ? "已加载 · 说话时会实时出草稿" : "文件已就位 · 首次使用会加载";
        TextView fl = new TextView(this);
        fl.setText("流式草稿：" + flow);
        fl.setTextColor(SUB);
        fl.setTextSize(11);
        fl.setLineSpacing(0, 1.4f);
        fl.setPadding(dp(2), dp(12), 0, 0);
        host.addView(fl);
    }

    private LinearLayout statusCard(String name, String value, boolean ok, String hint) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(roundBg(CARD, 16));
        box.setPadding(dp(20), dp(18), dp(20), dp(18));

        TextView n = new TextView(this);
        n.setText(name);
        n.setTextColor(SUB);
        n.setTextSize(11);
        box.addView(n);

        TextView v = new TextView(this);
        v.setText(value);
        v.setTextColor(ok ? TEXT : AMBER);
        v.setTextSize(17);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setPadding(0, dp(6), 0, 0);
        box.addView(v);

        TextView h = new TextView(this);
        h.setText(hint);
        h.setTextColor(SUB);
        h.setTextSize(10);
        h.setPadding(0, dp(4), 0, 0);
        box.addView(h);
        return box;
    }

    private void pageAsr() {
        bigTitle("语音识别", "选本地还是云端，配好接口就能用");
        sub("引擎");
        tvEngine = card("");
        tvEngine.setTextSize(12);
        row(subtleButton("本机离线", v -> { AsrSettings.setEngine(this, "local"); refresh(); }),
                subtleButton("云端 API", v -> { AsrSettings.setEngine(this, "cloud"); refresh(); }));

        sub("云端接口（可选）");
        etBase = edit(AsrSettings.baseUrl(this), "https://api.xiaomimimo.com/v1", false);
        etKey = edit(AsrSettings.apiKey(this), "sk-...", true);
        etModel = edit(AsrSettings.model(this), "mimo-v2.5-asr", false);
        etLang = edit(AsrSettings.language(this), "auto / zh / en", false);
        field("接口地址", etBase);
        field("API Key", etKey);
        field("识别模型名", etModel);
        field("语言", etLang);
        row(subtleButton("保存并测试", v -> saveAndTest()),
                subtleButton("列出可用模型", v -> listModels()));

        sub("AI 润色（可选）");
        tvPolish = card("");
        tvPolish.setTextSize(12);
        etPolishModel = edit(AsrSettings.polishModel(this), "mimo-v2.6-flash", false);
        field("润色模型名", etPolishModel);
        row(subtleButton("开 / 关润色", v -> {
            boolean on = !AsrSettings.polishEnabled(this);
            AsrSettings.setPolishEnabled(this, on);
            toast(on ? "已开启" : "已关闭");
            refresh();
        }), subtleButton("保存润色模型", v -> {
            if (etPolishModel != null) AsrSettings.setPolishModel(this, etPolishModel.getText().toString());
            toast("已保存");
            refresh();
        }));
        note("开启后识别出的文字会再整理一遍：修错别字、补标点、去口癖。只发文字不发录音，失败保持原文。");
    }

    private void pageInput() {
        bigTitle("输入与外观", "胶囊的形态与操作方式");
        sub("形态");
        note("216 × 56dp，圆角 28dp，四个按钮：录音 / 换行 / 删除 / 展开箭头。");
        sub("位置");
        note("可拖到屏幕任意位置，松手记住；下次唤醒、切换输入法都回到原位。");
        sub("展开面板");
        note("点箭头展开二级面板（撤销 / 历史 / 语言 / 设置）；长按箭头直接进入拖动。");
        sub("换行");
        note("· DSH / Harness 类应用：发 Shift+Enter，只换行不发送\n"
                + "· 其它应用：插入换行字符\n"
                + "· 任何情况都不模拟裸 Enter，识别结束也不会自动补回车");
        sub("删除");
        note("· 单击退一个字\n· 长按连续退\n· 按住上滑 28dp 进入清空预备态，松手清空");
    }

    private void pageLab() {
        bigTitle("识别实验室", "模型与引擎的真实状态");
        AsrEngine e = AsrEngine.get(this);
        StreamingAsr st = StreamingAsr.get(this);

        sub("离线识别（决定最终文字）");
        card("模型：" + (e.modelPresent() ? "文件已就位" : "缺失")
                + "\n引擎：" + (e.isReady() ? "已加载" : (e.isLoading() ? "加载中" : "未加载"))
                + "\n路径：" + e.modelDir()).setTextSize(11);

        sub("流式草稿（决定边说边出字）");
        card("模型：" + (st.modelPresent() ? "文件已就位" : "缺失")
                + "\n引擎：" + (st.isReady() ? "已加载" : "未加载")
                + "\n路径：" + st.modelDir()).setTextSize(11);

        sub("工具");
        row(subtleButton("重新预热引擎", v -> {
            AsrEngine.get(this).warmUp();
            StreamingAsr.get(this).warmUp();
            toast("正在预热…");
        }), subtleButton("列出云端模型", v -> listModels()));
        note("录音电平、识别耗时、弱音对照这些实验项还没做，做了再放进来，"
                + "不摆空控件。");
    }

    private void pagePermissions() {
        bigTitle("权限与系统", "");
        row(subtleButton("麦克风授权", v -> requestAudio()),
                subtleButton("悬浮窗授权", v -> openOverlaySettings()));
        row(subtleButton("输入法设置", v -> openImeSettings()),
                subtleButton("切换输入法", v -> {
                    try {
                        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                        if (imm != null) imm.showInputMethodPicker();
                    } catch (Throwable ignored) { }
                }));
        note("没有悬浮窗权限也能用，但胶囊只能待在底部、且会把画面顶起来。");
    }

    private void pageDev() {
        bigTitle("开发者工具", "参数与调试入口");
        sub("设计参数");
        note("胶囊 216×56dp · 圆角 28dp · 四键 44/40/40/34dp\n"
                + "面板 252×192dp · 圆角 24dp\n"
                + "主蓝 #60A5FA · 录音红 #FF6969 · 中性灰 #A2ADBD\n"
                + "警示琥珀 #FBBF24 · 成功绿 #22C55E");
        sub("AI 注入通道");
        note("本输入法是当前输入法且输入框有焦点时，可用一条广播把文本直接写进去：");
        tvCmd = card(commitCmd());
        tvCmd.setTextSize(11);
        tvCmd.setTextIsSelectable(true);
        tvCmd.setTypeface(Typeface.MONOSPACE);
        contentBox.addView(subtleButton("复制命令", v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("cmd", commitCmd()));
                toast("已复制");
            }
        }), matchWide());
    }

    // ---------------------------------------------------------------- 组件

    private void bigTitle(String title, String desc) {
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(TEXT);
        t.setTextSize(24);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        contentBox.addView(t);

        if (desc != null && !desc.isEmpty()) {
            TextView d = new TextView(this);
            d.setText(desc);
            d.setTextColor(SUB);
            d.setTextSize(12);
            d.setPadding(0, dp(6), 0, 0);
            contentBox.addView(d);
        }
        View sp = new View(this);
        contentBox.addView(sp, new LinearLayout.LayoutParams(1, dp(22)));
    }

    private void sub(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(SUB);
        t.setTextSize(11);
        t.setPadding(0, dp(18), 0, dp(8));
        contentBox.addView(t);
    }

    private void note(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(SUB);
        t.setTextSize(12);
        t.setLineSpacing(0, 1.55f);
        t.setPadding(0, dp(6), 0, dp(2));
        contentBox.addView(t);
    }

    private TextView card(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(TEXT);
        t.setTextSize(14);
        t.setLineSpacing(0, 1.5f);
        t.setBackground(roundBg(CARD, 16));
        t.setPadding(dp(20), dp(18), dp(20), dp(18));
        contentBox.addView(t, lp());
        return t;
    }

    private void field(String labelText, View v) {
        TextView l = new TextView(this);
        l.setText(labelText);
        l.setTextColor(SUB);
        l.setTextSize(11);
        l.setPadding(0, dp(10), 0, dp(3));
        contentBox.addView(l);
        contentBox.addView(v, lp());
    }

    private Button primaryButton(String text, View.OnClickListener l) {
        Button b = baseButton(text, l);
        b.setBackground(roundBg(BRAND, 12));
        b.setTextColor(0xFF06121F);
        b.setTextSize(15);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        // 不拉满整行：宽度随内容，最小 220dp
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        b.setLayoutParams(lp);
        b.setMinWidth(dp(220));
        b.setPadding(dp(28), dp(13), dp(28), dp(13));
        return b;
    }

    private Button subtleButton(String text, View.OnClickListener l) {
        Button b = baseButton(text, l);
        b.setBackground(roundBg(CARD2, 12));
        b.setTextColor(TEXT);
        b.setTextSize(13);
        return b;
    }

    private Button baseButton(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER);
        b.setOnClickListener(l);
        b.setPadding(dp(14), dp(11), dp(14), dp(11));
        b.setLayoutParams(matchWide());
        return b;
    }

    private void row(View a, View b) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams p1 = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        p1.topMargin = dp(8);
        LinearLayout.LayoutParams p2 = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        p2.topMargin = dp(8);
        p2.leftMargin = dp(10);
        r.addView(a, p1);
        r.addView(b, p2);
        contentBox.addView(r);
    }

    private LinearLayout.LayoutParams lp() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(8);
        return p;
    }

    private LinearLayout.LayoutParams matchWide() {
        return lp();
    }

    private EditText edit(String value, String hint, boolean secret) {
        EditText e = new EditText(this);
        e.setText(value == null ? "" : value);
        e.setHint(hint);
        e.setHintTextColor(0xFF6B7789);
        e.setTextColor(TEXT);
        e.setTextSize(13);
        e.setSingleLine(true);
        e.setBackgroundColor(CARD);
        e.setPadding(dp(14), dp(12), dp(14), dp(12));
        if (secret) {
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        }
        return e;
    }

    // ---------------------------------------------------------------- 状态

    private void refresh() {
        if (tvEngine != null) {
            tvEngine.setText(AsrSettings.isCloud(this)
                    ? "当前：云端 API — 录音会上传（" + AsrSettings.model(this) + "）"
                    : "当前：本机离线 — 音频不出设备（SenseVoice）");
            tvEngine.setTextColor(AsrSettings.isCloud(this) ? AMBER : GREEN);
        }
        if (tvPolish != null) {
            boolean on = AsrSettings.polishEnabled(this);
            tvPolish.setText(on ? "当前：已开启 · " + AsrSettings.polishModel(this)
                    : "当前：已关闭（识别完直接上屏）");
            tvPolish.setTextColor(on ? GREEN : SUB);
        }
        if (tvCmd != null) tvCmd.setText(commitCmd());
        if (page == 0) {
            // 总览的状态卡片是静态构建的，权限可能在系统里变过 → 重建
            contentBox.removeAllViews();
            tvStatus = null;
            pageOverview();
        }
    }

    // ---------------------------------------------------------------- 功能

    private String commitCmd() {
        return "am broadcast -a " + VoiceImeService.ACTION_COMMIT
                + " --es text '要写入的文字'\n"
                + "# 替换整行加 --es mode replace";
    }

    private boolean imeEnabled() {
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                for (android.view.inputmethod.InputMethodInfo info : imm.getEnabledInputMethodList()) {
                    if (getPackageName().equals(info.getPackageName())) return true;
                }
                return false;
            }
        } catch (Throwable ignored) { }
        try {
            String s = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_INPUT_METHODS);
            return s != null && s.contains(getPackageName());
        } catch (Throwable e) {
            return false;
        }
    }

    private void openImeSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS));
        } catch (Throwable e) {
            toast("打不开输入法设置：" + e.getMessage());
        }
    }

    private void openOverlaySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:" + getPackageName())));
        } catch (Throwable e) {
            toast("打不开悬浮窗设置：" + e.getMessage());
        }
    }

    private void requestAudio() {
        if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        super.onRequestPermissionsResult(code, p, r);
        refresh();
        if (code == REQ_AUDIO && (r.length == 0 || r[0] != PackageManager.PERMISSION_GRANTED)) {
            toast("未授予录音权限，语音输入不可用");
        }
    }

    private void saveAndTest() {
        if (etBase == null) return;
        AsrSettings.save(this,
                etBase.getText().toString(),
                etKey.getText().toString(),
                etModel.getText().toString(),
                etLang.getText().toString());
        if (AsrSettings.apiKey(this).isEmpty()) {
            toast("请先填 API Key");
            return;
        }
        toast("已保存，正在测试连接…");
        new Thread(() -> {
            String msg;
            try {
                long t0 = System.currentTimeMillis();
                String r = CloudAsr.test(this);
                msg = "连接成功（" + (System.currentTimeMillis() - t0) + "ms）"
                        + "　返回：" + ((r == null || r.isEmpty()) ? "（空，正常：发的是静音）" : r);
            } catch (Throwable e) {
                msg = "连接失败：" + e.getMessage();
            }
            final String m = msg;
            runOnUiThread(() -> { toast(m); refresh(); });
        }, "asr-test").start();
    }

    private void listModels() {
        toast("正在拉取模型列表…");
        new Thread(() -> {
            String msg;
            try {
                String json = CloudPolish.listModels(this);
                java.util.List<String> ids = new java.util.ArrayList<>();
                try {
                    org.json.JSONObject o = new org.json.JSONObject(json);
                    org.json.JSONArray arr = o.optJSONArray("data");
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            org.json.JSONObject m = arr.optJSONObject(i);
                            if (m != null) ids.add(m.optString("id", "?"));
                        }
                    }
                } catch (Throwable ignored) { }
                msg = ids.isEmpty()
                        ? "返回（非标准格式）：" + (json.length() > 300 ? json.substring(0, 300) : json)
                        : "可用模型：\n" + android.text.TextUtils.join("\n", ids);
            } catch (Throwable e) {
                msg = "拉取失败：" + e.getMessage();
            }
            final String m = msg;
            runOnUiThread(() -> new android.app.AlertDialog.Builder(this)
                    .setTitle("模型列表")
                    .setMessage(m)
                    .setPositiveButton("知道了", null)
                    .show());
        }, "models-list").start();
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    /** 圆角纯色背景（不用 XML，直接代码生成） */
    private android.graphics.drawable.GradientDrawable roundBg(int color, float radiusDp) {
        android.graphics.drawable.GradientDrawable d =
                new android.graphics.drawable.GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radiusDp * getResources().getDisplayMetrics().density);
        return d;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
