package id.ketik.keyboard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Swipe-typing decoder. Compares the finger path with the ideal path through each
 * candidate word's keys (location matching after resampling), weighted by word frequency.
 */
final class GestureDecoder {
    private static final int N = 48;

    private final Dictionary dict;
    private Map<Character, float[]> centers = new HashMap<>();
    private float keyW = 1;
    private final HashMap<String, List<String>> bucketCache = new HashMap<>();

    GestureDecoder(Dictionary d) {
        dict = d;
    }

    void setKeys(Map<Character, float[]> c, float keyWidth) {
        centers = c;
        keyW = Math.max(1, keyWidth);
    }

    private static final class Scored {
        final String w;
        final float s;
        Scored(String w, float s) { this.w = w; this.s = s; }
    }

    List<String> decode(List<float[]> pts) {
        List<String> out = new ArrayList<>();
        if (!dict.isLoaded() || pts.size() < 2 || centers.isEmpty()) return out;
        float[][] user = resample(pts, N);
        float[] first = pts.get(0), last = pts.get(pts.size() - 1);
        List<Character> starts = nearKeys(first[0], first[1], 3);
        List<Character> ends = nearKeys(last[0], last[1], 3);
        float userLen = pathLength(pts);

        List<Scored> scored = new ArrayList<>();
        for (char a : starts) {
            for (char b : ends) {
                for (String w : bucket(a, b)) {
                    List<float[]> ideal = idealPath(w);
                    if (ideal == null || ideal.size() < 2) continue;
                    float idealLen = pathLength(ideal);
                    if (idealLen > userLen * 1.9f + keyW || idealLen < userLen * 0.4f - keyW) continue;
                    if (!lettersNearPath(ideal, user)) continue;
                    float[][] ip = resample(ideal, N);
                    float d = 0;
                    for (int i = 0; i < N; i++) {
                        float dx = ip[i][0] - user[i][0], dy = ip[i][1] - user[i][1];
                        d += (float) Math.sqrt(dx * dx + dy * dy);
                    }
                    d /= N * keyW;
                    float startD = dist(ideal.get(0), first) / keyW;
                    float endD = dist(ideal.get(ideal.size() - 1), last) / keyW;
                    float s = -d * 3.0f - (startD + endD) * 0.6f + dict.score(w) / 255f * 1.1f;
                    scored.add(new Scored(w, s));
                }
            }
        }
        Collections.sort(scored, (x, y) -> Float.compare(y.s, x.s));
        for (Scored s : scored) {
            if (!out.contains(s.w)) out.add(s.w);
            if (out.size() == 3) break;
        }
        return out;
    }

    private List<String> bucket(char a, char b) {
        String k = "" + a + b;
        List<String> l = bucketCache.get(k);
        if (l == null) {
            l = dict.wordsByEnds(a, b, 2500);
            bucketCache.put(k, l);
        }
        return l;
    }

    void clearCache() {
        bucketCache.clear();
    }

    private List<Character> nearKeys(float x, float y, int max) {
        List<Character> l = new ArrayList<>();
        final HashMap<Character, Float> d = new HashMap<>();
        for (Map.Entry<Character, float[]> e : centers.entrySet()) {
            float dd = (float) Math.hypot(e.getValue()[0] - x, e.getValue()[1] - y);
            if (dd < keyW * 1.25f) {
                l.add(e.getKey());
                d.put(e.getKey(), dd);
            }
        }
        Collections.sort(l, (p, q) -> Float.compare(d.get(p), d.get(q)));
        if (l.size() > max) return l.subList(0, max);
        return l;
    }

    private List<float[]> idealPath(String w) {
        List<float[]> p = new ArrayList<>();
        char prev = 0;
        for (int i = 0; i < w.length(); i++) {
            char ch = w.charAt(i);
            if (ch == prev) continue;
            float[] c = centers.get(ch);
            if (c == null) {
                if (ch == '\'' || ch == '-') continue;
                return null;
            }
            p.add(c);
            prev = ch;
        }
        return p;
    }

    private boolean lettersNearPath(List<float[]> ideal, float[][] user) {
        float lim = keyW * 1.1f;
        for (float[] c : ideal) {
            float best = Float.MAX_VALUE;
            for (float[] u : user) {
                float dx = u[0] - c[0], dy = u[1] - c[1];
                float dd = dx * dx + dy * dy;
                if (dd < best) best = dd;
            }
            if (best > lim * lim) return false;
        }
        return true;
    }

    private static float dist(float[] a, float[] b) {
        return (float) Math.hypot(a[0] - b[0], a[1] - b[1]);
    }

    private static float pathLength(List<float[]> p) {
        float l = 0;
        for (int i = 1; i < p.size(); i++) l += dist(p.get(i - 1), p.get(i));
        return l;
    }

    static float[][] resample(List<float[]> p, int n) {
        float[][] out = new float[n][2];
        float total = pathLength(p);
        if (total <= 0) {
            for (int i = 0; i < n; i++) { out[i][0] = p.get(0)[0]; out[i][1] = p.get(0)[1]; }
            return out;
        }
        float step = total / (n - 1);
        out[0][0] = p.get(0)[0];
        out[0][1] = p.get(0)[1];
        int idx = 1;
        float acc = 0;
        float px = p.get(0)[0], py = p.get(0)[1];
        int i = 1;
        while (i < p.size() && idx < n) {
            float qx = p.get(i)[0], qy = p.get(i)[1];
            float seg = (float) Math.hypot(qx - px, qy - py);
            if (seg > 0 && acc + seg >= step) {
                float t = (step - acc) / seg;
                float nx = px + t * (qx - px), ny = py + t * (qy - py);
                out[idx][0] = nx;
                out[idx][1] = ny;
                idx++;
                px = nx;
                py = ny;
                acc = 0;
            } else {
                acc += seg;
                px = qx;
                py = qy;
                i++;
            }
        }
        float[] lastP = p.get(p.size() - 1);
        while (idx < n) {
            out[idx][0] = lastP[0];
            out[idx][1] = lastP[1];
            idx++;
        }
        return out;
    }
}
