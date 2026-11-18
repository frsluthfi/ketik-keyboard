package id.my.blankpoof.ketik;

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
    private static volatile OtpListenerService instance;

    @Override
    public void onListenerConnected() {
        instance = this;
    }

    @Override
    public void onListenerDisconnected() {
        if (instance == this) instance = null;
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }

    /**
     * After a code was filled in: press the message's own "Mark as read" button if it has one
     * (Google Messages, WhatsApp, most SMS apps), then clear the notification.
     * Finds the notification by the code it contains. Does nothing if access is off.
     */
    static void markHandled(String code) {
        OtpListenerService s = instance;
        if (s == null || code == null) return;
        try {
            StatusBarNotification[] active = s.getActiveNotifications();
            if (active == null) return;
            for (StatusBarNotification sbn : active) {
                if (s.getPackageName().equals(sbn.getPackageName())) continue;
                Notification n = sbn.getNotification();
                if (n == null || (n.flags & Notification.FLAG_ONGOING_EVENT) != 0) continue;
                String text = textOf(n);
                if (text == null || !text.replaceAll("[ -]", "").contains(code)) continue;
                pressMarkAsRead(n);
                if ((n.flags & Notification.FLAG_NO_CLEAR) == 0) s.cancelNotification(sbn.getKey());
            }
        } catch (Exception ignored) {
        }
    }

    private static void pressMarkAsRead(Notification n) {
        if (n.actions == null) return;
        for (Notification.Action a : n.actions) {
            if (a == null || a.actionIntent == null) continue;
            if (a.getRemoteInputs() != null && a.getRemoteInputs().length > 0) continue;   // reply boxes
            boolean semantic = a.getSemanticAction() == Notification.Action.SEMANTIC_ACTION_MARK_AS_READ;
            String t = a.title == null ? "" : a.title.toString().toLowerCase();
            boolean named = t.contains("mark as read") || t.contains("mark read") || t.contains("tandai")
                    || t.contains("sudah dibaca") || t.equals("read") || t.equals("baca");
            if (semantic || named) {
                try {
                    a.actionIntent.send();
                } catch (Exception ignored) {
                }
                return;
            }
        }
    }

    /** All the visible text of a notification. */
    static String textOf(Notification n) {
        Bundle ex = n.extras;
        if (ex == null) return null;
        StringBuilder sb = new StringBuilder();
        append(sb, ex.getCharSequence(Notification.EXTRA_TITLE));
        append(sb, ex.getCharSequence(Notification.EXTRA_TEXT));
        append(sb, ex.getCharSequence(Notification.EXTRA_BIG_TEXT));
        CharSequence[] lines = ex.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
        if (lines != null) for (CharSequence l : lines) append(sb, l);
        Parcelable[] msgs = ex.getParcelableArray(Notification.EXTRA_MESSAGES);
        if (msgs != null) for (Parcelable p : msgs) if (p instanceof Bundle) append(sb, ((Bundle) p).getCharSequence("text"));
        return sb.toString();
    }

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
