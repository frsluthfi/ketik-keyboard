package id.ketik.keyboard;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds a one-time code in a message (English + Indonesian wording). Pure Java, no Android.
 * Strict on purpose: phone numbers, call-centre numbers (1500xxx, 14xxx), amounts, card digits,
 * postal and promo codes are never offered.
 */
final class OtpExtractor {
    /** Words that really mean "here is a code". The code may be a little further away. */
    private static final Pattern STRONG = Pattern.compile(
            "(?i)(otp|one[- ]?time|verif\\w*|passcode|token|2fa|two[- ]?(factor|step)|authenticat\\w*|"
                    + "autentikasi|\\bkode\\b|\\bcode\\b|rahasia)");
    /** Words that only hint at a code. The number must be right next to them. */
    private static final Pattern WEAK = Pattern.compile(
            "(?i)(\\bpin\\b|password|sandi|\\blog ?in\\b|\\bsign ?in\\b|\\bmasuk\\b|konfirmasi|confirm|"
                    + "security|keamanan|aktivasi|activation)");
    // 4-8 digits, or 3+3 split by a space/dash, optionally after a short prefix like "G-"
    private static final Pattern CANDIDATE = Pattern.compile(
            "(?<![\\p{L}\\d.,:/*#+@_-])(?:[A-Z]{1,3}-)?(\\d{3}[ -]\\d{3}|\\d{4,8})(?![\\d%]|[.,:/-]\\d)");
    private static final Pattern MONEY_BEFORE = Pattern.compile("(?i)(rp\\.?|idr|usd|sgd|\\$|€|£)\\s*$");
    private static final Pattern CONTEXT_BEFORE = Pattern.compile(
            "(?i)(\\b(hubungi|call|telp\\.?|tel\\.?|hotline|halo|contact|cs|(?:wa|whatsapp) (?:ke|di)|nomor|no\\.?|rekening|rek\\.?|"
                    + "ref\\.?|ending in|ending|akhiran|card|kartu|layanan|customer\\w*|call ?cent\\w*|bebas pulsa|kunjungi|"
                    + "info|sms ke|kirim ke|ketik|kode pos|kode promo|promo|voucher|referral|resi|pos)\\b[^\\d]{0,12}"
                    + "|\\*{2,}\\s*|\\bx{2,}\\s*)$");
    private static final Pattern HIDDEN = Pattern.compile("(?i)sensitive notification content hidden");

    private OtpExtractor() {}

    private static List<int[]> spans(Pattern p, String text) {
        List<int[]> out = new ArrayList<>();
        Matcher m = p.matcher(text);
        while (m.find()) out.add(new int[]{m.start(), m.end()});
        return out;
    }

    private static int distance(List<int[]> keys, int start, int end) {
        int best = Integer.MAX_VALUE;
        for (int[] k : keys) {
            int d = start >= k[1] ? start - k[1] : (k[0] >= end ? k[0] - end : 0);
            best = Math.min(best, d);
        }
        return best;
    }

    static String find(String text) {
        if (text == null || text.length() < 4 || HIDDEN.matcher(text).find()) return null;
        List<int[]> strong = spans(STRONG, text);
        List<int[]> weak = spans(WEAK, text);
        if (strong.isEmpty() && weak.isEmpty()) return null;

        String best = null;
        int bestScore = Integer.MAX_VALUE;
        Matcher m = CANDIDATE.matcher(text);
        while (m.find()) {
            String code = m.group(1).replaceAll("[ -]", "");
            int start = m.start(1), end = m.end(1);
            if (MONEY_BEFORE.matcher(text.substring(Math.max(0, start - 6), start)).find()) continue;
            if (CONTEXT_BEFORE.matcher(text.substring(Math.max(0, start - 30), start)).find()) continue;
            int ds = distance(strong, start, end);
            int dw = distance(weak, start, end);
            if (code.matches("0800\\d*|(08|62)\\d{6}|1500\\d{3}")) continue;    // toll-free, phones, call centres
            if (code.equals("1500") && ds > 12) continue;   // "1500" only counts right after "kode"/"OTP"/"code"
            int score;
            if (ds <= 60) score = ds;
            else if (dw <= 25) score = dw + 20;
            else continue;
            if (code.matches("14\\d{3}") && ds > 20) continue;            // 14xxx call centres
            if (code.length() == 6) score -= 15;                          // most common length
            if (code.length() == 4 && code.matches("(19|20)\\d\\d")) {    // looks like a year
                if (Math.min(ds, dw) > 20) continue;
                score += 30;
            }
            if (score < bestScore) {
                bestScore = score;
                best = code;
            }
        }
        return best;
    }
}
