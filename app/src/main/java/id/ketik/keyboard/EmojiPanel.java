package id.ketik.keyboard;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.OverScroller;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Scrollable emoji grid with category tabs, recents, ABC and delete keys. */
final class EmojiPanel extends View {
    interface Listener {
        void onEmoji(String emoji);
        void onEmojiDelete();
        void onEmojiClose();
    }

    private static final String[] NAMES = {"smileys_emotion", "people_body", "animals_nature", "food_drink",
            "travel_places", "activities", "objects", "symbols", "flags"};
    private static final String[] TITLES = {"Smileys", "People", "Animals & nature", "Food & drink",
            "Travel & places", "Activities", "Objects", "Symbols", "Flags"};

    private static List<String[]> categories; // loaded once

    private final float density;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint emojiPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final OverScroller scroller;
    private final int touchSlop;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SharedPreferences sp;
    private Theme theme;
    private Listener listener;

    // flattened layout
    private final List<String> sectionTitles = new ArrayList<>();
    private final List<String[]> sectionItems = new ArrayList<>();
    private float[] sectionTop = new float[0];
    private int cols = 8;
    private float cell, headerH, barH, contentH;
    private float scrollY;

    private VelocityTracker vt;
    private float downX, downY, lastY;
    private boolean dragging, deleteHeld;
    private int downTarget; // -1 grid, -2 abc, -3 delete, >=0 tab
    private final Runnable deleteRepeat = new Runnable() {
        @Override
        public void run() {
            if (!deleteHeld || listener == null) return;
            listener.onEmojiDelete();
            handler.postDelayed(this, 60);
        }
    };

