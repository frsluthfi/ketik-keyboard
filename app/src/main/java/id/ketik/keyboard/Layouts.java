package id.ketik.keyboard;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Builds key layouts. Every row is 10 units wide. */
final class Layouts {
    static final int ALPHA = 0, SYM1 = 1, SYM2 = 2, NUMPAD = 3, PHONE = 4;
    static final int FIELD_NORMAL = 0, FIELD_EMAIL = 1, FIELD_URL = 2;

    static final class Row {
        final List<Key> keys = new ArrayList<>();
        float pad;            // left/right indent in units
        float heightFactor = 1f;
    }

    static final class Layout {
        final int kind;
        final List<Row> rows = new ArrayList<>();
        Layout(int kind) { this.kind = kind; }
    }

    private static final Map<String, String> ACCENTS = new HashMap<>();
    private static final Map<String, String[]> SYMBOL_POPUPS = new HashMap<>();

    static {
        ACCENTS.put("a", "áàâäãåāæ");
        ACCENTS.put("c", "çćč");
        ACCENTS.put("d", "ð");
        ACCENTS.put("e", "éèêëēę");
        ACCENTS.put("g", "ğ");
        ACCENTS.put("i", "íìîïī");
        ACCENTS.put("l", "ł");
        ACCENTS.put("n", "ñń");
        ACCENTS.put("o", "óòôöõøōœ");
        ACCENTS.put("s", "ßśš");
        ACCENTS.put("u", "úùûüū");
        ACCENTS.put("y", "ýÿ");
        ACCENTS.put("z", "žźż");

        SYMBOL_POPUPS.put("1", new String[]{"1", "¹", "½", "⅓", "¼", "⅛"});
        SYMBOL_POPUPS.put("2", new String[]{"2", "²", "⅔"});
        SYMBOL_POPUPS.put("3", new String[]{"3", "³", "¾", "⅜"});
        SYMBOL_POPUPS.put("4", new String[]{"4", "⁴"});
        SYMBOL_POPUPS.put("5", new String[]{"5", "⅝"});
        SYMBOL_POPUPS.put("7", new String[]{"7", "⅞"});
        SYMBOL_POPUPS.put("0", new String[]{"0", "⁰", "ⁿ", "∅"});
        SYMBOL_POPUPS.put("$", new String[]{"$", "Rp", "€", "£", "¥", "₩", "₹", "¢"});
        SYMBOL_POPUPS.put("-", new String[]{"-", "–", "—", "·"});
        SYMBOL_POPUPS.put("+", new String[]{"+", "±"});
        SYMBOL_POPUPS.put("(", new String[]{"(", "[", "{", "<"});
        SYMBOL_POPUPS.put(")", new String[]{")", "]", "}", ">"});
        SYMBOL_POPUPS.put("/", new String[]{"/", "\\"});
        SYMBOL_POPUPS.put("!", new String[]{"!", "¡"});
        SYMBOL_POPUPS.put("?", new String[]{"?", "¿"});
        SYMBOL_POPUPS.put("'", new String[]{"'", "‘", "’", "‚", "‹", "›"});
        SYMBOL_POPUPS.put("\"", new String[]{"\"", "“", "”", "„", "«", "»"});
        SYMBOL_POPUPS.put("%", new String[]{"%", "‰"});
        SYMBOL_POPUPS.put("*", new String[]{"*", "★", "†", "‡"});
        SYMBOL_POPUPS.put("=", new String[]{"=", "≠", "≈", "∞"});
        SYMBOL_POPUPS.put("<", new String[]{"<", "≤", "«"});
        SYMBOL_POPUPS.put(">", new String[]{">", "≥", "»"});
        SYMBOL_POPUPS.put("&", new String[]{"&", "§"});
        SYMBOL_POPUPS.put("#", new String[]{"#", "№"});
        SYMBOL_POPUPS.put(".", new String[]{".", ",", "?", "!", "'", "\"", ":", ";", "-", "@", "/", "#"});
        SYMBOL_POPUPS.put(".com", new String[]{".com", ".co.id", ".id", ".net", ".org", ".sg"});
    }

