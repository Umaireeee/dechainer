#!/usr/bin/env bash
# Runs on an emulator (see .github/workflows/ci.yml).
# 1) Smoke-tests the accessibility service: seeds a region rule, enables the service, drives the
#    UI, and fails on any crash in the app or if the service stops being bound.
# 2) Runs the instrumented (androidTest) suite.
set -uo pipefail

PKG=io.github.warleysr.dechainer
SERVICE="$PKG/$PKG.DechainerAccessibilityService"
mkdir -p emulator-logs
chmod +x gradlew

echo "== Installing debug build"
./gradlew installDebug --stacktrace || exit 1

echo "== Seeding a region rule for the Settings app (activity-agnostic, id that never matches,"
echo "   so the armed scan path runs without ever drawing)"
adb shell "run-as $PKG mkdir -p shared_prefs"
RULES='[{&quot;id&quot;:&quot;t1&quot;,&quot;pkg&quot;:&quot;com.android.settings&quot;,&quot;viewId&quot;:&quot;com.android.settings:id/does_not_exist&quot;,&quot;text&quot;:&quot;&quot;,&quot;label&quot;:&quot;t&quot;,&quot;extendDown&quot;:true,&quot;activity&quot;:&quot;&quot;}]'
printf '<?xml version="1.0" encoding="utf-8" standalone="yes" ?>\n<map><string name="rules">%s</string></map>\n' "$RULES" \
  | adb shell "run-as $PKG sh -c 'cat > shared_prefs/region_rules_prefs.xml'"

adb logcat -c
echo "== Enabling the accessibility service"
adb shell settings put secure enabled_accessibility_services "$SERVICE"
adb shell settings put secure accessibility_enabled 1

bound=false
for i in $(seq 1 30); do
  if adb shell dumpsys accessibility | grep -q "$PKG.DechainerAccessibilityService"; then bound=true; break; fi
  sleep 2
done
if [ "$bound" != true ]; then
  echo "::error::Accessibility service never bound"
  adb logcat -d > emulator-logs/logcat.txt
  exit 1
fi
echo "Service bound"

echo "== Driving the UI (Settings is the app with the rule, then others)"
adb shell am start -a android.settings.SETTINGS
sleep 3
for i in 1 2 3; do adb shell input swipe 540 1600 540 600 300; sleep 1; done
adb shell input keyevent KEYCODE_BACK
sleep 1
adb shell am start -a android.intent.action.VIEW -d "https://example.com" >/dev/null
sleep 2
adb shell input keyevent KEYCODE_HOME
sleep 3
adb shell am start -a android.settings.SETTINGS
sleep 3
adb shell input keyevent KEYCODE_HOME
sleep 2

adb logcat -d > emulator-logs/logcat.txt
if grep -E "FATAL EXCEPTION" -A 12 emulator-logs/logcat.txt | grep -q "$PKG"; then
  echo "::error::The app crashed during the smoke test"
  grep -E "FATAL EXCEPTION" -A 15 emulator-logs/logcat.txt | head -60
  exit 1
fi
if ! adb shell dumpsys accessibility | grep -q "$PKG.DechainerAccessibilityService"; then
  echo "::error::Accessibility service was unbound during the smoke test"
  exit 1
fi
if adb shell dumpsys window | grep -q "TYPE_ACCESSIBILITY_OVERLAY"; then
  echo "::error::An overlay window is showing although the rule can never match"
  exit 1
fi
echo "Smoke test passed"

echo "== Instrumented tests"
adb shell settings put secure enabled_accessibility_services ""
./gradlew connectedDebugAndroidTest --stacktrace
