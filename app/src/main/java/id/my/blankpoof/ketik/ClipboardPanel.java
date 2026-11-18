package id.my.blankpoof.ketik;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Clipboard history grid. Tap pastes; long-press selects for pin / delete. */
final class ClipboardPanel extends LinearLayout {
    interface Listener {
        void onClipPaste(ClipboardStore.Clip clip);
        void onClipClose();
    }

    private final float density;
    private Theme theme;
    private final ClipboardStore store;
    private Listener listener;

    private final IconButton back, pin, delete, clearAll;
    private final TextView title;
    private final View divider;
    private final LinearLayout grid;
    private final TextView empty;
    private final List<ClipboardStore.Clip> selected = new ArrayList<>();

    // thumbnails are decoded off the main thread and cached
    private static final LruCache<String, Bitmap> THUMBS = new LruCache<String, Bitmap>(12 * 1024 * 1024) {
        @Override
        protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };
    private static final ExecutorService DECODER = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    ClipboardPanel(Context c, Theme t, ClipboardStore s) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        theme = t;
        store = s;
        setOrientation(VERTICAL);

        LinearLayout header = new LinearLayout(c);
        header.setOrientation(HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        back = new IconButton(c, t, Icons.KEYBOARD, null);
        back.setOnClickListener(v -> {
            if (!selected.isEmpty()) { selected.clear(); refresh(); }
            else if (listener != null) listener.onClipClose();
        });
        header.addView(back, new LayoutParams(dp(52), dp(46)));
        divider = new View(c);
        LayoutParams dl = new LayoutParams(Math.max(1, dp(1)), dp(20));
        dl.setMargins(dp(2), 0, dp(12), 0);
        header.addView(divider, dl);
        title = new TextView(c);
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        pin = new IconButton(c, t, Icons.PIN, null);
        pin.setOnClickListener(v -> {
            boolean allPinned = true;
            for (ClipboardStore.Clip cl : selected) if (!cl.pinned) allPinned = false;
            store.setPinned(new ArrayList<>(selected), !allPinned);
            selected.clear();
            refresh();
        });
        header.addView(pin, new LayoutParams(dp(48), dp(44)));
        delete = new IconButton(c, t, Icons.TRASH, null);
        delete.setOnClickListener(v -> {
            store.remove(new ArrayList<>(selected));
            selected.clear();
            refresh();
        });
        header.addView(delete, new LayoutParams(dp(48), dp(44)));
        clearAll = new IconButton(c, t, 0, "Clear");
        clearAll.setOnClickListener(v -> {
            store.clearUnpinned();
            refresh();
        });
        header.addView(clearAll, new LayoutParams(dp(76), dp(44)));
        addView(header, new LayoutParams(LayoutParams.MATCH_PARENT, dp(44)));

        FrameLayout body = new FrameLayout(c);
        ScrollView scroll = new ScrollView(c);
        scroll.setVerticalScrollBarEnabled(false);
        grid = new LinearLayout(c);
        grid.setOrientation(VERTICAL);
        grid.setPadding(dp(6), 0, dp(6), dp(6));
        scroll.addView(grid, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        body.addView(scroll, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        empty = new TextView(c);
        empty.setText("Text you copy and screenshots you take will show up here.\nLong-press an item to pin or delete it.");
        empty.setGravity(Gravity.CENTER);
        empty.setTextSize(14);
        body.addView(empty, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        addView(body, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));
        applyTheme();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        // only take the height the parent gives exactly; never grow the keyboard window
        if (MeasureSpec.getMode(heightSpec) != MeasureSpec.EXACTLY) {
            heightSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.EXACTLY);
        }
        super.onMeasure(widthSpec, heightSpec);
    }

    void setListener(Listener l) { listener = l; }

    void setTheme(Theme t) {
        theme = t;
        back.setTheme(t); pin.setTheme(t); delete.setTheme(t); clearAll.setTheme(t);
        applyTheme();
        refresh();
    }

    private void applyTheme() {
        setBackgroundColor(theme.background);
        title.setTextColor(theme.text);
        divider.setBackgroundColor(theme.divider);
        empty.setTextColor(theme.hintText);
    }

    private int dp(float v) { return (int) (v * density + 0.5f); }

    void refresh() {
        List<ClipboardStore.Clip> items = store.items();
        // drop selections that no longer exist
        List<ClipboardStore.Clip> keep = new ArrayList<>();
        for (ClipboardStore.Clip c : selected) if (items.contains(c)) keep.add(c);
        selected.clear();
        selected.addAll(keep);

        boolean sel = !selected.isEmpty();
        title.setText(sel ? selected.size() + " selected" : "Clipboard");
        pin.setVisibility(sel ? VISIBLE : GONE);
        delete.setVisibility(sel ? VISIBLE : GONE);
        clearAll.setVisibility(sel || items.isEmpty() ? GONE : VISIBLE);
        back.icon = sel ? Icons.CLOSE : Icons.KEYBOARD;
        back.invalidate();
        empty.setVisibility(items.isEmpty() ? VISIBLE : GONE);

        grid.removeAllViews();
        LinearLayout row = null;
        for (int i = 0; i < items.size(); i++) {
            if (i % 3 == 0) {
                row = new LinearLayout(getContext());
                row.setOrientation(HORIZONTAL);
                grid.addView(row, new LayoutParams(LayoutParams.MATCH_PARENT, dp(124)));
            }
            row.addView(card(items.get(i)), cardParams());
        }
        while (row != null && row.getChildCount() < 3) {
            row.addView(new View(getContext()), cardParams());
        }
    }

    private LayoutParams cardParams() {
        LayoutParams lp = new LayoutParams(0, LayoutParams.MATCH_PARENT, 1f);
        lp.setMargins(dp(3), dp(3), dp(3), dp(3));
        return lp;
    }

    private View card(final ClipboardStore.Clip clip) {
        View v = clip.isImage() ? imageCard(clip) : textCard(clip);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(10));
        bg.setColor(theme.card);
        if (selected.contains(clip)) bg.setStroke(dp(2), theme.cardSelectedStroke);
        else if (!theme.dark) bg.setStroke(Math.max(1, dp(0.6f)), theme.divider);
        v.setBackground(bg);
        v.setOnClickListener(x -> {
            if (!selected.isEmpty()) {
                if (selected.contains(clip)) selected.remove(clip); else selected.add(clip);
                refresh();
            } else if (listener != null) {
                listener.onClipPaste(clip);
            }
        });
        v.setOnLongClickListener(x -> {
            if (!selected.contains(clip)) selected.add(clip);
            refresh();
            return true;
        });
        return v;
    }