    EmojiPanel(Context c, Theme t) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        theme = t;
        scroller = new OverScroller(c);
        touchSlop = ViewConfiguration.get(c).getScaledTouchSlop();
        sp = c.getSharedPreferences("ketik_emoji", Context.MODE_PRIVATE);
        emojiPaint.setTextAlign(Paint.Align.CENTER);
        paint.setTextAlign(Paint.Align.LEFT);
        loadCategories(c);
    }

    private static synchronized void loadCategories(Context c) {
        if (categories != null) return;
        categories = new ArrayList<>();
        String[][] tmp = new String[NAMES.length][];
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getAssets().open("emoji.txt"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                int tab = line.indexOf('\t');
                if (tab < 0) continue;
                String name = line.substring(0, tab);
                for (int i = 0; i < NAMES.length; i++) {
                    if (NAMES[i].equals(name)) tmp[i] = line.substring(tab + 1).split(" ");
                }
            }
        } catch (Exception ignored) {
        }
        for (String[] a : tmp) categories.add(a == null ? new String[0] : a);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        // only take the height the parent gives exactly; never grow the keyboard window
        int h = MeasureSpec.getMode(heightSpec) == MeasureSpec.EXACTLY ? MeasureSpec.getSize(heightSpec) : 0;
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), h);
    }

    void setListener(Listener l) { listener = l; }
    void setTheme(Theme t) { theme = t; invalidate(); }

    private List<String> recents() {
        List<String> l = new ArrayList<>();
        String s = sp.getString("recent", "");
        if (!s.isEmpty()) for (String e : s.split(" ")) if (!e.isEmpty()) l.add(e);
        return l;
    }

    private void addRecent(String e) {
        List<String> l = recents();
        l.remove(e);
        l.add(0, e);
        while (l.size() > 32) l.remove(l.size() - 1);
        StringBuilder sb = new StringBuilder();
        for (String x : l) { if (sb.length() > 0) sb.append(' '); sb.append(x); }
        sp.edit().putString("recent", sb.toString()).apply();
    }

    /** Called each time the panel opens so recents are current. */
    void reload() {
        sectionTitles.clear();
        sectionItems.clear();
        List<String> rec = recents();
        sectionTitles.add("Recent");
        sectionItems.add(rec.toArray(new String[0]));
        for (int i = 0; i < NAMES.length; i++) {
            sectionTitles.add(TITLES[i]);
            sectionItems.add(categories.get(i));
        }
        pendingInitialScroll = true;
        relayout();
        invalidate();
    }

    private boolean pendingInitialScroll;

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        relayout();
    }

    private void relayout() {
        int w = getWidth();
        if (w == 0 || sectionItems.isEmpty()) return;
        cols = Math.max(6, (int) (w / (44 * density)));
        cell = w / (float) cols;
        headerH = 28 * density;
        barH = 44 * density;
        sectionTop = new float[sectionItems.size()];
        float y = 0;
        for (int i = 0; i < sectionItems.size(); i++) {
            sectionTop[i] = y;
            int n = sectionItems.get(i).length;
            int rows = Math.max(1, (n + cols - 1) / cols);
            y += headerH + rows * cell;
        }
        contentH = y;
        if (pendingInitialScroll) {
            pendingInitialScroll = false;
            // open on Smileys when there are no recents yet
            scrollY = sectionItems.get(0).length == 0 && sectionTop.length > 1 ? sectionTop[1] : 0;
        }
        clampScroll();
    }

    private boolean ready() {
        return !sectionItems.isEmpty() && sectionTop.length == sectionItems.size() && cell > 0;
    }

    private float gridH() { return getHeight() - barH; }

    private void clampScroll() {
        float max = Math.max(0, contentH - gridH());
        if (scrollY < 0) scrollY = 0;
        if (scrollY > max) scrollY = max;
    }

    private int currentSection() {
        int s = 0;
        for (int i = 0; i < sectionTop.length; i++) if (sectionTop[i] <= scrollY + 4) s = i;
        return s;
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth();
        float gh = gridH();
        c.drawColor(theme.background);
        if (!ready()) return;
        c.save();
        c.clipRect(0, 0, w, gh);
        emojiPaint.setTextSize(cell * 0.62f);
        for (int s = 0; s < sectionItems.size(); s++) {
            float top = sectionTop[s] - scrollY;
            String[] items = sectionItems.get(s);
            int rows = Math.max(1, (items.length + cols - 1) / cols);
            float bottom = top + headerH + rows * cell;
            if (bottom < 0 || top > gh) continue;
            paint.setColor(theme.hintText);
            paint.setTypeface(Typeface.DEFAULT_BOLD);
            paint.setTextSize(12.5f * density);
            c.drawText(sectionTitles.get(s), 12 * density, top + headerH * 0.68f, paint);
            if (items.length == 0) {
                paint.setTypeface(Typeface.DEFAULT);
                c.drawText("Emoji you use will appear here", 12 * density, top + headerH + cell * 0.55f, paint);
            }
            for (int i = 0; i < items.length; i++) {
                float cy = top + headerH + (i / cols) * cell + cell / 2f;
                if (cy + cell < 0 || cy - cell > gh) continue;
                float cx = (i % cols) * cell + cell / 2f;
                c.drawText(items[i], cx, cy - (emojiPaint.descent() + emojiPaint.ascent()) / 2f, emojiPaint);
            }
        }
        c.restore();

        // bottom bar: ABC | tabs | delete
        float top = gh;
        paint.setColor(theme.funcKey);
        c.drawRect(0, top, w, getHeight(), paint);
        float side = 56 * density;
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setColor(theme.text);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setTextSize(14 * density);
        c.drawText("ABC", side / 2f, top + barH / 2f - (paint.descent() + paint.ascent()) / 2f, paint);
        paint.setTextAlign(Paint.Align.LEFT);
        Icons.draw(c, Icons.BACKSPACE, w - side / 2f, top + barH / 2f, 20 * density, theme.text);
        int tabs = sectionItems.size();
        float tw = (w - side * 2) / Math.max(1, tabs);
        int cur = currentSection();
        emojiPaint.setTextSize(Math.min(tw, barH) * 0.48f);
        for (int i = 0; i < tabs; i++) {
            float cx = side + tw * i + tw / 2f, cy = top + barH / 2f;
            if (i == cur) {
                paint.setColor(theme.accent);
                c.drawRect(cx - tw * 0.3f, top + barH - 3 * density, cx + tw * 0.3f, top + barH, paint);
            }
            if (i == 0) {
                Icons.draw(c, Icons.CLOCK, cx, cy, Math.min(tw, barH) * 0.42f, i == cur ? theme.iconActive : theme.icon);
            } else {
                String[] items = sectionItems.get(i);
                String e = items.length > 0 ? items[0] : "?";
                if (i == tabs - 1 && items.length > 0) e = "🏳";
                c.drawText(e, cx, cy - (emojiPaint.descent() + emojiPaint.ascent()) / 2f, emojiPaint);
            }
        }
    }

    private boolean pressedNow;

    private int targetAt(float x, float y) {
        if (!ready()) return -4;
        if (y < gridH()) return -1;
        int w = getWidth();
        float side = 56 * density;
        if (x < side) return -2;
        if (x > w - side) return -3;
        int tabs = sectionItems.size();
        float tw = (w - side * 2) / Math.max(1, tabs);
        return Math.min(tabs - 1, Math.max(0, (int) ((x - side) / tw)));
    }

    private String emojiAt(float x, float y) {
        if (!ready()) return null;
        float cy = y + scrollY;
        for (int s = sectionItems.size() - 1; s >= 0; s--) {
            if (cy >= sectionTop[s]) {
                float rel = cy - sectionTop[s] - headerH;
                if (rel < 0) return null;
                int row = (int) (rel / cell);
                int col = (int) (x / cell);
                int idx = row * cols + col;
                String[] items = sectionItems.get(s);
                return idx >= 0 && idx < items.length ? items[idx] : null;
            }
        }
        return null;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                scroller.forceFinished(true);
                if (vt == null) vt = VelocityTracker.obtain(); else vt.clear();
                vt.addMovement(e);
                downX = x; downY = y; lastY = y;
                dragging = false;
                pressedNow = true;
                downTarget = targetAt(x, y);
                if (downTarget == -3) {
                    deleteHeld = true;
                    if (listener != null) listener.onEmojiDelete();
                    handler.postDelayed(deleteRepeat, 400);
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (vt != null) vt.addMovement(e);
                if (downTarget == -1) {
                    if (!dragging && Math.abs(y - downY) > touchSlop) dragging = true;
                    if (dragging) {
                        scrollY -= y - lastY;
                        clampScroll();
                        invalidate();
                    }
                }
                lastY = y;
                return true;
            case MotionEvent.ACTION_UP:
                pressedNow = false;
                deleteHeld = false;
                handler.removeCallbacks(deleteRepeat);
                if (downTarget == -1) {
                    if (dragging && vt != null) {
                        vt.computeCurrentVelocity(1000);
                        float vy = vt.getYVelocity();
                        scroller.fling(0, (int) scrollY, 0, (int) -vy, 0, 0, 0,
                                (int) Math.max(0, contentH - gridH()));
                        postInvalidateOnAnimation();
                    } else {
                        String em = emojiAt(x, y);
                        if (em != null && listener != null) {
                            addRecent(em);
                            listener.onEmoji(em);
                        }
                    }
                } else if (downTarget == -2 && targetAt(x, y) == -2) {
                    if (listener != null) listener.onEmojiClose();
                } else if (downTarget >= 0) {
                    int t = targetAt(x, y);
                    if (t >= 0 && t < sectionTop.length) {
                        scrollY = sectionTop[t];
                        clampScroll();
                        invalidate();
                    }
                }
                performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                pressedNow = false;
                deleteHeld = false;
                handler.removeCallbacks(deleteRepeat);
                return true;
            default:
                return true;
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    @Override
    public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollY = scroller.getCurrY();
            clampScroll();
            postInvalidateOnAnimation();
        }
    }
}
