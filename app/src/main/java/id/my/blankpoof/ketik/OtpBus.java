package id.my.blankpoof.ketik;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Holds the latest one-time code in memory only. It is never written to disk or to clipboard
 * history, and it expires after a few minutes.
 */
final class OtpBus {
    interface Listener {
        void onOtpChanged();
    }

    private static final long TTL_MS = 3 * 60 * 1000;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static String code, source;
    private static long time;
    private static boolean shown;   // offered in a text box at least once

    private OtpBus() {}

    static void post(String c, String src) {
        synchronized (OtpBus.class) {
            code = c;
            source = src;
            time = SystemClock.elapsedRealtime();
            shown = false;
        }
        MAIN.post(OtpBus::notifyListeners);
        MAIN.postDelayed(OtpBus::notifyListeners, TTL_MS + 500); // refresh UI after it expires
    }

    /** @return {code, source} or null */
    static synchronized String[] current() {
        if (code == null) return null;
        if (SystemClock.elapsedRealtime() - time > TTL_MS) {
            code = null;
            source = null;
            return null;
        }
        return new String[]{code, source};
    }

    static void clear() {
        synchronized (OtpBus.class) {
            code = null;
            source = null;
        }
        MAIN.post(OtpBus::notifyListeners);
    }

    static synchronized void markShown() { if (code != null) shown = true; }
    static synchronized boolean wasShown() { return code != null && shown; }

    static void add(Listener l) { LISTENERS.add(l); }
    static void remove(Listener l) { LISTENERS.remove(l); }

    private static void notifyListeners() {
        for (Listener l : LISTENERS) l.onOtpChanged();
    }
}
