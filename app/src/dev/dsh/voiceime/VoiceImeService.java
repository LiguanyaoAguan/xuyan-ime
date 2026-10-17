package dev.dsh.voiceime;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.inputmethodservice.InputMethodService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;

/**
 * 语音输入法。
 *
 * 两个入口共用一条落字通道（commitText）：
 *   1) 用户：点中央麦克风 → 录音 → 本机 SenseVoice → 上屏
 *   2) AI  ：发广播 dev.dsh.voiceime.COMMIT（extra: text / mode）→ 直接上屏
 *
 * 原则：**永不自动回车**。换行只能点「↵」，发送只能按目标 App 自己的发送键。
 */
public class VoiceImeService extends InputMethodService implements VoicePadView.Callback {

    private static final String TAG = "VimIme";
    public static final String ACTION_COMMIT = "dev.dsh.voiceime.COMMIT";
    public static final String EXTRA_TEXT = "text";
    public static final String EXTRA_MODE = "mode";     // append(默认) | replace
    public static final String PREFS = "vim";
    public static final String K_COMMIT_COUNT = "commit_count";

    private VoicePadView pad;
    private Recorder recorder;
    private final Handler ui = new Handler(Looper.getMainLooper());
    // 刻意不在这里取 AsrEngine：InputMethodService 的字段初始化器跑在构造函数里，
    // 那时 base context 还没 attach，连 getApplicationContext() 都是 null——
    // 实测会 FATAL「Unable to create service ... getApplicationContext() on a null」，
    // 表现就是输入法一选中就闪退。真正的初始化放 onCreate。
    private AsrEngine asr;
    private BroadcastReceiver commitRx;
    private boolean busy;
    private int lastCommitLen;
    private String recTargetPkg;
    private volatile boolean streamingOn;   // 本次录音是否启用了流式草稿
    private volatile String draftTarget = "";   // 模型最新给的草稿全文
    private int draftShown;                     // 已经落定（commitText）的字符数
    private int draftWritten;                   // 本次草稿总共写进输入框的字符数
    private boolean tickerOn;      // 录音开始时锁定的目标 App，防止中途切走还往原处写
    private long recStart;

