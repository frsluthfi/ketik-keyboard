package id.ketik.keyboard;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;

/**
 * Draws a key label centred on a point. Letters, digits and ASCII punctuation share one baseline
 * (so "a", "g" and "." line up naturally). Other single symbols (× € ○ ♤ 《 ¡ …) often come from
 * fallback fonts with odd metrics, so they are centred by their actual drawn shape instead.
 */
final class Glyphs {
    private static final Rect BOUNDS = new Rect();

    private Glyphs() {}

    static boolean centreByShape(String s) {
        if (s == null || s.isEmpty()) return false;
        int cp = s.codePointAt(0);
        if (Character.charCount(cp) != s.length()) return false;   // multi-character labels
        if (cp < 128 || Character.isLetterOrDigit(cp)) return false;
        return cp != '°';                                            // degree sign belongs up high
    }

    static void draw(Canvas c, String s, float cx, float cy, Paint p) {
        if (centreByShape(s)) {
            p.getTextBounds(s, 0, s.length(), BOUNDS);
            Paint.Align old = p.getTextAlign();
            p.setTextAlign(Paint.Align.LEFT);
            c.drawText(s, cx - BOUNDS.exactCenterX(), cy - BOUNDS.exactCenterY(), p);
            p.setTextAlign(old);
        } else {
            Paint.Align old = p.getTextAlign();
            p.setTextAlign(Paint.Align.CENTER);
            c.drawText(s, cx, cy - (p.descent() + p.ascent()) / 2f, p);
            p.setTextAlign(old);
        }
    }
}
