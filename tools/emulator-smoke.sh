#!/usr/bin/env bash
set -euo pipefail
mkdir -p screenshots
pushd android >/dev/null
chmod +x gradlew
./gradlew --no-daemon :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
popd >/dev/null
PKG="il.nolimits.music.studio"
for ACTIVITY in StudioActivity CaptureActivity MediaPreferencesActivity YouTubeLibraryActivity LiveShowActivity ServerSyncActivity; do
  echo "Checking $ACTIVITY"
  adb shell am start -n "$PKG/il.nolimits.music.$ACTIVITY"
  sleep 4
  PID="$(adb shell pidof "$PKG" | tr -d '\r' || true)"
  if [ -z "$PID" ]; then
    echo "App process did not survive $ACTIVITY"
    adb logcat -d -b crash || true
    exit 1
  fi
  adb shell screencap -p "/sdcard/$ACTIVITY.png"
  adb pull "/sdcard/$ACTIVITY.png" "screenshots/$ACTIVITY.png"
done
CRASHES="$(adb logcat -d -b crash | grep -A 20 'FATAL EXCEPTION' | grep -E 'Process: il.nolimits.music.studio' || true)"
if [ -n "$CRASHES" ]; then
  echo "$CRASHES"
  exit 1
fi
echo "PASS: opened six primary MorinMusic screens with no process crash"