    // ---------------------------------------------------------------- 生命周期

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "IME onCreate");
        asr = AsrEngine.get(this);
        // 预热：切到本输入法就把 239MB 模型后台载入，用户按麦克风时无需等待
        asr.warmUp();
        registerCommitReceiver();
    }

    @Override
    public View onCreateInputView() {
        setupCapsule();
        if (overlayMode && attachOverlay()) {
            applyStubWindow();          // 必须！否则输入法窗口仍是全屏，把 App 压成白屏
            return makeStub();
        }
        overlayMode = false;
        applyWindowLayout();
        return pad;
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        AsrEngine.get(this).warmUp();          // 双保险
        if (!AsrSettings.isCloud(this)) StreamingAsr.get(this).warmUp();

        // 关键：onCreateInputView 通常只会被调用一次，而 onFinishInputView 里我们
        // 把悬浮窗卸下来了。所以每次显示键盘都要在这里重新判断 + 重新挂上。
        setupCapsule();
        if (overlayMode) {
            if (!attachOverlay()) {
                // 权限被撤销或挂载失败：把胶囊改回输入视图，保证可用
                overlayMode = false;
                setInputView(pad);
                applyWindowLayout();
            } else {
                applyStubWindow();
                placeCapsule();
            }
        } else {
            applyWindowLayout();
        }
        refreshStatus();
    }

    // ---------------------------------------------------------------- 窗口

    private static final String K_WIN_X = "win_x";
    private static final String K_WIN_Y = "win_y";
    private static final int OVERLAY_TYPE =
            android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;

    private android.view.WindowManager wm;
    private android.view.WindowManager.LayoutParams capsuleLp;
    private boolean overlayMode;      // 是否用悬浮窗模式
    private boolean overlayAttached;  // 当前是否已挂在 WindowManager 上
    private boolean panelOpen;        // L2 面板是否展开
    private int capX, capY;           // 展开前胶囊的位置（收起时还原）

    /** 确保胶囊视图对象存在（整个服务生命周期只创建一次） */
    private void setupCapsule() {
        if (pad != null) return;
        pad = new VoicePadView(this);
        pad.setCallback(this);
        overlayMode = canOverlay();
        Log.i(TAG, "胶囊视图已创建，悬浮窗权限=" + overlayMode);
    }

    private boolean canOverlay() {
        try {
            return android.provider.Settings.canDrawOverlays(this);
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * 悬浮窗模式下，把**输入法窗口本身**设成底部一条透明条（全宽 × 胶囊高）。
     *
     * 这一步不能省：AOSP 的 SoftInputWindow 默认 MATCH_PARENT × MATCH_PARENT，
     * 如果不改，系统会认为键盘铺满整屏，App 内容区被压到 0 高度 → 白屏。
     * 改成底部 56dp 后，避让量和普通小键盘一样。
     */
    private void applyStubWindow() {
        // 0px 实验：高度为 0 时系统可能判定“键盘未显示”，从而完全不占位。
        // 若胶囊因此消失，把这里改回 1 即可。
        final int h = 0;
        try {
            android.view.Window win = getWindow().getWindow();
            android.view.WindowManager.LayoutParams lp = win.getAttributes();
            lp.width = android.view.WindowManager.LayoutParams.MATCH_PARENT;
            lp.height = h;
            lp.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.START;
            lp.x = 0;
            lp.y = 0;
            // 关掉键盘弹出的滑入/淡入动画：残留的“往上跳一下”主要来自这个动画
            lp.windowAnimations = 0;
            lp.dimAmount = 0f;
            win.setAttributes(lp);
            try {
                win.setWindowAnimations(0);
            } catch (Throwable ignored) { }
            Log.i(TAG, "输入法窗口已设为底部 " + h + "px（无动画）");
        } catch (Throwable e) {
            Log.e(TAG, "设置输入法窗口高度失败", e);
        }
    }

    /** 输入法窗口里的透明占位视图。不能用 1px，否则 WebView 会被挤白屏。 */
    private View makeStub() {
        View stub = new View(this);
        stub.setBackgroundColor(0x00000000);
        stub.setLayoutParams(new android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, 0));
        return stub;
    }

    /** 把胶囊挂成悬浮窗；已挂则直接返回 true */
    private boolean attachOverlay() {
        if (pad == null) return false;
        if (overlayAttached) return true;
        if (!canOverlay()) {
            Log.w(TAG, "没有悬浮窗权限，退回输入视图模式");
            return false;
        }
        wm = (android.view.WindowManager) getSystemService(WINDOW_SERVICE);
        if (wm == null) return false;

        float density = getResources().getDisplayMetrics().density;
        int w = (int) (VoicePadView.W * density);
        int h = (int) (VoicePadView.H * density);
        if (capsuleLp == null) {
            capsuleLp = new android.view.WindowManager.LayoutParams(
                    w, h, OVERLAY_TYPE,
                    android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    android.graphics.PixelFormat.TRANSLUCENT);
            capsuleLp.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
        }
        int[] pos = savedPos(w, h);
        capsuleLp.x = pos[0];
        capsuleLp.y = pos[1];
        try {
            wm.addView(pad, capsuleLp);
            overlayAttached = true;
            pad.setWindowPos(pos[0], pos[1]);
            Log.i(TAG, "胶囊悬浮窗已挂上 @" + pos[0] + "," + pos[1]);
            return true;
        } catch (Throwable e) {
            Log.e(TAG, "胶囊悬浮窗挂载失败", e);
            overlayAttached = false;
            return false;
        }
    }

    /** 只卸下悬浮窗，不销毁视图对象，方便下次重新挂 */
    private void detachOverlay() {
        if (!overlayAttached || pad == null || wm == null) return;
        try {
            wm.removeViewImmediate(pad);
            Log.i(TAG, "胶囊悬浮窗已卸下");
        } catch (Throwable e) {
            Log.w(TAG, "卸下悬浮窗失败", e);
        }
        overlayAttached = false;
        panelOpen = false;
    }

    /** 记住的位置（不存在则右侧、垂直偏上 42%） */
    private int[] savedPos(int w, int h) {
        float density = getResources().getDisplayMetrics().density;
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        int x = sp.getInt(K_WIN_X, -1);
        int y = sp.getInt(K_WIN_Y, -1);
        if (x < 0 || y < 0) {
            x = screenW - w - (int) (16 * density);
            y = (int) (screenH * 0.42f) - h / 2;
        }
        return new int[]{clamp(x, 0, screenW - w), clamp(y, 0, screenH - h)};
    }

    private void placeCapsule() {
        if (pad == null || panelOpen) return;
        float density = getResources().getDisplayMetrics().density;
        int w = (int) (VoicePadView.W * density);
        int h = (int) (VoicePadView.H * density);
        int[] pos = savedPos(w, h);
        moveCapsule(pos[0], pos[1]);
    }

    private void moveCapsule(int nx, int ny) {
        if (pad == null) return;
        if (overlayMode && overlayAttached && wm != null && capsuleLp != null) {
            capsuleLp.x = nx;
            capsuleLp.y = ny;
            try {
                wm.updateViewLayout(pad, capsuleLp);
                pad.setWindowPos(nx, ny);
            } catch (Throwable e) {
                Log.e(TAG, "移动悬浮窗失败", e);
            }
            return;
        }
        try {
            android.view.Window win = getWindow().getWindow();
            android.view.WindowManager.LayoutParams lp = win.getAttributes();
            lp.x = nx;
            lp.y = ny;
            win.setAttributes(lp);
            pad.setWindowPos(nx, ny);
        } catch (Throwable e) {
            Log.e(TAG, "移动输入法窗口失败", e);
        }
    }

    /** 回退模式：把输入法窗口本身做成胶囊大小 */
    private void applyWindowLayout() {
        if (pad == null) return;
        float density = getResources().getDisplayMetrics().density;
        int w = (int) (VoicePadView.W * density);
        int h = (int) (VoicePadView.H * density);
        int[] pos = savedPos(w, h);
        try {
            android.view.Window win = getWindow().getWindow();
            android.view.WindowManager.LayoutParams lp = win.getAttributes();
            lp.width = w;
            lp.height = h;
            lp.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
            lp.x = pos[0];
            lp.y = pos[1];
            win.setAttributes(lp);
            pad.setWindowPos(pos[0], pos[1]);
        } catch (Throwable e) {
            Log.e(TAG, "设置输入法窗口位置失败", e);
        }
    }

    private static int clamp(int v, int lo, int hi) {
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }

    @Override
    public void onMoveTo(int x, int y) {
        float density = getResources().getDisplayMetrics().density;
        int w = (int) (VoicePadView.W * density);
        int h = (int) (VoicePadView.H * density);
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        moveCapsule(clamp(x, 0, screenW - w), clamp(y, 0, screenH - h));
    }

    @Override
    public void onMoveEnd(int x, int y) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putInt(K_WIN_X, x).putInt(K_WIN_Y, y).apply();
    }

    /** 长按空白处 / 面板右上角 ⌄：在胶囊与 L2 面板之间切换 */
    @Override
    public void onExpandPanel() {
        togglePanel();
    }

    private void togglePanel() {
        if (pad == null) return;
        float d = getResources().getDisplayMetrics().density;
        int cw = (int) (VoicePadView.W * d), ch = (int) (VoicePadView.H * d);
        int pw = (int) (VoicePadView.PW * d), ph = (int) (VoicePadView.PH * d);
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;

        if (!overlayAttached || capsuleLp == null || wm == null) {
            toast("面板需要悬浮窗权限");
            return;
        }

        if (!panelOpen) {
            capX = capsuleLp.x;
            capY = capsuleLp.y;
            int nx = clamp(capX + cw / 2 - pw / 2, 0, screenW - pw);
            // 胶囊在下半屏 → 面板向上长；在上半屏 → 向下长
            int ny = (capY + ch > screenH / 2) ? (capY + ch - ph) : capY;
            ny = clamp(ny, 0, screenH - ph);
            capsuleLp.width = pw;
            capsuleLp.height = ph;
            capsuleLp.x = nx;
            capsuleLp.y = ny;
            try {
                wm.updateViewLayout(pad, capsuleLp);
                pad.setWindowPos(nx, ny);
                panelOpen = true;
            } catch (Throwable e) {
                Log.e(TAG, "展开面板失败", e);
            }
        } else {
            capsuleLp.width = cw;
            capsuleLp.height = ch;
            capsuleLp.x = clamp(capX, 0, screenW - cw);
            capsuleLp.y = clamp(capY, 0, screenH - ch);
            try {
                wm.updateViewLayout(pad, capsuleLp);
                pad.setWindowPos(capsuleLp.x, capsuleLp.y);
                panelOpen = false;
            } catch (Throwable e) {
                Log.e(TAG, "收起面板失败", e);
            }
        }
    }

    /** 面板里的「撤销」：撤掉上一次语音上屏的那一段 */
    @Override
    public void onUndoLast() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        int n = lastCommitLen;
        if (n <= 0) {
            toast("没有可撤销的上屏内容");
            return;
        }
        try {
            ic.finishComposingText();
            ic.deleteSurroundingText(n, 0);
            lastCommitLen = 0;
        } catch (Throwable e) {
            Log.e(TAG, "撤销上屏失败", e);
        }
    }

    /** 面板里的「剪贴板」：把剪贴板内容直接粘到输入框 */
    @Override
    public void onPasteClipboard() {
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    getSystemService(CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip() || cm.getPrimaryClip() == null
                    || cm.getPrimaryClip().getItemCount() == 0) {
                toast("剪贴板是空的");
                return;
            }
            CharSequence t = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
            if (t == null || t.length() == 0) {
                toast("剪贴板是空的");
                return;
            }
            InputConnection ic = getCurrentInputConnection();
            if (ic == null) {
                toast("当前没有输入框");
                return;
            }
            String text = t.toString();
            ic.commitText(text, 1);
            lastCommitLen = text.length();
            toast("已粘贴 " + text.length() + " 字");
        } catch (Throwable e) {
            Log.e(TAG, "粘贴失败", e);
            toast("粘贴失败：" + e.getMessage());
        }
    }

    /** 面板里的「设置」：打开本 App 的安装与自检页 */
    @Override
    public void onOpenSettings() {
        openSettings();
        requestHideSelf(0);
    }

    @Override
    public void onFinishInputView(boolean finishingInput) {
        if (recorder != null && recorder.isRecording()) recorder.cancel();
        detachOverlay();                    // 只卸下，不销毁，下次还要挂回去
        super.onFinishInputView(finishingInput);
    }

    @Override
    public void onDestroy() {
        if (commitRx != null) try { unregisterReceiver(commitRx); } catch (Throwable ignored) { }
        if (recorder != null) recorder.cancel();
        detachOverlay();
        pad = null;
        super.onDestroy();
    }

    @Override
    public boolean onEvaluateFullscreenMode() { return false; }

    // ---------------------------------------------------------------- AI 注入通道

    private void registerCommitReceiver() {
        commitRx = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                if (i == null || !ACTION_COMMIT.equals(i.getAction())) return;
                final String text = i.getStringExtra(EXTRA_TEXT);
                final String mode = i.getStringExtra(EXTRA_MODE);
                if (text == null) return;
                ui.post(() -> doCommit(text, "replace".equals(mode)));
            }
        };
        IntentFilter f = new IntentFilter(ACTION_COMMIT);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(commitRx, f, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(commitRx, f);
        }
        Log.i(TAG, "AI 注入通道已就绪：" + ACTION_COMMIT);
    }

    /** 落字：这是输入法唯一能往任意 App 输入框写文本的通道 */
    /**
     * 打字机节奏：模型给什么先存着，界面**每 45ms 只落定一个字**。
     * 这样不管模型是一次吐 2 个还是 6 个字，看起来都是均匀地一个个蹦。
     */
    private final Runnable draftTick = new Runnable() {
        @Override public void run() {
            if (!tickerOn) return;
            InputConnection ic = getCurrentInputConnection();
            String fromModel = StreamingAsr.get(VoiceImeService.this).latest();
            if (fromModel != null && !fromModel.isEmpty()) draftTarget = fromModel;
            String t = draftTarget;
            if (ic != null && t != null) {
                try {
                    // 模型回改（新结果和已落定的前缀不一致）→ 整体重来
                    if (draftShown > t.length()) {
                        ic.setComposingText("", 1);
                        ic.finishComposingText();
                        ic.deleteSurroundingText(draftShown, 0);
                        draftWritten = Math.max(0, draftWritten - draftShown);
                        draftShown = 0;
                    }
                    if (draftShown < t.length()) {
                        ic.commitText(t.substring(draftShown, draftShown + 1), 1);
                        draftShown++;
                    }
                    String tail = t.substring(Math.min(draftShown, t.length()));
                    ic.setComposingText(tail, 1);
                    draftWritten = t.length();
                } catch (Throwable e) {
                    Log.e(TAG, "草稿落字失败", e);
                }
            }
            ui.postDelayed(this, 30);
        }
    };

    /** 模型给来新草稿：只更新目标文本，由 ticker 匀速落字 */
    private void applyDraft(String text) {
        if (text == null) return;
        draftTarget = text;
    }

    private void startDraftTicker() {
        tickerOn = true;
        ui.removeCallbacks(draftTick);
        ui.post(draftTick);
    }

    private void stopDraftTicker() {
        tickerOn = false;
        ui.removeCallbacks(draftTick);
    }

    private static int commonPrefix(String a, String b) {
        if (a == null || b == null) return 0;
        int n = Math.min(a.length(), b.length());
        int i = 0;
        while (i < n && a.charAt(i) == b.charAt(i)) i++;
        return i;
    }

    /** 收尾：把本次草稿写进输入框的字符全部撤回，交给最终结果重新写 */
    private void rollbackDraft() {
        stopDraftTicker();
        int n = Math.max(draftWritten, draftShown);
        draftShown = 0;
        draftWritten = 0;
        draftTarget = "";
        if (n <= 0) return;
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        try {
            ic.setComposingText("", 1);
            ic.finishComposingText();
            ic.deleteSurroundingText(n, 0);
        } catch (Throwable e) {
            Log.e(TAG, "撤回草稿失败", e);
        }
    }

    private void clearComposing() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        try {
            ic.setComposingText("", 1);
            ic.finishComposingText();
        } catch (Throwable ignored) { }
    }

    private String currentPkg() {
        try {
            EditorInfo ei = getCurrentInputEditorInfo();
            return (ei == null || ei.packageName == null) ? "" : ei.packageName.toString();
        } catch (Throwable e) {
            return "";
        }
    }

    private void doCommit(String text, boolean replace) {
        // 焦点保护：录音期间切到了别的 App，就不要再写，避免把话写到错误的地方
        String nowPkg = currentPkg();
        if (recTargetPkg != null && !recTargetPkg.isEmpty()
                && !nowPkg.isEmpty() && !nowPkg.equals(recTargetPkg)) {
            Log.w(TAG, "目标已切换：" + recTargetPkg + " → " + nowPkg + "，丢弃本次结果");
            toast("输入框已切换，本次未写入");
            return;
        }
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) {
            Log.w(TAG, "当前没有输入连接，丢弃：" + text);
            toast("当前没有输入框，未写入");
            return;
        }
        try {
            if (replace) {
                CharSequence before = ic.getTextBeforeCursor(10000, 0);
                int n = before == null ? 0 : before.length();
                if (n > 0) ic.deleteSurroundingText(n, 0);
            }
            ic.commitText(text, 1);
            ic.finishComposingText();
            lastCommitLen = text.length();
            SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
            sp.edit().putInt(K_COMMIT_COUNT, sp.getInt(K_COMMIT_COUNT, 0) + 1).apply();
            Log.i(TAG, "已写入 " + text.length() + " 字" + (replace ? "（替换）" : ""));
        } catch (Throwable e) {
            Log.e(TAG, "写入失败", e);
        }
    }

    // ---------------------------------------------------------------- VoicePad 回调

    @Override
    public void onMicToggle() {
        if (busy) return;
        if (recorder != null && recorder.isRecording()) stopAndTranscribe();
        else startRecord();
    }

    /** 按住说话：按下就开始录 */
    @Override
    public void onMicHoldStart() {
        if (busy) return;
        if (recorder != null && recorder.isRecording()) return;   // 已经在录（切换式）
        startRecord();
    }

    /** 按住说话：松手就结束并识别 */
    @Override
    public void onMicHoldEnd() {
        if (busy) return;
        if (recorder != null && recorder.isRecording()) stopAndTranscribe();
    }

    private void startRecord() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            pad.setState(VoicePadView.S_ERR);
            toast("请先授予录音权限");
            openSettings();
            return;
        }
        // 云端引擎不需要本地模型；本机引擎才检查模型是否就位
        if (!AsrSettings.isCloud(this)) {
            AsrEngine e = AsrEngine.get(this);
            if (!e.modelPresent()) {
                pad.setState(VoicePadView.S_ERR);
                return;
            }
            e.warmUp();                               // 万一还没预热
        } else if (AsrSettings.apiKey(this).isEmpty()) {
            pad.setState(VoicePadView.S_ERR);
            toast("请先在 App 里填 API Key");
            return;
        }

        recorder = new Recorder();
        recStart = System.currentTimeMillis();
        recTargetPkg = currentPkg();
        Log.i(TAG, "开始录音，目标 App = " + recTargetPkg);
        boolean ok = recorder.start((lvl, ms) -> ui.post(() -> {
            if (pad != null) pad.setLevel(lvl);
        }));
        if (!ok) {
            pad.setState(VoicePadView.S_ERR);
            recorder = null;
            return;
        }
        pad.setState(VoicePadView.S_REC);

        // ---- 流式草稿（边说边出字）----
        StreamingAsr st = StreamingAsr.get(this);
        streamingOn = !AsrSettings.isCloud(this) && st.modelPresent();
        draftTarget = "";
        draftShown = 0;
        draftWritten = 0;
        if (streamingOn) {
            st.warmUp();
            st.start();
            startDraftTicker();
            // 录音线程只入队，解码在 StreamingAsr 自己的线程里跑，绝不阻塞录音
            recorder.setPcmListener(chunk -> st.push(chunk));
        }
    }

    private void stopAndTranscribe() {
        float[] pcm = recorder.stop();
        recorder = null;
        pad.setState(VoicePadView.S_IDLE);

        if (pcm.length < AsrEngine.SAMPLE_RATE / 5) {     // <0.2s 视为误触
            pad.setState(VoicePadView.S_IDLE);
            return;
        }
        final boolean cloud = AsrSettings.isCloud(this);
        final String draft;
        if (streamingOn) {
            draft = StreamingAsr.get(this).finish();
            streamingOn = false;
            rollbackDraft();          // 把草稿全部撤回，最终文字重新写
        } else {
            draft = "";
        }
        busy = true;
        pad.setState(VoicePadView.S_ASR);

        new Thread(() -> {
            long t0 = System.currentTimeMillis();
            String text;
            try {
                if (cloud) {
                    // 云端整段送，不切句：模型自带 VAD 和标点，切碎反而更差
                    text = CloudAsr.transcribe(VoiceImeService.this, pcm, AsrEngine.SAMPLE_RATE);
                } else {
                    AsrEngine e = AsrEngine.get(VoiceImeService.this);
                    if (!e.isReady() && e.isLoading()) {
                        for (int i = 0; i < 300 && !e.isReady() && e.isLoading(); i++) Thread.sleep(100);
                    }
                    if (!e.isReady()) {
                        final String err = e.loadError();
                        ui.post(() -> { busy = false; pad.setState(VoicePadView.S_ERR); });
                        return;
                    }
                    text = e.transcribe(pcm, AsrEngine.SAMPLE_RATE);
                }
            } catch (Throwable ex) {
                Log.e(TAG, "转写异常", ex);
                final String m = ex.getMessage();
                ui.post(() -> { busy = false; pad.setState(VoicePadView.S_ERR); Log.w(TAG, "转写失败：" + m); });
                return;
            }
            final int cost = (int) (System.currentTimeMillis() - t0);
            final int dur = pcm.length / AsrEngine.SAMPLE_RATE;

            // 决定最终文本：优先离线/云端结果；都空就用流式草稿兜底
            String finalText = (text == null) ? "" : text.trim();
            if (finalText.isEmpty() && draft != null) finalText = AsrEngine.cleanPunct(draft);
            final String committed = finalText;

            ui.post(() -> {
                busy = false;
                if (committed.isEmpty()) {
                    pad.setState(VoicePadView.S_ERR);
                    return;
                }
                doCommit(committed, false);
                pad.setState(VoicePadView.S_DONE);        // 描边闪一次绿

                // AI 润色（可选，纯文本请求；失败就保持原文）
                if (AsrSettings.polishEnabled(VoiceImeService.this)
                        && !AsrSettings.apiKey(VoiceImeService.this).isEmpty()) {
                    final int committedLen = committed.length();
                    new Thread(() -> {
                        try {
                            String better = CloudPolish.polish(VoiceImeService.this, committed);
                            if (better == null || better.isEmpty() || better.equals(committed)) return;
                            ui.post(() -> {
                                InputConnection ic = getCurrentInputConnection();
                                if (ic == null) return;
                                try {
                                    ic.deleteSurroundingText(committedLen, 0);
                                    ic.commitText(better, 1);
                                    lastCommitLen = better.length();
                                    pad.setState(VoicePadView.S_DONE);
                                } catch (Throwable e) {
                                    Log.w(TAG, "润色替换失败", e);
                                }
                            });
                        } catch (Throwable e) {
                            Log.w(TAG, "润色失败（保持原文）：" + e.getMessage());
                        }
                    }, "vim-polish").start();
                }
                Log.i(TAG, "上屏 " + committed.length() + " 字 / " + cost + "ms / "
                        + (cloud ? "云端" : "本机") + " / 音频 " + dur + "s");
                // 绿闪结束后回到空闲色（胶囊外形不变）
                ui.postDelayed(() -> {
                    if (!busy && pad != null && pad.getState() == VoicePadView.S_DONE) {
                        pad.setState(VoicePadView.S_IDLE);
                    }
                }, 600);
            });
        }, "vim-asr").start();
    }

    /**
     * ⌫ 退一个字（单击）/ 连续退（长按由 View 侧定时驱动）。
     * 先结束组合态，避免删到「未定稿」那一段。
     */
    @Override
    public void onUndo() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        try {
            if (ic.getSelectedText(0) != null && ic.getSelectedText(0).length() > 0) {
                ic.commitText("", 1);            // 有选区就删选区
                return;
            }
            ic.finishComposingText();
            if (!ic.deleteSurroundingText(1, 0)) {
                sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL);
            }
        } catch (Throwable e) {
            Log.e(TAG, "退格失败", e);
        }
    }

    /** 按住 ⌫ 上滑、松手：清空输入框里的全部文字 */
    @Override
    public void onClearAll() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        try {
            ic.setComposingText("", 1);
            ic.finishComposingText();
            CharSequence before = ic.getTextBeforeCursor(20000, 0);
            CharSequence after = ic.getTextAfterCursor(20000, 0);
            int nBefore = before == null ? 0 : before.length();
            int nAfter = after == null ? 0 : after.length();
            if (nBefore > 0 || nAfter > 0) {
                ic.deleteSurroundingText(nBefore, nAfter);
            }
        } catch (Throwable e) {
            Log.e(TAG, "清空失败", e);
        }
    }

    /**
     * ↵ 换行。
     *
     * 实测结论（2026-10-18）：WebView 内核会把 commitText("\n") 翻译成一个 Enter 按键事件，
     * 所以对 DSH 这类「Enter = 发送」的应用来说，commitText("\n") 并不安全，会直接把消息发出去。
     *
     * 因此分两条路：
     *   · DSH / Harness：发 **Shift+Enter**（它自己定义的换行快捷键）
     *   · 其它 App：照常用 commitText("\n")
     */
    @Override
    public void onEnter() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) {
            toast("当前没有输入框");
            return;
        }
        try {
            ic.finishComposingText();
            EditorInfo ei = getCurrentInputEditorInfo();
            String pkg = (ei == null || ei.packageName == null) ? "" : ei.packageName.toString();
            boolean harness = pkg.contains("dsharnessmobile") || pkg.toLowerCase().contains("harness");

            if (harness) {
                long t = android.os.SystemClock.uptimeMillis();
                ic.sendKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN,
                        KeyEvent.KEYCODE_ENTER, 0, KeyEvent.META_SHIFT_ON));
                ic.sendKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP,
                        KeyEvent.KEYCODE_ENTER, 0, KeyEvent.META_SHIFT_ON));
                Log.i(TAG, "换行：Shift+Enter（" + pkg + "）");
            } else {
                ic.commitText("\n", 1);
                Log.i(TAG, "换行：commitText（" + pkg + "）");
            }
        } catch (Throwable e) {
            Log.e(TAG, "插入换行失败", e);
        }
    }

    /** 双击胶囊空白处：收起输入法（回到系统默认输入法） */
    @Override
    public void onCollapse() {
        if (recorder != null && recorder.isRecording()) recorder.cancel();
        recorder = null;
        requestHideSelf(0);
    }

    // ---------------------------------------------------------------- 工具

    private void openSettings() {
        try {
            Intent i = new Intent(this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable e) {
            toast("打不开设置：" + e.getMessage());
        }
    }

    private void refreshStatus() {
        if (pad == null) return;

        // 以「录音器 / 识别任务」的真实状态为准，而不是看界面当前显示什么。
        // 录音时草稿会不断写进输入框，WebView 可能因此重新绑定输入法并再次调用
        // onStartInputView —— 旧写法此时会把红色麦克风刷回蓝色，看起来像"没在录"。
        if (recorder != null && recorder.isRecording()) {
            pad.setState(VoicePadView.S_REC);
            return;
        }
        if (busy) {
            pad.setState(VoicePadView.S_ASR);
            return;
        }
        int st = pad.getState();
        if (st == VoicePadView.S_REC || st == VoicePadView.S_ASR) return;

        if (AsrSettings.isCloud(this)) {
            pad.setState(VoicePadView.S_IDLE);
            return;
        }
        AsrEngine e = AsrEngine.get(this);
        if (e.isReady() || e.isLoading()) {
            pad.setState(VoicePadView.S_IDLE);
        } else if (!e.modelPresent()) {
            pad.setState(VoicePadView.S_ERR);
        } else {
            pad.setState(VoicePadView.S_IDLE);
        }
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
