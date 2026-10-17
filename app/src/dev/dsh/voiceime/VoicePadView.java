package dev.dsh.voiceime;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * VoicePad v3.2 · 三键常驻胶囊（设计稿：176×56dp，圆角 28dp）。
 *
 *   [ 🎤 44 ]   [ ↵ 40 ]   [ ⌫ 40 ]        ← 全部无文字，只靠颜色表达状态
 *
 * 交互：
 *   点麦克风        开始 / 停止录音
 *   点 ↵            插入换行（commitText("\n")，**绝不模拟 Enter**）
 *   点 ⌫            退一个字
 *   长按 ⌫          连续退（400ms 后每 70ms）
 *   按住 ⌫ 上滑 28dp 进入清空预备态（变红），松手清空，滑回来取消
 *   空白处拖动       自由移动窗口（不吸边），松手记住位置
 *   长按空白处       展开 L2 面板（预留）
 *   双击空白处       收起输入法
 *
 * 窗口尺寸 == 胶囊尺寸，所以本视图等于“整个窗口”，绘制从 (0,0) 开始铺满。
 */
public class VoicePadView extends View {

    public interface Callback {
        void onMicToggle();      // 单击：切换录音
        void onMicHoldStart();   // 按住：开始录音
        void onMicHoldEnd();     // 松开：结束并识别
        void onEnter();
        void onUndo();
        void onClearAll();
        void onCollapse();
        void onExpandPanel();    // 长按空白：展开/收起 L2 面板
        void onUndoLast();       // 面板里的“撤销”：撤掉上一次上屏
        void onOpenSettings();   // 面板里的“设置”
        void onPasteClipboard(); // 面板里的“剪贴板”
        void onMoveTo(int x, int y);          // 窗口左上角（屏幕坐标 px）
        void onMoveEnd(int x, int y);
    }

    // ---------------------------------------------------------------- 尺寸(dp)
    public static final float W = 216f;
    public static final float H = 56f;
    private static final float MIC = 44f;
    private static final float BTN = 40f;
    private static final float SMALL = 34f;      // 收起键（略小，视觉次要）
    private static final float GAP = 8f;
    private static final float ICON = 20f;
    private static final float SLOP_DRAG = 12f;     // 超过才算拖动

    // L2 面板（设计稿 252×204dp，圆角 24）
    public static final float PW = 252f;
    public static final float PH = 192f;
    private static final float PR = 24f;
    private static final float CELL_W = 110f;
    private static final float CELL_H = 52f;
    private static final float KEYS_Y = 132f;       // 四键行顶部
    private static final float SMALL_ICON = 20f;
    private static final float SLOP_CLEAR = 28f;    // 删除上滑阈值

    // ---------------------------------------------------------------- 颜色
    private static final int C_BG      = 0xF01A1D23;
    private static final int C_BORDER  = 0x24FFFFFF;
    private static final int C_BTN     = 0xFFAEB9CA;
    private static final int C_BLUE    = 0xFF60A5FA;
    private static final int C_BLUE_BG = 0x1A3B82F6;
    private static final int C_RED     = 0xFFFF6969;
    private static final int C_RED_BG  = 0x1BFF6969;
    private static final int C_GRAY    = 0xFFA2ADBD;
    private static final int C_AMBER   = 0xFFFBBF24;
    private static final int C_GREEN   = 0xFF22C55E;

