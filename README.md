# Ketik Keyboard

An offline Android keyboard for English and Indonesian, laid out the way Galaxy users are used to:
number hints on the top row, symbols on long-press, `!#1` / `,` / space / `.` / enter on the bottom row,
and a tool strip above the keys.

**Safety:** the app requests **no INTERNET permission** (check `app/src/main/AndroidManifest.xml`).
The optional permissions (photos for screenshots, notification access / SMS for one-time codes) are only
asked for when you switch those features on. Android itself stops it from opening any network connection, so
nothing you type can be sent anywhere. It has no dependencies or trackers; the only code is in `app/src/main/java`.
Nothing is learned in password fields, and clips that apps mark as sensitive are never saved.

## Features

- QWERTY with symbol hints and long-press accents (á, é, ñ…), optional number row
- Two symbol pages (1/2, 2/2), number pad for number fields, phone pad for phone fields
- Email fields get `@` and `.com` keys (long-press `.com` for `.co.id`, `.id`, `.net`…)
- Shift: tap = one capital, double-tap or long-press = caps lock; auto-capitalisation
- Word suggestions (English + Indonesian together, ~80k words), auto-correct on space,
  **backspace right after an auto-correct undoes it** and teaches the word
- Learns your words and which word you usually type next (stored only on the phone)
- Long-press a learned suggestion to forget it
- Swipe typing (slide across letters), long-press a key to see alternatives
- Hold or slide the spacebar, then move left/right (characters) or up/down (lines) to move the cursor
- Hold backspace: deletes letters, then whole words
- Double-space types ". "
- **Clipboard history** (never deleted by time; only the oldest unpinned items go past the limit),
  **screenshots and copied images included**: tap to paste, long-press to select → pin / delete, "Clear" keeps pinned,
  paste chip in the toolbar right after you copy something
- **One-time codes**: codes from SMS, WhatsApp, email or bank notifications appear in the top bar;
  tap to type them. Kept in memory only, gone after 5 minutes (needs notification access; optional SMS backup)
- Emoji panel with recents and categories (long-press `,` or the toolbar smiley)
- Text-editing panel: arrows, select mode, select all, copy, cut, paste, home/end, undo/redo
- One-handed mode (left/right), adjustable keyboard height, key height and text size, light/dark/follow-system theme
- Key font: Inter (default, closest free match to the Galaxy look), phone default, Nunito Sans,
  or any .ttf/.otf you import. Inter and Nunito Sans are under the SIL Open Font License
  (`app/src/main/assets/fonts/OFL-*.txt`)
- Key-press vibration (light or strong) and optional sound

## Build the APK

You need **JDK 17** and the **Android SDK (platform 34)**. Pick one way:

### A. Docker (easiest on a home server)

```bash
unzip -o KetikKeyboard.zip && cd ketik
bash build.sh
```

The APK is written to `ketik/KetikKeyboard.apk`. The script keeps the Android SDK, the Gradle cache and
the signing key in Docker volumes, so later builds are quick and install over the old version.

### B. Android SDK already installed

```bash
export ANDROID_HOME=$HOME/Android/Sdk          # wherever your SDK is
echo "sdk.dir=$ANDROID_HOME" > local.properties
chmod +x gradlew
./gradlew assembleRelease
```

The first build downloads Gradle and the Android build plugin (a few hundred MB).

### C. GitHub Actions (no server needed)

Push this folder to a GitHub repo. The included workflow (`.github/workflows/build.yml`) builds the
APK on every push. Download it from the run's **Artifacts** section.

**Output:** two APKs (same app, same settings; either installs over the other)

- `KetikKeyboard.apk`: **full**, with one-time code autofill. Google Play Protect blocks apps that ask for
  SMS or notification access when they are installed from a file manager or browser, so install this one with **adb**.
- `KetikKeyboard-lite.apk`: everything except one-time code autofill. Installs from a file manager.

## Install the full edition with adb (from the home server)

On the phone (once): Settings → About phone → tap **OS version** 7 times → back to Settings →
Additional settings → **Developer options**: turn on **Wireless debugging** and **Install via USB**
(HyperOS asks you to sign in with a Xiaomi account for this). Phone and server must be on the same Wi-Fi.

```bash
sudo apt install -y adb
# Phone: Wireless debugging → "Pair device with pairing code" shows IP:port + 6-digit code
adb pair 192.168.0.X:PAIRPORT        # type the code
# Phone: the Wireless debugging screen shows "IP address & Port" (a different port)
adb connect 192.168.0.X:PORT
adb install -r ~/ketik/KetikKeyboard.apk
```
Later updates only need `adb connect …` and `adb install -r …` (pairing is remembered).
You can turn Wireless debugging off again afterwards.

## Install on the Poco (HyperOS)

1. Copy `app-release.apk` to the phone and open it with **File Manager**.
2. Allow **Install unknown apps** for File Manager when asked. If HyperOS or Play Protect warns
   that the app is from an unknown developer, choose **Install anyway**. That warning shows up for
   any app you build yourself.
3. Open **Ketik Keyboard** from the app drawer:
   - **1. Turn on Ketik Keyboard**: switch it on. Android shows its standard warning that a keyboard
     "may be able to collect all the text you type". Every keyboard gets this warning. Ketik has no
     internet access, so it can't send anything anywhere.
   - **2. Choose Ketik as your keyboard**
4. Try it in the test box on the same screen.

To update later, build again and install over the old version. Your settings and learned words are kept.
