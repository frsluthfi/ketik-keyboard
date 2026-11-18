package id.my.blankpoof.ketik;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Telephony;
import android.telephony.SmsMessage;

/**
 * Optional backup for SMS codes (used when the system hides codes inside notifications).
 * Only active if "Also read codes from SMS" is on and SMS permission was granted.
 * The message is checked for a code and then discarded; nothing is stored.
 */
public final class SmsOtpReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            if (!Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(intent.getAction())) return;
            if (!Prefs.bool(context, Prefs.OTP_SMS)) return;
            SmsMessage[] msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent);
            if (msgs == null || msgs.length == 0) return;
            StringBuilder body = new StringBuilder();
            for (SmsMessage m : msgs) if (m != null && m.getMessageBody() != null) body.append(m.getMessageBody());
            String code = OtpExtractor.find(body.toString());
            if (code == null) return;
            String from = msgs[0] != null ? msgs[0].getDisplayOriginatingAddress() : null;
            OtpBus.post(code, from == null ? "SMS" : from);
        } catch (Exception ignored) {
        }
    }
}
