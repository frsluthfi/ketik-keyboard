package id.ketik.keyboard;

import android.content.Context;
import android.content.res.Configuration;
import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.util.SparseArray;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Draws the keys and turns touches into key presses, long presses, cursor drags and swipes. */
final class KeyboardView extends View {

    interface Listener {
        void onPress(Key key);
        void onKey(Key key);
        void onText(String text);
        void onLongPress(Key key);
        void onCursorMove(int steps);
        void onCursorMoveLines(int lines);
        void onDeleteRepeat(int count);
        void onGesture(List<float[]> points);
    }

    private static final int MODE_NORMAL = 0, MODE_CURSOR = 1, MODE_GESTURE = 2, MODE_POPUP = 3, MODE_DONE = 4;
    private static final int LONG_PRESS_MS = 380;

    private final class Ptr {
        int id;
        Key key;
        float downX, downY, lastX, lastY;
        float cursorRefY;
        long downTime;
        int mode = MODE_NORMAL;
        float cursorRef;
        final List<float[]> points = new ArrayList<>();
    }

    private final float density;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Paint keyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final SparseArray<Ptr> ptrs = new SparseArray<>();
    private final int[] myLoc = new int[2];
    private final int[] overlayLoc = new int[2];

    private Theme theme;
    private PopupOverlay overlay;
    private Listener listener;
    private Layouts.Layout layout;
    private final List<Key> keys = new ArrayList<>();

    private int shiftState;
    private float keyH;
    private int heightPercent = 100;
    private float fontScale = 1f;
    private float labelAlpha = 1f;          // 0 while dragging the spacebar: keys show only their shapes
    private ValueAnimator labelAnimator;
    private float keyFraction = 0.8f;   // how much of each row the key itself fills
    private boolean showPreview = true, gestureEnabled = true, spaceCursor = true;
    private String spaceLabel = "";

    private Ptr longPtr;
    private Ptr repeatPtr;
    private int repeatCount;

    private final Runnable longRunnable = this::onLongTimeout;
    private final Runnable repeatRunnable = new Runnable() {
        @Override
        public void run() {
            if (repeatPtr == null || listener == null) return;
            // safety: never keep deleting if the keyboard was hidden or the release got lost
            if (!isShown() || repeatCount > 600) {
                repeatPtr = null;
                return;
            }
            repeatCount++;
            listener.onDeleteRepeat(repeatCount);
            handler.postDelayed(this, repeatCount > 12 ? 45 : 65);
        }
    };
    private final Runnable hidePreviewRunnable = () -> { if (overlay != null) overlay.hidePreview(); };

