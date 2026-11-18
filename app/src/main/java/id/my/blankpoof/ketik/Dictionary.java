package id.my.blankpoof.ketik;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * English + Indonesian word list with frequencies, plus words and word pairs learned
 * from the user. Everything stays on the device (app-private files).
 */
final class Dictionary {
    private static Dictionary instance;

    static synchronized Dictionary get(Context c) {
        if (instance == null) instance = new Dictionary(c.getApplicationContext());
        return instance;
    }

    static final class Suggestions {
        final String[] words = new String[3]; // left, middle (best), right
        String autoCorrect;                   // non-null if space should replace the typed word
    }

    private static final class Cand {
        final String word;
        final float score;
        Cand(String w, float s) { word = w; score = s; }
    }

    private static final String NEIGHBORS_SRC =
            "q:wa w:qeas e:wrsd r:etdf t:ryfg y:tugh u:yihj i:uojk o:iplk p:ol " +
            "a:qwsz s:awedxz d:serfcx f:drtgvc g:ftyhbv h:gyujnb j:huikmn k:jiolm l:kop " +
            "z:asx x:zsdc c:xdfv v:cfgb b:vghn n:bhjm m:njk";
    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz'";

    /** English words that are always capitalised / need an apostrophe. */
    private static final Map<String, String> FORCED = new HashMap<>();
    static {
        String[] p = {"i", "I", "im", "I'm", "i'm", "I'm", "ive", "I've", "i've", "I've", "i'll", "I'll",
                "i'd", "I'd", "id've", "I'd've"};
        for (int k = 0; k < p.length; k += 2) FORCED.put(p[k], p[k + 1]);
    }

    private final Context ctx;
    private final HashMap<String, Integer> freq = new HashMap<>(160000);
    private String[] sorted = new String[0];
    private final HashMap<String, String[]> topByPrefix = new HashMap<>();
    private final HashMap<String, Integer> user = new HashMap<>();
    private final HashMap<String, HashMap<String, Integer>> bigrams = new HashMap<>();
    private final Map<Character, String> neighbors = new HashMap<>();
    private volatile boolean loaded;
    private boolean dirty;

