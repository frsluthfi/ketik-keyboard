package id.ketik.keyboard;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

/** Small pill button with an icon and/or label, drawn in theme colours. */
final class IconButton extends View {
    private final float density;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Theme theme;
    int icon;
    String label;
    boolean filled;     // draw key background even when not pressed
    boolean active;     // highlighted (toggle on)
    float iconSize;
    float textSize;

    IconButton(Context c, Theme t, int icon, String label) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        theme = t;
        this.icon = icon;
        this.label = label;
        iconSize = 20 * density;
        textSize = 14 * density;
        setClickable(true);
        paint.setTextAlign(Paint.Align.CENTER);
    }

    void setTheme(Theme t) { theme = t; invalidate(); }

    @Override
    protected void drawableStateChanged() {
        super.drawableStateChanged();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float inset = 3 * density;
        RectF r = new RectF(inset, inset, w - inset, h - inset);
        float rad = Math.min(10 * density, r.height() / 2);
        if (isPressed() || filled || active) {
            int col = active ? theme.accent : isPressed() ? theme.keyPressed : theme.key;
            if (!filled && !active && isPressed()) col = theme.funcKeyPressed;
            paint.setColor(col);
            c.drawRoundRect(r, rad, rad, paint);
        }
        int fg = active ? theme.accentText : theme.text;
        if (!isEnabled()) fg = theme.hintText;
        float cx = w / 2f, cy = h / 2f;
        if (icon != 0 && label != null) {
            Icons.draw(c, icon, cx, cy - textSize * 0.7f, iconSize * 0.9f, fg);
            paint.setColor(fg);
            paint.setTypeface(Typeface.DEFAULT);
            paint.setTextSize(textSize * 0.85f);
            c.drawText(label, cx, cy + iconSize * 0.5f + textSize * 0.6f, paint);
        } else if (icon != 0) {
            Icons.draw(c, icon, cx, cy, iconSize, fg);
        } else if (label != null) {
            paint.setColor(fg);
            paint.setTypeface(Typeface.DEFAULT_BOLD);
            paint.setTextSize(textSize);
            float tw = paint.measureText(label);
            float max = w - 10 * density;
            if (tw > max) paint.setTextSize(textSize * max / tw);
            c.drawText(label, cx, cy - (paint.descent() + paint.ascent()) / 2f, paint);
        }
    }
}
