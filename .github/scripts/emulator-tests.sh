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
if adb shell dumpsys window | grep -q "DechainerRegionOverlay"; then
  echo "::error::An overlay window is showing although the rule can never match"
  exit 1
fi
echo "Smoke test passed"


echo "== End to end: capture a real screen, turn a captured element into a rule, expect the overlay"
disable_service() { adb shell settings put secure enabled_accessibility_services ""; sleep 2; }
enable_service() {
  adb shell settings put secure enabled_accessibility_services "$SERVICE"
  adb shell settings put secure accessibility_enabled 1
  for i in $(seq 1 30); do
    if adb shell dumpsys accessibility | grep -q "$PKG.DechainerAccessibilityService"; then return 0; fi
    sleep 2
  done
  echo "::error::Accessibility service never bound"; return 1
}
overlay_showing() { adb shell dumpsys window windows | grep -q "DechainerRegionOverlay"; }

disable_service
adb shell am force-stop "$PKG"
adb shell "run-as $PKG rm -f shared_prefs/region_rules_prefs.xml"
enable_service || exit 1
adb logcat -c

# Capture: the service goes Home, waits 8 s, then reads whatever app is in front.
adb shell am broadcast -a io.github.warleysr.dechainer.DEBUG_CAPTURE --el delay 8000 -p "$PKG" >/dev/null
sleep 3
adb shell am start -a android.settings.SETTINGS >/dev/null
got=false
for i in $(seq 1 30); do
  if adb logcat -d -s RegionCapture:I | grep -q "pkg=com.android.settings"; then got=true; break; fi
  sleep 1
done
adb logcat -d -s RegionCapture:I > emulator-logs/capture.txt
if [ "$got" != true ]; then
  echo "::error::Capture never produced a result for the Settings app"
  cat emulator-logs/capture.txt; exit 1
fi
cat emulator-logs/capture.txt | head -40

nodes_with_id=$(grep -c "node id=[a-zA-Z]" emulator-logs/capture.txt || true)
if [ "$nodes_with_id" -eq 0 ]; then
  echo "::error::Capture found no view ids (is flagReportViewIds set?)"; exit 1
fi

# Pick a reasonably large element that has a view id, plus the activity the capture ran on.
ID=$(sed -n 's/.*node id=\([^ ]*\) size=\([0-9]*\)x\([0-9]*\) .*/\1 \2 \3/p' emulator-logs/capture.txt \
     | awk '$1 != "null" && $2 >= 200 && $3 >= 50 { print $1; exit }')
ACTIVITY=$(sed -n 's/.*pkg=com.android.settings activity=\([^ ]*\) .*/\1/p' emulator-logs/capture.txt | head -1)
[ "$ACTIVITY" = "null" ] && ACTIVITY=""
if [ -z "$ID" ]; then echo "::error::No suitable element in the capture"; exit 1; fi
echo "Rule: id=$ID activity=${ACTIVITY:-<any>}"

disable_service
adb shell am force-stop "$PKG"
RULES="[{&quot;id&quot;:&quot;e2e&quot;,&quot;pkg&quot;:&quot;com.android.settings&quot;,&quot;viewId&quot;:&quot;$ID&quot;,&quot;text&quot;:&quot;&quot;,&quot;label&quot;:&quot;e2e&quot;,&quot;extendDown&quot;:false,&quot;activity&quot;:&quot;$ACTIVITY&quot;}]"
printf '<?xml version="1.0" encoding="utf-8" standalone="yes" ?>\n<map><string name="rules">%s</string></map>\n' "$RULES" \
  | adb shell "run-as $PKG sh -c 'cat > shared_prefs/region_rules_prefs.xml'"
enable_service || exit 1

adb shell input keyevent KEYCODE_HOME
sleep 2
if overlay_showing; then echo "::error::Overlay visible on the launcher"; exit 1; fi
adb shell am start -a android.settings.SETTINGS >/dev/null
shown=false
for i in $(seq 1 20); do
  if overlay_showing; then shown=true; break; fi
  sleep 1
done
adb logcat -d > emulator-logs/logcat-e2e.txt
adb shell dumpsys window windows > emulator-logs/windows-e2e.txt
if [ "$shown" != true ]; then
  echo "::error::The overlay never appeared on the captured element"
  exit 1
fi
echo "Overlay appeared on the captured element"

adb shell input keyevent KEYCODE_HOME
gone=false
for i in $(seq 1 10); do
  if ! overlay_showing; then gone=true; break; fi
  sleep 1
done
if [ "$gone" != true ]; then
  echo "::error::Overlay stayed on screen after leaving the app"
  exit 1
fi
echo "Overlay removed after leaving the app; end-to-end test passed"

echo "== Instrumented tests"
adb shell settings put secure enabled_accessibility_services ""
./gradlew connectedDebugAndroidTest --stacktrace