    private Dictionary(Context c) {
        ctx = c;
        for (String part : NEIGHBORS_SRC.split(" ")) neighbors.put(part.charAt(0), part.substring(2));
        Thread t = new Thread(this::load, "ketik-dict");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    boolean isLoaded() { return loaded; }

    private void load() {
        HashMap<String, Integer> f = new HashMap<>(160000);
        readAsset("dict_en.txt", f);
        readAsset("dict_id.txt", f);
        String[] s = f.keySet().toArray(new String[0]);
        Arrays.sort(s);
        HashMap<String, String[]> top = new HashMap<>();
        // pre-compute best completions for 1-3 letter prefixes (their ranges are huge)
        HashMap<String, List<String>> tmp = new HashMap<>();
        for (String w : s) {
            for (int len = 1; len <= 3 && len < w.length(); len++) {
                String p = w.substring(0, len);
                List<String> l = tmp.get(p);
                if (l == null) { l = new ArrayList<>(6); tmp.put(p, l); }
                insertTop(l, w, f, 6);
            }
        }
        for (Map.Entry<String, List<String>> e : tmp.entrySet()) {
            top.put(e.getKey(), e.getValue().toArray(new String[0]));
        }
        HashMap<String, Integer> u = new HashMap<>();
        HashMap<String, HashMap<String, Integer>> b = new HashMap<>();
        readUser(u, b);
        synchronized (this) {
            freq.putAll(f);
            sorted = s;
            topByPrefix.putAll(top);
            user.putAll(u);
            bigrams.putAll(b);
            loaded = true;
        }
    }

    private static void insertTop(List<String> l, String w, Map<String, Integer> f, int max) {
        int fw = f.get(w);
        int i = 0;
        while (i < l.size() && f.get(l.get(i)) >= fw) i++;
        if (i < max) {
            l.add(i, w);
            if (l.size() > max) l.remove(l.size() - 1);
        }
    }

    private void readAsset(String name, Map<String, Integer> out) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(ctx.getAssets().open(name), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                int tab = line.indexOf('\t');
                if (tab <= 0) continue;
                String w = line.substring(0, tab);
                int v = Integer.parseInt(line.substring(tab + 1).trim());
                Integer old = out.get(w);
                if (old == null || old < v) out.put(w, v);
            }
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------ user data

    private File userFile() { return new File(ctx.getFilesDir(), "user_words.tsv"); }
    private File bigramFile() { return new File(ctx.getFilesDir(), "user_bigrams.tsv"); }

    private void readUser(Map<String, Integer> u, Map<String, HashMap<String, Integer>> b) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(userFile()), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split("\t");
                if (p.length == 2) u.put(p[0], Integer.parseInt(p[1]));
            }
        } catch (Exception ignored) {
        }
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(bigramFile()), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split("\t");
                if (p.length != 3) continue;
                HashMap<String, Integer> m = b.get(p[0]);
                if (m == null) { m = new HashMap<>(); b.put(p[0], m); }
                m.put(p[1], Integer.parseInt(p[2]));
            }
        } catch (Exception ignored) {
        }
    }

    synchronized void save() {
        if (!loaded || !dirty) return;
        dirty = false;
        try (Writer w = new OutputStreamWriter(new FileOutputStream(userFile()), StandardCharsets.UTF_8)) {
            List<Map.Entry<String, Integer>> list = new ArrayList<>(user.entrySet());
            Collections.sort(list, (a, b) -> b.getValue() - a.getValue());
            int n = 0;
            for (Map.Entry<String, Integer> e : list) {
                if (n++ > 5000) break;
                w.write(e.getKey() + "\t" + e.getValue() + "\n");
            }
        } catch (Exception ignored) {
        }
        try (Writer w = new OutputStreamWriter(new FileOutputStream(bigramFile()), StandardCharsets.UTF_8)) {
            int n = 0;
            for (Map.Entry<String, HashMap<String, Integer>> e : bigrams.entrySet()) {
                for (Map.Entry<String, Integer> e2 : e.getValue().entrySet()) {
                    if (n++ > 20000) break;
                    w.write(e.getKey() + "\t" + e2.getKey() + "\t" + e2.getValue() + "\n");
                }
            }
        } catch (Exception ignored) {
        }
    }

    synchronized void clearLearned() {
        user.clear();
        bigrams.clear();
        dirty = true;
        //noinspection ResultOfMethodCallIgnored
        userFile().delete();
        //noinspection ResultOfMethodCallIgnored
        bigramFile().delete();
    }

    /** Records that the user typed or picked this word. */
    synchronized void learn(String word, int amount) {
        if (!loaded || word == null) return;
        String w = word.toLowerCase();
        if (w.length() < 2 || w.length() > 32 || !isWordChars(w)) return;
        Integer c = user.get(w);
        user.put(w, Math.min(1000, (c == null ? 0 : c) + amount));
        dirty = true;
    }

    synchronized void learnPair(String prev, String word) {
        if (!loaded || prev == null || word == null) return;
        String a = prev.toLowerCase(), b = word.toLowerCase();
        if (a.isEmpty() || b.isEmpty() || !isWordChars(a) || !isWordChars(b)) return;
        HashMap<String, Integer> m = bigrams.get(a);
        if (m == null) {
            if (bigrams.size() > 8000) return;
            m = new HashMap<>();
            bigrams.put(a, m);
        }
        Integer c = m.get(b);
        m.put(b, (c == null ? 0 : c) + 1);
        dirty = true;
    }

    synchronized void forget(String word) {
        String w = word.toLowerCase();
        user.remove(w);
        for (HashMap<String, Integer> m : bigrams.values()) m.remove(w);
        dirty = true;
    }

    synchronized boolean isLearned(String word) {
        Integer c = user.get(word.toLowerCase());
        return c != null && !freq.containsKey(word.toLowerCase());
    }

    private static boolean isWordChars(String w) {
        for (int i = 0; i < w.length(); i++) {
            char ch = w.charAt(i);
            if (!Character.isLetter(ch) && ch != '\'' && ch != '-') return false;
        }
        return true;
    }

    // ------------------------------------------------------------ queries

    synchronized boolean isWord(String lower) {
        if (freq.containsKey(lower)) return true;
        Integer c = user.get(lower);
        return c != null && c >= 2;
    }

    /** 0..~300. */
    synchronized float score(String lower) {
        Integer f = freq.get(lower);
        Integer u = user.get(lower);
        float s = f == null ? 0 : f;
        if (u != null) s = Math.max(s, 90) + Math.min(80, u * 12);
        return s;
    }

    synchronized List<String> nextWords(String prev) {
        List<String> out = new ArrayList<>();
        if (prev == null) return out;
        HashMap<String, Integer> m = bigrams.get(prev.toLowerCase());
        if (m == null) return out;
        List<Map.Entry<String, Integer>> list = new ArrayList<>(m.entrySet());
        Collections.sort(list, (a, b) -> b.getValue() - a.getValue());
        for (Map.Entry<String, Integer> e : list) {
            out.add(e.getKey());
            if (out.size() == 3) break;
        }
        return out;
    }

    /** Words sorted by score for gesture decoding: all words that start with a and end with b. */
    synchronized List<String> wordsByEnds(char a, char b, int max) {
        List<String> out = new ArrayList<>();
        String first = String.valueOf(a);
        int lo = lowerBound(first);
        for (int i = lo; i < sorted.length; i++) {
            String w = sorted[i];
            if (w.charAt(0) != a) break;
            if (w.length() >= 2 && w.charAt(w.length() - 1) == b) out.add(w);
        }
        for (Map.Entry<String, Integer> e : user.entrySet()) {
            String w = e.getKey();
            if (e.getValue() >= 2 && w.length() >= 2 && w.charAt(0) == a && w.charAt(w.length() - 1) == b
                    && !freq.containsKey(w)) out.add(w);
        }
        final HashMap<String, Float> sc = new HashMap<>();
        for (String w : out) sc.put(w, score(w));
        Collections.sort(out, (x, y) -> Float.compare(sc.get(y), sc.get(x)));
        if (out.size() > max) return new ArrayList<>(out.subList(0, max));
        return out;
    }

    private int lowerBound(String key) {
        int lo = 0, hi = sorted.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (sorted[mid].compareTo(key) < 0) lo = mid + 1; else hi = mid;
        }
        return lo;
    }

    private void completions(String prefix, Map<String, Float> out) {
        String[] top = topByPrefix.get(prefix);
        if (top != null) {
            for (String w : top) put(out, w, score(w) - 25);
        } else {
            int lo = lowerBound(prefix);
            List<String> found = new ArrayList<>();
            for (int i = lo; i < sorted.length && i < lo + 4000; i++) {
                if (!sorted[i].startsWith(prefix)) break;
                if (sorted[i].length() > prefix.length()) found.add(sorted[i]);
            }
            for (String w : found) put(out, w, score(w) - 25 - 4 * (w.length() - prefix.length()));
        }
        for (Map.Entry<String, Integer> e : user.entrySet()) {
            String w = e.getKey();
            if (w.length() > prefix.length() && w.startsWith(prefix) && e.getValue() >= 1) {
                put(out, w, score(w) - 25);
            }
        }
    }

    private static void put(Map<String, Float> m, String w, float s) {
        Float old = m.get(w);
        if (old == null || old < s) m.put(w, s);
    }

    private boolean isNeighbor(char a, char b) {
        String n = neighbors.get(a);
        return n != null && n.indexOf(b) >= 0;
    }

    /** Edits of distance 1 with keyboard-aware costs. */
    private void edits(String w, float base, Map<String, Float> out) {
        int n = w.length();
        for (int i = 0; i < n; i++) {
            put(out, base, w.substring(0, i) + w.substring(i + 1), 0.85f);           // extra letter typed
            if (i < n - 1) {
                put(out, base, w.substring(0, i) + w.charAt(i + 1) + w.charAt(i) + w.substring(i + 2), 0.7f); // swapped
            }
            char orig = w.charAt(i);
            for (int k = 0; k < ALPHABET.length(); k++) {
                char ch = ALPHABET.charAt(k);
                if (ch == orig) continue;
                float cost = isNeighbor(orig, ch) ? 0.55f : 1.05f;
                put(out, base, w.substring(0, i) + ch + w.substring(i + 1), cost);    // wrong key
            }
        }
        for (int i = 0; i <= n; i++) {
            for (int k = 0; k < ALPHABET.length(); k++) {
                char ch = ALPHABET.charAt(k);
                float cost = ch == '\'' ? 0.25f : "aeiou".indexOf(ch) >= 0 ? 0.6f : 0.75f; // missed key
                put(out, base, w.substring(0, i) + ch + w.substring(i), cost);
            }
        }
    }

    private static void put(Map<String, Float> m, float base, String w, float cost) {
        float c = base + cost;
        Float old = m.get(w);
        if (old == null || old > c) m.put(w, c);
    }

    synchronized Suggestions suggest(String typed, boolean allowCorrection) {
        Suggestions res = new Suggestions();
        if (!loaded || typed.isEmpty()) return res;
        String lower = typed.toLowerCase();
        String forced = FORCED.get(lower);
        if (forced != null) {
            res.words[1] = forced;
            res.words[0] = typed.equals(forced) ? null : typed;
            if (allowCorrection && !typed.equals(forced)) res.autoCorrect = forced;
            return res;
        }
        boolean valid = isWord(lower);
        Map<String, Float> cands = new HashMap<>();
        if (valid) put(cands, lower, score(lower) + 45);
        completions(lower, cands);

        String bestCorr = null;
        float bestCorrScore = -1e9f, bestCorrCost = 9;
        if (lower.length() >= 2 && lower.length() <= 20 && isWordChars(lower)) {
            Map<String, Float> e1 = new HashMap<>();
            edits(lower, 0, e1);
            Map<String, Float> all = new HashMap<>(e1);
            if (lower.length() >= 4 && !valid) {
                for (Map.Entry<String, Float> e : e1.entrySet()) {
                    if (e.getValue() > 0.7f) continue; // only expand cheap edits
                    Map<String, Float> e2 = new HashMap<>();
                    edits(e.getKey(), e.getValue(), e2);
                    for (Map.Entry<String, Float> x : e2.entrySet()) {
                        float cost = x.getValue() + 0.25f; // two mistakes are less likely than one
                        if (cost <= 1.7f && isWord(x.getKey())) put(all, 0, x.getKey(), cost);
                    }
                }
            }
            for (Map.Entry<String, Float> e : all.entrySet()) {
                String w = e.getKey();
                if (w.equals(lower) || !isWord(w)) continue;
                float s = score(w) - e.getValue() * 70;
                put(cands, w, s);
                if (s > bestCorrScore) {
                    bestCorrScore = s;
                    bestCorr = w;
                    bestCorrCost = e.getValue();
                }
                // a correction may also be a prefix of a longer word
            }
        }

        List<Cand> list = new ArrayList<>();
        for (Map.Entry<String, Float> e : cands.entrySet()) list.add(new Cand(e.getKey(), e.getValue()));
        Collections.sort(list, (a, b) -> Float.compare(b.score, a.score));

        String top = list.isEmpty() ? null : list.get(0).word;
        List<String> order = new ArrayList<>();
        if (top != null) order.add(top);
        if (top == null || !top.equals(lower)) order.add(0, lower); // typed text goes left
        for (Cand c : list) {
            if (order.size() >= 3) break;
            if (!order.contains(c.word)) order.add(c.word);
        }
        // layout: [typed or 2nd], [best], [3rd]
        String left, mid, right;
        if (top == null) {
            left = null; mid = typed; right = null;
        } else if (top.equals(lower)) {
            mid = lower;
            left = order.size() > 1 ? order.get(1) : null;
            right = order.size() > 2 ? order.get(2) : null;
        } else {
            left = lower;
            mid = top;
            right = order.size() > 2 ? order.get(2) : null;
        }
        res.words[0] = matchCase(left, typed);
        res.words[1] = matchCase(mid, typed);
        res.words[2] = matchCase(right, typed);

        if (allowCorrection && !valid && bestCorr != null && top != null && top.equals(bestCorr)
                && lower.length() >= 2 && !hasInnerCaps(typed)) {
            float maxCost = lower.length() <= 3 ? 0.9f : lower.length() <= 5 ? 1.3f : 1.7f;
            if (bestCorrCost <= maxCost && bestCorrScore > 40) res.autoCorrect = matchCase(bestCorr, typed);
        }
        return res;
    }

    private static boolean hasInnerCaps(String s) {
        for (int i = 1; i < s.length(); i++) if (Character.isUpperCase(s.charAt(i))) return !isAllCaps(s);
        return false;
    }

    private static boolean isAllCaps(String s) {
        for (int i = 0; i < s.length(); i++) if (Character.isLowerCase(s.charAt(i))) return false;
        return true;
    }

    static String matchCase(String word, String typed) {
        if (word == null || word.isEmpty() || typed.isEmpty()) return word;
        if (typed.length() > 1 && isAllCaps(typed)) return word.toUpperCase();
        if (Character.isUpperCase(typed.charAt(0))) {
            return Character.toUpperCase(word.charAt(0)) + word.substring(1);
        }
        return word;
    }
}
