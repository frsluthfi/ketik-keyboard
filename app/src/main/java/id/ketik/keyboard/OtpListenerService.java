package id.ketik.keyboard;

import android.app.Notification;
import android.os.Bundle;
import android.os.Parcelable;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/**
 * Looks at new notifications (SMS, WhatsApp, email, bank apps…) for a one-time code.
 * Only the code itself is kept, in memory, for a few minutes. Nothing else is read, stored or sent.
 */
public final class OtpListenerService extends NotificationListenerService {

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        try {
            if (sbn == null || getPackageName().equals(sbn.getPackageName())) return;
            Notification n = sbn.getNotification();
            if (n == null) return;
            if ((n.flags & Notification.FLAG_ONGOING_EVENT) != 0) return;
            if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;
            if (System.currentTimeMillis() - sbn.getPostTime() > 10 * 60 * 1000) return;
            Bundle ex = n.extras;
            if (ex == null) return;

            StringBuilder sb = new StringBuilder();
            CharSequence title = ex.getCharSequence(Notification.EXTRA_TITLE);
            append(sb, title);
            append(sb, ex.getCharSequence(Notification.EXTRA_TEXT));
            append(sb, ex.getCharSequence(Notification.EXTRA_BIG_TEXT));
            CharSequence[] lines = ex.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
            if (lines != null && lines.length > 0) append(sb, lines[lines.length - 1]);
            Parcelable[] msgs = ex.getParcelableArray(Notification.EXTRA_MESSAGES);
            if (msgs != null && msgs.length > 0 && msgs[msgs.length - 1] instanceof Bundle) {
                append(sb, ((Bundle) msgs[msgs.length - 1]).getCharSequence("text")); // newest message only
            }
            String code = OtpExtractor.find(sb.toString());
            if (code == null) return;
            String src = title != null && title.length() > 0 && title.length() <= 30
                    ? title.toString() : shortName(sbn.getPackageName());
            OtpBus.post(code, src);
        } catch (Exception ignored) {
        }
    }

    private static void append(StringBuilder sb, CharSequence cs) {
        if (cs != null && cs.length() > 0) sb.append(cs).append('\n');
    }

    private static String shortName(String pkg) {
        int i = pkg.lastIndexOf('.');
        String s = i >= 0 ? pkg.substring(i + 1) : pkg;
        return s.isEmpty() ? pkg : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
