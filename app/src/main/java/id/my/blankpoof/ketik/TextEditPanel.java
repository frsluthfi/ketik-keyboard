package id.my.blankpoof.ketik;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;

/** Cursor and selection controls: arrows, select mode, select all, copy, cut, paste, undo. */
final class TextEditPanel extends View {
    static final int UP = 1, DOWN = 2, LEFT = 3, RIGHT = 4, SELECT = 5, SELECT_ALL = 6, COPY = 7,
            CUT = 8, PASTE = 9, HOME = 10, END = 11, UNDO = 12, REDO = 13, BACK = 14, DELETE = 15;

    interface Listener {
        void onEditAction(int action, boolean selecting);
    }

    private static final class Btn {
        final int action, icon;
        final String label;
        final int col, row, colSpan;
        final RectF rect = new RectF();
        Btn(int action, int icon, String label, int col, int row, int colSpan) {
            this.action = action; this.icon = icon; this.label = label;
            this.col = col; this.row = row; this.colSpan = colSpan;
        }
    }

    private final Btn[] buttons = {
            new Btn(HOME, 0, "Home", 0, 0, 1), new Btn(UP, Icons.ARROW_UP, null, 1, 0, 1),
            new Btn(END, 0, "End", 2, 0, 1), new Btn(SELECT_ALL, 0, "Select all", 3, 0, 2),
            new Btn(LEFT, Icons.ARROW_LEFT, null, 0, 1, 1), new Btn(SELECT, 0, "Select", 1, 1, 1),
            new Btn(RIGHT, Icons.ARROW_RIGHT, null, 2, 1, 1), new Btn(COPY, 0, "Copy", 3, 1, 2),
            new Btn(UNDO, 0, "Undo", 0, 2, 1), new Btn(DOWN, Icons.ARROW_DOWN, null, 1, 2, 1),
            new Btn(REDO, 0, "Redo", 2, 2, 1), new Btn(CUT, 0, "Cut", 3, 2, 2),
            new Btn(BACK, Icons.KEYBOARD, null, 0, 3, 2), new Btn(DELETE, Icons.BACKSPACE, null, 2, 3, 1),
            new Btn(PASTE, 0, "Paste", 3, 3, 2),
    };

    private final float density;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Theme theme;
    private Listener listener;
    private boolean selecting;
    private Btn pressed;

    private final Runnable repeat = new Runnable() {
        @Override
        public void run() {
            if (pressed == null || listener == null) return;
            listener.onEditAction(pressed.action, selecting);
            handler.postDelayed(this, 55);
        }
    };

    TextEditPanel(Context c, Theme t) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        theme = t;
        paint.setTextAlign(Paint.Align.CENTER);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        // only take the height the parent gives exactly; never grow the keyboard window
        int h = MeasureSpec.getMode(heightSpec) == MeasureSpec.EXACTLY ? MeasureSpec.getSize(heightSpec) : 0;
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), h);
    }

    void setListener(Listener l) { listener = l; }
    void setTheme(Theme t) { theme = t; invalidate(); }

    void reset() {
        selecting = false;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        float pad = 4 * density;
        float cw = (w - pad * 2) / 5f, rh = (h - pad * 2) / 4f;
        float g = 3 * density;
        for (Btn b : buttons) {
            float l = pad + b.col * cw, t = pad + b.row * rh;
            b.rect.set(l + g, t + g, l + cw * b.colSpan - g, t + rh - g);
        }
    }

    private static boolean repeats(int a) {
        return a == UP || a == DOWN || a == LEFT || a == RIGHT || a == DELETE;
    }

    @Override
    protected void onDraw(Canvas c) {
        c.drawColor(theme.background);
        float r = 9 * density;
        for (Btn b : buttons) {
            boolean on = b.action == SELECT && selecting;
            int bg = on ? theme.accent : b == pressed ? theme.keyPressed
                    : (b.col >= 3 || b.action == BACK) ? theme.funcKey : theme.key;
            paint.setColor(bg);
            c.drawRoundRect(b.rect, r, r, paint);
            int fg = on ? theme.accentText : theme.text;
            if (b.icon != 0) {
                Icons.draw(c, b.icon, b.rect.centerX(), b.rect.centerY(), Math.min(b.rect.height() * 0.42f, 24 * density), fg);
            } else {
                paint.setColor(fg);
                paint.setTypeface(b.col >= 3 ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
                paint.setTextSize(14 * density);
                float tw = paint.measureText(b.label);
                if (tw > b.rect.width() * 0.9f) paint.setTextSize(paint.getTextSize() * b.rect.width() * 0.9f / tw);
                c.drawText(b.label, b.rect.centerX(), b.rect.centerY() - (paint.descent() + paint.ascent()) / 2f, paint);
            }
        }
    }

    private Btn at(float x, float y) {
        for (Btn b : buttons) if (b.rect.contains(x, y)) return b;
        return null;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pressed = at(e.getX(), e.getY());
                if (pressed != null && repeats(pressed.action) && listener != null) {
                    listener.onEditAction(pressed.action, selecting);
                    handler.postDelayed(repeat, 400);
                }
                invalidate();
                return true;
            case MotionEvent.ACTION_UP: {
                handler.removeCallbacks(repeat);
                Btn b = pressed;
                pressed = null;
                if (b != null && b == at(e.getX(), e.getY()) && !repeats(b.action)) {
                    if (b.action == SELECT) {
                        selecting = !selecting;
                    } else if (listener != null) {
                        listener.onEditAction(b.action, selecting);
                        if (b.action == COPY || b.action == CUT || b.action == SELECT_ALL || b.action == PASTE) {
                            selecting = false;
                        }
                    }
                }
                performClick();
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                handler.removeCallbacks(repeat);
                pressed = null;
                invalidate();
                return true;
            default:
                return true;
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }
}
