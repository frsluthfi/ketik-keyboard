package id.my.blankpoof.ketik;

import android.content.Context;
import android.content.SharedPreferences;

/** All user settings, stored in one SharedPreferences file. */
public final class Prefs {
    public static final String FILE = "ketik_settings";

    public static final String NUMBER_ROW = "number_row";
    public static final String HINTS = "hint_symbols";
    public static final String SOUND = "sound";
    public static final String VIBRATE = "vibrate";
    public static final String VIBRATE_STRONG = "vibrate_strong";
    public static final String AUTO_CAPS = "auto_caps";
    public static final String AUTO_CORRECT = "auto_correct";
    public static final String PREDICTIONS = "predictions";
    public static final String DOUBLE_SPACE = "double_space";
    public static final String SWIPE = "swipe_typing";
    public static final String SPACE_CURSOR = "space_cursor";
    public static final String KEY_PREVIEW = "key_preview";
    public static final String NEXT_WORD = "next_word";
    public static final String CLIP_SUGGEST = "clip_suggest";
    public static final String SCREENSHOTS = "clip_screenshots";
    public static final String OTP_SMS = "otp_sms";
    public static final String OTP_MARK_READ = "otp_mark_read";
    public static final String ANIMATIONS = "animations";
    public static final String TOOLBAR = "toolbar_order";  // e.g. "more,emoji,clipboard,settings"
    public static final String FONT = "font";             // system / inter / nunito / custom
    public static final String THEME = "theme";          // 0 = follow system, 1 = light, 2 = dark
    public static final String HEIGHT = "height";        // percent, 70..140
    public static final String FONT_SIZE = "font_size";  // percent, 70..130
    public static final String PREVIEW_HEIGHT = "preview_height"; // percent, 80..180
    public static final String PREVIEW_WIDTH = "preview_width";   // percent of key width, 100..170
    public static final String KEY_SIZE = "key_size";    // key height inside its row, percent 60..100
    public static final String ONE_HANDED = "one_handed"; // 0 = off, 1 = left, 2 = right
    public static final String CLIP_MAX = "clip_max";

    private Prefs() {}

    public static SharedPreferences get(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static boolean bool(Context c, String key) {
        return get(c).getBoolean(key, defaultBool(key));
    }

    public static int integer(Context c, String key) {
        return get(c).getInt(key, defaultInt(key));
    }

    public static void put(Context c, String key, boolean v) {
        get(c).edit().putBoolean(key, v).apply();
    }

    public static void put(Context c, String key, int v) {
        get(c).edit().putInt(key, v).apply();
    }

    static boolean defaultBool(String key) {
        switch (key) {
            case NUMBER_ROW:
            case SOUND:
            case VIBRATE_STRONG:
            case OTP_SMS:
                return false;
            default:
                return true;
        }
    }

    static int defaultInt(String key) {
        switch (key) {
            case HEIGHT: return 100;
            case FONT_SIZE: return 100;
            case KEY_SIZE: return 80;
            case PREVIEW_HEIGHT: return 120;
            case PREVIEW_WIDTH: return 130;
            case CLIP_MAX: return 30;
            default: return 0;
        }
    }
}
