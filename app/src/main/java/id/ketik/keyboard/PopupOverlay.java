package id.ketik.keyboard;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.animation.ValueAnimator;
import android.graphics.RectF;
import android.view.animation.DecelerateInterpolator;
import android.view.View;

/**
 * Transparent layer above the whole keyboard window. Draws key previews, long-press
 * popups and the swipe-typing trail so they can extend over the toolbar.
 * It never consumes touches.
 */
final class PopupOverlay extends View {
    private Theme theme;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trailPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;

    // preview
    private boolean previewOn;
    private String previewLabel;
    private final RectF previewRect = new RectF();   // the head of the balloon
    private final RectF previewKey = new RectF();    // the key it grows out of
    private final Path balloon = new Path();
    private float previewAnim = 1f;
    private float previewScale = 1.2f;

    void setPreviewScale(float s) { previewScale = s; }

    private float previewWidth = 1.3f;
    void setPreviewWidth(float w) { previewWidth = w; }
    private ValueAnimator previewAnimator;

    // long-press popup
    private String[] popupItems;
    private RectF[] popupRects;
    private int popupSel = -1;
    private final RectF popupBounds = new RectF();

    // swipe trail
    private float[] trail = new float[512];
    private int trailCount;
    private final Path trailPath = new Path();

    PopupOverlay(Context c, Theme t) {
        super(c);
        theme = t;
        density = c.getResources().getDisplayMetrics().density;
        text.setTextAlign(Paint.Align.CENTER);
        trailPaint.setStyle(Paint.Style.STROKE);
        trailPaint.setStrokeCap(Paint.Cap.ROUND);
        trailPaint.setStrokeJoin(Paint.Join.ROUND);
        trailPaint.setStrokeWidth(5f * density);
        setWillNotDraw(false);
        setClickable(false);
        setFocusable(false);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        // only take the height the parent gives exactly; never grow the keyboard window
        int h = MeasureSpec.getMode(heightSpec) == MeasureSpec.EXACTLY ? MeasureSpec.getSize(heightSpec) : 0;
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), h);
    }

    void setTheme(Theme t) {
        theme = t;
        invalidate();
    }

    /**
     * Balloon preview: a head about the size of the key (just slightly wider), lifted above the key by
     * a neck of the key's width. The "preview height" setting lengthens the neck, not the head.
     */
    void showPreview(String label, RectF keyVisual, float keyH) {
        previewLabel = label;
        previewKey.set(keyVisual);
        float kw = keyVisual.width();
        float kh = keyVisual.height();
        float headW = kw * previewWidth;
        float headH = Math.max(kh, keyH * 0.85f);
        float neck = keyH * Math.max(0.12f, previewScale - 0.85f);
        float headBottom = keyVisual.top - neck;
        float headTop = headBottom - headH;
        if (headTop < 0) {                      // top row: shorten the neck first so the head stays whole
            float lack = -headTop;
            float take = Math.min(lack, neck - keyH * 0.06f);
            headBottom += take;
            headTop += take;
            if (headTop < 0) { headBottom -= headTop; headTop = 0; }
        }
        float cx = keyVisual.centerX();
        float left = cx - headW / 2f, right = cx + headW / 2f;
        if (left < 2 * density) { right += 2 * density - left; left = 2 * density; }
        if (right > getWidth() - 2 * density) { left -= right - (getWidth() - 2 * density); right = getWidth() - 2 * density; }
        left = Math.min(left, keyVisual.left);
        right = Math.max(right, keyVisual.right);
        previewRect.set(left, headTop, right, headBottom);
        buildBalloon(keyH);
        previewOn = true;
        previewAnim = 1f;   // instant, no animation
        invalidate();
    }

    private void buildBalloon(float keyH) {
        RectF h = previewRect, k = previewKey;
        float r = Math.min(10 * density, h.height() / 3f);    // head corner radius
        float kr = 8.5f * density;                              // key corner radius
        // short smooth step from the slightly wider head to the key's width
        float step = Math.min(keyH * 0.18f, Math.max(2 * density, k.top - h.bottom + keyH * 0.1f));
        float neckTop = Math.min(h.bottom + step, k.bottom - kr);
        float mid = (h.bottom + neckTop) / 2f;
        balloon.reset();
        balloon.moveTo(h.left + r, h.top);
        balloon.lineTo(h.right - r, h.top);
        balloon.quadTo(h.right, h.top, h.right, h.top + r);
        balloon.lineTo(h.right, h.bottom);
        balloon.cubicTo(h.right, mid, k.right, mid, k.right, neckTop);
        balloon.lineTo(k.right, k.bottom - kr);
        balloon.quadTo(k.right, k.bottom, k.right - kr, k.bottom);
        balloon.lineTo(k.left + kr, k.bottom);
        balloon.quadTo(k.left, k.bottom, k.left, k.bottom - kr);
        balloon.lineTo(k.left, neckTop);
        balloon.cubicTo(k.left, mid, h.left, mid, h.left, h.bottom);
        balloon.lineTo(h.left, h.top + r);
        balloon.quadTo(h.left, h.top, h.left + r, h.top);
        balloon.close();
    }

    void hidePreview() {
        if (previewAnimator != null) previewAnimator.cancel();
        if (previewOn) {
            previewOn = false;
            invalidate();
        }
    }

    /** Lays out popup items above the key; index 0 sits right above the key. */
    void showPopup(String[] items, RectF keyVisual, float keyH) {
        popupItems = items;
        int n = items.length;
        float itemW = Math.max(keyVisual.width(), 34 * density);
        float maxW = getWidth() - 8 * density;
        if (itemW * n > maxW) itemW = maxW / n;
        float h = keyH * 0.95f;
        float bottom = keyVisual.top - 4 * density;
        float top = bottom - h;
        if (top < 0) { bottom -= top; top = 0; }
        float left = keyVisual.centerX() - itemW / 2f;
        float right = left + itemW * n;
        if (right > getWidth() - 4 * density) {
            float shift = right - (getWidth() - 4 * density);
            left -= shift;
            right -= shift;
        }
        if (left < 4 * density) {
            float shift = 4 * density - left;
            left += shift;
            right += shift;
        }
        popupBounds.set(left, top, right, bottom);
        popupRects = new RectF[n];
        for (int i = 0; i < n; i++) {
            popupRects[i] = new RectF(left + i * itemW, top, left + (i + 1) * itemW, bottom);
        }
        popupSel = 0;
        invalidate();
    }

    /** x is in overlay coordinates. */
    void updatePopupSelection(float x) {
        if (popupRects == null) return;
        int sel;
        if (x < popupBounds.left) sel = 0;
        else if (x >= popupBounds.right) sel = popupRects.length - 1;
        else sel = (int) ((x - popupBounds.left) / (popupBounds.width() / popupRects.length));
        if (sel != popupSel) {
            popupSel = sel;
            invalidate();
        }
    }

    String popupSelection() {
        if (popupItems == null || popupSel < 0 || popupSel >= popupItems.length) return null;
        return popupItems[popupSel];
    }

    void hidePopup() {
        if (popupItems != null) {
            popupItems = null;
            popupRects = null;
            popupSel = -1;
            invalidate();
        }
    }

    boolean isPopupShowing() {
        return popupItems != null;
    }

    void clearTrail() {
        trailCount = 0;
        invalidate();
    }

    void addTrailPoint(float x, float y) {
        if (trailCount * 2 + 2 > trail.length) {
            float[] n = new float[trail.length * 2];
            System.arraycopy(trail, 0, n, 0, trail.length);
            trail = n;
        }
        trail[trailCount * 2] = x;
        trail[trailCount * 2 + 1] = y;
        trailCount++;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        if (trailCount > 1) {
            // draw the newest ~60 points so the tail fades away while swiping
            int start = Math.max(0, trailCount - 60);
            int segs = trailCount - 1 - start;
            for (int i = start; i < trailCount - 1; i++) {
                float f = (i - start + 1) / (float) Math.max(1, segs);
                int alpha = (int) (((theme.trail >>> 24) & 0xFF) * (0.25f + 0.75f * f));
                trailPaint.setColor((theme.trail & 0x00FFFFFF) | (alpha << 24));
                trailPaint.setStrokeWidth((2f + 4f * f) * density);
                c.drawLine(trail[i * 2], trail[i * 2 + 1], trail[i * 2 + 2], trail[i * 2 + 3], trailPaint);
            }
        }
        float r = 10 * density;
        if (previewOn && previewLabel != null) {
            // grow up out of the key: scale the whole balloon from the key's bottom centre
            float sc = 0.82f + 0.18f * previewAnim;
            int save = c.save();
            c.scale(sc, sc, previewKey.centerX(), previewKey.bottom);
            int alpha = (int) (255 * Math.min(1f, 0.35f + previewAnim));
            c.save();
            c.translate(0, 1.5f * density);
            fill.setColor(theme.shadow);
            fill.setAlpha((int) (((theme.shadow >>> 24) & 0xFF) * alpha / 255f));
            c.drawPath(balloon, fill);
            c.restore();
            fill.setColor(theme.popupBg);
            fill.setAlpha(alpha);
            c.drawPath(balloon, fill);
            fill.setAlpha(255);
            text.setColor(theme.popupText);
            text.setTypeface(Fonts.regular());
            text.setAlpha(alpha);
            text.setTextSize(previewRect.height() * 0.58f);
            fitText(previewLabel, previewRect.width() * 0.86f);
            float base = previewRect.centerY() - (text.descent() + text.ascent()) / 2f;
            Glyphs.draw(c, previewLabel, previewRect.centerX(), previewRect.centerY(), text);
            text.setAlpha(255);
            c.restoreToCount(save);
        }
        if (popupItems != null && popupRects != null) {
            fill.setColor(theme.shadow);
            c.drawRoundRect(popupBounds.left, popupBounds.top + 2 * density, popupBounds.right,
                    popupBounds.bottom + 2 * density, r, r, fill);
            fill.setColor(theme.popupBg);
            c.drawRoundRect(popupBounds, r, r, fill);
            for (int i = 0; i < popupItems.length; i++) {
                RectF pr = popupRects[i];
                if (i == popupSel) {
                    fill.setColor(theme.popupSelected);
                    c.drawRoundRect(pr.left + 2 * density, pr.top + 2 * density, pr.right - 2 * density,
                            pr.bottom - 2 * density, r * 0.8f, r * 0.8f, fill);
                    text.setColor(theme.popupSelectedText);
                } else {
                    text.setColor(theme.popupText);
                }
                text.setTypeface(Fonts.regular());
                text.setTextSize(pr.height() * 0.42f);
                fitText(popupItems[i], pr.width() * 0.86f);
                float base = pr.centerY() - (text.descent() + text.ascent()) / 2f;
                Glyphs.draw(c, popupItems[i], pr.centerX(), pr.centerY(), text);
            }
        }
    }

    private void fitText(String s, float maxW) {
        float w = text.measureText(s);
        if (w > maxW && w > 0) text.setTextSize(text.getTextSize() * maxW / w);
    }
}
