package id.ketik.keyboard;

import android.graphics.RectF;

/** One key on a layout. code == 0 means a character key that outputs {@link #text}. */
final class Key {
    static final int CHAR = 0;
    static final int SHIFT = -1, SYMBOLS = -2, ALPHA = -3, SYM_PAGE = -4, DELETE = -5,
            EMOJI = -6, ENTER = -10, SPACE = -32;

    static final int STYLE_CHAR = 0, STYLE_FUNC = 1, STYLE_SPACE = 2, STYLE_ENTER = 3;

    final int code;
    final String text;     // output for CHAR keys
    final String label;    // what is drawn (null = icon or text)
    final float width;     // in layout units (a row is 10 units wide)
    final int style;
    int icon;
    String hint;           // small symbol at top-right, inserted on long press
    String[] popup;        // long-press choices
    int longCode;          // special long-press action (e.g. EMOJI)
    boolean pressed;

    final RectF rect = new RectF();   // touch area
    final RectF visual = new RectF(); // drawn area

    Key(int code, String text, String label, float width, int style) {
        this.code = code;
        this.text = text;
        this.label = label;
        this.width = width;
        this.style = style;
    }

    static Key ch(String s) {
        return new Key(CHAR, s, s, 1f, STYLE_CHAR);
    }

    static Key ch(String s, float width) {
        return new Key(CHAR, s, s, width, STYLE_CHAR);
    }

    static Key func(int code, String label, float width) {
        return new Key(code, null, label, width, STYLE_FUNC);
    }

    static Key icon(int code, int icon, float width) {
        Key k = new Key(code, null, null, width, STYLE_FUNC);
        k.icon = icon;
        return k;
    }

    boolean isChar() {
        return code == CHAR;
    }

    boolean isLetter() {
        return code == CHAR && text.length() == 1 && Character.isLetter(text.charAt(0));
    }
}
