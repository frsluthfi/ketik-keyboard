package id.my.blankpoof.ketik;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.ContentUris;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.database.Cursor;
import android.inputmethodservice.InputMethodService;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.provider.MediaStore;
import android.os.Vibrator;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.transition.AutoTransition;
import android.transition.TransitionManager;
import android.view.Window;
import android.view.WindowInsetsController;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputContentInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The keyboard service. Holds the typing logic; views only report touches. */
public final class KetikIME extends InputMethodService implements KeyboardView.Listener,
        ToolbarView.Listener, EmojiPanel.Listener, ClipboardPanel.Listener, TextEditPanel.Listener,
        SizePanel.Listener {

    private static final int PANEL_KEYS = 0, PANEL_EMOJI = 1, PANEL_CLIP = 2, PANEL_EDIT = 3, PANEL_SIZE = 4, PANEL_MORE = 5;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Theme theme;
    private Dictionary dict;
    private ClipboardStore clips;
    private GestureDecoder decoder;
    private ClipboardManager clipboard;
    private Vibrator vibrator;
    private AudioManager audio;

    // views
    private FrameLayout root;
    private ToolbarView toolbar;
    private FrameLayout host;
    private KeyboardView kv;
    private PopupOverlay overlay;
    private EmojiPanel emojiPanel;
    private ClipboardPanel clipPanel;
    private TextEditPanel editPanel;
    private SizePanel sizePanel;
    private LinearLayout morePanel;
    private LinearLayout leftStrip, rightStrip;
    private int panel = PANEL_KEYS;

    // editor
    private EditorInfo editor;
    private int baseKind = Layouts.ALPHA, kind = Layouts.ALPHA;
    private int field = Layouts.FIELD_NORMAL;
    private int enterIcon = Icons.ENTER, enterAction;
    private boolean rawKeys, isPassword;
    private boolean predictOn, correctOn, autoCapsOn, learnOn, gestureOn;

    // cached prefs
    private boolean vibOn, vibStrong, soundOn, doubleSpaceOn, nextWordOn, clipSuggestOn;

    // typing state
    private int shift;              // 0 off, 1 once, 2 caps lock
    private boolean shiftManual;
    private long lastShiftTap, lastSpaceTime, lastPickTime;
    private boolean autoSpaced;     // we added a space after a picked suggestion
    private boolean toolsForced;
    private String corrOriginal, corrReplacement;
    private String gestureWord;
    private List<String> gestureAlts;
    private long lastClipTime;
    private int cursorPos = -1;     // last known cursor position, for smooth spacebar moves
    private long lastCursorMove;
    private String lastClipShown;

    private final CrashLog.SafeExecutor worker = new CrashLog.SafeExecutor(this);
    private final CrashLog.SafeExecutor io = new CrashLog.SafeExecutor(this);
    private ContentObserver shotObserver;
    private long lastShotId = -1; // only touched on the io thread
    private final Runnable scanLater = () -> io.execute(this::scanScreenshots);
    private int suggestGen;
    private String cacheWord;
    private Dictionary.Suggestions cacheSugg;

    private final Runnable refreshRunnable = () -> {
        try {
            deleteRepeating = false;
            updateShift();
            refreshSuggestions();
        } catch (Throwable t) {
            CrashLog.write(this, "refresh", t);
        }
    };

    private final ClipboardManager.OnPrimaryClipChangedListener clipListener = () -> captureClip(true);
    private final OtpBus.Listener otpListener = this::scheduleRefresh;

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onCreate() {
        super.onCreate();
        dict = Dictionary.get(this);
        clips = ClipboardStore.get(this);
        decoder = new GestureDecoder(dict);
        clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (clipboard != null) clipboard.addPrimaryClipChangedListener(clipListener);
        OtpBus.add(otpListener);
    }

    @Override
    public void onDestroy() {
        if (clipboard != null) clipboard.removePrimaryClipChangedListener(clipListener);
        OtpBus.remove(otpListener);
        saveAsync();
        if (shotObserver != null) getContentResolver().unregisterContentObserver(shotObserver);
        handler.removeCallbacks(scanLater);
        worker.shutdownNow();
        io.shutdownNow();
        super.onDestroy();
    }

    @Override
    public boolean onEvaluateFullscreenMode() {
        return false;
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    public View onCreateInputView() {
        theme = Theme.of(this);
        emojiPanel = null;
        clipPanel = null;
        editPanel = null;
        sizePanel = null;
        morePanel = null;
        panel = PANEL_KEYS;

        root = new FrameLayout(this);
        root.setBackgroundColor(theme.toolbarBg);
        LinearLayout main = new LinearLayout(this);
        main.setOrientation(LinearLayout.HORIZONTAL);

        leftStrip = makeStrip(true);
        rightStrip = makeStrip(false);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        toolbar = new ToolbarView(this, theme);
        toolbar.setListener(this);
        column.addView(toolbar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        host = new FrameLayout(this);
        host.setBackgroundColor(theme.keyboardBg);
        kv = new KeyboardView(this, theme);
        kv.setListener(this);
        host.addView(kv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        column.addView(host, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        main.addView(leftStrip, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT));
        main.addView(column, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        main.addView(rightStrip, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(main, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        overlay = new PopupOverlay(this, theme);
        root.addView(overlay, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        kv.setOverlay(overlay);

        readPrefs();
        applyOneHanded();
        kv.setLayout(buildLayout());
        return root;
    }

    private LinearLayout makeStrip(final boolean left) {
        LinearLayout s = new LinearLayout(this);
        s.setOrientation(LinearLayout.VERTICAL);
        s.setGravity(Gravity.CENTER);
        s.setBackgroundColor(theme.keyboardBg);
        // strip on the left means the keyboard sits on the right
        IconButton swap = new IconButton(this, theme, left ? Icons.CHEVRON_LEFT : Icons.CHEVRON_RIGHT, null);
        swap.setOnClickListener(v -> {
            Prefs.put(this, Prefs.ONE_HANDED, left ? 1 : 2);
            animateNext();
            applyOneHanded();
        });
        IconButton exit = new IconButton(this, theme, Icons.EXPAND, null);
        exit.setOnClickListener(v -> {
            Prefs.put(this, Prefs.ONE_HANDED, 0);
            animateNext();
            applyOneHanded();
        });
        s.addView(swap, new LinearLayout.LayoutParams(dp(52), dp(60)));
        s.addView(exit, new LinearLayout.LayoutParams(dp(52), dp(60)));
        return s;
    }

    /** Smoothly animates the next layout change (panel switch, one-handed mode), if enabled. */
    private void animateNext() {
        if (root == null || !Prefs.bool(this, Prefs.ANIMATIONS)) return;
        AutoTransition t = new AutoTransition();
        t.setDuration(170);
        t.excludeTarget(overlay, true);
        TransitionManager.beginDelayedTransition(root, t);
    }

    private void applyOneHanded() {
        if (leftStrip == null) return;
        int oh = Prefs.integer(this, Prefs.ONE_HANDED);
        if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) oh = 0;
        int w = (int) (getResources().getDisplayMetrics().widthPixels * 0.2f);
        setStrip(leftStrip, oh == 2 ? w : 0);
        setStrip(rightStrip, oh == 1 ? w : 0);
        if (kv != null) kv.cancelAll();
    }

    private static void setStrip(LinearLayout s, int width) {
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) s.getLayoutParams();
        if (lp == null) return;
        lp.width = width;
        s.setLayoutParams(lp);
        s.setVisibility(width > 0 ? View.VISIBLE : View.GONE);
    }

    private void readPrefs() {
        Fonts.load(this);
        if (overlay != null) {
            overlay.setPreviewScale(Prefs.integer(this, Prefs.PREVIEW_HEIGHT) / 100f);
            overlay.setPreviewWidth(Prefs.integer(this, Prefs.PREVIEW_WIDTH) / 100f);
        }
        if (kv != null) kv.invalidate();
        if (toolbar != null) {
            toolbar.setOrder(Prefs.get(this).getString(Prefs.TOOLBAR, ToolbarView.DEFAULT_ORDER));
            toolbar.invalidate();
        }
        vibOn = Prefs.bool(this, Prefs.VIBRATE);
        vibStrong = Prefs.bool(this, Prefs.VIBRATE_STRONG);
        soundOn = Prefs.bool(this, Prefs.SOUND);
        doubleSpaceOn = Prefs.bool(this, Prefs.DOUBLE_SPACE);
        nextWordOn = Prefs.bool(this, Prefs.NEXT_WORD);
        clipSuggestOn = Prefs.bool(this, Prefs.CLIP_SUGGEST);
        if (kv != null) {
            kv.setOptions(Prefs.bool(this, Prefs.KEY_PREVIEW), gestureOn,
                    Prefs.bool(this, Prefs.SPACE_CURSOR), Prefs.integer(this, Prefs.HEIGHT),
                    Prefs.integer(this, Prefs.FONT_SIZE), Prefs.integer(this, Prefs.KEY_SIZE));
        }
    }

    private Layouts.Layout buildLayout() {
        return Layouts.build(kind, Prefs.bool(this, Prefs.NUMBER_ROW), Prefs.bool(this, Prefs.HINTS), field, enterIcon);
    }

    @Override
    public void onStartInputView(EditorInfo ei, boolean restarting) {
        super.onStartInputView(ei, restarting);
        try {
            startInputViewImpl(ei);
        } catch (Throwable t) {
            CrashLog.write(this, "onStartInputView", t);
        }
    }

    private void startInputViewImpl(EditorInfo ei) {
        if (kv == null) return;
        Theme t = Theme.of(this);
        if (t.dark != theme.dark) setInputView(onCreateInputView());
        analyze(ei);
        cursorPos = ei.initialSelEnd;
        kind = baseKind;
        readPrefs();
        applyOneHanded();
        kv.setLayout(buildLayout());
        showPanel(PANEL_KEYS);
        shift = 0;
        shiftManual = false;
        autoSpaced = false;
        toolsForced = false;
        corrOriginal = null;
        gestureWord = null;
        gestureAlts = null;
        decoder.clearCache();
        cacheWord = null;
        cacheSugg = null;
        captureClip(false);
        ensureScreenshotObserver();
        styleNavBar();
        scheduleRefresh();
    }

    @Override
    public void onFinishInputView(boolean finishingInput) {
        super.onFinishInputView(finishingInput);
        if (OtpBus.wasShown()) OtpBus.clear();     // offered once; don't follow you to other boxes
        if (kv != null) kv.cancelAll();
        saveAsync();
    }

    private void saveAsync() {
        new Thread(() -> dict.save(), "ketik-save").start();
    }

    private void analyze(EditorInfo ei) {
        editor = ei;
        int type = ei.inputType;
        int cls = type & InputType.TYPE_MASK_CLASS;
        int var = type & InputType.TYPE_MASK_VARIATION;
        rawKeys = type == InputType.TYPE_NULL;
        isPassword = (cls == InputType.TYPE_CLASS_TEXT && (var == InputType.TYPE_TEXT_VARIATION_PASSWORD
                || var == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                || var == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD))
                || (cls == InputType.TYPE_CLASS_NUMBER && var == InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        if (cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_DATETIME) baseKind = Layouts.NUMPAD;
        else if (cls == InputType.TYPE_CLASS_PHONE) baseKind = Layouts.PHONE;
        else baseKind = Layouts.ALPHA;

        field = Layouts.FIELD_NORMAL;
        if (cls == InputType.TYPE_CLASS_TEXT) {
            if (var == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS || var == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS) {
                field = Layouts.FIELD_EMAIL;
            } else if (var == InputType.TYPE_TEXT_VARIATION_URI) {
                field = Layouts.FIELD_URL;
            }
        }
        boolean noSuggest = (type & InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0;
        boolean textField = cls == InputType.TYPE_CLASS_TEXT && !rawKeys;
        predictOn = Prefs.bool(this, Prefs.PREDICTIONS) && textField && !isPassword
                && field == Layouts.FIELD_NORMAL && !noSuggest;
        correctOn = predictOn && Prefs.bool(this, Prefs.AUTO_CORRECT)
                && var != InputType.TYPE_TEXT_VARIATION_PERSON_NAME
                && var != InputType.TYPE_TEXT_VARIATION_FILTER;
        learnOn = predictOn && (ei.imeOptions & EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) == 0;
        autoCapsOn = Prefs.bool(this, Prefs.AUTO_CAPS) && textField && !isPassword && field == Layouts.FIELD_NORMAL;
        gestureOn = Prefs.bool(this, Prefs.SWIPE) && textField && !isPassword && field == Layouts.FIELD_NORMAL;

        int action = ei.imeOptions & EditorInfo.IME_MASK_ACTION;
        boolean noEnterAction = (ei.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0;
        enterAction = 0;
        enterIcon = Icons.ENTER;
        if (!noEnterAction) {
            switch (action) {
                case EditorInfo.IME_ACTION_GO: enterIcon = Icons.SEND; enterAction = action; break;
                case EditorInfo.IME_ACTION_SEARCH: enterIcon = Icons.SEARCH; enterAction = action; break;
                case EditorInfo.IME_ACTION_SEND: enterIcon = Icons.SEND; enterAction = action; break;
                case EditorInfo.IME_ACTION_NEXT: enterIcon = Icons.NEXT; enterAction = action; break;
                case EditorInfo.IME_ACTION_DONE: enterIcon = Icons.DONE; enterAction = action; break;
                case EditorInfo.IME_ACTION_PREVIOUS: enterIcon = Icons.BACK; enterAction = action; break;
                default: break;
            }
        }
    }

    private void styleNavBar() {
        try {
            Window w = getWindow().getWindow();
            if (w == null) return;
            w.setNavigationBarColor(theme.keyboardBg);
            if (Build.VERSION.SDK_INT >= 30) {
                WindowInsetsController c = w.getInsetsController();
                if (c != null) {
                    c.setSystemBarsAppearance(theme.dark ? 0 : WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
                }
            } else {
                View d = w.getDecorView();
                int f = d.getSystemUiVisibility();
                if (theme.dark) f &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                else f |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                d.setSystemUiVisibility(f);
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onUpdateSelection(int oldSelStart, int oldSelEnd, int newSelStart, int newSelEnd,
                                  int candidatesStart, int candidatesEnd) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd);
        // while dragging the spacebar our own position is newer than these (delayed) reports
        if (SystemClock.uptimeMillis() - lastCursorMove > 400) cursorPos = newSelEnd;
        if (newSelStart != newSelEnd) {
            corrOriginal = null;
            autoSpaced = false;
        }
        scheduleRefresh();
    }

    private void scheduleRefresh() {
        handler.removeCallbacks(refreshRunnable);
        handler.post(refreshRunnable);
    }

    // ------------------------------------------------------------------ clipboard

    private void captureClip(boolean fresh) {
        if (clipboard == null) return;
        try {
            ClipData cd = clipboard.getPrimaryClip();
            if (cd == null || cd.getItemCount() == 0) return;
            ClipDescription d = cd.getDescription();
            // apps (password managers, banking apps) mark secrets as sensitive: never store those
            if (d != null && d.getExtras() != null
                    && d.getExtras().getBoolean("android.content.extra.IS_SENSITIVE", false)) return;
            ClipData.Item item = cd.getItemAt(0);
            Uri u = item.getUri();
            // Android keeps the last copied item; re-reading it every time the keyboard opens must not
            // move it back to the top of the history. Each copy has its own timestamp, so remember it.
            CharSequence sigText = u != null ? u.toString() : item.getText();
            String sig = (d != null ? d.getTimestamp() : 0) + ":" + (sigText == null ? "" : sigText.toString().hashCode());
            android.content.SharedPreferences cp = getSharedPreferences("ketik_clipboard", MODE_PRIVATE);
            if (sig.equals(cp.getString("last_sig", null))) return;
            cp.edit().putString("last_sig", sig).apply();
            if (u != null) {
                if (ClipProvider.authority(this).equals(u.getAuthority())) return; // our own paste
                String type = getContentResolver().getType(u);
                if (type == null && d != null && d.getMimeTypeCount() > 0) type = d.getMimeType(0);
                if (type != null && type.startsWith("image/")) {
                    final String mime = type;
                    io.execute(() -> importImage(u, mime, "Image", fresh, null));
                    return;
                }
            }
            CharSequence t = item.coerceToText(this);
            if (t == null) return;
            boolean changed = clips.add(t.toString());
            if (changed && fresh) {
                lastClipTime = System.currentTimeMillis();
                lastClipShown = null;
                scheduleRefresh();
            }
            if (clipPanel != null && panel == PANEL_CLIP) clipPanel.refresh();
        } catch (Exception ignored) {
        }
    }

    /** Runs on the io thread. */
    private void importImage(Uri u, String mime, String label, boolean fresh, String source) {
        boolean changed;
        try (InputStream in = getContentResolver().openInputStream(u)) {
            if (in == null) return;
            changed = clips.addImage(in, mime, label, source);
        } catch (Exception e) {
            return;
        }
        if (!changed) return;
        handler.post(() -> {
            if (fresh) {
                lastClipTime = System.currentTimeMillis();
                lastClipShown = null;
                scheduleRefresh();
            }
            if (clipPanel != null && panel == PANEL_CLIP) clipPanel.refresh();
        });
    }

    private boolean hasPhotoAccess() {
        String perm = Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_IMAGES
                : Manifest.permission.READ_EXTERNAL_STORAGE;
        return checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    /** Watches the photo library so new screenshots land in clipboard history. */
    private void ensureScreenshotObserver() {
        boolean want = Prefs.bool(this, Prefs.SCREENSHOTS) && hasPhotoAccess();
        if (want && shotObserver == null) {
            shotObserver = new ContentObserver(handler) {
                @Override
                public void onChange(boolean selfChange) {
                    // the system reports a new screenshot several times while it is still writing it:
                    // wait until the reports stop, then read it once
                    handler.removeCallbacks(scanLater);
                    handler.postDelayed(scanLater, 1500);
                }
            };
            try {
                getContentResolver().registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, shotObserver);
                io.execute(() -> lastShotId = newestImageId()); // don't import old screenshots
            } catch (Exception e) {
                shotObserver = null;
            }
        } else if (!want && shotObserver != null) {
            getContentResolver().unregisterContentObserver(shotObserver);
            shotObserver = null;
        }
    }

    private long newestImageId() {
        try (Cursor c = getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.Images.Media._ID}, null, null, MediaStore.Images.Media._ID + " DESC")) {
            if (c != null && c.moveToFirst()) return c.getLong(0);
        } catch (Exception ignored) {
        }
        return 0;
    }

    /** Runs on the io thread. */
    private void scanScreenshots() {
        if (lastShotId < 0) lastShotId = newestImageId();
        String[] proj = {MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.RELATIVE_PATH, MediaStore.Images.Media.MIME_TYPE};
        long since = System.currentTimeMillis() / 1000 - 120;
        List<long[]> found = new ArrayList<>();
        List<String> mimes = new ArrayList<>();
        long maxId = lastShotId;
        try (Cursor c = getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj,
                MediaStore.Images.Media._ID + ">? AND " + MediaStore.Images.Media.DATE_ADDED + ">=?",
                new String[]{String.valueOf(lastShotId), String.valueOf(since)},
                MediaStore.Images.Media._ID + " ASC")) {
            if (c == null) return;
            while (c.moveToNext()) {
                long id = c.getLong(0);
                String name = c.getString(1);
                String path = c.getString(2);
                String all = ((name == null ? "" : name) + " " + (path == null ? "" : path)).toLowerCase();
                maxId = Math.max(maxId, id);
                if (all.contains("screenshot")) {
                    found.add(new long[]{id});
                    mimes.add(c.getString(3));
                }
            }
        } catch (Exception e) {
            return;
        }
        lastShotId = maxId;
        for (int i = 0; i < found.size(); i++) {
            Uri u = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, found.get(i)[0]);
            importImage(u, mimes.get(i) == null ? "image/png" : mimes.get(i), "Screenshot", true,
                    "media:" + found.get(i)[0]);
        }
    }

    /** Pastes an image clip: directly if the app accepts images, otherwise via the system clipboard. */
    private void pasteImage(ClipboardStore.Clip clip) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null || clip.image == null) return;
        Uri uri = ClipProvider.uriFor(this, clip.image);
        String mime = clip.mime == null ? "image/png" : clip.mime;
        EditorInfo ei = getCurrentInputEditorInfo();
        boolean accepted = false;
        if (ei != null && ei.contentMimeTypes != null) {
            for (String m : ei.contentMimeTypes) {
                if (ClipDescription.compareMimeTypes(mime, m)) accepted = true;
            }
        }
        if (accepted) {
            InputContentInfo info = new InputContentInfo(uri, new ClipDescription(clip.text, new String[]{mime}), null);
            if (ic.commitContent(info, InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null)) return;
        }
        // fall back: put the image on the system clipboard and ask the app to paste it
        try {
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newUri(getContentResolver(), clip.text, uri));
                ic.performContextMenuAction(android.R.id.paste);
            }
        } catch (Exception e) {
            Toast.makeText(this, "This app can't receive images", Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------------ helpers

    private boolean deleteRepeating;

    private void after() {
        if (deleteRepeating) {
            // while ⌫ is held, refresh suggestions only once it is released (keeps repeats smooth)
            handler.removeCallbacks(refreshRunnable);
            handler.postDelayed(refreshRunnable, 220);
        } else {
            scheduleRefresh();
        }
    }

    private void commit(InputConnection ic, String s) {
        if (rawKeys && s.length() == 1) {
            sendKeyChar(s.charAt(0));   // single typed keys in terminals: real key presses
        } else {
            ic.commitText(s, 1);        // anything longer goes in one piece, in order
        }
    }

    /** Pastes clipboard text: one piece, Windows/web line endings turned into plain line breaks. */
    private void pasteText(InputConnection ic, String text) {
        String t = text.replace("\r\n", "\n").replace('\r', '\n');
        ic.beginBatchEdit();
        CharSequence sel = ic.getSelectedText(0);
        if (sel != null && sel.length() > 0 && !rawKeys) ic.commitText("", 1);
        ic.commitText(t, 1);
        ic.endBatchEdit();
        autoSpaced = false;
        corrOriginal = null;
    }

    private static boolean wordChar(CharSequence b, int i) {
        char c = b.charAt(i);
        if (Character.isLetter(c)) return true;
        return (c == '\'' || c == '-') && i > 0 && Character.isLetter(b.charAt(i - 1));
    }

    private String currentWord(InputConnection ic) {
        CharSequence b = ic.getTextBeforeCursor(48, 0);
        if (b == null) return "";
        int i = b.length();
        while (i > 0 && wordChar(b, i - 1)) i--;
        return b.subSequence(i, b.length()).toString();
    }

    /** Word before the current one, or null at a sentence boundary. */
    private String prevWord(InputConnection ic, int skip) {
        CharSequence b = ic.getTextBeforeCursor(80, 0);
        if (b == null) return null;
        int end = b.length() - skip;
        if (end <= 0) return null;
        int i = end;
        while (i > 0 && b.charAt(i - 1) == ' ') i--;
        int wordEnd = i;
        while (i > 0 && wordChar(b, i - 1)) i--;
        if (i == wordEnd) return null;
        return b.subSequence(i, wordEnd).toString();
    }

    private boolean cursorInsideWord(InputConnection ic) {
        CharSequence a = ic.getTextAfterCursor(1, 0);
        return a != null && a.length() > 0 && Character.isLetter(a.charAt(0));
    }

    private void updateShift() {
        if (kv == null) return;
        if (kind != Layouts.ALPHA) {
            kv.setShiftState(0);
            return;
        }
        if (shift == 2 || shiftManual) {
            kv.setShiftState(shift);
            return;
        }
        int s = 0;
        InputConnection ic = getCurrentInputConnection();
        if (autoCapsOn && ic != null && editor != null) {
            try {
                if (ic.getCursorCapsMode(editor.inputType) != 0) s = 1;
            } catch (Exception ignored) {
            }
        }
        shift = s;
        kv.setShiftState(shift);
    }

    private String applyShiftCase(String w) {
        if (w == null) return null;
        if (shift == 2) return w.toUpperCase();
        if (shift == 1 && !w.isEmpty()) return Character.toUpperCase(w.charAt(0)) + w.substring(1);
        return w;
    }

    private void feedback(Key k) {
        if (vibOn && vibrator != null) {
            try {
                vibrator.vibrate(VibrationEffect.createPredefined(vibStrong
                        ? VibrationEffect.EFFECT_CLICK : VibrationEffect.EFFECT_TICK));
            } catch (Exception ignored) {
            }
        }
        if (soundOn && audio != null) {
            int fx = AudioManager.FX_KEYPRESS_STANDARD;
            if (k != null) {
                if (k.code == Key.DELETE) fx = AudioManager.FX_KEYPRESS_DELETE;
                else if (k.code == Key.SPACE) fx = AudioManager.FX_KEYPRESS_SPACEBAR;
                else if (k.code == Key.ENTER) fx = AudioManager.FX_KEYPRESS_RETURN;
            }
            audio.playSoundEffect(fx, -1);
        }
    }

    // ------------------------------------------------------------------ key handling

    @Override
    public void onPress(Key key) {
        deleteRepeating = false;
        feedback(key);
    }

    /** Light tick for each repeated delete. */
    private void repeatTick() {
        if (!vibOn || vibrator == null) return;
        try {
            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK));
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onKey(Key k) {
        try {
            onKeyImpl(k);
        } catch (Throwable t) {
            CrashLog.write(this, "onKey", t);
        }
    }

    private void onKeyImpl(Key k) {
        switch (k.code) {
            case Key.CHAR: handleText(k.text); break;
            case Key.SHIFT: handleShiftTap(); break;
            case Key.DELETE: handleDelete(false); break;
            case Key.SPACE: handleSpace(); break;
            case Key.ENTER: handleEnter(); break;
            case Key.SYMBOLS: setKind(Layouts.SYM1); break;
            case Key.ALPHA: setKind(baseKind == Layouts.ALPHA ? Layouts.ALPHA : baseKind); break;
            case Key.SYM_PAGE: setKind(kind == Layouts.SYM1 ? Layouts.SYM2 : Layouts.SYM1); break;
            case Key.EMOJI: showPanel(PANEL_EMOJI); break;
            default: break;
        }
    }

    @Override
    public void onText(String text) {
        try {
            onTextImpl(text);
        } catch (Throwable t) {
            CrashLog.write(this, "onText", t);
        }
    }

    private void onTextImpl(String text) {
        handleText(text);
    }

    private void setKind(int k) {
        kind = k;
        kv.setLayout(buildLayout());
        if (k == Layouts.ALPHA) {
            shiftManual = false;
            if (shift != 2) shift = 0;
        }
        updateShift();
    }

    private void handleText(String s) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null || s == null || s.isEmpty()) return;
        if (toolbar != null && toolbar.isShowingOtp()) OtpBus.clear();   // you're typing something else
        String out = s;
        if (kind == Layouts.ALPHA && shift != 0 && Character.isLetter(s.charAt(0))) out = s.toUpperCase();
        char c = out.charAt(0);
        corrOriginal = null;
        gestureWord = null;
        gestureAlts = null;
        toolsForced = false;
        lastClipTime = 0;

        if (out.length() == 1 && ".,!?;:)".indexOf(c) >= 0 && autoSpaced) {
            CharSequence b = ic.getTextBeforeCursor(1, 0);
            if (b != null && b.length() == 1 && b.charAt(0) == ' ') {
                ic.beginBatchEdit();
                ic.deleteSurroundingText(1, 0);
                commit(ic, out + (c == ')' ? "" : " "));
                ic.endBatchEdit();
                autoSpaced = c != ')';
                if (c == ')') autoSpaced = false;
                lastPickTime = SystemClock.uptimeMillis();
                after();
                return;
            }
        }
        autoSpaced = false;
        commit(ic, out);
        if (shift != 2) {
            shiftManual = false;
            if (shift == 1 && Character.isLetter(c)) shift = 0;
        }
        if (kv != null) kv.setShiftState(kind == Layouts.ALPHA ? shift : 0);
        after();
    }

    private void handleShiftTap() {
        if (kind != Layouts.ALPHA) return;
        long now = SystemClock.uptimeMillis();
        if (shift == 2) shift = 0;
        else if (shift == 1 && now - lastShiftTap < 350) shift = 2;
        else if (shift == 1) shift = 0;
        else shift = 1;
        shiftManual = true;
        lastShiftTap = now;
        kv.setShiftState(shift);
    }

    private void handleSpace() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        long now = SystemClock.uptimeMillis();
        gestureWord = null;
        gestureAlts = null;
        toolsForced = false;
        lastClipTime = 0;
        if (rawKeys) {
            sendKeyChar(' ');
            return;
        }
        if (autoSpaced) {
            // a space was already added after the picked word: second tap gives ". "
            autoSpaced = false;
            if (doubleSpaceOn && now - lastPickTime < 4000) {
                CharSequence b = ic.getTextBeforeCursor(2, 0);
                if (b != null && b.length() == 2 && b.charAt(1) == ' ' && Character.isLetterOrDigit(b.charAt(0))) {
                    ic.beginBatchEdit();
                    ic.deleteSurroundingText(1, 0);
                    ic.commitText(". ", 1);
                    ic.endBatchEdit();
                }
            }
            lastSpaceTime = 0;
            after();
            return;
        }
        ic.beginBatchEdit();
        boolean done = false;
        if (doubleSpaceOn && now - lastSpaceTime < 600) {
            CharSequence b = ic.getTextBeforeCursor(2, 0);
            if (b != null && b.length() == 2 && b.charAt(1) == ' '
                    && (Character.isLetterOrDigit(b.charAt(0)) || ")\"'".indexOf(b.charAt(0)) >= 0)) {
                ic.deleteSurroundingText(1, 0);
                ic.commitText(". ", 1);
                done = true;
                corrOriginal = null;
                lastSpaceTime = 0;
            }
        }
        if (!done) {
            String word = currentWord(ic);
            String prev = word.isEmpty() ? null : prevWord(ic, word.length());
            String finalWord = word;
            if (!word.isEmpty() && correctOn && dict.isLoaded() && !cursorInsideWord(ic)) {
                Dictionary.Suggestions s = word.equals(cacheWord) && cacheSugg != null
                        ? cacheSugg : dict.suggest(word, true);
                if (s.autoCorrect != null && !s.autoCorrect.equals(word)) {
                    ic.deleteSurroundingText(word.length(), 0);
                    ic.commitText(s.autoCorrect + " ", 1);
                    corrOriginal = word;
                    corrReplacement = s.autoCorrect + " ";
                    finalWord = s.autoCorrect;
                    done = true;
                }
            }
            if (!done) {
                ic.commitText(" ", 1);
                corrOriginal = null;
            }
            if (learnOn && !finalWord.isEmpty()) {
                dict.learn(finalWord, 1);
                dict.learnPair(prev, finalWord);
            }
            lastSpaceTime = now;
        }
        ic.endBatchEdit();
        after();
    }

    private void handleEnter() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        corrOriginal = null;
        gestureWord = null;
        gestureAlts = null;
        autoSpaced = false;
        toolsForced = false;
        if (enterAction != 0) {
            ic.performEditorAction(enterAction);
        } else {
            sendKeyChar('\n');
        }
        after();
    }

    private void handleDelete(boolean repeating) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        lastClipTime = 0;
        toolsForced = false;
        if (!repeating && gestureWord != null) {
            CharSequence b = ic.getTextBeforeCursor(gestureWord.length(), 0);
            String w = gestureWord;
            gestureWord = null;
            gestureAlts = null;
            if (b != null && b.toString().equals(w)) {
                ic.deleteSurroundingText(w.length(), 0);
                after();
                return;
            }
        }
        if (!repeating && corrOriginal != null) {
            CharSequence b = ic.getTextBeforeCursor(corrReplacement.length(), 0);
            String orig = corrOriginal;
            corrOriginal = null;
            if (b != null && b.toString().equals(corrReplacement)) {
                ic.beginBatchEdit();
                ic.deleteSurroundingText(corrReplacement.length(), 0);
                ic.commitText(orig, 1);
                ic.endBatchEdit();
                if (learnOn) dict.learn(orig, 2);
                after();
                return;
            }
        }
        autoSpaced = false;
        if (rawKeys) {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL);
            return;
        }
        CharSequence sel = ic.getSelectedText(0);
        if (sel != null && sel.length() > 0) {
            ic.commitText("", 1);
            after();
            return;
        }
        CharSequence b = ic.getTextBeforeCursor(32, 0);
        if (b == null || b.length() == 0) {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL);
            after();
            return;
        }
        ic.deleteSurroundingText(lastGraphemeLength(b), 0);
        after();
    }

    private static int lastGraphemeLength(CharSequence b) {
        try {
            android.icu.text.BreakIterator bi = android.icu.text.BreakIterator.getCharacterInstance();
            String s = b.toString();
            bi.setText(s);
            int prev = bi.preceding(s.length());
            if (prev == android.icu.text.BreakIterator.DONE || prev < 0) return 1;
            return Math.max(1, s.length() - prev);
        } catch (Exception e) {
            return Character.isLowSurrogate(b.charAt(b.length() - 1)) && b.length() > 1 ? 2 : 1;
        }
    }

    @Override
    public void onDeleteRepeat(int count) {
        try {
            onDeleteRepeatImpl(count);
        } catch (Throwable t) {
            CrashLog.write(this, "onDeleteRepeat", t);
        }
    }

    private void onDeleteRepeatImpl(int count) {
        deleteRepeating = true;
        repeatTick();
        if (count < 18 || rawKeys) {
            handleDelete(true);
            return;
        }
        // held long: delete whole words (only where the app lets us read the text)
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        CharSequence b = ic.getTextBeforeCursor(64, 0);
        if (b == null || b.length() == 0) {
            // terminals and some apps don't share their text: keep deleting one character at a time
            handleDelete(true);
            return;
        }
        int i = b.length();
        while (i > 0 && Character.isWhitespace(b.charAt(i - 1))) i--;
        while (i > 0 && !Character.isWhitespace(b.charAt(i - 1))) i--;
        int n = b.length() - i;
        if (n <= 0) n = 1;
        ic.deleteSurroundingText(n, 0);
        after();
    }

    @Override
    public void onLongPress(Key k) {
        try {
            onLongPressImpl(k);
        } catch (Throwable t) {
            CrashLog.write(this, "onLongPress", t);
        }
    }

    private void onLongPressImpl(Key k) {
        feedback(k);
        if (k.code == Key.SPACE) {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showInputMethodPicker();
        } else if (k.code == Key.SHIFT) {
            shift = 2;
            shiftManual = true;
            kv.setShiftState(2);
        } else if (k.longCode == Key.EMOJI) {
            showPanel(PANEL_EMOJI);
        }
    }

    @Override
    public void onCursorMoveLines(int lines) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        int code = lines > 0 ? KeyEvent.KEYCODE_DPAD_DOWN : KeyEvent.KEYCODE_DPAD_UP;
        for (int i = 0; i < Math.abs(lines); i++) sendDownUpKeyEvents(code);
        // our position is unknown after a line move: take the app's next report
        cursorPos = -1;
        lastCursorMove = 0;
    }

    @Override
    public void onCursorMove(int steps) {
        try {
            onCursorMoveImpl(steps);
        } catch (Throwable t) {
            CrashLog.write(this, "onCursorMove", t);
        }
    }

    private void onCursorMoveImpl(int steps) {
        corrOriginal = null;
        autoSpaced = false;
        gestureWord = null;
        gestureAlts = null;
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        if (rawKeys || cursorPos < 0) {
            int code = steps > 0 ? KeyEvent.KEYCODE_DPAD_RIGHT : KeyEvent.KEYCODE_DPAD_LEFT;
            for (int i = 0; i < Math.abs(steps); i++) sendDownUpKeyEvents(code);
            return;
        }
        // move by whole characters (emoji count as one) with a single setSelection call
        int n = Math.abs(steps);
        int delta = 0;
        if (steps > 0) {
            CharSequence a = ic.getTextAfterCursor(n * 2 + 2, 0);
            if (a == null) return;
            for (int i = 0; i < n && delta < a.length(); i++) {
                delta += Character.charCount(Character.codePointAt(a, delta));
            }
        } else {
            CharSequence b = ic.getTextBeforeCursor(n * 2 + 2, 0);
            if (b == null) return;
            int end = b.length();
            for (int i = 0; i < n && end > 0; i++) {
                end -= Character.charCount(Character.codePointBefore(b, end));
            }
            delta = end - b.length();
        }
        if (delta == 0) return;
        cursorPos = Math.max(0, cursorPos + delta);
        lastCursorMove = SystemClock.uptimeMillis();
        ic.setSelection(cursorPos, cursorPos);
    }

    @Override
    public void onGesture(List<float[]> points) {
        try {
            onGestureImpl(points);
        } catch (Throwable t) {
            CrashLog.write(this, "onGesture", t);
        }
    }

    private void onGestureImpl(List<float[]> points) {
        if (!dict.isLoaded()) return;
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        decoder.setKeys(kv.letterCenters(), kv.letterKeyWidth());
        List<String> res = decoder.decode(points);
        if (res.isEmpty()) return;
        List<String> cased = new ArrayList<>();
        for (String w : res) cased.add(applyShiftCase(w));
        String w = cased.get(0);
        String prev = prevWord(ic, 0);
        ic.beginBatchEdit();
        CharSequence b = ic.getTextBeforeCursor(1, 0);
        String prefix = "";
        if (b != null && b.length() == 1) {
            char pc = b.charAt(0);
            if (!Character.isWhitespace(pc) && "([{\"'¿¡@#/".indexOf(pc) < 0) prefix = " ";
        }
        ic.commitText(prefix + w, 1);
        ic.endBatchEdit();
        gestureWord = w;
        gestureAlts = cased;
        autoSpaced = false;
        corrOriginal = null;
        toolsForced = false;
        lastClipTime = 0;
        if (shift == 1) {
            shift = 0;
            shiftManual = false;
        }
        if (learnOn) {
            dict.learn(w, 1);
            dict.learnPair(prev, w);
        }
        after();
    }

    // ------------------------------------------------------------------ suggestions

    private void refreshSuggestions() {
        if (toolbar == null) return;
        final int gen = ++suggestGen;
        String[] otp = OtpBus.current();
        if (otp != null && getCurrentInputConnection() != null) {
            toolbar.showOtp(otp[0], otp[1]);   // a fresh one-time code beats everything else
            OtpBus.markShown();
            return;
        }
        if (panel != PANEL_KEYS || toolsForced) {
            toolbar.showTools();
            return;
        }
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) {
            toolbar.showTools();
            return;
        }
        if (clipSuggestOn && !isPassword && lastClipTime > 0
                && System.currentTimeMillis() - lastClipTime < 60_000) {
            ClipboardStore.Clip c = clips.newest();
            if (c != null && !(c.isImage() ? c.image : c.text).equals(lastClipShown)) {
                if (c.isImage()) {
                    android.graphics.Bitmap thumb = ClipboardPanel.cachedThumb(c.image);
                    toolbar.showClip("Paste " + c.text.toLowerCase(), thumb);
                    if (thumb == null) {
                        final java.io.File f = clips.fileOf(c);
                        final String key = c.image;
                        io.execute(() -> {
                            if (ClipboardPanel.loadThumb(f, key, dp(120)) != null) handler.post(this::scheduleRefresh);
                        });
                    }
                } else {
                    toolbar.showClip(c.text);
                }
                return;
            }
        }
        if (!predictOn || !dict.isLoaded()) {
            toolbar.showTools();
            return;
        }
        if (gestureAlts != null && gestureWord != null) {
            CharSequence b = ic.getTextBeforeCursor(gestureWord.length(), 0);
            if (b != null && b.toString().equals(gestureWord)) {
                toolbar.showSuggestions(alt(1), gestureWord, alt(2));
                return;
            }
            gestureAlts = null;
            gestureWord = null;
        }
        String word = currentWord(ic);
        if (word.isEmpty()) {
            if (nextWordOn) {
                List<String> nx = dict.nextWords(prevWord(ic, 0));
                if (!nx.isEmpty()) {
                    toolbar.showSuggestions(nx.size() > 1 ? applyShiftCase(nx.get(1)) : null,
                            applyShiftCase(nx.get(0)), nx.size() > 2 ? applyShiftCase(nx.get(2)) : null);
                    return;
                }
            }
            toolbar.showTools();
            return;
        }
        if (word.equals(cacheWord) && cacheSugg != null) {
            toolbar.showSuggestions(cacheSugg.words[0], cacheSugg.words[1], cacheSugg.words[2]);
            return;
        }
        final boolean corr = correctOn;
        worker.execute(() -> {
            final Dictionary.Suggestions s = dict.suggest(word, corr);
            handler.post(() -> {
                if (gen != suggestGen || toolbar == null) return;
                cacheWord = word;
                cacheSugg = s;
                toolbar.showSuggestions(s.words[0], s.words[1], s.words[2]);
            });
        });
    }

    /** i-th swipe alternative that is not the word currently inserted (1-based). */
    private String alt(int i) {
        if (gestureAlts == null) return null;
        int n = 0;
        for (String a : gestureAlts) {
            if (a.equals(gestureWord)) continue;
            if (++n == i) return a;
        }
        return null;
    }

    @Override
    public void onSuggestionPicked(int index, String word) {
        try {
            onSuggestionPickedImpl(index, word);
        } catch (Throwable t) {
            CrashLog.write(this, "onSuggestionPicked", t);
        }
    }

    private void onSuggestionPickedImpl(int index, String word) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        feedback(null);
        if (gestureAlts != null && gestureWord != null) {
            CharSequence b = ic.getTextBeforeCursor(gestureWord.length(), 0);
            if (b != null && b.toString().equals(gestureWord)) {
                ic.beginBatchEdit();
                ic.deleteSurroundingText(gestureWord.length(), 0);
                ic.commitText(word, 1);
                ic.endBatchEdit();
                gestureWord = word;
                if (learnOn) dict.learn(word, 2);
                after();
                return;
            }
        }
        gestureAlts = null;
        gestureWord = null;
        corrOriginal = null;
        String cur = currentWord(ic);
        String prev = prevWord(ic, cur.length());
        ic.beginBatchEdit();
        if (!cur.isEmpty()) ic.deleteSurroundingText(cur.length(), 0);
        CharSequence a = ic.getTextAfterCursor(1, 0);
        boolean spaceAfter = a != null && a.length() > 0 && a.charAt(0) == ' ';
        ic.commitText(spaceAfter ? word : word + " ", 1);
        ic.endBatchEdit();
        autoSpaced = !spaceAfter;
        lastPickTime = SystemClock.uptimeMillis();
        if (learnOn) {
            dict.learn(word, cur.isEmpty() ? 1 : 2);
            dict.learnPair(prev, word);
        }
        if (shift == 1 && !shiftManual) shift = 0;
        shiftManual = false;
        after();
    }

    @Override
    public void onSuggestionLongPress(String word) {
        if (dict.isLearned(word)) {
            dict.forget(word);
            Toast.makeText(this, "Removed \"" + word + "\" from learned words", Toast.LENGTH_SHORT).show();
            after();
        }
    }

    @Override
    public void onToolbarAction(int action) {
        try {
            onToolbarActionImpl(action);
        } catch (Throwable t) {
            CrashLog.write(this, "onToolbarAction", t);
        }
    }

    private void onToolbarActionImpl(int action) {
        feedback(null);
        switch (action) {
            case ToolbarView.ACT_EMOJI: togglePanel(PANEL_EMOJI); break;
            case ToolbarView.ACT_CLIPBOARD: togglePanel(PANEL_CLIP); break;
            case ToolbarView.ACT_TEXT_EDIT: togglePanel(PANEL_EDIT); break;
            case ToolbarView.ACT_SIZE: togglePanel(PANEL_SIZE); break;
            case ToolbarView.ACT_MORE:
                animateNext();
                showPanel(panel == PANEL_MORE || panel == PANEL_EDIT ? PANEL_KEYS : PANEL_MORE);
                break;
            case ToolbarView.ACT_ONE_HAND: {
                int oh = Prefs.integer(this, Prefs.ONE_HANDED);
                Prefs.put(this, Prefs.ONE_HANDED, oh == 0 ? 2 : 0);
                animateNext();
                applyOneHanded();
                break;
            }
            case ToolbarView.ACT_SETTINGS: {
                Intent i = new Intent(this, SettingsActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                requestHideSelf(0);
                break;
            }
            case ToolbarView.ACT_HIDE:
                requestHideSelf(0);
                break;
            case ToolbarView.ACT_TOOLS:
                if (toolbar.isShowingOtp()) OtpBus.clear();
                toolsForced = true;
                lastClipTime = 0;
                toolbar.showTools();
                break;
            case ToolbarView.ACT_PASTE_OTP: {
                String[] otp = OtpBus.current();
                InputConnection ic = getCurrentInputConnection();
                if (otp != null && ic != null) {
                    ic.beginBatchEdit();
                    CharSequence sel = ic.getSelectedText(0);
                    if (sel != null && sel.length() > 0) ic.commitText("", 1);
                    commit(ic, otp[0]);
                    ic.endBatchEdit();
                    if (Prefs.bool(this, Prefs.OTP_MARK_READ)) {
                        final String code = otp[0];
                        io.execute(() -> OtpListenerService.markHandled(code));
                    }
                }
                OtpBus.clear();
                break;
            }
            case ToolbarView.ACT_PASTE_CLIP: {
                ClipboardStore.Clip c = clips.newest();
                InputConnection ic = getCurrentInputConnection();
                if (c != null && ic != null) {
                    if (c.isImage()) pasteImage(c); else pasteText(ic, c.text);
                    lastClipShown = c.isImage() ? c.image : c.text;
                }
                lastClipTime = 0;
                after();
                break;
            }
            default:
                break;
        }
    }

    // ------------------------------------------------------------------ panels

    private void togglePanel(int p) {
        animateNext();
        showPanel(panel == p ? PANEL_KEYS : p);
    }

    /** "More" panel: the rarely used tools, as two big buttons. */
    private LinearLayout buildMorePanel() {
        LinearLayout p = new LinearLayout(this) {
            @Override
            protected void onMeasure(int w, int h) {
                if (MeasureSpec.getMode(h) != MeasureSpec.EXACTLY) h = MeasureSpec.makeMeasureSpec(0, MeasureSpec.EXACTLY);
                super.onMeasure(w, h);
            }
        };
        p.setOrientation(LinearLayout.HORIZONTAL);
        p.setGravity(Gravity.CENTER);
        p.setBackgroundColor(theme.keyboardBg);
        IconButton edit = new IconButton(this, theme, Icons.TEXT_EDIT, "Text editing");
        IconButton hand = new IconButton(this, theme, Icons.ONE_HAND, "One-handed");
        for (IconButton b : new IconButton[]{edit, hand}) {
            b.filled = true;
            b.iconSize = 28 * getResources().getDisplayMetrics().density;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(132), dp(104));
            lp.setMargins(dp(10), 0, dp(10), 0);
            p.addView(b, lp);
        }
        edit.setOnClickListener(v -> {
            animateNext();
            showPanel(PANEL_EDIT);
        });
        hand.setOnClickListener(v -> {
            int oh = Prefs.integer(this, Prefs.ONE_HANDED);
            Prefs.put(this, Prefs.ONE_HANDED, oh == 0 ? 2 : 0);
            animateNext();
            showPanel(PANEL_KEYS);
            applyOneHanded();
        });
        return p;
    }

    private FrameLayout.LayoutParams fill() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private void showPanel(int p) {
        if (kv == null) return;
        panel = p;
        kv.cancelAll();
        if (p == PANEL_EMOJI && emojiPanel == null) {
            emojiPanel = new EmojiPanel(this, theme);
            emojiPanel.setListener(this);
            host.addView(emojiPanel, fill());
        }
        if (p == PANEL_CLIP && clipPanel == null) {
            clipPanel = new ClipboardPanel(this, theme, clips);
            clipPanel.setListener(this);
            host.addView(clipPanel, fill());
        }
        if (p == PANEL_EDIT && editPanel == null) {
            editPanel = new TextEditPanel(this, theme);
            editPanel.setListener(this);
            host.addView(editPanel, fill());
        }
        if (p == PANEL_SIZE && sizePanel == null) {
            sizePanel = new SizePanel(this, theme);
            sizePanel.setListener(this);
            host.addView(sizePanel, fill());
        }
        if (p == PANEL_MORE && morePanel == null) {
            morePanel = buildMorePanel();
            host.addView(morePanel, fill());
        }
        if (morePanel != null) morePanel.setVisibility(p == PANEL_MORE ? View.VISIBLE : View.GONE);
        kv.setVisibility(p == PANEL_KEYS ? View.VISIBLE : View.INVISIBLE);
        // the clipboard gets its own header and more height; the tool row hides while it is open
        toolbar.setVisibility(p == PANEL_CLIP ? View.GONE : View.VISIBLE);
        if (clipPanel != null) {
            int kh = kv.getHeight() > 0 ? kv.getHeight() : kv.standardHeight();
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) clipPanel.getLayoutParams();
            lp.height = kh + toolbar.getMeasuredHeight() + dp(90);
            clipPanel.setLayoutParams(lp);
        }
        if (emojiPanel != null) emojiPanel.setVisibility(p == PANEL_EMOJI ? View.VISIBLE : View.GONE);
        if (clipPanel != null) clipPanel.setVisibility(p == PANEL_CLIP ? View.VISIBLE : View.GONE);
        if (editPanel != null) editPanel.setVisibility(p == PANEL_EDIT ? View.VISIBLE : View.GONE);
        if (sizePanel != null) sizePanel.setVisibility(p == PANEL_SIZE ? View.VISIBLE : View.GONE);
        if (p == PANEL_EMOJI) emojiPanel.post(emojiPanel::reload);
        if (p == PANEL_CLIP) clipPanel.refresh();
        if (p == PANEL_EDIT) editPanel.reset();
        if (p == PANEL_SIZE) sizePanel.setValues(Prefs.integer(this, Prefs.HEIGHT),
                Prefs.integer(this, Prefs.KEY_SIZE), Prefs.integer(this, Prefs.FONT_SIZE));
        int act = p == PANEL_EMOJI ? ToolbarView.ACT_EMOJI : p == PANEL_CLIP ? ToolbarView.ACT_CLIPBOARD
                : (p == PANEL_EDIT || p == PANEL_MORE) ? ToolbarView.ACT_MORE : p == PANEL_SIZE ? ToolbarView.ACT_SIZE : 0;
        toolbar.setActive(act);
        scheduleRefresh();
    }

    @Override
    public void onEmoji(String emoji) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        feedback(null);
        autoSpaced = false;
        corrOriginal = null;
        commit(ic, emoji);
    }

    @Override
    public void onEmojiDelete() {
        feedback(null);
        handleDelete(false);
    }

    @Override
    public void onEmojiClose() {
        animateNext();
        showPanel(PANEL_KEYS);
    }

    @Override
    public void onClipPaste(ClipboardStore.Clip clip) {
        try {
            onClipPasteImpl(clip);
        } catch (Throwable t) {
            CrashLog.write(this, "onClipPaste", t);
        }
    }

    private void onClipPasteImpl(ClipboardStore.Clip clip) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        feedback(null);
        if (clip.isImage()) pasteImage(clip); else pasteText(ic, clip.text);
        lastClipShown = clip.isImage() ? clip.image : clip.text;
        showPanel(PANEL_KEYS);
    }

    @Override
    public void onClipClose() {
        animateNext();
        showPanel(PANEL_KEYS);
    }

    private void sendKey(InputConnection ic, int keyCode, int meta) {
        long t = SystemClock.uptimeMillis();
        ic.sendKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, keyCode, 0, meta));
        ic.sendKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, keyCode, 0, meta));
    }

    @Override
    public void onEditAction(int action, boolean selecting) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        feedback(null);
        int shiftMeta = selecting ? (KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_LEFT_ON) : 0;
        int ctrl = KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON;
        switch (action) {
            case TextEditPanel.UP: sendKey(ic, KeyEvent.KEYCODE_DPAD_UP, shiftMeta); break;
            case TextEditPanel.DOWN: sendKey(ic, KeyEvent.KEYCODE_DPAD_DOWN, shiftMeta); break;
            case TextEditPanel.LEFT: sendKey(ic, KeyEvent.KEYCODE_DPAD_LEFT, shiftMeta); break;
            case TextEditPanel.RIGHT: sendKey(ic, KeyEvent.KEYCODE_DPAD_RIGHT, shiftMeta); break;
            case TextEditPanel.HOME: sendKey(ic, KeyEvent.KEYCODE_MOVE_HOME, shiftMeta); break;
            case TextEditPanel.END: sendKey(ic, KeyEvent.KEYCODE_MOVE_END, shiftMeta); break;
            case TextEditPanel.SELECT_ALL: ic.performContextMenuAction(android.R.id.selectAll); break;
            case TextEditPanel.COPY: ic.performContextMenuAction(android.R.id.copy); break;
            case TextEditPanel.CUT: ic.performContextMenuAction(android.R.id.cut); break;
            case TextEditPanel.PASTE: ic.performContextMenuAction(android.R.id.paste); break;
            case TextEditPanel.UNDO: sendKey(ic, KeyEvent.KEYCODE_Z, ctrl); break;
            case TextEditPanel.REDO: sendKey(ic, KeyEvent.KEYCODE_Z, ctrl | KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_LEFT_ON); break;
            case TextEditPanel.DELETE: handleDelete(false); break;
            case TextEditPanel.BACK: showPanel(PANEL_KEYS); break;
            default: break;
        }
    }

    @Override
    public void onHeightChanged(int percent) {
        Prefs.put(this, Prefs.HEIGHT, percent);
        readPrefs();
    }

    @Override
    public void onKeySizeChanged(int percent) {
        Prefs.put(this, Prefs.KEY_SIZE, percent);
        readPrefs();
    }

    @Override
    public void onFontChanged(int percent) {
        Prefs.put(this, Prefs.FONT_SIZE, percent);
        readPrefs();
    }

    @Override
    public void onSizeDone() {
        showPanel(PANEL_KEYS);
    }
}