    private View imageCard(final ClipboardStore.Clip clip) {
        FrameLayout f = new FrameLayout(getContext());
        int pad = selected.contains(clip) ? dp(3) : dp(1);
        f.setPadding(pad, pad, pad, pad);
        final ImageView iv = new ImageView(getContext());
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(9));
            }
        });
        iv.setClipToOutline(true);
        f.addView(iv, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        final File file = store.fileOf(clip);
        Bitmap cached = THUMBS.get(clip.image);
        if (cached != null) {
            iv.setImageBitmap(cached);
        } else if (file != null) {
            DECODER.execute(() -> {
                Bitmap b = decodeThumb(file, dp(220));
                if (b == null) return;
                THUMBS.put(clip.image, b);
                main.post(() -> iv.setImageBitmap(b));
            });
        }
        TextView tag = new TextView(getContext());
        tag.setText(clip.pinned ? "PINNED · " + clip.text : clip.text);
        tag.setTextSize(10.5f);
        tag.setTypeface(Typeface.DEFAULT_BOLD);
        tag.setTextColor(0xFFFFFFFF);
        tag.setPadding(dp(6), dp(2), dp(6), dp(2));
        GradientDrawable tb = new GradientDrawable();
        tb.setCornerRadius(dp(6));
        tb.setColor(clip.pinned ? theme.accent : 0x99000000);
        tag.setBackground(tb);
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.START | Gravity.BOTTOM);
        tl.setMargins(dp(5), dp(5), dp(5), dp(5));
        f.addView(tag, tl);
        return f;
    }

    /** Cached thumbnail for an image clip, or null if not decoded yet. */
    static Bitmap cachedThumb(String key) {
        return key == null ? null : THUMBS.get(key);
    }

    /** Decodes and caches a thumbnail. Call off the main thread. */
    static Bitmap loadThumb(File f, String key, int target) {
        Bitmap b = THUMBS.get(key);
        if (b != null) return b;
        b = decodeThumb(f, target);
        if (b != null) THUMBS.put(key, b);
        return b;
    }

    private static Bitmap decodeThumb(File f, int target) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            int sample = 1;
            int smaller = Math.min(o.outWidth, o.outHeight);
            while (smaller / (sample * 2) >= target) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            return BitmapFactory.decodeFile(f.getPath(), o2);
        } catch (Throwable e) {   // includes running out of memory on a huge image
            return null;
        }
    }

    private View textCard(final ClipboardStore.Clip clip) {
        TextView tv = new TextView(getContext());
        tv.setPadding(dp(10), dp(8), dp(10), dp(8));
        tv.setTextSize(13.5f);
        tv.setTextColor(theme.text);
        tv.setMaxLines(6);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        SpannableStringBuilder sb = new SpannableStringBuilder();
        if (clip.pinned) {
            sb.append("PINNED\n");
            sb.setSpan(new ForegroundColorSpan(theme.iconActive), 0, 6, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new RelativeSizeSpan(0.75f), 0, 6, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new StyleSpan(Typeface.BOLD), 0, 6, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        sb.append(clip.text.length() > 400 ? clip.text.substring(0, 400) : clip.text);
        tv.setText(sb);
        return tv;
    }
}
