package id.my.blankpoof.ketik;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Clipboard history kept in app-private storage. Text clips live in SharedPreferences,
 * image clips (screenshots, copied images) as files in files/clips. Pinned items are never trimmed.
 */
final class ClipboardStore {
    static final class Clip {
        String text;      // text, or a label ("Screenshot") for images
        String image;     // file name in clips dir, null for text
        String mime;
        String source;    // where an image came from (e.g. a screenshot's media id), to avoid duplicates
        long time;
        boolean pinned;

        boolean isImage() { return image != null; }
    }

    private static final long MAX_IMAGE_BYTES = 25L * 1024 * 1024;
    private static ClipboardStore instance;

    static synchronized ClipboardStore get(Context c) {
        if (instance == null) instance = new ClipboardStore(c.getApplicationContext());
        return instance;
    }

    static File dir(Context c) {
        File d = new File(c.getFilesDir(), "clips");
        if (!d.exists()) //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        return d;
    }

    private final Context ctx;
    private final SharedPreferences sp;
    private final List<Clip> items = new ArrayList<>();

    private ClipboardStore(Context c) {
        ctx = c;
        sp = c.getSharedPreferences("ketik_clipboard", Context.MODE_PRIVATE);
        try {
            JSONArray a = new JSONArray(sp.getString("clips", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Clip cl = new Clip();
                cl.text = o.getString("t");
                cl.time = o.optLong("ts");
                cl.pinned = o.optBoolean("p");
                if (o.has("i")) {
                    cl.image = o.getString("i");
                    cl.mime = o.optString("m", "image/png");
                    cl.source = o.has("s") ? o.getString("s") : null;
                    if (!new File(dir(c), cl.image).exists()) continue;
                }
                items.add(cl);
            }
        } catch (Exception ignored) {
        }
        sort();
    }

    private void sort() {
        Collections.sort(items, (a, b) -> {
            if (a.pinned != b.pinned) return a.pinned ? -1 : 1;
            return Long.compare(b.time, a.time);
        });
    }

    private void save() {
        JSONArray a = new JSONArray();
        try {
            for (Clip c : items) {
                JSONObject o = new JSONObject();
                o.put("t", c.text);
                o.put("ts", c.time);
                o.put("p", c.pinned);
                if (c.image != null) {
                    o.put("i", c.image);
                    o.put("m", c.mime);
                    if (c.source != null) o.put("s", c.source);
                }
                a.put(o);
            }
        } catch (Exception ignored) {
        }
        sp.edit().putString("clips", a.toString()).apply();
    }

    private void deleteFile(Clip c) {
        if (c.image == null) return;
        for (Clip other : items) if (other != c && c.image.equals(other.image)) return;
        //noinspection ResultOfMethodCallIgnored
        new File(dir(ctx), c.image).delete();
    }

    synchronized List<Clip> items() {
        return new ArrayList<>(items);
    }

    synchronized Clip newest() {
        Clip best = null;
        for (Clip c : items) if (best == null || c.time > best.time) best = c;
        return best;
    }

    /** @return true if the history changed */
    synchronized boolean add(String text) {
        if (text == null) return false;
        if (text.trim().isEmpty()) return false;
        if (text.length() > 10000) text = text.substring(0, 10000);
        Clip existing = null;
        for (Clip c : items) if (c.image == null && c.text.equals(text)) existing = c;
        if (existing != null) {
            if (items.get(0) == existing && !existing.pinned) return false;
            existing.time = System.currentTimeMillis();
        } else {
            Clip c = new Clip();
            c.text = text;
            c.time = System.currentTimeMillis();
            items.add(c);
        }
        sort();
        trim();
        save();
        return true;
    }

    private static String extFor(String mime) {
        if (mime == null) return "png";
        switch (mime) {
            case "image/jpeg": return "jpg";
            case "image/webp": return "webp";
            case "image/gif": return "gif";
            case "image/heic": return "heic";
            default: return "png";
        }
    }

    /**
     * Copies an image into private storage and adds it to the history. Call off the main thread.
     * Identical images (same bytes) are stored once.
     * @return true if the history changed
     */
    boolean addImage(InputStream in, String mime, String label, String source) {
        File d = dir(ctx);
        File tmp = new File(d, "incoming-" + Thread.currentThread().getId() + ".tmp");
        String name;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            long total = 0;
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_IMAGE_BYTES) throw new IllegalStateException("too big");
                    md.update(buf, 0, n);
                    out.write(buf, 0, n);
                }
            }
            if (total == 0) throw new IllegalStateException("empty");
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            name = sb + "." + extFor(mime);
            File dest = new File(d, name);
            if (dest.exists()) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            } else if (!tmp.renameTo(dest)) {
                throw new IllegalStateException("rename failed");
            }
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return false;
        }
        synchronized (this) {
            Clip existing = null;
            for (Clip c : items) if (name.equals(c.image)) existing = c;
            if (existing == null && source != null) {
                // same screenshot read again (e.g. after the system finished writing it): replace, don't duplicate
                for (Clip c : items) {
                    if (source.equals(c.source)) {
                        String old = c.image;
                        c.image = name;
                        c.mime = mime == null ? "image/png" : mime;
                        Clip probe = new Clip();
                        probe.image = old;
                        deleteFile(probe);
                        save();
                        return true;
                    }
                }
            }
            if (existing != null) {
                if (items.get(0) == existing && !existing.pinned) return false;
                existing.time = System.currentTimeMillis();
            } else {
                Clip c = new Clip();
                c.text = label;
                c.image = name;
                c.mime = mime == null ? "image/png" : mime;
                c.source = source;
                c.time = System.currentTimeMillis();
                items.add(c);
            }
            sort();
            trim();
            save();
        }
        return true;
    }

    File fileOf(Clip c) {
        return c.image == null ? null : new File(dir(ctx), c.image);
    }

    private void trim() {
        int max = Prefs.integer(ctx, Prefs.CLIP_MAX);
        int unpinned = 0;
        for (int i = 0; i < items.size(); i++) {
            Clip c = items.get(i);
            if (!c.pinned) {
                unpinned++;
                if (unpinned > max) {
                    items.remove(i);
                    deleteFile(c);
                    i--;
                }
            }
        }
    }

    synchronized void setPinned(List<Clip> clips, boolean pinned) {
        for (Clip c : clips) c.pinned = pinned;
        sort();
        trim();
        save();
    }

    synchronized void remove(List<Clip> clips) {
        items.removeAll(clips);
        for (Clip c : clips) deleteFile(c);
        save();
    }

    synchronized void clearUnpinned() {
        List<Clip> keep = new ArrayList<>();
        List<Clip> gone = new ArrayList<>();
        for (Clip c : items) (c.pinned ? keep : gone).add(c);
        items.clear();
        items.addAll(keep);
        for (Clip c : gone) deleteFile(c);
        save();
    }

    synchronized void clearAll() {
        items.clear();
        File[] files = dir(ctx).listFiles();
        if (files != null) for (File f : files) //noinspection ResultOfMethodCallIgnored
            f.delete();
        save();
    }
}