    private Layouts() {}

    static Layout build(int kind, boolean numberRow, boolean hints, int field, int enterIcon) {
        switch (kind) {
            case SYM1: return symbols(true, numberRow, field, enterIcon);
            case SYM2: return symbols(false, numberRow, field, enterIcon);
            case NUMPAD: return numpad(false, numberRow, enterIcon);
            case PHONE: return numpad(true, numberRow, enterIcon);
            default: return alpha(numberRow, hints, field, enterIcon);
        }
    }

    private static Layout alpha(boolean numberRow, boolean hints, int field, int enterIcon) {
        Layout l = new Layout(ALPHA);
        if (numberRow) {
            Row r = new Row();
            r.heightFactor = 0.8f;
            for (char c : "1234567890".toCharArray()) {
                Key k = Key.ch(String.valueOf(c));
                k.popup = SYMBOL_POPUPS.get(k.text);
                r.keys.add(k);
            }
            l.rows.add(r);
        }
        String[] letters = {"qwertyuiop", "asdfghjkl", "zxcvbnm"};
        String[] hintSets = {"1234567890", "@#$%&-+()", "*\"':;!?"};
        for (int i = 0; i < 3; i++) {
            Row r = new Row();
            if (i == 1) r.pad = 0.5f;
            if (i == 2) {
                Key shift = Key.icon(Key.SHIFT, Icons.SHIFT, 1.5f);
                r.keys.add(shift);
            }
            for (int j = 0; j < letters[i].length(); j++) {
                String s = String.valueOf(letters[i].charAt(j));
                Key k = Key.ch(s);
                // with a number row, the top-row hints would just repeat the numbers
                String hint = (i == 0 && numberRow) ? null : String.valueOf(hintSets[i].charAt(j));
                if (hints) k.hint = hint;
                String acc = ACCENTS.get(s);
                List<String> pop = new ArrayList<>();
                if (hint != null) pop.add(hint);
                if (acc != null) for (char a : acc.toCharArray()) pop.add(String.valueOf(a));
                if (!pop.isEmpty()) k.popup = pop.toArray(new String[0]);
                r.keys.add(k);
            }
            if (i == 2) r.keys.add(Key.icon(Key.DELETE, Icons.BACKSPACE, 1.5f));
            l.rows.add(r);
        }
        l.rows.add(bottomRow("!#1", Key.SYMBOLS, field, enterIcon));
        return l;
    }

    private static Row bottomRow(String modeLabel, int modeCode, int field, int enterIcon) {
        Row r = new Row();
        r.keys.add(Key.func(modeCode, modeLabel, 1.5f));
        float space = 5f;
        if (field == FIELD_EMAIL) {
            r.keys.add(withPopup(Key.ch("@")));
            space = 4f;
        } else if (field == FIELD_URL) {
            r.keys.add(withPopup(Key.ch("/")));
        } else {
            Key comma = Key.ch(",");
            comma.longCode = Key.EMOJI;
            comma.icon = Icons.EMOJI; // drawn as small hint
            r.keys.add(comma);
        }
        Key sp = new Key(Key.SPACE, null, null, space, Key.STYLE_SPACE);
        r.keys.add(sp);
        Key dot = Key.ch(".");
        dot.popup = field == FIELD_URL ? SYMBOL_POPUPS.get(".com") : SYMBOL_POPUPS.get(".");
        if (field == FIELD_URL) dot.popup = new String[]{".", ".com", ".co.id", ".id", ".net", ".org", "/", ":"};
        r.keys.add(dot);
        if (field == FIELD_EMAIL) {
            Key com = Key.ch(".com");
            com.popup = SYMBOL_POPUPS.get(".com");
            r.keys.add(com);
        }
        r.keys.add(enterKey(enterIcon, 1.5f));
        return r;
    }