    KeyboardView(Context c, Theme t) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        theme = t;
        textPaint.setTextAlign(Paint.Align.CENTER);
        hintPaint.setTextAlign(Paint.Align.RIGHT);
        setWillNotDraw(false);
    }

    void setOverlay(PopupOverlay o) { overlay = o; }

    private void setCursorMode(boolean on) {
        float target = on ? 0f : 1f;
        if (labelAnimator != null) labelAnimator.cancel();
        if (labelAlpha == target) return;
        labelAnimator = ValueAnimator.ofFloat(labelAlpha, target);
        labelAnimator.setDuration(on ? 120 : 160);
        labelAnimator.addUpdateListener(a -> {
            labelAlpha = (float) a.getAnimatedValue();
            invalidate();
        });
        labelAnimator.start();
    }

    private int fade(int color) {
        int a = (int) (((color >>> 24) & 0xFF) * labelAlpha);
        return (color & 0x00FFFFFF) | (a << 24);
    }
    void setListener(Listener l) { listener = l; }

    void setTheme(Theme t) {
        theme = t;
        invalidate();
    }

    void setOptions(boolean preview, boolean gesture, boolean cursor, int heightPct, int fontPct, int keyPct) {
        float kf = keyPct / 100f;
        if (kf != keyFraction) {
            keyFraction = kf;
            if (getWidth() > 0) layoutKeys(getWidth(), getHeight());
            invalidate();
        }
        float fs = fontPct / 100f;
        if (fs != fontScale) {
            fontScale = fs;
            invalidate();
        }
        showPreview = preview;
        gestureEnabled = gesture;
        spaceCursor = cursor;
        if (heightPct != heightPercent) {
            heightPercent = heightPct;
            requestLayout();
        }
    }

    void setSpaceLabel(String s) {
        spaceLabel = s;
        invalidate();
    }

    void setLayout(Layouts.Layout l) {
        cancelAll();
        layout = l;
        keys.clear();
        for (Layouts.Row r : l.rows) keys.addAll(r.keys);
        requestLayout();
        if (getWidth() > 0) layoutKeys(getWidth(), getHeight());
        invalidate();
    }

    Layouts.Layout getLayout() { return layout; }

    void setShiftState(int s) {
        if (s != shiftState) {
            shiftState = s;
            invalidate();
        }
    }

    float keyHeight() {
        boolean land = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        float base = (land ? 40f : 50f) * density;
        return base * heightPercent / 100f;
    }

    /** Text size reference: independent of key height, so shorter keys keep the same font. */
    private float textBase() {
        boolean land = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        return (land ? 40f : 50f) * density * fontScale;
    }

    private float textSize(float ratio, float maxOfKey) {
        return Math.min(textBase() * ratio, keyH * maxOfKey);
    }

    /** Height for a standard 4-row layout; panels use this so they match the keys. */
    int standardHeight() {
        return (int) (keyHeight() * 4 + 8 * density);
    }

    private int computeHeight() {
        float h = 0;
        if (layout != null) for (Layouts.Row r : layout.rows) h += r.heightFactor;
        if (h < 3) h = 3;
        return (int) (keyHeight() * h + 8 * density);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        setMeasuredDimension(w, computeHeight());
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        layoutKeys(w, h);
    }

    private void layoutKeys(int w, int h) {
        if (layout == null || w == 0) return;
        keyH = keyHeight();
        float side = 3 * density;
        float totalFactor = 0;
        for (Layouts.Row r : layout.rows) totalFactor += r.heightFactor;
        float rowUnit = (h - 8 * density) / Math.max(1f, totalFactor);
        float unit = (w - side * 2) / 10f;
        float y = 4 * density;
        float gapX = 2.6f * density;
        int rowIdx = 0;
        for (Layouts.Row r : layout.rows) {
            float rowH = rowUnit * r.heightFactor;
            // key height inside the row: the row (touch area) stays the same, only the key shrinks
            float gapY = Math.max(1f * density, rowH * (1f - keyFraction) / 2f);
            float x = side + r.pad * unit;
            for (int i = 0; i < r.keys.size(); i++) {
                Key k = r.keys.get(i);
                float kw = k.width * unit;
                k.visual.set(x + gapX, y + gapY, x + kw - gapX, y + rowH - gapY);
                float left = (i == 0) ? 0 : x;
                float right = (i == r.keys.size() - 1) ? w : x + kw;
                float top = rowIdx == 0 ? 0 : y;
                float bottom = rowIdx == layout.rows.size() - 1 ? h : y + rowH;
                k.rect.set(left, top, right, bottom);
                x += kw;
            }
            // the spacebar is the most-hit key: give it 25% of a key extra on each side,
            // taken from the neighbouring keys (",", ".", "@", "/"), so near-misses still type a space
            for (int i = 0; i < r.keys.size(); i++) {
                Key k = r.keys.get(i);
                if (k.code != Key.SPACE) continue;
                float extra = unit * 0.25f;
                if (i > 0) {
                    Key prev = r.keys.get(i - 1);
                    if (prev.isChar()) { k.rect.left -= extra; prev.rect.right -= extra; }
                }
                if (i < r.keys.size() - 1) {
                    Key next = r.keys.get(i + 1);
                    if (next.isChar()) { k.rect.right += extra; next.rect.left += extra; }
                }
            }
            y += rowH;
            rowIdx++;
        }
    }

    /** Centres of the letter keys, used by the swipe decoder. */
    Map<Character, float[]> letterCenters() {
        Map<Character, float[]> m = new HashMap<>();
        for (Key k : keys) {
            if (k.isLetter()) {
                m.put(k.text.charAt(0), new float[]{k.visual.centerX(), k.visual.centerY()});
            }
        }
        return m;
    }

    float letterKeyWidth() {
        for (Key k : keys) if (k.isLetter()) return k.rect.width();
        return getWidth() / 10f;
    }

    // ---------------------------------------------------------------- drawing

    @Override
    protected void onDraw(Canvas c) {
        float r = 8.5f * density;
        boolean letters = layout != null && layout.kind == Layouts.ALPHA;
        for (Key k : keys) {
            RectF v = k.visual;
            int bg;
            switch (k.style) {
                case Key.STYLE_FUNC:
                    bg = k.pressed ? theme.funcKeyPressed : theme.funcKey;
                    if (k.code == Key.SHIFT && shiftState == 2) bg = theme.funcKeyPressed;
                    break;
                case Key.STYLE_ENTER:
                    bg = k.pressed ? theme.accentPressed : theme.accent;
                    break;
                default:
                    bg = k.pressed ? theme.keyPressed : theme.key;
            }
            if (!theme.dark) {
                keyPaint.setColor(theme.shadow);
                c.drawRoundRect(v.left, v.top + 1.2f * density, v.right, v.bottom + 1.2f * density, r, r, keyPaint);
            }
            keyPaint.setColor(bg);
            c.drawRoundRect(v, r, r, keyPaint);

            int fg = fade(k.style == Key.STYLE_ENTER ? theme.accentText : theme.text);
            if (labelAlpha <= 0.01f) continue;   // spacebar cursor mode: shapes only
            float cx = v.centerX(), cy = v.centerY();

            if (k.code == Key.SPACE) {
                if (spaceLabel == null || spaceLabel.isEmpty()) continue;
                textPaint.setColor(fade(theme.hintText));
                textPaint.setTypeface(Fonts.regular());
                textPaint.setTextSize(textSize(0.25f, 0.4f));
                fit(textPaint, spaceLabel, v.width() * 0.9f);
                c.drawText(spaceLabel, cx, baseline(textPaint, cy), textPaint);
                continue;
            }
            if (k.code == Key.SHIFT) {
                int ic = shiftState == 2 ? Icons.SHIFT_LOCK : shiftState == 1 ? Icons.SHIFT_ON : Icons.SHIFT;
                Icons.draw(c, ic, cx, cy, Math.min(v.height() * 0.6f, v.width() * 0.46f),
                        shiftState == 2 ? fade(theme.accent) : fg);
                continue;
            }
            if (k.icon != 0 && !k.isChar()) {
                float isz = k.code == Key.DELETE ? Math.min(v.height() * 0.6f, v.width() * 0.5f)
                        : Math.min(v.height() * 0.42f, v.width() * 0.5f);
                Icons.draw(c, k.icon, cx, cy, isz, fg);
                continue;
            }
            String label = k.label;
            if (label == null) continue;
            boolean isLetter = k.isLetter();
            if (isLetter && letters && shiftState != 0) label = label.toUpperCase();
            if (k.isChar() && label.length() <= 2) {
                textPaint.setTypeface(Fonts.regular());
                textPaint.setTextSize(isLetter ? textSize(0.44f, 0.62f) : textSize(0.4f, 0.58f));
            } else {
                textPaint.setTypeface(Fonts.bold());
                textPaint.setTextSize(textSize(0.28f, 0.42f));
            }
            textPaint.setColor(fg);
            fit(textPaint, label, v.width() * 0.84f);
            Glyphs.draw(c, label, cx, cy, textPaint);

            if (k.hint != null) {
                hintPaint.setColor(fade(theme.hintText));
                hintPaint.setTypeface(Fonts.regular());
                hintPaint.setTextSize(textSize(0.21f, 0.3f));
                c.drawText(k.hint, v.right - 4.5f * density, v.top + hintPaint.getTextSize() + 2f * density, hintPaint);
            } else if (k.longCode == Key.EMOJI) {
                float s = keyH * 0.19f;
                Icons.draw(c, Icons.EMOJI, v.right - s / 2 - 4f * density, v.top + s / 2 + 4f * density, s, fade(theme.hintText));
            }
        }
    }

    private static float baseline(Paint p, float cy) {
        return cy - (p.descent() + p.ascent()) / 2f;
    }

    private static void fit(Paint p, String s, float maxW) {
        float w = p.measureText(s);
        if (w > maxW && w > 0) p.setTextSize(p.getTextSize() * maxW / w);
    }

    // ---------------------------------------------------------------- touch

    private Key keyAt(float x, float y) {
        Key best = null;
        float bestD = Float.MAX_VALUE;
        for (Key k : keys) {
            if (k.rect.contains(x, y)) return k;
            float dx = Math.max(0, Math.max(k.rect.left - x, x - k.rect.right));
            float dy = Math.max(0, Math.max(k.rect.top - y, y - k.rect.bottom));
            float d = dx * dx + dy * dy;
            if (d < bestD) { bestD = d; best = k; }
        }
        return best;
    }

    private void offsets(float[] out) {
        getLocationInWindow(myLoc);
        if (overlay != null) overlay.getLocationInWindow(overlayLoc);
        out[0] = myLoc[0] - overlayLoc[0];
        out[1] = myLoc[1] - overlayLoc[1];
    }

    private final float[] off = new float[2];

    private RectF toOverlay(RectF r) {
        offsets(off);
        return new RectF(r.left + off[0], r.top + off[1], r.right + off[0], r.bottom + off[1]);
    }

    private void showPreviewFor(Key k) {
        if (overlay == null) return;
        handler.removeCallbacks(hidePreviewRunnable);
        boolean pad = layout != null && (layout.kind == Layouts.NUMPAD || layout.kind == Layouts.PHONE);
        if (!showPreview || pad || !k.isChar() || k.style != Key.STYLE_CHAR || k.width > 1.6f) {
            overlay.hidePreview();
            return;
        }
        String label = k.label;
        if (k.isLetter() && layout.kind == Layouts.ALPHA && shiftState != 0) label = label.toUpperCase();
        overlay.showPreview(label, toOverlay(k.visual), keyH);
    }

    private void hidePreviewSoon() {
        handler.removeCallbacks(hidePreviewRunnable);
        handler.postDelayed(hidePreviewRunnable, 45);
    }

    private boolean gestureEligible(Ptr p) {
        return gestureEnabled && layout != null && layout.kind == Layouts.ALPHA
                && p.key != null && p.key.isLetter() && ptrs.size() == 1;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (layout == null || listener == null) return true;
        int action = e.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int idx = e.getActionIndex();
                for (int i = 0; i < ptrs.size(); i++) {
                    Ptr other = ptrs.valueAt(i);
                    if (other.mode != MODE_NORMAL && other.mode != MODE_DONE) return true; // busy
                }
                // roll-over typing: a new finger commits the keys still held by other fingers
                for (int i = ptrs.size() - 1; i >= 0; i--) {
                    Ptr other = ptrs.valueAt(i);
                    if (other.mode == MODE_NORMAL && other.key != null
                            && other.key.code != Key.SHIFT && other.key.code != Key.DELETE) {
                        other.key.pressed = false;
                        commit(other);
                        if (longPtr == other) cancelLong();
                        ptrs.removeAt(i);
                    }
                }
                float x = e.getX(idx), y = e.getY(idx);
                Ptr p = new Ptr();
                p.id = e.getPointerId(idx);
                p.key = keyAt(x, y);
                p.downX = x;
                p.lastX = x;
                p.lastY = y;
                p.downY = y;
                p.downTime = e.getEventTime();
                if (p.key == null) return true;
                ptrs.put(p.id, p);
                p.points.add(new float[]{x, y});
                p.key.pressed = true;
                listener.onPress(p.key);
                if (p.key.code == Key.SHIFT) {
                    listener.onKey(p.key);
                    p.mode = MODE_DONE;
                    startLong(p);
                } else if (p.key.code == Key.DELETE) {
                    listener.onKey(p.key);
                    p.mode = MODE_DONE;
                    repeatPtr = p;
                    repeatCount = 0;
                    handler.postDelayed(repeatRunnable, 400);
                } else {
                    showPreviewFor(p.key);
                    startLong(p);
                }
                invalidate();
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                for (int i = 0; i < e.getPointerCount(); i++) {
                    Ptr p = ptrs.get(e.getPointerId(i));
                    if (p == null) continue;
                    float x = e.getX(i), y = e.getY(i);
                    handleMove(p, e, i, x, y);
                }
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                int idx = e.getActionIndex();
                Ptr p = ptrs.get(e.getPointerId(idx));
                if (p == null) break;
                ptrs.remove(p.id);
                if (longPtr == p) cancelLong();
                if (repeatPtr == p) {
                    repeatPtr = null;
                    handler.removeCallbacks(repeatRunnable);
                }
                if (p.key != null) p.key.pressed = false;
                if (p.mode == MODE_CURSOR) setCursorMode(false);
                switch (p.mode) {
                    case MODE_NORMAL:
                        commit(p);
                        break;
                    case MODE_POPUP:
                        if (overlay != null) {
                            String s = overlay.popupSelection();
                            overlay.hidePopup();
                            if (s != null) listener.onText(s);
                        }
                        break;
                    case MODE_GESTURE:
                        p.points.add(new float[]{e.getX(idx), e.getY(idx)});
                        listener.onGesture(p.points);
                        if (overlay != null) handler.postDelayed(overlay::clearTrail, 120);
                        break;
                    default:
                        break;
                }
                hidePreviewSoon();
                invalidate();
                break;
            }
            case MotionEvent.ACTION_CANCEL:
                cancelAll();
                break;
            default:
                break;
        }
        return true;
    }

    private void handleMove(Ptr p, MotionEvent e, int i, float x, float y) {
        switch (p.mode) {
            case MODE_NORMAL: {
                float dx = x - p.downX, dy = y - p.downY;
                p.lastX = x;
                p.lastY = y;
                if (p.key.code == Key.SPACE) {
                    if (spaceCursor && Math.abs(dx) > 14 * density) {
                        p.mode = MODE_CURSOR;
                        setCursorMode(true);
                        p.cursorRefY = y;
                        p.cursorRef = x;
                        cancelLong();
                    }
                    return;
                }
                p.points.add(new float[]{x, y});
                float dist = (float) Math.hypot(dx, dy);
                if (gestureEligible(p) && dist > letterKeyWidth() * 0.75f) {
                    p.mode = MODE_GESTURE;
                    cancelLong();
                    p.key.pressed = false;
                    handler.removeCallbacks(hidePreviewRunnable);
                    if (overlay != null) {
                        overlay.hidePreview();
                        overlay.clearTrail();
                        offsets(off);
                        for (float[] pt : p.points) overlay.addTrailPoint(pt[0] + off[0], pt[1] + off[1]);
                    }
                    invalidate();
                    return;
                }
                Key k = keyAt(x, y);
                if (k != null && k != p.key && k.code != Key.SPACE) {
                    p.key.pressed = false;
                    p.key = k;
                    k.pressed = true;
                    showPreviewFor(k);
                    startLong(p);
                    invalidate();
                }
                break;
            }
            case MODE_CURSOR: {
                float step = 8 * density;
                int steps = (int) ((x - p.cursorRef) / step);
                if (steps != 0) {
                    p.cursorRef += steps * step;
                    listener.onCursorMove(steps);
                }
                // up/down: one line per ~ key height of vertical travel
                float lineStep = Math.max(28 * density, keyH * 0.85f);
                int lines = (int) ((y - p.cursorRefY) / lineStep);
                if (lines != 0) {
                    p.cursorRefY += lines * lineStep;
                    listener.onCursorMoveLines(lines);
                }
                break;
            }
            case MODE_GESTURE: {
                offsets(off);
                int hs = e.getHistorySize();
                for (int h = 0; h < hs; h++) {
                    float hx = e.getHistoricalX(i, h), hy = e.getHistoricalY(i, h);
                    p.points.add(new float[]{hx, hy});
                    if (overlay != null) overlay.addTrailPoint(hx + off[0], hy + off[1]);
                }
                p.points.add(new float[]{x, y});
                if (overlay != null) overlay.addTrailPoint(x + off[0], y + off[1]);
                break;
            }
            case MODE_POPUP: {
                if (overlay != null) {
                    offsets(off);
                    overlay.updatePopupSelection(x + off[0]);
                }
                break;
            }
            default:
                break;
        }
    }

    private void commit(Ptr p) {
        if (p.key == null) return;
        if (p.key.code == Key.SHIFT || p.key.code == Key.DELETE) return;
        listener.onKey(p.key);
    }

    private void startLong(Ptr p) {
        handler.removeCallbacks(longRunnable);
        longPtr = p;
        handler.postDelayed(longRunnable, LONG_PRESS_MS);
    }

    private void cancelLong() {
        handler.removeCallbacks(longRunnable);
        longPtr = null;
    }

    private void onLongTimeout() {
        Ptr p = longPtr;
        longPtr = null;
        if (p == null || p.key == null) return;
        Key k = p.key;
        if (p.mode == MODE_DONE && k.code == Key.SHIFT) {
            listener.onLongPress(k);
            return;
        }
        if (p.mode != MODE_NORMAL) return;
        if (k.code == Key.SPACE && spaceCursor) {
            // hold the spacebar, then slide left/right to move the cursor
            p.mode = MODE_CURSOR;
            setCursorMode(true);
            p.cursorRef = p.lastX;
            p.cursorRefY = p.lastY;
            if (overlay != null) overlay.hidePreview();
            listener.onPress(k);
            return;
        }
        if (k.code == Key.SPACE || k.longCode != 0) {
            p.mode = MODE_DONE;
            k.pressed = false;
            if (overlay != null) overlay.hidePreview();
            listener.onLongPress(k);
            invalidate();
            return;
        }
        if (k.popup != null && k.popup.length > 0) {
            listener.onPress(k);
            if (overlay != null) overlay.hidePreview();
            if (k.popup.length == 1 || overlay == null) {
                p.mode = MODE_DONE;
                listener.onText(k.popup[0]);
            } else {
                p.mode = MODE_POPUP;
                overlay.showPopup(applyShift(k.popup), toOverlay(k.visual), keyH);
            }
        }
    }

    private String[] applyShift(String[] items) {
        if (shiftState == 0 || layout.kind != Layouts.ALPHA) return items;
        String[] out = new String[items.length];
        for (int i = 0; i < items.length; i++) out[i] = items[i].toUpperCase();
        return out;
    }

    void cancelAll() {
        if (labelAnimator != null) labelAnimator.cancel();
        labelAlpha = 1f;
        cancelLong();
        handler.removeCallbacks(repeatRunnable);
        repeatPtr = null;
        for (int i = 0; i < ptrs.size(); i++) {
            Ptr p = ptrs.valueAt(i);
            if (p.key != null) p.key.pressed = false;
        }
        ptrs.clear();
        if (overlay != null) {
            overlay.hidePreview();
            overlay.hidePopup();
            overlay.clearTrail();
        }
        invalidate();
    }
}
