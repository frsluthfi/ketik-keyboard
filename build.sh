#!/usr/bin/env bash
# Builds KetikKeyboard.apk with Docker. Run from this folder:  bash build.sh
# Docker volumes keep the Android SDK, Gradle cache and the SIGNING KEY between builds,
# so new versions install over old ones.
set -e
cd "$(dirname "$0")"
docker run --rm -v "$PWD":/project -v ketik-sdk:/sdk -v ketik-gradle:/root/.gradle \
  -v ketik-android:/root/.android -w /project eclipse-temurin:17-jdk bash -c '
set -e
export DEBIAN_FRONTEND=noninteractive
if ! command -v unzip >/dev/null || ! command -v wget >/dev/null; then
  apt-get update -qq && apt-get install -y -qq unzip wget >/dev/null
fi
if [ ! -d /sdk/cmdline-tools/latest ]; then
  wget -q https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -O /tmp/t.zip
  mkdir -p /sdk/cmdline-tools && unzip -q /tmp/t.zip -d /sdk/cmdline-tools
  mv /sdk/cmdline-tools/cmdline-tools /sdk/cmdline-tools/latest
fi
if [ ! -d /sdk/platforms/android-34 ]; then
  yes | /sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root=/sdk --licenses >/dev/null
  /sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root=/sdk "platforms;android-34" "build-tools;34.0.0" >/dev/null
fi
echo "sdk.dir=/sdk" > local.properties
chmod +x gradlew
./gradlew --no-daemon -q assembleRelease
cp app/build/outputs/apk/full/release/app-full-release.apk KetikKeyboard.apk
cp app/build/outputs/apk/lite/release/app-lite-release.apk KetikKeyboard-lite.apk
chmod 644 KetikKeyboard.apk KetikKeyboard-lite.apk
'
echo
echo "Done:"
echo "  $(pwd)/KetikKeyboard.apk       full, with OTP autofill  -> install with: adb install -r KetikKeyboard.apk"
echo "  $(pwd)/KetikKeyboard-lite.apk  no OTP autofill          -> installs from a file manager"
