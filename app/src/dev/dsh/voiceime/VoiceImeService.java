package dev.dsh.voiceime;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ClipboardManager;
import android.content.ClipData;
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
    private boolean numericKeyboardMode;
    private android.widget.PopupWindow numericKeyboardPopup;
    private FloatingQwertyView qwertyView;
    // 键盘展开时使用的独立胶囊实例。绝不把主胶囊 pad 挪进别的父容器：
    // 同一个 View 同时被 WindowManager 和键盘容器持有会直接抛
    // "The specified child already has a parent"，展开就此中断且不回滚。
    private VoicePadView padExpanded;
    private android.view.WindowManager qwertyWm;
    private android.view.WindowManager.LayoutParams qwertyLp;
    private boolean qwertyDragging;
    private View qwertyRoot;
    private float qwertyDownRawX, qwertyDownRawY;
    private int qwertyDragStartX, qwertyDragStartY;
    private static final String K_QWERTY_X = "qwerty_x";
    private static final String K_QWERTY_Y = "qwerty_y";
    private InputConnection keyboardTargetConnection;
    private String keyboardTargetPackage;

    // 剪贴板历史仅保存在本次服务进程内存中，最多 10 条，不落盘、不写日志。
    private final java.util.ArrayList<String> clipboardHistory = new java.util.ArrayList<>();
    private android.view.WindowManager historyWm;
    private View historyView;
    private android.view.WindowManager.LayoutParams historyLp;
    private android.widget.LinearLayout historyList;
    private boolean historyAttached;
    private InputConnection historyTargetConnection;
    private String historyTargetPackage;
    private String recTargetPkg;
    private volatile boolean streamingOn;   // 本次录音是否启用了流式草稿
    private volatile boolean forceCloudTake; // 本次录音是否由「长按麦克风」发起 → 强制走云端
    private volatile String draftTarget = "";   // 模型最新给的草稿全文
    private int draftShown;                     // 已经落定（commitText）的字符数
    private int draftWritten;                   // 本次草稿总共写进输入框的字符数
    private boolean tickerOn;      // 录音开始时锁定的目标 App，防止中途切走还往原处写
    private long recStart;
    // 每次录音/识别使用代次令牌；焦点变更或取消后，旧后台结果不得再上屏。
    private volatile long captureGeneration;
    private InputConnection recTargetConnection;
    private String recTargetEditorKey;

    // ---------------------------------------------------------------- 生命周期

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "IME onCreate");
        asr = AsrEngine.get(this);
        // 应用上次选定的本机识别语言（默认 zh）。强制 zh 时英文专名容易被音译。
        asr.setLanguage(AsrSettings.localLanguage(this));
        // 预热：切到本输入法就把 239MB 模型后台载入，用户按麦克风时无需等待
        asr.warmUp();
        registerCommitReceiver();
    }

    @Override
    public View onCreateInputView() {
        setupCapsule();
        if(qwertyRoot!=null){applyStubWindow();return makeStub();}
        if (inlineKeyboardView instanceof FloatingQwertyView) {
            setInputView(inlineKeyboardView);
            applyNumericWindowLayout();
            return inlineKeyboardView;
        }
        if (inlineKeyboardView != null) {
            setInputView(inlineKeyboardView);
            applyNumericWindowLayout();
            return inlineKeyboardView;
        }
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
        cancelCaptureIfTargetChanged("onStartInputView");
        AsrEngine.get(this).warmUp();          // 双保险
        if (!AsrSettings.isCloud(this)) StreamingAsr.get(this).warmUp();

        // 浮动 QWERTY 自身承载胶囊与键区，保持独立输入法窗口透明占位。
        if(qwertyRoot!=null){applyStubWindow();refreshStatus();return;}
        // 关键：onCreateInputView 通常只会被调用一次，而 onFinishInputView 里我们
        // 把悬浮窗卸下来了。所以每次显示键盘都要在这里重新判断 + 重新挂上。
        if (inlineKeyboardView instanceof FloatingQwertyView) {
            setInputView(inlineKeyboardView);
            applyNumericWindowLayout();
            refreshStatus();
            return;
        }
        if (inlineKeyboardView != null) {
            setInputView(inlineKeyboardView);
            applyNumericWindowLayout();
            refreshStatus();
            return;
        }
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
    private View inlineKeyboardView;
    private boolean panelOpen;        // L2 面板是否展开
    private int capX, capY;           // 展开前胶囊的位置（收起时还原）

    /** 确保胶囊视图对象存在（整个服务生命周期只创建一次） */
    private void setupCapsule() {
        if (pad != null) return;
        pad = new VoicePadView(this);
        pad.setWindowPos(getSharedPreferences(PREFS,MODE_PRIVATE).getInt(K_WIN_X,-1),getSharedPreferences(PREFS,MODE_PRIVATE).getInt(K_WIN_Y,-1));
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

    // ------------------------------------------------------------ 胶囊状态转发
    // 录音/识别状态必须同时落到"当前可见的那个胶囊"上：常态是 pad，
    // 键盘展开时 pad 已移出屏幕，只有 padExpanded 可见。

    private VoicePadView activePad() { return padExpanded != null ? padExpanded : pad; }

    private void setPadState(int state) {
        VoicePadView a = activePad();
        if (a != null) a.setState(state);
    }

    private void setPadLevel(float level) {
        VoicePadView a = activePad();
        if (a != null) a.setLevel(level);
    }

    private int activePadState() {
        VoicePadView a = activePad();
        return a == null ? VoicePadView.S_IDLE : a.getState();
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
        if(pad.getParent()!=null){try{((android.view.ViewGroup)pad.getParent()).removeView(pad);}catch(Throwable ignored){}}
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
        // 输入法整体收起时不能走 hideQwertyKeyboard()：那个方法会把胶囊重新挂回屏幕。
        if (qwertyRoot != null && qwertyWm != null) {
            try { qwertyWm.removeViewImmediate(qwertyRoot); } catch (Throwable ignored) { }
        }
        qwertyRoot = null; qwertyWm = null; qwertyLp = null;
        qwertyView = null; padExpanded = null; qwertyDragging = false;
        if (inlineKeyboardView instanceof FloatingQwertyView) {
            inlineKeyboardView = null;
            try { setInputView(makeStub()); } catch (Throwable ignored) { }
        }
        hideNumericKeyboard();
        dismissClipboardHistory();
        if (!overlayAttached || pad == null || wm == null) { overlayAttached = false; return; }
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
        if (pad == null) return;
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
                getSharedPreferences(PREFS,MODE_PRIVATE).edit().putInt(K_WIN_X,nx).putInt(K_WIN_Y,ny).apply();
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
            getSharedPreferences(PREFS,MODE_PRIVATE).edit().putInt(K_WIN_X,nx).putInt(K_WIN_Y,ny).apply();
        } catch (Throwable e) {
            Log.e(TAG, "移动输入法窗口失败", e);
        }
    }

    private void applyNumericWindowLayout() {
        if (inlineKeyboardView == null) return;
        try {
            android.view.Window win = getWindow().getWindow();
            android.view.WindowManager.LayoutParams lp = win.getAttributes();
            lp.width = android.view.WindowManager.LayoutParams.MATCH_PARENT;
            lp.height = android.view.WindowManager.LayoutParams.WRAP_CONTENT;
            lp.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.START;
            lp.x = 0;
            lp.y = 0;
            win.setAttributes(lp);
        } catch (Throwable e) {
            Log.e(TAG, "设置数字键盘窗口失败", e);
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

    /** 长按剪贴板：显示本机内存历史（不是系统全局剪贴板历史）。 */
    @Override
    public void onToggleKeyboard() {
        if (qwertyView != null || inlineKeyboardView instanceof FloatingQwertyView) hideQwertyKeyboard();
        else showQwertyKeyboard();
    }

    private void showQwertyKeyboard() {
        if (qwertyRoot != null || inlineKeyboardView instanceof FloatingQwertyView) return;   // 已经展开
        hideNumericKeyboard();
        keyboardTargetConnection = getCurrentInputConnection();
        EditorInfo editor = getCurrentInputEditorInfo();
        keyboardTargetPackage = editor == null ? null : editor.packageName;
        if (keyboardTargetConnection == null) { toast("当前没有输入框"); return; }
        final SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        boolean hadCapsulePosition = capsuleLp != null || sp.contains(K_WIN_X);
        int capsuleX = capsuleLp == null ? sp.getInt(K_WIN_X, -1) : capsuleLp.x;
        int capsuleY = capsuleLp == null ? sp.getInt(K_WIN_Y, -1) : capsuleLp.y;
        if (wm == null) wm = (android.view.WindowManager) getSystemService(WINDOW_SERVICE);
        boolean useFloating = canOverlay() && wm != null;

        final android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        final int width = Math.min(dp(540), Math.max(dp(300), dm.widthPixels - dp(24)));
        final int height = Math.min(dp((int) VoicePadView.EXPANDED_H),
                Math.max(dp((int) VoicePadView.H + 40), dm.heightPixels - dp(36)));

        // 展开态的胶囊是**另一个实例**。主胶囊 pad 始终只属于胶囊窗口，
        // 不搬进键盘布局 —— 同一个 View 被两个父容器持有会抛
        // IllegalStateException，之前的"半展开死状态"就是这么来的。
        padExpanded = new VoicePadView(this);
        padExpanded.setExpandedKeyboard(true);
        padExpanded.setCallback(this);
        padExpanded.setWindowPos(0, 0);
        FloatingQwertyView view = new FloatingQwertyView(this, padExpanded);
        view.setActions(new FloatingQwertyView.Actions() {
            @Override public void onKey(String key) { commitQwertyKey(key); }
            @Override public void onVoice() { hideQwertyKeyboard(); }
        });
        view.setOnDragHandle(new FloatingQwertyView.DragHandle() {
            @Override public void onDown(float rawX, float rawY) {
                qwertyDragging = false;
                qwertyDownRawX = rawX; qwertyDownRawY = rawY;
                qwertyDragStartX = qwertyLp == null ? dp(12) : qwertyLp.x;
                qwertyDragStartY = qwertyLp == null ? dm.heightPixels - height - dp(18) : qwertyLp.y;
            }
            @Override public void onMove(float rawX, float rawY) {
                if (Math.abs(rawX - qwertyDownRawX) + Math.abs(rawY - qwertyDownRawY) > dp(5)) qwertyDragging = true;
                if (!qwertyDragging || qwertyLp == null || qwertyWm == null || qwertyRoot == null) return;
                qwertyLp.x = clamp(qwertyDragStartX + (int) (rawX - qwertyDownRawX), 0, Math.max(0, dm.widthPixels - width));
                qwertyLp.y = clamp(qwertyDragStartY + (int) (rawY - qwertyDownRawY), dp(24), Math.max(dp(24), dm.heightPixels - height - dp(12)));
                try { qwertyWm.updateViewLayout(qwertyRoot, qwertyLp); } catch (Throwable ignored) { }
            }
            @Override public void onUp() {
                if (qwertyLp != null) {
                    sp.edit().putInt(K_QWERTY_X, qwertyLp.x).putInt(K_QWERTY_Y, qwertyLp.y).apply();
                }
            }
        });

        if (!useFloating) {
            if (overlayAttached && wm != null) {
                try { wm.removeViewImmediate(pad); } catch (Throwable ignored) { }
                overlayAttached = false;
            }
            if (pad.getParent() != null) {
                try { ((android.view.ViewGroup) pad.getParent()).removeView(pad); } catch (Throwable ignored) { }
            }
            qwertyView = view;
            inlineKeyboardView = view;
            try {
                setInputView(view);
                applyNumericWindowLayout();
                Log.i(TAG, "QWERTY 已展开为输入法内嵌视图");
            } catch (Throwable e) {
                Log.e(TAG, "内嵌键盘展开失败", e);
                abortQwertyKeyboard();
            }
            return;
        }

        int x = sp.getInt(K_QWERTY_X, Math.max(dp(12), (dm.widthPixels - width) / 2));
        int y = sp.getInt(K_QWERTY_Y, Math.max(dp(24), dm.heightPixels - height - dp(18)));
        x = clamp(x, 0, Math.max(0, dm.widthPixels - width));
        y = clamp(y, dp(24), Math.max(dp(24), dm.heightPixels - height - dp(12)));
        if (hadCapsulePosition && capsuleX >= 0 && capsuleY >= 0) {
            // 让展开后面板顶部的胶囊停在原胶囊位置附近
            x = clamp(capsuleX, 0, Math.max(0, dm.widthPixels - width));
            y = clamp(capsuleY - height + dp((int) VoicePadView.H),
                    dp(24), Math.max(dp(24), dm.heightPixels - height - dp(12)));
        }
        qwertyRoot = view;
        qwertyRoot.setLayoutParams(new android.view.ViewGroup.LayoutParams(width, height));
        qwertyWm = wm;
        qwertyLp = new android.view.WindowManager.LayoutParams(width, height, OVERLAY_TYPE,
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.graphics.PixelFormat.TRANSLUCENT);
        qwertyLp.gravity = android.view.Gravity.TOP | android.view.Gravity.LEFT;
        qwertyLp.x = x; qwertyLp.y = y;
        try {
            if (overlayAttached) { wm.removeViewImmediate(pad); overlayAttached = false; }
            // 兼容"没有悬浮权限时胶囊曾作为输入视图"的情况：先确保原胶囊离开屏幕，
            // 否则会出现「胶囊 + 键盘」两个面板叠在一起。
            if (pad.getParent() != null) {
                try { ((android.view.ViewGroup) pad.getParent()).removeView(pad); } catch (Throwable ignored) { }
            }
            qwertyWm.addView(qwertyRoot, qwertyLp);
            qwertyView = view;
            pad.setWindowPos(x, y);
            applyStubWindow();
            Log.i(TAG, "QWERTY 浮窗已展开 @" + x + "," + y + " " + width + "x" + height);
        } catch (Throwable e) {
            Log.e(TAG, "QWERTY 浮窗展开失败，回滚到胶囊", e);
            abortQwertyKeyboard();
        }
    }

    /** 展开失败时的统一回滚：不留"键盘已隐藏、胶囊又没回来"的死状态。 */
    private void abortQwertyKeyboard() {
        if (qwertyRoot != null && qwertyWm != null) {
            try { qwertyWm.removeViewImmediate(qwertyRoot); } catch (Throwable ignored) { }
        }
        qwertyRoot = null; qwertyWm = null; qwertyLp = null;
        qwertyView = null; padExpanded = null; qwertyDragging = false;
        inlineKeyboardView = null;
        keyboardTargetConnection = null; keyboardTargetPackage = null;
        if (pad != null) pad.setExpandedKeyboard(false);
        try {
            overlayMode = canOverlay();
            if (overlayMode && attachOverlay()) {
                applyStubWindow();
                setInputView(makeStub());
            } else {
                overlayMode = false;
                setInputView(pad);
                applyWindowLayout();
            }
        } catch (Throwable e) {
            Log.e(TAG, "回滚胶囊失败", e);
        }
        toast("键盘展开失败，已恢复胶囊");
    }

    private void commitQwertyKey(String key) {
        InputConnection current = getCurrentInputConnection();
        EditorInfo editor = getCurrentInputEditorInfo();
        String pkg = editor == null ? null : editor.packageName;
        if (current == null || current != keyboardTargetConnection || !java.util.Objects.equals(pkg, keyboardTargetPackage)) {
            toast("输入目标已变化，未输入"); hideQwertyKeyboard(); return;
        }
        try {
            if ("⌫".equals(key)) {
                CharSequence selected = current.getSelectedText(0);
                if (selected != null && selected.length() > 0) current.commitText("", 1);
                else current.deleteSurroundingText(1, 0);
            } else if ("↵".equals(key)) {
                if (current == getCurrentInputConnection()) onEnter();
            }
            else if ("空格".equals(key)) current.commitText(" ", 1);
            else current.commitText(key, 1);
        } catch (Throwable e) { toast("按键输入失败"); }
    }

    private void hideQwertyKeyboard() {
        boolean wasInline = inlineKeyboardView instanceof FloatingQwertyView;
        final SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        int restoreX = qwertyLp == null
                ? (capsuleLp == null ? sp.getInt(K_WIN_X, dp(12)) : capsuleLp.x) : qwertyLp.x;
        int restoreY = qwertyLp == null
                ? (capsuleLp == null ? sp.getInt(K_WIN_Y, dp(120)) : capsuleLp.y)
                : qwertyLp.y + dp((int) (VoicePadView.EXPANDED_H - VoicePadView.H));
        if (qwertyRoot != null && qwertyWm != null) {
            try { qwertyWm.removeViewImmediate(qwertyRoot); } catch (Throwable ignored) { }
        }
        qwertyRoot = null; qwertyWm = null; qwertyLp = null; qwertyDragging = false;
        qwertyView = null; padExpanded = null;
        keyboardTargetConnection = null; keyboardTargetPackage = null;
        if (pad == null) setupCapsule();
        if (pad == null) return;
        pad.setExpandedKeyboard(false);
        pad.setWindowPos(restoreX, restoreY);
        sp.edit().putInt(K_WIN_X, restoreX).putInt(K_WIN_Y, restoreY).apply();
        if (wasInline) {
            inlineKeyboardView = null;
            overlayMode = canOverlay();
            try {
                if (overlayMode && attachOverlay()) {
                    if (capsuleLp != null) { capsuleLp.x = restoreX; capsuleLp.y = restoreY; }
                    applyStubWindow();
                    setInputView(makeStub());
                } else {
                    overlayMode = false;
                    setInputView(pad);
                    applyWindowLayout();
                }
            } catch (Throwable e) {
                Log.e(TAG, "收起键盘后恢复胶囊失败", e);
            }
        } else if (overlayMode && canOverlay()) {
            if (attachOverlay()) {
                if (capsuleLp != null) {
                    capsuleLp.x = restoreX; capsuleLp.y = restoreY;
                    try { wm.updateViewLayout(pad, capsuleLp); } catch (Throwable ignored) { }
                }
                applyStubWindow();
            }
        }
    }

    private void showNumericKeyboard() {
        hideNumericKeyboard();
        keyboardTargetConnection = getCurrentInputConnection();
        EditorInfo editor = getCurrentInputEditorInfo();
        keyboardTargetPackage = editor == null ? null : editor.packageName;
        if (keyboardTargetConnection == null) {
            toast("当前没有输入框");
            return;
        }
        numericKeyboardMode = true;
        android.widget.LinearLayout root = buildNumericKeyboardView();
        if (!overlayAttached || !canOverlay()) {
            inlineKeyboardView = root;
            try { setInputView(inlineKeyboardView); } catch (Throwable e) { toast("无法切换键盘"); }
            return;
        }
        numericKeyboardPopup = new android.widget.PopupWindow(root, dp(264), dp(390), false);
        numericKeyboardPopup.setTouchable(true);
        numericKeyboardPopup.setOutsideTouchable(true);
        numericKeyboardPopup.setBackgroundDrawable(roundRect(0xFF1D2530, dp(18), 0));
        numericKeyboardPopup.setOnDismissListener(() -> {
            numericKeyboardPopup = null;
            numericKeyboardMode = false;
            keyboardTargetConnection = null;
            keyboardTargetPackage = null;
        });
        int x = clamp((int) (getResources().getDisplayMetrics().widthPixels / getResources().getDisplayMetrics().density / 2 - 132), 4, Math.max(4, (int) (getResources().getDisplayMetrics().widthPixels / getResources().getDisplayMetrics().density) - 268));
        int y = clamp((int) (getResources().getDisplayMetrics().heightPixels / getResources().getDisplayMetrics().density - 410), 4, Integer.MAX_VALUE);
        try {
            numericKeyboardPopup.showAtLocation(pad, android.view.Gravity.TOP | android.view.Gravity.LEFT, dp(x), dp(y));
        } catch (Throwable e) {
            hideNumericKeyboard();
            toast("无法打开数字键盘");
        }
    }

    private android.widget.LinearLayout buildNumericKeyboardView() {
        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(10), dp(12), dp(12));
        root.setBackground(roundRect(0xFF1D2530, dp(18), 0x1AFFFFFF));

        android.widget.LinearLayout top = new android.widget.LinearLayout(this);
        top.setGravity(android.view.Gravity.CENTER_VERTICAL);
        android.widget.TextView title = historyText("数字键盘", 15, 0xFFF1F5F9, true);
        top.addView(title, new android.widget.LinearLayout.LayoutParams(0, dp(36), 1));
        android.widget.TextView voice = historyAction("语音");
        voice.setTextSize(13);
        voice.setOnClickListener(v -> hideNumericKeyboard());
        top.addView(voice, new android.widget.LinearLayout.LayoutParams(dp(56), dp(40)));
        root.addView(top);

        android.widget.GridLayout grid = new android.widget.GridLayout(this);
        grid.setColumnCount(3);
        String[] keys = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "@", "0", "⌫", ".", "-", "_", "+", ":", "/", "空格", "↵"};
        for (String key : keys) {
            android.widget.TextView button = historyAction(key);
            button.setTextSize(key.length() > 1 ? 15 : 20);
            button.setTextColor(0xFFE2E8F0);
            button.setBackground(roundRect(0xFF252F3D, dp(12), 0));
            button.setContentDescription("键 " + key);
            button.setOnClickListener(v -> commitKeyboardKey(key));
            android.widget.GridLayout.LayoutParams glp = new android.widget.GridLayout.LayoutParams();
            glp.width = 0;
            glp.height = dp(48);
            glp.columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f);
            glp.setMargins(dp(3), dp(3), dp(3), dp(3));
            grid.addView(button, glp);
        }
        root.addView(grid);

        return root;
    }

    private void commitKeyboardKey(String key) {
        InputConnection current = getCurrentInputConnection();
        EditorInfo editor = getCurrentInputEditorInfo();
        String pkg = editor == null ? null : editor.packageName;
        if (keyboardTargetConnection == null || current == null || current != keyboardTargetConnection
                || !java.util.Objects.equals(pkg, keyboardTargetPackage)) {
            toast("输入目标已变化，未输入");
            hideNumericKeyboard();
            return;
        }
        try {
            if ("⌫".equals(key)) {
                CharSequence selected = current.getSelectedText(0);
                if (selected != null && selected.length() > 0) current.commitText("", 1);
                else current.deleteSurroundingText(1, 0);
            } else if ("↵".equals(key)) {
                onEnter();
            } else if ("空格".equals(key)) {
                current.commitText(" ", 1);
                lastCommitLen = 1;
            } else {
                current.commitText(key, 1);
                lastCommitLen = key.length();
            }
        } catch (Throwable e) {
            toast("输入失败");
        }
    }

    private void hideNumericKeyboard() {
        if (inlineKeyboardView != null) {
            View oldInline = inlineKeyboardView;
            inlineKeyboardView = null;
            numericKeyboardMode = false;
            keyboardTargetConnection = null;
            keyboardTargetPackage = null;
            setupCapsule();
            if (oldInline.getParent() != null) ((android.view.ViewGroup) oldInline.getParent()).removeView(oldInline);
            overlayMode = canOverlay();
            if (overlayMode && attachOverlay()) {
                applyStubWindow();
                placeCapsule();
                try { setInputView(makeStub()); } catch (Throwable ignored) { }
            } else {
                overlayMode = false;
                try { setInputView(pad); applyWindowLayout(); } catch (Throwable ignored) { }
            }
        } else if (numericKeyboardPopup != null) {
            // 浮层模式关闭时保留当前输入法视图及输入焦点。
        }
        android.widget.PopupWindow popup = numericKeyboardPopup;
        numericKeyboardPopup = null;
        if (popup != null) {
            try { if (popup.isShowing()) popup.dismiss(); } catch (Throwable ignored) { }
        }
        numericKeyboardMode = false;
        keyboardTargetConnection = null;
        keyboardTargetPackage = null;
    }

    @Override
    public void onClipboardHistory() {
        if (!captureClipboardText(false)) {
            // 继续显示已采集历史；无历史时由浮层显示空态。
        }
        showClipboardHistory();
    }

    /** 单击：将当前剪贴板纯文本插入录音时对应的当前输入框。 */
    @Override
    public void onPasteClipboard() {
        String text = readClipboardText();
        if (text == null) {
            toast("剪贴板没有可粘贴的文本");
            return;
        }
        addClipboardHistory(text);
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) {
            toast("当前没有输入框");
            return;
        }
        try {
            ic.commitText(text, 1);
            lastCommitLen = text.length();
            toast("已粘贴 " + text.length() + " 字");
        } catch (Throwable e) {
            Log.w(TAG, "粘贴失败");
            toast("粘贴失败");
        }
    }

    private String readClipboardText() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) return null;
            ClipData clip = cm.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return null;
            CharSequence text = clip.getItemAt(0).getText();
            if (text == null || text.length() == 0) return null;
            return text.toString();
        } catch (Throwable e) {
            Log.w(TAG, "读取剪贴板文本失败");
            return null;
        }
    }

    /** 读取当前纯文本，去除首尾空内容；不会把 URI / Intent 强行转换成文本。 */
    private boolean captureClipboardText(boolean notify) {
        String text = readClipboardText();
        if (text == null) {
            if (notify) toast("剪贴板没有可保存的文本");
            return false;
        }
        addClipboardHistory(text);
        return true;
    }

    private void addClipboardHistory(String text) {
        if (text == null || text.trim().isEmpty()) return;
        clipboardHistory.remove(text);
        clipboardHistory.add(0, text);
        while (clipboardHistory.size() > 10) clipboardHistory.remove(clipboardHistory.size() - 1);
    }

    private void showClipboardHistory() {
        if (historyView != null) {
            renderClipboardHistory();
            return;
        }
        if (!overlayAttached || !canOverlay()) {
            toast("请先开启悬浮窗权限");
            return;
        }
        historyTargetConnection = getCurrentInputConnection();
        EditorInfo editor = getCurrentInputEditorInfo();
        historyTargetPackage = editor == null ? null : editor.packageName;
        if (historyTargetConnection == null) {
            toast("当前没有输入框");
            return;
        }
        historyWm = (android.view.WindowManager) getSystemService(WINDOW_SERVICE);
        if (historyWm == null) {
            toast("无法打开剪贴板历史");
            return;
        }

        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(12), dp(14), dp(10));
        root.setBackground(roundRect(0xFF1D2530, dp(18), 0x1AFFFFFF));
        root.setElevation(0f);
        historyView = root;

        android.widget.LinearLayout header = new android.widget.LinearLayout(this);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        android.widget.TextView title = historyText("剪贴板历史", 16, 0xFFF1F5F9, true);
        header.addView(title, new android.widget.LinearLayout.LayoutParams(0, dp(42), 1));
        android.widget.TextView clear = historyAction("清空");
        clear.setOnClickListener(v -> {
            clipboardHistory.clear();
            renderClipboardHistory();
        });
        header.addView(clear, new android.widget.LinearLayout.LayoutParams(dp(48), dp(42)));
        android.widget.TextView close = historyAction("×");
        close.setTextSize(24);
        close.setOnClickListener(v -> dismissClipboardHistory());
        header.addView(close, new android.widget.LinearLayout.LayoutParams(dp(36), dp(42)));
        root.addView(header);

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.setFillViewport(true);
        android.widget.LinearLayout list = new android.widget.LinearLayout(this);
        list.setOrientation(android.widget.LinearLayout.VERTICAL);
        historyList = list;
        scroll.addView(list, new android.widget.ScrollView.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        android.widget.TextView privacy = historyText("仅显示本输入法实际读取过的文本；历史仅保存在内存中。", 11, 0xFF94A3B8, false);
        privacy.setPadding(dp(2), dp(8), dp(2), 0);
        root.addView(privacy);

        float density = getResources().getDisplayMetrics().density;
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        int width = Math.min(dp(336), screenW - dp(24));
        int maxHeight = Math.min(dp(360), screenH - dp(48));
        int capsuleW = (int) (VoicePadView.W * density);
        int capsuleH = (int) (VoicePadView.H * density);
        int left = overlayAttached && capsuleLp != null ? capsuleLp.x : 0;
        int top = overlayAttached && capsuleLp != null ? capsuleLp.y : Math.max(0, screenH - capsuleH);
        int x = clamp(left + capsuleW / 2 - width / 2, dp(12), screenW - width - dp(12));
        int estimatedH = Math.min(maxHeight, dp(252));
        int y;
        if (top > screenH / 2 || top + capsuleH + estimatedH > screenH - dp(12)) {
            y = Math.max(dp(12), top - estimatedH - dp(8));
        } else {
            y = Math.min(screenH - estimatedH - dp(12), top + capsuleH + dp(8));
        }

        historyLp = new android.view.WindowManager.LayoutParams(
                width, maxHeight,
                OVERLAY_TYPE,
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.graphics.PixelFormat.TRANSLUCENT);
        historyLp.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
        historyLp.x = x;
        historyLp.y = y;
        try {
            historyWm.addView(historyView, historyLp);
            historyAttached = true;
            renderClipboardHistory();
        } catch (Throwable e) {
            historyView = null;
            historyLp = null;
            historyAttached = false;
            historyTargetConnection = null;
            historyTargetPackage = null;
            toast("无法打开剪贴板历史");
        }
    }

    private void renderClipboardHistory() {
        if (!(historyView instanceof android.widget.LinearLayout) || historyList == null) return;
        android.widget.LinearLayout list = historyList;
        list.removeAllViews();
        if (clipboardHistory.isEmpty()) {
            android.widget.TextView empty = historyText("还没有历史文本\n点按剪贴板键可粘贴并加入历史。", 13, 0xFF94A3B8, false);
            empty.setGravity(android.view.Gravity.CENTER);
            empty.setPadding(dp(10), dp(28), dp(10), dp(28));
            list.addView(empty, new android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
            return;
        }
        for (int i = 0; i < clipboardHistory.size(); i++) {
            final String text = clipboardHistory.get(i);
            android.widget.LinearLayout row = new android.widget.LinearLayout(this);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(dp(10), dp(7), dp(4), dp(7));
            row.setBackground(roundRect(0xFF252F3D, dp(12), 0x00000000));
            android.widget.TextView preview = historyText(text, 13, 0xFFE2E8F0, false);
            preview.setMaxLines(2);
            preview.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.addView(preview, new android.widget.LinearLayout.LayoutParams(0, dp(58), 1));
            android.widget.TextView delete = historyAction("×");
            delete.setTextSize(21);
            delete.setOnClickListener(v -> {
                clipboardHistory.remove(text);
                renderClipboardHistory();
            });
            row.addView(delete, new android.widget.LinearLayout.LayoutParams(dp(38), dp(48)));
            row.setOnClickListener(v -> pasteHistoryItem(text));
            android.widget.LinearLayout.LayoutParams rp = new android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            rp.bottomMargin = dp(6);
            list.addView(row, rp);
        }
    }

    private void pasteHistoryItem(String text) {
        InputConnection current = getCurrentInputConnection();
        EditorInfo editor = getCurrentInputEditorInfo();
        String currentPkg = editor == null ? null : editor.packageName;
        if (historyTargetConnection == null || current == null
                || current != historyTargetConnection
                || current.getHandler() != historyTargetConnection.getHandler()
                || !java.util.Objects.equals(currentPkg, historyTargetPackage)) {
            toast("输入目标已变化，未粘贴");
            dismissClipboardHistory();
            return;
        }
        try {
            current.commitText(text, 1);
            lastCommitLen = text.length();
            dismissClipboardHistory();
            toast("已粘贴 " + text.length() + " 字");
        } catch (Throwable e) {
            toast("粘贴失败");
            dismissClipboardHistory();
        }
    }

    private void dismissClipboardHistory() {
        hideQwertyKeyboard();
        hideNumericKeyboard();
        if (historyView != null && historyWm != null && historyAttached) {
            try { historyWm.removeViewImmediate(historyView); } catch (Throwable ignored) { }
        }
        historyAttached = false;
        historyView = null;
        historyLp = null;
        historyList = null;
        historyTargetConnection = null;
        historyTargetPackage = null;
    }

    private android.widget.TextView historyText(String text, int size, int color, boolean bold) {
        android.widget.TextView view = new android.widget.TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        view.setGravity(android.view.Gravity.CENTER_VERTICAL);
        return view;
    }

    private android.widget.TextView historyAction(String text) {
        android.widget.TextView view = historyText(text, 12, 0xFF60A5FA, false);
        view.setGravity(android.view.Gravity.CENTER);
        view.setBackground(roundRect(0x00000000, dp(10), 0x00000000));
        view.setOnClickListener(v -> { });
        return view;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private android.graphics.drawable.Drawable roundRect(int color, float radius, int strokeColor) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        if (strokeColor != 0) d.setStroke(dp(1), strokeColor);
        return d;
    }

    /** 面板里的「设置」：打开本 App 的安装与自检页 */
    public void onOpenSettings() {
        openSettings();
        requestHideSelf(0);
    }

    @Override
    public void onFinishInputView(boolean finishingInput) {
        cancelCapture("IME input view hidden");
        detachOverlay();                    // 只卸下，不销毁，下次还要挂回去
        super.onFinishInputView(finishingInput);
    }

    @Override
    public void onStartInput(EditorInfo attribute, boolean restarting) {
        super.onStartInput(attribute, restarting);
        cancelCaptureIfTargetChanged("onStartInput");
    }

    @Override
    public void onFinishInput() {
        cancelCapture("IME input finished");
        super.onFinishInput();
    }

    @Override
    public void onDestroy() {
        if (commitRx != null) try { unregisterReceiver(commitRx); } catch (Throwable ignored) { }
        cancelCapture("IME service destroyed");
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
            cancelCaptureIfTargetChanged("draft ticker");
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
        // 必须撤回录音开始时的连接，绝不能把撤回操作作用于新焦点。
        InputConnection ic = recTargetConnection;
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

    /** 目标编辑器/连接变化时取消整次采集，包含录音、流式草稿和后台整段识别。 */
    private void cancelCaptureIfTargetChanged(String source) {
        if (recorder == null && !busy && !tickerOn) return;
        InputConnection now = getCurrentInputConnection();
        String nowPkg = currentPkg();
        boolean connectionChanged = now != recTargetConnection;
        String nowEditorKey = editorKey(getCurrentInputEditorInfo());
        boolean editorChanged = !recTargetEditorKey.equals(nowEditorKey);
        boolean packageChanged = recTargetPkg != null && !recTargetPkg.isEmpty()
                && !nowPkg.isEmpty() && !nowPkg.equals(recTargetPkg);
        if (connectionChanged || editorChanged || packageChanged) {
            Log.w(TAG, "采集目标变化（" + source + "），取消本次录音/识别");
            cancelCapture("input target changed");
        }
    }

    /** 取消而非收尾：不触发最终识别、网络请求或上屏。 */
    private void cancelCapture(String reason) {
        boolean active = recorder != null || busy || tickerOn;
        if (!active) return;
        ++captureGeneration; // 让已运行的 ASR/润色回调失效
        Recorder old = recorder;
        recorder = null;
        if (old != null) old.cancel();
        if (streamingOn) {
            try { StreamingAsr.get(this).cancel(); } catch (Throwable e) {
                Log.w(TAG, "取消流式识别失败", e);
            }
        }
        streamingOn = false;
        // 只撤回录音开始时连接里的流式草稿，不触碰后来获得焦点的输入框。
        rollbackDraft();
        busy = false;
        recTargetConnection = null;
        recTargetPkg = null;
        recTargetEditorKey = "";
        if (pad != null) setPadState(VoicePadView.S_IDLE);
        Log.i(TAG, "本次采集已取消：" + reason);
    }

    private static String editorKey(EditorInfo info) {
        if (info == null) return "";
        return String.valueOf(info.packageName) + "|" + info.fieldId + "|"
                + String.valueOf(info.fieldName) + "|" + info.inputType + "|" + info.imeOptions;
    }

    private String currentPkg() {
        try {
            EditorInfo ei = getCurrentInputEditorInfo();
            return (ei == null || ei.packageName == null) ? "" : ei.packageName.toString();
        } catch (Throwable e) {
            return "";
        }
    }

    /**
     * 采集期对照记录：云端已上屏，这里后台再跑一遍本地 SenseVoice，把两份结果记进
     * asr-compare.tsv（ts / 时长秒 / 云端 / 本地），只记账不上屏。
     * 硬上限 3000 秒音频，累计到达后自动停止，避免悄悄产生 API 费用。
     */
    private void logCompare(float[] pcm, String cloudText) {
        try {
            if (pcm == null || pcm.length == 0) return;
            if (cloudText == null || cloudText.trim().isEmpty()) return;
            final int dur = Math.max(1, pcm.length / AsrEngine.SAMPLE_RATE);
            SharedPreferences p = getSharedPreferences("asr_cfg", MODE_PRIVATE);
            final int used = p.getInt("compare_secs", 0);
            if (used >= 3000) return;
            p.edit().putInt("compare_secs", used + dur).apply();

            final String cloudTextSafe = oneLine(cloudText);
            final float[] audio = pcm;
            new Thread(() -> {
                try {
                    AsrEngine e = AsrEngine.get(VoiceImeService.this);
                    if (!e.isReady()) return;
                    String local = e.transcribeForCompare(audio, AsrEngine.SAMPLE_RATE);
                    String line = System.currentTimeMillis() + "\t" + dur + "\t"
                            + cloudTextSafe + "\t" + oneLine(local) + "\n";
                    java.io.File f = new java.io.File(getFilesDir(), "asr-compare.tsv");
                    java.io.FileOutputStream out = new java.io.FileOutputStream(f, true);
                    try {
                        out.write(line.getBytes("UTF-8"));
                    } finally {
                        out.close();
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "对照记录失败", t);
                }
            }, "asr-compare").start();
        } catch (Throwable t) {
            Log.w(TAG, "对照开关异常", t);
        }
    }

    private static String oneLine(String s) {
        if (s == null) return "";
        return s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim();
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
        else { forceCloudTake = false; startRecord(); }   // 单击 = 按设置里的引擎（默认本地）
    }

    /** 按住说话：按下就开始录 */
    @Override
    public void onMicHoldStart() {
        if (busy) return;
        if (recorder != null && recorder.isRecording()) return;   // 已经在录（切换式）
        forceCloudTake = true;                                    // 按住说话 = 强制云端
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
            setPadState(VoicePadView.S_ERR);
            toast("请先授予录音权限");
            openSettings();
            return;
        }
        // 云端引擎不需要本地模型；本机引擎才检查模型是否就位
        if (!AsrSettings.isCloud(this)) {
            AsrEngine e = AsrEngine.get(this);
            if (!e.modelPresent()) {
                setPadState(VoicePadView.S_ERR);
                return;
            }
            e.warmUp();                               // 万一还没预热
        } else if (AsrSettings.apiKey(this).isEmpty()) {
            setPadState(VoicePadView.S_ERR);
            toast("请先在 App 里填 API Key");
            return;
        }

        recorder = new Recorder();
        ++captureGeneration;
        recTargetConnection = getCurrentInputConnection();
        recTargetEditorKey = editorKey(getCurrentInputEditorInfo());
        recStart = System.currentTimeMillis();
        recTargetPkg = currentPkg();
        Log.i(TAG, "开始录音，目标 App = " + recTargetPkg);
        boolean ok = recorder.start((lvl, ms) -> ui.post(() -> {
            if (pad != null) setPadLevel(lvl);
        }));
        if (!ok) {
            setPadState(VoicePadView.S_ERR);
            recorder = null;
            return;
        }
        setPadState(VoicePadView.S_REC);

        // ---- 流式草稿（边说边出字）----
        StreamingAsr st = StreamingAsr.get(this);
        String lang = AsrEngine.get(this).language();
        boolean langOk = "zh".equals(lang) || "auto".equals(lang);
        streamingOn = st.modelPresent() && langOk;   // 云端/本地都开草稿，否则用户会觉得"没在听"
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
        setPadState(VoicePadView.S_IDLE);

        if (pcm.length < AsrEngine.SAMPLE_RATE / 5) {     // <0.2s 视为误触
            setPadState(VoicePadView.S_IDLE);
            return;
        }
        final boolean cloud = forceCloudTake || AsrSettings.isCloud(this);   // 长按强制云端，单击按设置
        final String draft;
        if (streamingOn) {
            draft = StreamingAsr.get(this).finish();
            streamingOn = false;
            rollbackDraft();          // 把草稿全部撤回，最终文字重新写
        } else {
            draft = "";
        }
        busy = true;
        setPadState(VoicePadView.S_ASR);
        final long generation = captureGeneration;

        new Thread(() -> {
            long t0 = System.currentTimeMillis();
            String text;
            try {
                if (cloud) {
                    // 云端整段送，不切句：模型自带 VAD 和标点，切碎反而更差
                    text = CloudAsr.transcribe(VoiceImeService.this, pcm, AsrEngine.SAMPLE_RATE);
                    // 采集期对照记录：云端上屏，同时后台跑一遍本地模型，只记账不上屏
                    logCompare(pcm, text);
                } else {
                    AsrEngine e = AsrEngine.get(VoiceImeService.this);
                    if (!e.isReady() && e.isLoading()) {
                        for (int i = 0; i < 300 && !e.isReady() && e.isLoading(); i++) Thread.sleep(100);
                    }
                    if (!e.isReady()) {
                        final String err = e.loadError();
                        ui.post(() -> { busy = false; setPadState(VoicePadView.S_ERR); });
                        return;
                    }
                    text = e.transcribe(pcm, AsrEngine.SAMPLE_RATE);
                }
            } catch (Throwable ex) {
                Log.e(TAG, "转写异常", ex);
                final String m = ex.getMessage();
                ui.post(() -> { busy = false; setPadState(VoicePadView.S_ERR); Log.w(TAG, "转写失败：" + m); });
                return;
            }
            final int cost = (int) (System.currentTimeMillis() - t0);
            final int dur = pcm.length / AsrEngine.SAMPLE_RATE;

            // 决定最终文本：优先离线/云端结果；都空就用流式草稿兜底
            String finalText = (text == null) ? "" : text.trim();
            if (finalText.isEmpty() && draft != null) finalText = AsrEngine.cleanPunct(draft);
            final String committed = finalText;

            ui.post(() -> {
                if (generation != captureGeneration) return;
                cancelCaptureIfTargetChanged("ASR callback");
                if (generation != captureGeneration) return;
                busy = false;
                if (committed.isEmpty()) {
                    setPadState(VoicePadView.S_ERR);
                    return;
                }
                doCommit(committed, false);
                setPadState(VoicePadView.S_DONE);        // 描边闪一次绿

                // AI 润色（可选，纯文本请求；失败就保持原文）
                if (AsrSettings.polishEnabled(VoiceImeService.this)
                        && !AsrSettings.apiKey(VoiceImeService.this).isEmpty()) {
                    final int committedLen = committed.length();
                    new Thread(() -> {
                        try {
                            String better = CloudPolish.polish(VoiceImeService.this, committed);
                            if (better == null || better.isEmpty() || better.equals(committed)) return;
                            ui.post(() -> {
                                if (generation != captureGeneration) return;
                                cancelCaptureIfTargetChanged("polish callback");
                                if (generation != captureGeneration) return;
                                InputConnection ic = recTargetConnection;
                                if (ic == null || ic != getCurrentInputConnection()) return;
                                try {
                                    ic.deleteSurroundingText(committedLen, 0);
                                    ic.commitText(better, 1);
                                    lastCommitLen = better.length();
                                    setPadState(VoicePadView.S_DONE);
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
                    if (generation != captureGeneration) return;
                    if (!busy && pad != null && activePadState() == VoicePadView.S_DONE) {
                        setPadState(VoicePadView.S_IDLE);
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
        dismissClipboardHistory();
        cancelCapture("capsule collapsed");
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
            setPadState(VoicePadView.S_REC);
            return;
        }
        if (busy) {
            setPadState(VoicePadView.S_ASR);
            return;
        }
        int st = activePadState();
        if (st == VoicePadView.S_REC || st == VoicePadView.S_ASR) return;

        if (AsrSettings.isCloud(this)) {
            setPadState(VoicePadView.S_IDLE);
            return;
        }
        AsrEngine e = AsrEngine.get(this);
        if (e.isReady() || e.isLoading()) {
            setPadState(VoicePadView.S_IDLE);
        } else if (!e.modelPresent()) {
            setPadState(VoicePadView.S_ERR);
        } else {
            setPadState(VoicePadView.S_IDLE);
        }
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
