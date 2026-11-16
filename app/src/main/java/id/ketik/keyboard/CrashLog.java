package id.ketik.keyboard;

import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Keeps the last few errors in a private file so they can be copied from Settings and sent for
 * fixing. Contains only the error and app version: never typed text.
 */
final class CrashLog {
    private static Context app;

    private CrashLog() {}

    static File file(Context c) {
        return new File(c.getFilesDir(), "crash_log.txt");
    }

    /** Installs a handler that records fatal errors before the app closes. */
    static void install(Context c) {
        app = c.getApplicationContext();
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            write(app, "FATAL in thread " + t.getName(), e);
            if (prev != null) prev.uncaughtException(t, e);
        });
    }

    static void write(Context c, String where, Throwable e) {
        if (c == null) c = app;
        if (c == null) return;
        try {
            File f = file(c);
            if (f.length() > 64 * 1024) //noinspection ResultOfMethodCallIgnored
                f.delete();
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            String ver;
            try {
                ver = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
            } catch (Exception x) {
                ver = "?";
            }
            try (FileWriter w = new FileWriter(f, true)) {
                w.write("=== " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())
                        + "  Ketik " + ver + "  Android " + Build.VERSION.RELEASE + " (" + Build.MODEL + ")\n");
                w.write(where + "\n");
                w.write(sw.toString());
                w.write("\n");
            }
        } catch (Exception ignored) {
        }
    }

    /** Single background thread whose tasks can never crash the keyboard. */
    static final class SafeExecutor {
        private final ExecutorService inner = Executors.newSingleThreadExecutor();
        private final Context ctx;

        SafeExecutor(Context c) { ctx = c; }

        void execute(Runnable r) {
            try {
                inner.execute(() -> {
                    try {
                        r.run();
                    } catch (Throwable t) {
                        write(ctx, "background task", t);
                    }
                });
            } catch (Exception ignored) {
                // executor already shut down
            }
        }

        void shutdownNow() { inner.shutdownNow(); }
    }
}
