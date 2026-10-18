package dev.dsh.voiceime;

import android.content.Context;
import android.graphics.Color;
import android.view.MotionEvent;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** A compact floating QWERTY keyboard. It types literal characters; IME does not provide pinyin. */
public final class FloatingQwertyView extends LinearLayout {
    public interface Actions {
        void onKey(String key);
        void onVoice();
    }
    public interface DragHandle {
        void onDown(float rawX, float rawY);
        void onMove(float rawX, float rawY);
        void onUp();
    }

    private static final int BG = 0xFFF1F1F4;
    private static final int KEY = 0xFFFFFFFF;
    private static final int UTIL = 0xFFCFD1D6;
    private static final int TEXT = 0xFF17191D;
    private Actions actions;
    private DragHandle dragHandle;
    private boolean symbols;
    private boolean shift;
    private boolean chinese = true;
    private boolean caps;
    private boolean dragActive;
    private float downX, downY;
    private final VoicePadView capsule;

    public FloatingQwertyView(Context context) { this(context,null); }
    public FloatingQwertyView(Context context, VoicePadView capsuleView) {
        super(context);
        capsule=capsuleView;
        setOrientation(VERTICAL);
        setPadding(dp(8), dp(6), dp(8), dp(4));
        setBackground(shape(BG, dp(22), 0x553B82F6));
        setElevation(dp(10));
        rebuild();
    }

    public void setActions(Actions a) { actions = a; }
    public void setOnDragHandle(DragHandle h) { dragHandle = h; }
    private void bindDrag(View handle) {
        handle.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX=e.getRawX(); downY=e.getRawY(); dragActive=false;
                    if (dragHandle!=null) dragHandle.onDown(downX,downY);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (Math.abs(e.getRawX()-downX)+Math.abs(e.getRawY()-downY)>dp(4)) dragActive=true;
                    if (dragActive && dragHandle!=null) dragHandle.onMove(e.getRawX(),e.getRawY());
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (dragHandle!=null) dragHandle.onUp();
                    boolean dragged=dragActive; dragActive=false;
                    return dragged || e.getActionMasked()==MotionEvent.ACTION_CANCEL;
            }
            return true;
        });
    }

    private int dp(float n) { return (int) (n * getResources().getDisplayMetrics().density + .5f); }
    private GradientDrawable shape(int color, int radius, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color); d.setCornerRadius(radius);
        if (stroke != 0) d.setStroke(dp(1), stroke);
        return d;
    }

    private void rebuild() {
        removeAllViews();
        if(capsule!=null){
            capsule.setExpandedKeyboard(true);
            LayoutParams cp=new LayoutParams(-1,dp(56)); cp.bottomMargin=dp(3);
            addView(capsule,cp);
        } else {
            TextView title = new TextView(getContext()); title.setText("序言输入法"); title.setTextColor(0xFF365EAA); title.setTextSize(12);
            title.setGravity(Gravity.CENTER_VERTICAL); addView(title,new LayoutParams(-1,dp(29)));
        }
        View divider=new View(getContext()); divider.setBackgroundColor(0x223B82F6); addView(divider,new LayoutParams(-1,dp(1)));

        String[][] rows = symbols
                ? new String[][]{{"1","2","3","4","5","6","7","8","9","0"},
                    {"@","#","$","%","&","-","+","(",")"},
                    {"=","_","/",":",";","!","?","'","⌫"}}
                : new String[][]{{"q","w","e","r","t","y","u","i","o","p"},
                    {"a","s","d","f","g","h","j","k","l"},
                    {"⇧","z","x","c","v","b","n","m","⌫"}};
        for(int ri=0;ri<rows.length;ri++){
            LinearLayout row=new LinearLayout(getContext()); row.setGravity(Gravity.CENTER);
            row.setPadding(ri==1?dp(12):0,dp(3),ri==1?dp(12):0,dp(1));
            for(String key:rows[ri]){
                Button b=button(display(key),isUtility(key)); b.setTextSize(isUtility(key)?17:22);
                b.setOnClickListener(v->press(key));
                LayoutParams p=new LayoutParams(0,dp(43),1f); p.setMargins(dp(2),0,dp(2),0); row.addView(b,p);
            }
            addView(row,new LayoutParams(-1,dp(49)));
        }
        LinearLayout bottom=new LinearLayout(getContext()); bottom.setGravity(Gravity.CENTER);
        Button mode=button(symbols?"ABC":"123",true); mode.setOnClickListener(v->{symbols=!symbols;rebuild();});
        addWeighted(bottom,mode,1.3f);
        Button comma=button(",",true); comma.setOnClickListener(v->press(",")); addWeighted(bottom,comma,.8f);
        Button voice=button("▮▮▮",true); voice.setTextColor(0xFF8A8D94); voice.setOnClickListener(v->{if(actions!=null)actions.onVoice();}); addWeighted(bottom,voice,2.8f);
        Button lang=button(chinese?"中\n英":"英\n中",true); lang.setTextSize(11); lang.setOnClickListener(v->{chinese=!chinese;rebuild();}); addWeighted(bottom,lang,.85f);
        Button enter=button("换行",true); enter.setOnClickListener(v->press("↵")); addWeighted(bottom,enter,1.35f);
        addView(bottom,new LayoutParams(-1,dp(48)));
        TextView home=new TextView(getContext()); home.setGravity(Gravity.CENTER); home.setText("━"); home.setContentDescription("按住拖动键盘"); home.setTextColor(0xFF7182A1); home.setTextSize(17); bindDrag(home);
        addView(home,new LayoutParams(-1,dp(24)));
    }
    private void addWeighted(LinearLayout row,Button b,float weight){LayoutParams p=new LayoutParams(0,dp(42),weight);p.setMargins(dp(2),0,dp(2),0);row.addView(b,p);}

    private boolean isUtility(String key) { return key.length() > 1 || key.equals("⇧") || key.equals("⌫"); }
    private String display(String key) {
        if (key.equals("⇧") && shift) return "⇧";
        if (!symbols && (shift || caps) && key.length() == 1) return key.toUpperCase();
        return key;
    }
    private Button button(String label, boolean utility) {
        Button b = new Button(getContext());
        b.setAllCaps(false); b.setText(label); b.setTextColor(TEXT); b.setTextSize(utility ? 13 : 18);
        b.setGravity(Gravity.CENTER); b.setPadding(0, 0, 0, 0);
        b.setMinWidth(0); b.setMinimumWidth(0); b.setMinHeight(0); b.setMinimumHeight(0);
        b.setStateListAnimator(null); b.setElevation(dp(1));
        b.setBackground(shape(utility ? UTIL : KEY, dp(7), utility ? 0 : 0x18000000));
        return b;
    }
    private void press(String key) {
        if (key.equals("⇧")) { if(shift) caps=true; else { shift=true; caps=false; } rebuild(); return; }
        if (key.equals("⌫") || key.equals("↵") || key.equals("空格")) { if(actions!=null)actions.onKey(key); return; }
        if (actions == null) return;
        if ((shift || caps) && !symbols && key.length() == 1) { key = key.toUpperCase(); if(!caps)shift = false; rebuild(); }
        actions.onKey(key);
    }
}