    public static final int S_IDLE = 0, S_REC = 1, S_ASR = 2, S_DONE = 3, S_ERR = 4;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);

    private final RectF micRect = new RectF();
    private final RectF enterRect = new RectF();
    private final RectF delRect = new RectF();
    private final RectF collapseRect = new RectF();
    private final RectF cell1 = new RectF();   // 撤销
    private final RectF cell2 = new RectF();   // 历史
    private final RectF cell3 = new RectF();   // 语言
    private final RectF cell4 = new RectF();   // 设置
    private final RectF panelClose = new RectF();

    private Callback cb;
    private int state = S_IDLE;
    private float level;
    private long doneAt;

    private int winX, winY;                 // 窗口左上角（屏幕坐标）
    private int pressed = -1;               // 0 mic 1 enter 2 del 3 blank
    private boolean dragging;
    private boolean clearArmed;
    private boolean undoRepeating;
    private long lastTap;
    private float downRawX, downRawY, maxDisp;
    private int dragStartWinX, dragStartWinY;

    /**
     * 长按空白：展开 / 收起 L2 面板。
     * （收起键盘由右侧 ⌄ 按钮负责，别再绑到长按上，否则一按就把键盘关了）
     */
    private final Runnable longPressBlank = () -> {
        if (pressed == 4 && !dragging) {
            pressed = -1;
            if (cb != null) cb.onExpandPanel();
        }
    };
    private static final float HOLD_DELAY = 250f;   // 超过这个时间算“按住”
    private boolean longFired;      // 箭头已被长按消费
    private boolean holdMode;                       // 当前是按住录音

    private final Runnable holdStart = () -> {
        if (pressed != 0) return;
        holdMode = true;
        if (cb != null) cb.onMicHoldStart();
    };
    /** 箭头长按：直接进入拖动（单击是开/关面板） */
    private final Runnable arrowLongPress = () -> {
        if (pressed == 3) {
            dragging = true;
            longFired = true;
            invalidate();
        }
    };

    private final Runnable undoRepeat = () -> {
        if (pressed != 2 || !undoRepeating || clearArmed) return;
        if (cb != null) cb.onUndo();
        postDelayed(this.undoRepeat, 70);
    };
    private final Runnable undoStart = () -> {
        if (pressed != 2) return;
        undoRepeating = true;
        if (cb != null) cb.onUndo();
        invalidate();
        postDelayed(undoRepeat, 70);
    };

    private float density;

    public VoicePadView(Context c) { this(c, null); }

    public VoicePadView(Context c, AttributeSet a) {
        super(c, a);
        density = getResources().getDisplayMetrics().density;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        fill.setStyle(Paint.Style.FILL);
    }

    public void setCallback(Callback c) { this.cb = c; }

    public void setWindowPos(int x, int y) { winX = x; winY = y; }

    public int getState() { return state; }

    public void setState(int s) {
        if (s == state && s != S_DONE) return;
        state = s;
        if (s == S_DONE) doneAt = System.currentTimeMillis();
        invalidate();
    }

    public void setLevel(float v) {
        level = Math.max(0f, Math.min(1f, v));
        if (state == S_REC) invalidate();
    }

    private float dp(float v) { return v * density; }

    // ---------------------------------------------------------------- 布局

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        // 窗口尺寸由 Service 决定（胶囊 208×56 / 面板 252×204），这里照它给的大小走
        int w = MeasureSpec.getSize(wSpec);
        int h = MeasureSpec.getSize(hSpec);
        if (w <= 0) w = (int) dp(W);
        if (h <= 0) h = (int) dp(H);
        setMeasuredDimension(w, h);
    }

    /** 当前是不是面板形态（按高度判断，够稳） */
    public boolean isPanel() { return getHeight() > dp(H + 24); }

    private void layoutSlots() {
        float w = getWidth(), h = getHeight();
        float total = dp(MIC + GAP + BTN + GAP + BTN + GAP + SMALL);
        float pad = Math.max(0, (w - total) / 2f);
        float cy = h / 2f;
        float keysTop = 0;

        if (isPanel()) {
            // 规则网格：两列两行 + 底部三键行
            float left = dp(12), top = dp(12);
            float col2 = dp(12 + CELL_W + 8);
            cell1.set(left, top, left + dp(CELL_W), top + dp(CELL_H));
            cell2.set(col2, top, col2 + dp(CELL_W), top + dp(CELL_H));
            cell3.set(left, top + dp(CELL_H + 6), left + dp(CELL_W), top + dp(CELL_H * 2 + 6));
            cell4.set(col2, top + dp(CELL_H + 6), col2 + dp(CELL_W), top + dp(CELL_H * 2 + 6));
            panelClose.setEmpty();          // 面板不再单独放收起键
            cy = dp(KEYS_Y) + dp(MIC) / 2f;
        }

        float x = pad;
        micRect.set(x, cy - dp(MIC) / 2f, x + dp(MIC), cy + dp(MIC) / 2f);
        x += dp(MIC + GAP);
        enterRect.set(x, cy - dp(BTN) / 2f, x + dp(BTN), cy + dp(BTN) / 2f);
        x += dp(BTN + GAP);
        delRect.set(x, cy - dp(BTN) / 2f, x + dp(BTN), cy + dp(BTN) / 2f);
        x += dp(BTN + GAP);
        collapseRect.set(x, cy - dp(SMALL) / 2f, x + dp(SMALL), cy + dp(SMALL) / 2f);
    }

    // ---------------------------------------------------------------- 绘制

    @Override
    protected void onDraw(Canvas c) {
        layoutSlots();
        boolean panel = isPanel();
        float r = panel ? dp(PR) : getHeight() / 2f;

        RectF shell = new RectF(0, 0, getWidth(), getHeight());
        fill.setColor(C_BG);
        c.drawRoundRect(shell, r, r, fill);
        stroke.setStrokeWidth(dp(1));
        stroke.setColor(state == S_DONE ? flashColor() : C_BORDER);
        c.drawRoundRect(shell, r, r, stroke);

        // 录音外圈呼吸
        if (state == S_REC) {
            long t = System.currentTimeMillis();
            float phase = (t % 800) / 800f;
            int alpha = (int) (70 * (1 - phase));
            stroke.setStrokeWidth(dp(2));
            stroke.setColor((alpha << 24) | 0x00FF6969);
            float grow = dp(2) + dp(3) * phase + level * dp(2);
            RectF g = new RectF(-grow, -grow, getWidth() + grow, getHeight() + grow);
            c.drawRoundRect(g, r + grow, r + grow, stroke);
        }

        int micColor = C_BLUE, micBg = C_BLUE_BG;
        if (state == S_REC) { micColor = C_RED; micBg = C_RED_BG; }
        else if (state == S_ASR) { micColor = C_GRAY; micBg = 0x1494A3B8; }
        else if (state == S_ERR) { micColor = C_AMBER; micBg = 0x14F59E0B; }

        fill.setColor(micBg);
        c.drawCircle(micRect.centerX(), micRect.centerY(), micRect.width() / 2f, fill);
        drawMic(c, micRect.centerX(), micRect.centerY(), dp(ICON), micColor);

        int btnColor = clearArmed ? C_RED : C_BTN;
        if (state == S_DONE) btnColor = C_BTN;

        // ↵ 换行
        if (pressed == 1) {
            fill.setColor(0x18FFFFFF);
            c.drawCircle(enterRect.centerX(), enterRect.centerY(), enterRect.width() / 2f, fill);
        }
        drawEnter(c, enterRect.centerX(), enterRect.centerY(), dp(ICON), btnColor);

        // ⌫ 删除（上滑清空预备态：红底 + 红图标）
        if (clearArmed || pressed == 2) {
            fill.setColor(clearArmed ? C_RED_BG : 0x18FFFFFF);
            c.drawCircle(delRect.centerX(), delRect.centerY(), delRect.width() / 2f, fill);
        }
        drawDelete(c, delRect.centerX(), delRect.centerY(), dp(ICON), btnColor);

        // ⌄ 收起键盘
        if (pressed == 3) {
            fill.setColor(0x18FFFFFF);
            c.drawCircle(collapseRect.centerX(), collapseRect.centerY(),
                    collapseRect.width() / 2f, fill);
        }
        drawChevron(c, collapseRect.centerX(), collapseRect.centerY(), dp(ICON), C_BTN, panel);

        if (panel) drawPanel(c);

        if (state == S_REC) postInvalidateDelayed(50);
        if (state == S_DONE && System.currentTimeMillis() - doneAt < 500) postInvalidateDelayed(60);
    }

    /** L2 面板：2×2 功能格 + 右上角收起 */
    private void drawPanel(Canvas c) {
        float rad = dp(11);
        drawCell(c, cell1, "↶", "撤销", true, rad);
        drawCell(c, cell2, "▤", "剪贴板", true, rad);
        drawCell(c, cell3, "文", "语言", false, rad);
        drawCell(c, cell4, "⚙", "设置", true, rad);

        // 右上角收起（从面板回到胶囊）
        stroke.setStrokeWidth(dp(2));
        stroke.setColor(C_BTN);
        float cx = panelClose.centerX(), cy = panelClose.centerY(), k = dp(6);
        c.drawLine(cx - k, cy - k * 0.4f, cx, cy + k * 0.6f, stroke);
        c.drawLine(cx, cy + k * 0.6f, cx + k, cy - k * 0.4f, stroke);
    }

    private void drawCell(Canvas c, RectF box, String glyph, String label,
                          boolean enabled, float rad) {
        fill.setColor(pressed == cellIndex(box) ? 0x1FFFFFFF : 0x10FFFFFF);
        c.drawRoundRect(box, rad, rad, fill);
        stroke.setStrokeWidth(dp(1));
        stroke.setColor(0x14FFFFFF);
        c.drawRoundRect(box, rad, rad, stroke);

        TextPaint t = text;
        t.setTypeface(Typeface.DEFAULT);
        t.setTextAlign(Paint.Align.CENTER);
        t.setTextSize(dp(15));
        t.setColor(enabled ? C_BTN : 0xFF5A6270);
        c.drawText(glyph, box.centerX(), box.top + dp(21), t);
        t.setTextSize(dp(11));
        t.setColor(enabled ? C_BTN : 0xFF5A6270);
        c.drawText(label, box.centerX(), box.top + dp(38), t);
        t.setTextAlign(Paint.Align.LEFT);
    }

    private int cellIndex(RectF box) {
        if (box == cell1) return 5;
        if (box == cell2) return 6;
        if (box == cell4) return 8;
        return -1;
    }

    /** 完成时描边闪一次绿 */
    private int flashColor() {
        long dt = System.currentTimeMillis() - doneAt;
        if (dt > 500) return C_BORDER;
        int a = (int) (200 * (1 - dt / 500f));
        return (a << 24) | 0x0022C55E;
    }

    /** 麦克风：胶囊 + 下半圆弧 + 支架（设计稿 SVG 等比） */
    private void drawMic(Canvas c, float cx, float cy, float size, int color) {
        float k = size / 24f;
        float l = cx - 12f * k, t = cy - 12f * k;
        fill.setColor(color);
        c.drawRoundRect(new RectF(l + 9f * k, t + 3f * k, l + 15f * k, t + 15f * k),
                3f * k, 3f * k, fill);
        stroke.setStrokeWidth(2.2f * k);
        stroke.setColor(color);
        c.drawArc(new RectF(l + 5f * k, t + 4f * k, l + 19f * k, t + 18f * k),
                0f, 180f, false, stroke);
        c.drawLine(cx, t + 18f * k, cx, t + 21f * k, stroke);
        c.drawLine(l + 8f * k, t + 21f * k, l + 16f * k, t + 21f * k, stroke);
    }

    /** ↵ 弯箭头：头部在左，横线向右再上折 */
    private void drawEnter(Canvas c, float cx, float cy, float size, int color) {
        float k = size / 24f;
        float l = cx - 12f * k, t = cy - 12f * k;
        stroke.setStrokeWidth(2.2f * k);
        stroke.setColor(color);
        float ax = l + 9f * k, ay = t + 10f * k;
        float bx = l + 5f * k, by = t + 14f * k;
        float dx = l + 9f * k, dy = t + 18f * k;
        c.drawLine(ax, ay, bx, by, stroke);
        c.drawLine(bx, by, dx, dy, stroke);
        c.drawLine(bx, by, l + 15f * k, by, stroke);
        c.drawArc(new RectF(l + 11f * k, t + 6f * k, l + 19f * k, t + 14f * k),
                90f, -90f, false, stroke);
        c.drawLine(l + 19f * k, t + 10f * k, l + 19f * k, t + 5f * k, stroke);
    }

    /**
     * ⌫ 删除键：左边尖角 + 圆角矩形身，里面一个小叉。
     * 比旧版更宽扁、线条更细、转角更圆，20dp 下也不会糊成一团。
     */
    private void drawDelete(Canvas c, float cx, float cy, float size, int color) {
        float k = size / 24f;
        float l = cx - 12f * k, t = cy - 12f * k;

        stroke.setStrokeWidth(1.9f * k);
        stroke.setColor(color);
        stroke.setStrokeJoin(android.graphics.Paint.Join.ROUND);

        float leftX = l + 2.5f * k;      // 尖角顶点
        float topY  = t + 6f * k;
        float botY  = t + 18f * k;
        float cornerX = l + 8.5f * k;    // 尖角与矩形身的交界
        float rightX = l + 21f * k;

        android.graphics.Path p = new android.graphics.Path();
        p.moveTo(leftX, t + 12f * k);          // 尖
        p.lineTo(cornerX, topY);               // 上斜边
        p.lineTo(rightX - 2f * k, topY);       // 上边
        p.quadTo(rightX, topY, rightX, topY + 2f * k);
        p.lineTo(rightX, botY - 2f * k);
        p.quadTo(rightX, botY, rightX - 2f * k, botY);
        p.lineTo(cornerX, botY);               // 下边
        p.close();
        c.drawPath(p, stroke);

        // 内部小叉（居中在矩形身里）
        float mx = (cornerX + rightX) / 2f + 0.8f * k;
        float s2 = 2.4f * k;
        float my = t + 12f * k;
        c.drawLine(mx - s2, my - s2, mx + s2, my + s2, stroke);
        c.drawLine(mx + s2, my - s2, mx - s2, my + s2, stroke);
    }

    /**
     * 形态开关箭头：
     *   胶囊态 → 尖朝上（∧）提示“会向上长开”
     *   面板态 → 尖朝下（∨）提示“会收回”
     */
    private void drawChevron(Canvas c, float cx, float cy, float size, int color, boolean panel) {
        float k = size / 24f;
        float l = cx - 12f * k, t = cy - 12f * k;
        stroke.setStrokeWidth(2.2f * k);
        stroke.setColor(color);
        if (panel) {   // ∨
            c.drawLine(l + 5f * k, t + 9f * k, l + 12f * k, t + 16f * k, stroke);
            c.drawLine(l + 12f * k, t + 16f * k, l + 19f * k, t + 9f * k, stroke);
        } else {       // ∧
            c.drawLine(l + 5f * k, t + 16f * k, l + 12f * k, t + 9f * k, stroke);
            c.drawLine(l + 12f * k, t + 9f * k, l + 19f * k, t + 16f * k, stroke);
        }
    }

    // ---------------------------------------------------------------- 触摸

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                downRawX = e.getRawX();
                downRawY = e.getRawY();
                dragStartWinX = winX;
                dragStartWinY = winY;
                dragging = false;
                clearArmed = false;
                undoRepeating = false;
                maxDisp = 0f;

                holdMode = false;
                if (micRect.contains(x, y)) {
                    pressed = 0;
                    postDelayed(holdStart, (long) HOLD_DELAY);
                } else if (enterRect.contains(x, y)) {
                    pressed = 1;
                } else if (delRect.contains(x, y)) {
                    pressed = 2;
                    postDelayed(undoStart, 400);
                } else if (collapseRect.contains(x, y)) {
                    longFired = false;
                    pressed = 3;
                    postDelayed(arrowLongPress, 500);
                } else if (isPanel() && cell1.contains(x, y)) {
                    pressed = 5;                       // 撤销
                } else if (isPanel() && cell2.contains(x, y)) {
                    pressed = 6;                       // 历史（未开放）
                } else if (isPanel() && cell3.contains(x, y)) {
                    pressed = 7;                       // 语言（未开放）
                } else if (isPanel() && cell4.contains(x, y)) {
                    pressed = 8;                       // 设置
                } else {
                    pressed = 4;      // 空白：拖动 / 双击收起键盘
                }
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                float dxRaw = e.getRawX() - downRawX;
                float dyRaw = e.getRawY() - downRawY;
                maxDisp = Math.max(maxDisp, (float) Math.hypot(dxRaw, dyRaw));

                if (pressed == 2) {
                    // 删除键：上滑超过 28dp → 清空预备态
                    boolean up = dyRaw < -dp(SLOP_CLEAR);
                    if (up != clearArmed) {
                        clearArmed = up;
                        if (up) {
                            undoRepeating = false;
                            removeCallbacks(undoStart);
                            removeCallbacks(undoRepeat);
                        }
                        invalidate();
                    }
                    return true;
                }

                // 箭头长按后进入拖动：跟手移动
                if (pressed == 3 && dragging) {
                    if (cb != null) cb.onMoveTo(dragStartWinX + (int) dxRaw, dragStartWinY + (int) dyRaw);
                    return true;
                }

                if (pressed == 4) {
                    if (!dragging && (Math.abs(dxRaw) > dp(SLOP_DRAG) || Math.abs(dyRaw) > dp(SLOP_DRAG))) {
                        dragging = true;
                        removeCallbacks(longPressBlank);
                    }
                    if (dragging && cb != null) {
                        cb.onMoveTo(dragStartWinX + (int) dxRaw, dragStartWinY + (int) dyRaw);
                    }
                    return true;
                }

                // 按住说话模式：手指移动不取消（说话时手会抖）
                if (holdMode) return true;

                // 手指离开按钮就取消（拖动窗口时坐标会变，用 raw 判断）
                if (pressed >= 0 && Math.hypot(dxRaw, dyRaw) > dp(BTN)) {
                    pressed = -1;
                    removeCallbacks(undoStart);
                    removeCallbacks(undoRepeat);
                    invalidate();
                }
                return true;
            }
            case MotionEvent.ACTION_UP: {
                int p = pressed;
                boolean wasRepeating = undoRepeating;
                boolean wasArmed = clearArmed;
                boolean wasDragging = dragging;
                pressed = -1;
                undoRepeating = false;
                clearArmed = false;
                dragging = false;
                removeCallbacks(undoStart);
                removeCallbacks(undoRepeat);
                removeCallbacks(longPressBlank);
                invalidate();

                // 真正移动过才算拖动；只是抖一下就当点击
                if (wasDragging && maxDisp > dp(SLOP_DRAG)) {
                    if (cb != null) cb.onMoveEnd(winX, winY);
                    return true;
                }
                if (p == 0) {
                    removeCallbacks(holdStart);
                    if (holdMode) {
                        holdMode = false;
                        if (cb != null) cb.onMicHoldEnd();
                    } else {
                        if (cb != null) cb.onMicToggle();
                    }
                    return true;
                }
                if (p == 1) { if (cb != null) cb.onEnter(); return true; }
                if (p == 2) {
                    if (wasArmed) { if (cb != null) cb.onClearAll(); }
                    else if (!wasRepeating) { if (cb != null) cb.onUndo(); }
                    return true;
                }
                if (p == 3) {
                    removeCallbacks(arrowLongPress);
                    if (!longFired && cb != null) cb.onExpandPanel();   // 单击：开/关面板
                    return true;
                }
                if (p == 9) { if (cb != null) cb.onExpandPanel(); return true; }   // 面板 → 胶囊
                if (p == 5) { if (cb != null) cb.onUndoLast(); return true; }
                if (p == 8) { if (cb != null) cb.onOpenSettings(); return true; }
                if (p == 6) { if (cb != null) cb.onPasteClipboard(); return true; }
                if (p == 4) {
                    long now = System.currentTimeMillis();
                    if (now - lastTap < 400) {
                        lastTap = 0;
                        if (cb != null) cb.onCollapse();
                    } else {
                        lastTap = now;
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL: {
                pressed = -1;
                undoRepeating = false;
                clearArmed = false;
                dragging = false;
                removeCallbacks(undoStart);
                removeCallbacks(undoRepeat);
                removeCallbacks(longPressBlank);
                removeCallbacks(holdStart);
                removeCallbacks(arrowLongPress);
                holdMode = false;
                invalidate();
                return true;
            }
        }
        return super.onTouchEvent(e);
    }
}
