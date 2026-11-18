package id.my.blankpoof.ketik;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Live adjustment of keyboard height, key height and key text size. */
final class SizePanel extends LinearLayout {
    interface Listener {
        void onHeightChanged(int percent);
        void onKeySizeChanged(int percent);
        void onFontChanged(int percent);
        void onSizeDone();
    }

    private final float density;
    private Theme theme;
    private Listener listener;
    private final TextView heightLabel, keyLabel, fontLabel;
    private final IconButton[] buttons = new IconButton[8];
    private int height = 100, keySize = 80, font = 100;

    SizePanel(Context c, Theme t) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        theme = t;
        setOrientation(VERTICAL);
        setPadding(0, dp(4), 0, dp(4));

        heightLabel = label(c);
        keyLabel = label(c);
        fontLabel = label(c);
        buttons[0] = small(c, "−", v -> setHeight(height - 5));
        buttons[1] = small(c, "+", v -> setHeight(height + 5));
        buttons[2] = small(c, "−", v -> setKeySize(keySize - 5));
        buttons[3] = small(c, "+", v -> setKeySize(keySize + 5));
        buttons[4] = small(c, "−", v -> setFont(font - 5));
        buttons[5] = small(c, "+", v -> setFont(font + 5));
        addView(row(c, heightLabel, buttons[0], buttons[1]), weighted());
        addView(row(c, keyLabel, buttons[2], buttons[3]), weighted());
        addView(row(c, fontLabel, buttons[4], buttons[5]), weighted());

        LinearLayout bottom = new LinearLayout(c);
        bottom.setOrientation(HORIZONTAL);
        bottom.setGravity(Gravity.CENTER);
        buttons[6] = new IconButton(c, t, 0, "Reset");
        buttons[6].filled = true;
        buttons[6].setOnClickListener(v -> {
            setHeight(100);
            setKeySize(80);
            setFont(100);
        });
        buttons[7] = new IconButton(c, t, 0, "Done");
        buttons[7].active = true;
        buttons[7].setOnClickListener(v -> { if (listener != null) listener.onSizeDone(); });
        bottom.addView(buttons[6], new LayoutParams(dp(96), LayoutParams.MATCH_PARENT));
        bottom.addView(buttons[7], new LayoutParams(dp(96), LayoutParams.MATCH_PARENT));
        addView(bottom, weighted());
        applyTheme();
    }

    private LayoutParams weighted() {
        return new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f);
    }

    private TextView label(Context c) {
        TextView tv = new TextView(c);
        tv.setTextSize(15);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        return tv;
    }

    private IconButton small(Context c, String s, OnClickListener l) {
        IconButton b = new IconButton(c, theme, 0, s);
        b.filled = true;
        b.textSize = 22 * density;
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout row(Context c, TextView label, IconButton minus, IconButton plus) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(20), 0, dp(14), 0);
        r.addView(label, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        r.addView(minus, new LayoutParams(dp(64), LayoutParams.MATCH_PARENT));
        r.addView(plus, new LayoutParams(dp(64), LayoutParams.MATCH_PARENT));
        return r;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        // only take the height the parent gives exactly; never grow the keyboard window
        if (MeasureSpec.getMode(heightSpec) != MeasureSpec.EXACTLY) {
            heightSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.EXACTLY);
        }
        super.onMeasure(widthSpec, heightSpec);
    }

    private int dp(float v) { return (int) (v * density + 0.5f); }

    void setListener(Listener l) { listener = l; }

    void setValues(int heightPct, int keyPct, int fontPct) {
        height = heightPct;
        keySize = keyPct;
        font = fontPct;
        heightLabel.setText("Keyboard height  " + height + "%");
        keyLabel.setText("Key height  " + keySize + "%");
        fontLabel.setText("Text size  " + font + "%");
    }

    void setTheme(Theme t) {
        theme = t;
        for (IconButton b : buttons) b.setTheme(t);
        applyTheme();
    }

    private void applyTheme() {
        setBackgroundColor(theme.background);
        heightLabel.setTextColor(theme.text);
        keyLabel.setTextColor(theme.text);
        fontLabel.setTextColor(theme.text);
    }

    private void setHeight(int p) {
        p = Math.max(70, Math.min(140, p));
        setValues(p, keySize, font);
        if (listener != null) listener.onHeightChanged(p);
    }

    private void setKeySize(int p) {
        p = Math.max(60, Math.min(100, p));
        setValues(height, p, font);
        if (listener != null) listener.onKeySizeChanged(p);
    }

    private void setFont(int p) {
        p = Math.max(70, Math.min(130, p));
        setValues(height, keySize, p);
        if (listener != null) listener.onFontChanged(p);
    }
}
