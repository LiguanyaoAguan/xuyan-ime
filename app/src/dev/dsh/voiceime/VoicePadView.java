package dev.dsh.voiceime;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * 序言 · 主胶囊 v1.1（五区版）
 *
 *   ┌────────────────────────────────────────────┐
 *   │  🎤   │  剪贴板  │   ↵   │   ⌫   ║ 把手    │   240 × 56dp
 *   └────────────────────────────────────────────┘
 *    44dp     44dp      44dp    44dp     44dp
 *   两侧留白 10dp，五个触控区紧挨着，把手前有一条极浅分隔线。
 *
 * 交互：
 *   点麦克风      开始 / 停止录音
 *   按住麦克风    250ms 后开始录，松手结束
 *   点剪贴板      粘贴系统剪贴板文本
 *   长按剪贴板    450ms 打开剪贴板历史（松手不再触发粘贴）
 *   点 ↵          安全换行（DSH 走 Shift+Enter）
 *   点 ⌫          退一个字
 *   长按 ⌫        连删
 *   按住 ⌫ 上滑   28dp 进入清空预备，松手清空
 *   按住把手拖    整个胶囊跟手移动，松手记住位置
 *   拖空白区      也可移动（辅助）
 *   双击空白      收起输入法
 *
 * 原 L2 面板已停用：功能并入主胶囊与设置页。
 */
public class VoicePadView extends View {

    public interface Callback {
        void onMicToggle();
        void onMicHoldStart();
        void onMicHoldEnd();
        void onEnter();
        void onUndo();
        void onClearAll();
        void onPasteClipboard();
        void onClipboardHistory();
        void onToggleKeyboard();
        void onCollapse();
        void onMoveTo(int x, int y);
        void onMoveEnd(int x, int y);
    }

    public static final int S_IDLE = 0, S_REC = 1, S_ASR = 2, S_DONE = 3, S_ERR = 4;

    // ---------------------------------------------------------------- 尺寸(dp)
    public static final float W = 264f;
    public static final float H = 56f;
    public static final float EXPANDED_H = 302f;
    /** 窗口比底板每侧多留的透明边（避免系统窗口圆角的边缘痕迹） */
    public static final float PAD = 4f;

    private static final float CELL = 44f;       // 四个功能键各 44dp
    private static final float HANDLE_W = 34f;   // 把手区域更窄
    private static final float KEYBOARD_W = 38f; // 删除键后紧接键盘，右端独立保留拖动区
    private static final float EDGE = 10f;      // 两侧留白
    private static final float ICON = 20f;

    private static final float SLOP_DRAG = 10f;
    private static final float SLOP_CLEAR = 28f;
    private static final long HOLD_MIC = 250;
    private static final long HOLD_CLIP = 450;
    private static final long LONG_DEL = 400;
    private static final long REPEAT_DEL = 70;

    // ---------------------------------------------------------------- 颜色
    private static final int C_BG      = 0xF01A1D23;
    private static final int C_BORDER  = 0x24FFFFFF;
    private static final int C_DIV     = 0x1AFFFFFF;
    private static final int C_BTN     = 0xFFAEB9CA;
    private static final int C_BLUE    = 0xFF60A5FA;
    private static final int C_BLUE_BG = 0x1A3B82F6;
    private static final int C_RED     = 0xFFFF6969;
    private static final int C_RED_BG  = 0x1BFF6969;
    private static final int C_GRAY    = 0xFFA2ADBD;
    private static final int C_AMBER   = 0xFFFBBF24;
    private static final int C_GREEN   = 0xFF22C55E;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF micRect = new RectF();
    private final RectF clipRect = new RectF();
    private final RectF enterRect = new RectF();
    private final RectF delRect = new RectF();
    private final RectF handleRect = new RectF();
    private final RectF keyboardRect = new RectF();

    private Callback cb;
    private boolean expandedKeyboard;
    private int state = S_IDLE;
    private float level;
    private long doneAt;

    private int winX, winY;
    private int pressed = -1;          // 0麦 1剪贴板 2换行 3删除 4把手 5空白
    private boolean dragging;
    private boolean clearArmed;
    private boolean undoRepeating;
    private boolean holdMode;
    private boolean clipLongFired;
    private long lastTap;
    private float downRawX, downRawY, maxDisp;
    private int dragStartX, dragStartY;

