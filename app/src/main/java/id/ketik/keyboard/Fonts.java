package id.ketik.keyboard;

import android.content.Context;
import android.graphics.Typeface;

import java.io.File;

/**
 * Key font choice. Bundled fonts (SIL Open Font License, see assets/fonts/OFL-*.txt):
 * Inter (closest free match to the Galaxy look) and Nunito Sans. "custom" is a font file the
 * user imported themselves, stored privately in the app.
 */
final class Fonts {
    static final String SYSTEM = "system", INTER = "inter", NUNITO = "nunito", CUSTOM = "custom";

    private static String cachedKey;
    private static Typeface regular = Typeface.DEFAULT, bold = Typeface.DEFAULT_BOLD;

    private Fonts() {}

    static File customFile(Context c) {
        return new File(c.getFilesDir(), "custom_font");
    }

    static String choice(Context c) {
        return Prefs.get(c).getString(Prefs.FONT, INTER);
    }

    static synchronized void load(Context c) {
        String k = choice(c);
        File cf = customFile(c);
        String key = k + ":" + (CUSTOM.equals(k) ? cf.lastModified() : 0);
        if (key.equals(cachedKey)) return;
        cachedKey = key;
        regular = Typeface.DEFAULT;
        bold = Typeface.DEFAULT_BOLD;
        try {
            switch (k) {
                case INTER:
                    regular = variable(c, "fonts/Inter.ttf", 400);
                    bold = variable(c, "fonts/Inter.ttf", 600);
                    break;
                case NUNITO:
                    regular = variable(c, "fonts/NunitoSans.ttf", 400);
                    bold = variable(c, "fonts/NunitoSans.ttf", 700);
                    break;
                case CUSTOM:
                    if (cf.isFile()) {
                        Typeface t = new Typeface.Builder(cf).build();
                        if (t != null) {
                            regular = t;
                            bold = Typeface.create(t, Typeface.BOLD);
                        }
                    }
                    break;
                default:
                    break;
            }
        } catch (Exception ignored) {
            regular = Typeface.DEFAULT;
            bold = Typeface.DEFAULT_BOLD;
        }
        if (regular == null) regular = Typeface.DEFAULT;
        if (bold == null) bold = Typeface.DEFAULT_BOLD;
    }

    private static Typeface variable(Context c, String asset, int weight) {
        Typeface t = new Typeface.Builder(c.getAssets(), asset)
                .setFontVariationSettings("'wght' " + weight)
                .build();
        return t != null ? t : Typeface.DEFAULT;
    }

    /** @return true if the file is a font Android can use. */
    static boolean isValidFont(File f) {
        try {
            return new Typeface.Builder(f).build() != null;
        } catch (Exception e) {
            return false;
        }
    }

    static Typeface regular() { return regular; }
    static Typeface bold() { return bold; }
}
