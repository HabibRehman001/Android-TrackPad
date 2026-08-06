#!/usr/bin/env bash
# Local (no sudo) helpers for Phone Trackpad desktop + adb
ROOT="$(cd "$(dirname "$0")" && pwd)"
PKG="$ROOT/.local-pkgs"
ANDROID_HOME="${ANDROID_HOME:-$HOME/android-dev/sdk}"

export PYTHONPATH="$PKG/usr/lib/python3/dist-packages${PYTHONPATH:+:$PYTHONPATH}"
# Prefer Google's platform-tools when present
if [ -x "$ANDROID_HOME/platform-tools/adb" ]; then
  export PATH="$ANDROID_HOME/platform-tools:$PATH"
else
  export LD_LIBRARY_PATH="$PKG/usr/lib/x86_64-linux-gnu/android:$PKG/usr/lib/x86_64-linux-gnu${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
  export PATH="$PKG/usr/bin:$PATH"
fi

case "${1:-server}" in
  server)
    exec python3 "$ROOT/server.py"
    ;;
  adb)
    shift
    exec adb "$@"
    ;;
  reverse)
    adb devices -l
    exec adb reverse tcp:6000 tcp:6000
    ;;
  install)
    APK="$ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
    if [ ! -f "$APK" ]; then
      echo "APK not found. Build first: cd android && ./gradlew assembleDebug"
      exit 1
    fi
    adb install -r "$APK"
    adb reverse tcp:6000 tcp:6000
    adb shell am start -n com.example.phonetrackpad/.MainActivity
    ;;
  *)
    echo "Usage: $0 {server|adb|reverse|install}"
    exit 1
    ;;
esac