    static Key enterKey(int enterIcon, float width) {
        Key e = new Key(Key.ENTER, null, null, width,
                enterIcon == Icons.ENTER ? Key.STYLE_FUNC : Key.STYLE_ENTER);
        e.icon = enterIcon;
        return e;
    }

    private static Key withPopup(Key k) {
        k.popup = SYMBOL_POPUPS.get(k.text);
        return k;
    }

    private static Layout symbols(boolean first, boolean numberRowSetting, int field, int enterIcon) {
        Layout l = new Layout(first ? SYM1 : SYM2);
        // symbol pages always have the number row on top
        String[] rows = first
                ? new String[]{"1234567890", "+×÷=/_<>[]", "!@#$%^&*()", "-'\":;,?"}
                : new String[]{"1234567890", "`~\\|{}€£¥₩", "°•○●□■♤♡◇♧", "☆▪¤《》¡¿"};
        // keep the same total height as the letters page
        float factor = numberRowSetting ? 1f : 0.8f;
        for (int i = 0; i < 4; i++) {
            Row r = new Row();
            r.heightFactor = i == 0 ? 0.8f : factor;
            if (i == 3) r.keys.add(Key.func(Key.SYM_PAGE, first ? "1/2" : "2/2", 1.5f));
            String chars = rows[i];
            int count = chars.codePointCount(0, chars.length());
            float w = i == 3 ? 7f / count : 10f / count;
            int idx = 0;
            while (idx < chars.length()) {
                int cp = chars.codePointAt(idx);
                String str = new String(Character.toChars(cp));
                idx += Character.charCount(cp);
                r.keys.add(withPopup(Key.ch(str, w)));
            }
            if (i == 3) r.keys.add(Key.icon(Key.DELETE, Icons.BACKSPACE, 1.5f));
            l.rows.add(r);
        }
        Row bottom = bottomRow("ABC", Key.ALPHA, field, enterIcon);
        bottom.heightFactor = factor;
        l.rows.add(bottom);
        return l;
    }

    /**
     * Compact number pad: digits on the left, actions in a right-hand column (⌫ top, ✓ bottom),
     * a wide 0, and the few symbols tucked into one key with long-press. Rows are shorter than letters.
     */
    private static Layout numpad(boolean phone, boolean numberRow, int enterIcon) {
        // same total height as the letters page (4 rows, or 4.8 with the number row)
        float rowH = (numberRow ? 4.8f : 4f) / 4f;
        Layout l = new Layout(phone ? PHONE : NUMPAD);
        float dw = 2.6f, aw = 2.2f;   // digit width, action-column width (3 * 2.6 + 2.2 = 10)
        String[][] digits = {{"1", "2", "3"}, {"4", "5", "6"}, {"7", "8", "9"}};
        for (int i = 0; i < 3; i++) {
            Row r = new Row();
            r.heightFactor = rowH;
            for (String d : digits[i]) r.keys.add(Key.ch(d, dw));
            Key side;
            if (i == 0) {
                side = Key.icon(Key.DELETE, Icons.BACKSPACE, aw);
            } else if (i == 1) {
                side = new Key(Key.CHAR, phone ? "+" : "-", phone ? "+" : "-", aw, Key.STYLE_FUNC);
                side.popup = phone ? new String[]{"+", "*", "#", "(", ")", ",", ";"} : new String[]{"-", "+", ","};
            } else {
                side = new Key(Key.CHAR, phone ? "-" : ".", phone ? "-" : ".", aw, Key.STYLE_FUNC);
                side.popup = phone ? new String[]{"-", ".", " "} : new String[]{".", ","};
            }
            r.keys.add(side);
            l.rows.add(r);
        }
        Row last = new Row();
        last.heightFactor = rowH;
        last.keys.add(Key.ch("*", dw));
        last.keys.add(Key.ch("0", dw));      // 0 in the middle, under 8
        last.keys.add(Key.ch("#", dw));
        last.keys.add(enterKey(enterIcon, aw));
        l.rows.add(last);
        return l;
    }
}
