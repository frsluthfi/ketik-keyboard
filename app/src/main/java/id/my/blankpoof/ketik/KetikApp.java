package id.my.blankpoof.ketik;

import android.app.Application;

public final class KetikApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        CrashLog.install(this);
    }
}
