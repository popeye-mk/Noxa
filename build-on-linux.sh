#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Guardian — one-shot APK builder for Linux.
#
# Run this ON YOUR OWN PC (not inside Claude). It downloads everything it needs
# into ~/.guardian-build (no admin/root required), compiles the app, and leaves
# an installable file at:
#     app/build/outputs/apk/debug/app-debug.apk
#
# Usage:
#     cd <the privacy folder>
#     bash build-on-linux.sh
#
# Needs: internet, curl, unzip, tar (all standard on Ubuntu). If a step errors,
# copy the last ~15 lines of output back to Claude and it'll fix it.
# ---------------------------------------------------------------------------
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
TOOLS="$HOME/.guardian-build"
mkdir -p "$TOOLS"

need() { command -v "$1" >/dev/null 2>&1 || { echo "ERROR: '$1' is not installed. Install it first (e.g. sudo apt install $1)"; exit 1; }; }
need curl; need unzip; need tar

echo "==> [1/6] Java 17"
if java -version 2>&1 | grep -q 'version "17'; then
  JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")"
  echo "    using system Java 17"
else
  if [ ! -x "$TOOLS/jdk17/bin/java" ]; then
    echo "    downloading Temurin JDK 17 (one time)..."
    curl -fL -o "$TOOLS/jdk17.tar.gz" \
      "https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse"
    mkdir -p "$TOOLS/jdk17"
    tar -xzf "$TOOLS/jdk17.tar.gz" -C "$TOOLS/jdk17" --strip-components=1
  fi
  JAVA_HOME="$TOOLS/jdk17"
fi
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"
echo "    JAVA_HOME=$JAVA_HOME"

echo "==> [2/6] Android SDK command-line tools"
SDK="$TOOLS/android-sdk"
if [ ! -d "$SDK/cmdline-tools/latest" ]; then
  echo "    downloading Android command-line tools (one time)..."
  mkdir -p "$SDK/cmdline-tools"
  curl -fL -o "$TOOLS/cmdtools.zip" \
    "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
  unzip -q -o "$TOOLS/cmdtools.zip" -d "$SDK/cmdline-tools"
  rm -rf "$SDK/cmdline-tools/latest"
  mv "$SDK/cmdline-tools/cmdline-tools" "$SDK/cmdline-tools/latest"
fi
export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$SDK"
SDKMGR="$SDK/cmdline-tools/latest/bin/sdkmanager"

echo "==> [3/6] SDK packages (platform 34, build-tools, platform-tools) + licenses"
yes | "$SDKMGR" --sdk_root="$SDK" --licenses >/dev/null 2>&1 || true
"$SDKMGR" --sdk_root="$SDK" "platform-tools" "platforms;android-34" "build-tools;34.0.0"

echo "==> [4/6] point the project at the SDK"
echo "sdk.dir=$SDK" > "$ROOT/local.properties"

echo "==> [5/6] Gradle 8.7"
if [ ! -x "$TOOLS/gradle-8.7/bin/gradle" ]; then
  echo "    downloading Gradle 8.7 (one time)..."
  curl -fL -o "$TOOLS/gradle.zip" "https://services.gradle.org/distributions/gradle-8.7-bin.zip"
  unzip -q -o "$TOOLS/gradle.zip" -d "$TOOLS"
fi
GRADLE="$TOOLS/gradle-8.7/bin/gradle"

echo "==> [6/6] Building the APK (first run downloads dependencies, be patient)..."
cd "$ROOT"
# Pass "release" as the first argument for a signed release build
# (needs keystore.properties — see docs/FDROID-AND-RELEASE.md):
#     bash build-on-linux.sh release
TARGET="assembleDebug"
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
[ "${1:-}" = "release" ] && TARGET="assembleRelease" \
  && APK="$ROOT/app/build/outputs/apk/release/app-release.apk"
"$GRADLE" "$TARGET" --no-daemon

echo ""
echo "-----------------------------------------------------------------------"
if [ -f "$APK" ]; then
  echo "SUCCESS ✓  Your app is here:"
  echo "    $APK"
  echo ""
  echo "Install it on the plugged-in phone (USB debugging on):"
  echo "    $SDK/platform-tools/adb install -r \"$APK\""
  echo ""
  echo "…or just copy app-debug.apk onto the phone and tap it to install."
else
  echo "Build finished but no APK was produced — copy the output above to Claude."
fi
echo "-----------------------------------------------------------------------"
