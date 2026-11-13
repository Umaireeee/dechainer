#!/usr/bin/env bash
# Runs on an emulator (see .github/workflows/ci.yml).
#   1) blocked activity: a saved blocked activity must be closed the moment it opens
#   2) smoke test: drive the UI through other apps; nothing may crash, service must stay bound
#   3) instrumented (androidTest) suite
set -uo pipefail

PKG=io.github.warleysr.dechainer
SERVICE="$PKG/$PKG.DechainerAccessibilityService"
BLOCKED=com.android.settings.homepage.SettingsHomepageActivity
mkdir -p emulator-logs

fail() {
  echo "::error::$1"
  adb logcat -d > emulator-logs/logcat-fail.txt
  exit 1
}
top_activity() { adb shell dumpsys activity activities | grep -E "topResumedActivity|mResumedActivity" | head -1; }

echo "== Installing the prebuilt APKs"
adb install -r -t apks/app-debug.apk || exit 1
adb install -r -t apks/app-debug-androidTest.apk || exit 1

echo "== Saving a blocked activity (before the service starts, so its first read sees it)"
adb shell "run-as $PKG mkdir -p shared_prefs"
printf '<?xml version="1.0" encoding="utf-8" standalone="yes" ?>\n<map><set name="blocked_activities"><string>%s</string></set></map>\n' "$BLOCKED" \
  | adb shell "run-as $PKG sh -c 'cat > shared_prefs/activity_blocker_prefs.xml'"

echo "== Enabling the accessibility service"
adb logcat -c
adb shell settings put secure enabled_accessibility_services "$SERVICE"
adb shell settings put secure accessibility_enabled 1

echo "== 1) Opening the blocked activity must send the user straight back"
blocked=false
for i in $(seq 1 30); do
  adb shell input keyevent KEYCODE_HOME
  sleep 1
  adb shell am start -a android.settings.SETTINGS >/dev/null
  sleep 3
  if ! top_activity | grep -q "SettingsHomepageActivity"; then blocked=true; break; fi
done
[ "$blocked" = true ] || fail "The blocked activity stayed on screen"
echo "Blocked activity was closed (attempt $i)"

# Not blocked: a different Settings screen must open normally.
adb shell input keyevent KEYCODE_HOME; sleep 1
adb shell am start -a android.settings.WIRELESS_SETTINGS >/dev/null
sleep 3
top_activity | grep -q "com.android.settings" || fail "An unrelated Settings screen was blocked too: $(top_activity)"
echo "Unrelated screens still open"

echo "== 2) Smoke test through other apps"
adb shell input keyevent KEYCODE_HOME; sleep 1
adb shell am start -a android.intent.action.VIEW -d "https://example.com" >/dev/null
sleep 3
adb shell input keyevent KEYCODE_HOME; sleep 2
adb shell am start -a android.settings.DISPLAY_SETTINGS >/dev/null
sleep 3
for _ in 1 2 3; do adb shell input swipe 540 1600 540 600 300; sleep 1; done
adb shell input keyevent KEYCODE_BACK; sleep 1
adb shell input keyevent KEYCODE_HOME; sleep 2
adb logcat -d > emulator-logs/logcat.txt
if grep -E "FATAL EXCEPTION" -A 12 emulator-logs/logcat.txt | grep -q "$PKG"; then
  grep -E "FATAL EXCEPTION" -A 15 emulator-logs/logcat.txt | head -60
  fail "The app crashed during the smoke test"
fi
adb shell dumpsys accessibility | grep -q "$PKG.DechainerAccessibilityService" || fail "Service was unbound during the smoke test"
echo "Smoke test passed"

echo "== 3) Instrumented tests"
adb shell settings delete secure enabled_accessibility_services
out=$(adb shell am instrument -w "$PKG.test/androidx.test.runner.AndroidJUnitRunner")
echo "$out" | tail -20
echo "$out" > emulator-logs/instrumentation.txt
if ! echo "$out" | grep -q "^OK (" || echo "$out" | grep -q "FAILURES!!!"; then
  fail "Instrumented tests failed"
fi
echo "Instrumented tests passed"