    private final Runnable micHold = () -> {
        if (pressed != 0) return;
        holdMode = true;
        if (cb != null) cb.onMicHoldStart();
    };
    private final Runnable clipHold = () -> {
        if (pressed != 1) return;
        clipLongFired = true;
        if (cb != null) cb.onClipboardHistory();
    };
    private final Runnable delRepeat = () -> {
        if (pressed != 3 || !undoRepeating || clearArmed) return;
        if (cb != null) cb.onUndo();
        postDelayed(this.delRepeat, REPEAT_DEL);
    };
    private final Runnable delStart = () -> {
        if (pressed != 3) return;
        undoRepeating = true;
        if (cb != null) cb.onUndo();
        invalidate();
        postDelayed(delRepeat, REPEAT_DEL);
    };

    private float density;

    public VoicePadView(Context c) { this(c, null); }

    public VoicePadView(Context c, AttributeSet a) {
        super(c, a);
        density = getResources().getDisplayMetrics().density;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        fill.setStyle(Paint.Style.FILL);
    }

    public void setCallback(Callback c) { this.cb = c; }
    public void setWindowPos(int x, int y) { winX = x; winY = y; }
    public void setExpandedKeyboard(boolean expanded) { expandedKeyboard=expanded; invalidate(); }
    public boolean hasExpandedKeyboard() { return expandedKeyboard; }
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
        int w = MeasureSpec.getSize(wSpec);
        int h = MeasureSpec.getSize(hSpec);
        if (w <= 0) w = (int) dp(W + PAD * 2);
        if (h <= 0) h = (int) dp(H + PAD * 2);
        if (expandedKeyboard) h=MeasureSpec.getSize(hSpec)>0?MeasureSpec.getSize(hSpec):(int)dp(H+PAD*2);
        setMeasuredDimension(w, h);
    }

    private void layoutSlots() {
        float h = getHeight();
        float edge = dp(EDGE + PAD);
        float cw = dp(CELL);
        float cy = h / 2f;

        float x = edge;
        float capsuleKeyboardWidth=dp(expandedKeyboard?0:KEYBOARD_W);
        float visibleHandleWidth=dp(expandedKeyboard?0:HANDLE_W);
        micRect.set(x, cy - cw / 2f, x + cw, cy + cw / 2f); x += cw;
        clipRect.set(x, cy - cw / 2f, x + cw, cy + cw / 2f); x += cw;
        enterRect.set(x, cy - cw / 2f, x + cw, cy + cw / 2f); x += cw;
        delRect.set(x, cy - cw / 2f, x + cw, cy + cw / 2f); x += cw;
        float kw=capsuleKeyboardWidth; keyboardRect.set(x,cy-cw/2f,x+kw,cy+cw/2f); x+=kw;
        float hw=expandedKeyboard?0:Math.max(visibleHandleWidth,getWidth()-edge-x);
        handleRect.set(x,cy-cw/2f,x+hw,cy+cw/2f);
    }

    // ---------------------------------------------------------------- 绘制

    @Override
    protected void onDraw(Canvas c) {
        layoutSlots();
        float inset = dp(PAD);
        RectF shell = new RectF(inset, inset, getWidth() - inset, getHeight() - inset);
        float r = shell.height() / 2f;

        fill.setColor(C_BG);
        c.drawRoundRect(shell, r, r, fill);
        stroke.setStrokeWidth(dp(1));
        stroke.setColor(state == S_DONE ? flashColor() : C_BORDER);
        c.drawRoundRect(shell, r, r, stroke);

        // 录音呼吸环
        if (state == S_REC) {
            long t = System.currentTimeMillis();
            float phase = (t % 800) / 800f;
            int alpha = (int) (70 * (1 - phase));
            stroke.setStrokeWidth(dp(2));
            stroke.setColor((alpha << 24) | 0x00FF6969);
            float grow = dp(2) + dp(3) * phase + level * dp(2);
            RectF g = new RectF(shell.left - grow, shell.top - grow,
                    shell.right + grow, shell.bottom + grow);
            c.drawRoundRect(g, r + grow, r + grow, stroke);
        }

        int micColor = C_BLUE, micBg = C_BLUE_BG;
        if (state == S_REC) { micColor = C_RED; micBg = C_RED_BG; }
        else if (state == S_ASR) { micColor = C_GRAY; micBg = 0x1494A3B8; }
        else if (state == S_ERR) { micColor = C_AMBER; micBg = 0x14F59E0B; }

        fill.setColor(micBg);
        c.drawCircle(micRect.centerX(), micRect.centerY(), micRect.width() / 2f - dp(2), fill);
        drawMic(c, micRect.centerX(), micRect.centerY(), dp(ICON), micColor);

        int btnColor = clearArmed ? C_RED : C_BTN;

        if (pressed == 1) {
            fill.setColor(0x18FFFFFF);
            c.drawCircle(clipRect.centerX(), clipRect.centerY(), clipRect.width() / 2f - dp(4), fill);
        }
        drawClipboard(c, clipRect.centerX(), clipRect.centerY(), dp(ICON), btnColor);

        if (pressed == 2) {
            fill.setColor(0x18FFFFFF);
            c.drawCircle(enterRect.centerX(), enterRect.centerY(), enterRect.width() / 2f - dp(4), fill);
        }
        drawReturn(c, enterRect.centerX(), enterRect.centerY(), dp(ICON), btnColor);

        if (clearArmed || pressed == 3) {
            fill.setColor(clearArmed ? C_RED_BG : 0x18FFFFFF);
            c.drawCircle(delRect.centerX(), delRect.centerY(), delRect.width() / 2f - dp(4), fill);
        }
        drawBackspace(c, delRect.centerX(), delRect.centerY(), dp(ICON), btnColor);

        // 删除键后的功能区/拖动区分隔：小竖线之前依次是删除与键盘按钮
        stroke.setStrokeWidth(dp(1));
        stroke.setColor(C_DIV);
        if (!expandedKeyboard) {
            float lx = (keyboardRect.right + handleRect.left) / 2f;
            c.drawLine(lx, shell.top + dp(14), lx, shell.bottom - dp(14), stroke);
            if (pressed == 4) {
                fill.setColor(0x14FFFFFF);
                c.drawCircle(handleRect.centerX(), handleRect.centerY(),
                        handleRect.width() / 2f - dp(2), fill);
            }
            drawHandle(c, handleRect.centerX(), handleRect.centerY(), dp(13));
        }
        if (pressed == 5) {
            fill.setColor(0x18FFFFFF);
            c.drawCircle(keyboardRect.centerX(), keyboardRect.centerY(), keyboardRect.width() / 2f - dp(2), fill);
        }
        if (!expandedKeyboard) drawKeyboard(c, keyboardRect.centerX(), keyboardRect.centerY(), dp(16), C_BTN);

        if (state == S_REC) postInvalidateDelayed(50);
        if (state == S_DONE && System.currentTimeMillis() - doneAt < 500) postInvalidateDelayed(60);
    }

    private int flashColor() {
        long dt = System.currentTimeMillis() - doneAt;
        if (dt > 500) return C_BORDER;
        int a = (int) (200 * (1 - dt / 500f));
        return (a << 24) | 0x0022C55E;
    }

    private void drawMic(Canvas c, float cx, float cy, float size, int color) {
        float k = size / 24f;
        float l = cx - 12f * k, t = cy - 12f * k;
        fill.setColor(color);
        c.drawRoundRect(new RectF(l + 9f * k, t + 3f * k, l + 15f * k, t + 15f * k),
                3f * k, 3f * k, fill);
        stroke.setStrokeWidth(2.1f * k);
        stroke.setColor(color);
        c.drawArc(new RectF(l + 5f * k, t + 4f * k, l + 19f * k, t + 18f * k), 0f, 180f, false, stroke);
        c.drawLine(cx, t + 18f * k, cx, t + 21f * k, stroke);
        c.drawLine(l + 8f * k, t + 21f * k, l + 16f * k, t + 21f * k, stroke);
    }

    private void drawKeyboard(Canvas c, float cx, float cy, float size, int color) {
        float k = size / 24f;
        float l = cx - 12f * k, t = cy - 9f * k;
        stroke.setStrokeWidth(1.8f * k);
        stroke.setColor(color);
        c.drawRoundRect(new RectF(l + 3f * k, t + 2f * k, l + 21f * k, t + 16f * k),
                2f * k, 2f * k, stroke);
        for (int row = 0; row < 2; row++) {
            for (int col = 0; col < 4; col++) {
                float x = l + (6f + col * 4f) * k;
                float y = t + (7f + row * 4f) * k;
                c.drawCircle(x, y, 0.8f * k, stroke);
            }
        }
        c.drawLine(l + 9f * k, t + 21f * k, l + 15f * k, t + 21f * k, stroke);
    }

    /** 剪贴板：竖向圆角板 + 顶部夹子 + 两条内容线 */
    private void drawClipboard(Canvas c, float cx, float cy, float size, int color) {
        float k = size / 24f;
        float l = cx - 12f * k, t = cy - 12f * k;
        stroke.setStrokeWidth(2.0f * k);
        stroke.setColor(color);
        c.drawRoundRect(new RectF(l + 5f * k, t + 4f * k, l + 19f * k, t + 21f * k),
                2.5f * k, 2.5f * k, stroke);
        c.drawRoundRect(new RectF(l + 9f * k, t + 2f * k, l + 15f * k, t + 7f * k),
                1.6f * k, 1.6f * k, stroke);
        c.drawLine(l + 8.5f * k, t + 11f * k, l + 15.5f * k, t + 11f * k, stroke);
        c.drawLine(l + 8.5f * k, t + 15f * k, l + 13.5f * k, t + 15f * k, stroke);
    }

    /** 换行：标准 Return 符号（横线 + 右端圆角上折 + 左端箭头） */
    private void drawReturn(Canvas c, float cx, float cy, float size, int color) {
        float k = size / 24f;
        float l = cx - 12f * k, t = cy - 12f * k;
        stroke.setStrokeWidth(2.0f * k);
        stroke.setColor(color);

        float y = t + 15f * k;
        float x0 = l + 4.5f * k;
        float x1 = l + 17f * k;

        Path p = new Path();
        p.moveTo(x0 + 3f * k, y);
        p.lineTo(x1 - 3f * k, y);
        p.quadTo(x1, y, x1, y - 3f * k);
        p.lineTo(x1, t + 6.5f * k);
        c.drawPath(p, stroke);

        c.drawLine(x0 + 3f * k, y, x0 + 8f * k, y - 4.5f * k, stroke);
        c.drawLine(x0 + 3f * k, y, x0 + 8f * k, y + 4.5f * k, stroke);
    }

    /** 删除：Backspace 轮廓 + 内部小叉 */
    private void drawBackspace(Canvas c, float cx, float cy, float size, int color) {
        float k = size / 24f;
        float l = cx - 12f * k, t = cy - 12f * k;
        stroke.setStrokeWidth(1.9f * k);
        stroke.setColor(color);

        float tip = l + 2.5f * k, top = t + 6.5f * k, bot = t + 17.5f * k;
        float corner = l + 8.5f * k, right = l + 21f * k;

        Path p = new Path();
        p.moveTo(tip, t + 12f * k);
        p.lineTo(corner, top);
        p.lineTo(right - 2f * k, top);
        p.quadTo(right, top, right, top + 2f * k);
        p.lineTo(right, bot - 2f * k);
        p.quadTo(right, bot, right - 2f * k, bot);
        p.lineTo(corner, bot);
        p.close();
        c.drawPath(p, stroke);

        float mx = (corner + right) / 2f + 0.6f * k, my = t + 12f * k, s2 = 2.3f * k;
        c.drawLine(mx - s2, my - s2, mx + s2, my + s2, stroke);
        c.drawLine(mx + s2, my - s2, mx - s2, my + s2, stroke);
    }

    /** 拖动把手：两条细竖线（低对比度，不抢视觉） */
    private void drawHandle(Canvas c, float cx, float cy, float h) {
        stroke.setStrokeWidth(dp(2));
        stroke.setColor(0xFF6B7789);
        float d = dp(3);                 // 两线中心距 6dp
        c.drawLine(cx - d, cy - h / 2f, cx - d, cy + h / 2f, stroke);
        c.drawLine(cx + d, cy - h / 2f, cx + d, cy + h / 2f, stroke);
    }

    // ---------------------------------------------------------------- 触摸

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                downRawX = e.getRawX();
                downRawY = e.getRawY();
                dragStartX = winX;
                dragStartY = winY;
                maxDisp = 0f;
                dragging = false;
                clearArmed = false;
                undoRepeating = false;
                holdMode = false;
                clipLongFired = false;

                if (micRect.contains(x, y)) {
                    pressed = 0;
                    postDelayed(micHold, HOLD_MIC);
                } else if (clipRect.contains(x, y)) {
                    pressed = 1;
                    postDelayed(clipHold, HOLD_CLIP);
                } else if (enterRect.contains(x, y)) {
                    pressed = 2;
                } else if (delRect.contains(x, y)) {
                    pressed = 3;
                    postDelayed(delStart, LONG_DEL);
                } else if (!expandedKeyboard && handleRect.contains(x, y)) {
                    pressed = 4;
                } else if (!expandedKeyboard && keyboardRect.contains(x, y)) {
                    pressed = 6;
                } else {
                    pressed = expandedKeyboard ? 7 : 5;
                }
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                float dx = e.getRawX() - downRawX;
                float dy = e.getRawY() - downRawY;
                maxDisp = Math.max(maxDisp, (float) Math.hypot(dx, dy));

                // 把手：按下后移动即拖动，不需要长按
                if (pressed == 4) {
                    if (!dragging && maxDisp > dp(SLOP_DRAG)) dragging = true;
                    if (dragging && cb != null) {
                        cb.onMoveTo(dragStartX + (int) dx, dragStartY + (int) dy);
                    }
                    return true;
                }

                // 删除键上滑清空
                if (pressed == 3) {
                    boolean up = dy < -dp(SLOP_CLEAR);
                    if (up != clearArmed) {
                        clearArmed = up;
                        if (up) {
                            undoRepeating = false;
                            removeCallbacks(delStart);
                            removeCallbacks(delRepeat);
                        }
                        invalidate();
                    }
                    return true;
                }

                // 键盘入口只接受点按，不把按键操作误识别为拖动。
                if (pressed == 6) return true;

                // 空白区拖动（辅助入口）
                if (pressed == 5) {
                    if (!dragging && maxDisp > dp(SLOP_DRAG)) dragging = true;
                    if (dragging && cb != null) {
                        cb.onMoveTo(dragStartX + (int) dx, dragStartY + (int) dy);
                    }
                    return true;
                }

                // 按住麦克风时手抖不取消
                if (holdMode) return true;

                if (expandedKeyboard && pressed == 7) return true;
                if (pressed >= 0 && maxDisp > dp(CELL)) {
                    pressed = -1;
                    removeCallbacks(micHold);
                    removeCallbacks(clipHold);
                    removeCallbacks(delStart);
                    removeCallbacks(delRepeat);
                    invalidate();
                }
                return true;
            }
            case MotionEvent.ACTION_UP: {
                int p = pressed;
                boolean wasDragging = dragging && maxDisp > dp(SLOP_DRAG);
                boolean wasClear = clearArmed;
                boolean wasRepeat = undoRepeating;
                boolean wasHold = holdMode;
                boolean wasClipLong = clipLongFired;

                pressed = -1;
                dragging = false;
                clearArmed = false;
                undoRepeating = false;
                holdMode = false;
                clipLongFired = false;
                removeCallbacks(micHold);
                removeCallbacks(clipHold);
                removeCallbacks(delStart);
                removeCallbacks(delRepeat);
                invalidate();

                if (wasDragging) {
                    if (cb != null) cb.onMoveEnd(winX, winY);
                    return true;
                }

                if (p == 0) {
                    if (wasHold) { if (cb != null) cb.onMicHoldEnd(); }
                    else { if (cb != null) cb.onMicToggle(); }
                    return true;
                }
                if (p == 1) {
                    if (wasClipLong) return true;      // 长按已打开历史，松手不再粘贴
                    if (cb != null) cb.onPasteClipboard();
                    return true;
                }
                if (p == 2) { if (cb != null) cb.onEnter(); return true; }
                if (p == 3) {
                    if (wasClear) { if (cb != null) cb.onClearAll(); }
                    else if (!wasRepeat) { if (cb != null) cb.onUndo(); }
                    return true;
                }
                if (p == 4 || (expandedKeyboard && p == 7)) return true;
                if (p == 6) {
                    if (cb != null) cb.onToggleKeyboard();
                    return true;
                }
                if (p == 5) {
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
                dragging = false;
                clearArmed = false;
                undoRepeating = false;
                holdMode = false;
                clipLongFired = false;
                removeCallbacks(micHold);
                removeCallbacks(clipHold);
                removeCallbacks(delStart);
                removeCallbacks(delRepeat);
                invalidate();
                return true;
            }
        }
        return super.onTouchEvent(e);
    }
}
