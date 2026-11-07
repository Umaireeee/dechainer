#!/usr/bin/env bash
# Runs on an emulator (see .github/workflows/ci.yml). Everything happens with the accessibility
# service staying enabled: region rules are changed inside the running app through a debug-only
# broadcast, exactly as the service sees a rule added in the real app.
#   1) smoke test: a rule that can never match, drive the UI, nothing may crash or draw
#   2) capture a real screen and turn a captured element into a rule: overlay must appear/vanish
#   3) invisible extend-down cover: absorbs taps, survives scrolling its target out of view
#   4) instrumented (androidTest) suite
set -uo pipefail

PKG=io.github.warleysr.dechainer
SERVICE="$PKG/$PKG.DechainerAccessibilityService"
mkdir -p emulator-logs

fail() {
  echo "::error::$1"
  adb logcat -d > emulator-logs/logcat-fail.txt
  adb shell dumpsys window windows > emulator-logs/windows-fail.txt
  exit 1
}
overlay_showing() { adb shell dumpsys window windows | grep -q "DechainerRegionOverlay"; }
wait_overlay() { for _ in $(seq 1 "$1"); do overlay_showing && return 0; sleep 1; done; return 1; }
wait_no_overlay() { for _ in $(seq 1 "$1"); do overlay_showing || return 0; sleep 1; done; return 1; }
top_activity() { adb shell dumpsys activity activities | grep -E "topResumedActivity|mResumedActivity" | head -1; }
set_rules() {
  adb shell am broadcast -a io.github.warleysr.dechainer.DEBUG_SET_RULES \
    --es b64 "$(printf '%s' "$1" | base64 -w0)" -p "$PKG" >/dev/null
  sleep 1
}
rule() { # id pkg viewId text extendDown activity invisible
  printf '[{"id":"%s","pkg":"%s","viewId":"%s","text":"%s","label":"t","extendDown":%s,"activity":"%s","invisible":%s}]' "$@"
}

echo "== Installing the prebuilt APKs"
adb install -r -t apks/app-debug.apk || exit 1
adb install -r -t apks/app-debug-androidTest.apk || exit 1

echo "== Enabling the accessibility service"
adb logcat -c
adb shell settings put secure enabled_accessibility_services "$SERVICE"
adb shell settings put secure accessibility_enabled 1
connected=false
for _ in $(seq 1 40); do
  if adb logcat -d -s RegionCapture:I | grep -q "service connected"; then connected=true; break; fi
  sleep 2
done
[ "$connected" = true ] || fail "Accessibility service never connected"
sleep 4
[ "$(adb shell settings get secure enabled_accessibility_services | tr -d '\r')" = "$SERVICE" ] \
  || fail "Accessibility service was switched off again"
echo "Service connected"

echo "== 1) Smoke test: a rule that can never match (armed path runs, nothing is drawn)"
set_rules "$(rule t1 com.android.settings com.android.settings:id/does_not_exist "" true "" false)"
adb shell am start -a android.settings.SETTINGS >/dev/null
sleep 3
for _ in 1 2 3; do adb shell input swipe 540 1600 540 600 300; sleep 1; done
adb shell input keyevent KEYCODE_BACK
sleep 1
adb shell am start -a android.intent.action.VIEW -d "https://example.com" >/dev/null
sleep 2
adb shell input keyevent KEYCODE_HOME
sleep 3
adb shell am start -a android.settings.SETTINGS >/dev/null
sleep 3
adb shell input keyevent KEYCODE_HOME
sleep 2
adb logcat -d > emulator-logs/logcat.txt
if grep -E "FATAL EXCEPTION" -A 12 emulator-logs/logcat.txt | grep -q "$PKG"; then
  grep -E "FATAL EXCEPTION" -A 15 emulator-logs/logcat.txt | head -60
  fail "The app crashed during the smoke test"
fi
adb shell dumpsys accessibility | grep -q "$PKG.DechainerAccessibilityService" || fail "Service was unbound during the smoke test"
overlay_showing && fail "An overlay is showing although the rule can never match"
echo "Smoke test passed"

echo "== 2) Capture a real screen, turn a captured element into a rule, expect the overlay"
set_rules '[]'
adb logcat -c
adb shell am broadcast -a io.github.warleysr.dechainer.DEBUG_CAPTURE --el delay 8000 -p "$PKG" >/dev/null
sleep 3 # the service goes Home first, then waits for the delay before reading the screen
adb shell am start -a android.settings.SETTINGS >/dev/null
got=false
for _ in $(seq 1 30); do
  if adb logcat -d -s RegionCapture:I | grep -q "pkg=com.android.settings"; then got=true; break; fi
  sleep 1
done
adb logcat -d -s RegionCapture:I > emulator-logs/capture.txt
[ "$got" = true ] || { cat emulator-logs/capture.txt; fail "Capture never produced a result for the Settings app"; }
head -40 emulator-logs/capture.txt
grep -q "node id=[a-zA-Z]" emulator-logs/capture.txt || fail "Capture found no view ids (is flagReportViewIds set?)"

ID=$(sed -n 's/.*node id=\([^ ]*\) size=\([0-9]*\)x\([0-9]*\) .*/\1 \2 \3/p' emulator-logs/capture.txt \
     | awk '$1 != "null" && $2 >= 200 && $3 >= 50 { print $1; exit }')
ACTIVITY=$(sed -n 's/.*pkg=com.android.settings activity=\([^ ]*\) .*/\1/p' emulator-logs/capture.txt | head -1)
[ "$ACTIVITY" = "null" ] && ACTIVITY=""
[ -n "$ID" ] || fail "No suitable element in the capture"
echo "Rule: id=$ID activity=${ACTIVITY:-<any>}"

set_rules "$(rule e2e com.android.settings "$ID" "" false "$ACTIVITY" false)"
adb shell input keyevent KEYCODE_HOME
sleep 2
overlay_showing && fail "Overlay visible on the launcher"
adb shell am start -a android.settings.SETTINGS >/dev/null
wait_overlay 20 || fail "The overlay never appeared on the captured element"
echo "Overlay appeared on the captured element"
adb shell input keyevent KEYCODE_HOME
wait_no_overlay 10 || fail "Overlay stayed on screen after leaving the app"
echo "Overlay removed after leaving the app"

echo "== 3) Invisible extend-down cover: absorbs taps and survives scrolling"
TEXT=""
for cand in "Network & internet" "Connected devices" "Apps"; do
  if grep -qF "text=$cand" emulator-logs/capture.txt; then TEXT="$cand"; break; fi
done
if [ -z "$TEXT" ]; then
  echo "::warning::No known Settings entry in the capture; skipping the scroll test"
else
  # Control: with no rule, a tap in the middle of the Settings list opens a sub-screen.
  set_rules '[]'
  adb shell input keyevent KEYCODE_HOME; sleep 2
  adb shell am start -a android.settings.SETTINGS >/dev/null; sleep 4
  before=$(top_activity)
  adb shell input tap 540 1300; sleep 3
  after=$(top_activity)
  adb shell input keyevent KEYCODE_BACK; sleep 2
  CONTROL_OK=true
  if [ "$before" = "$after" ]; then
    echo "::warning::Control tap did not navigate; skipping the touch-blocking assertion"
    CONTROL_OK=false
  fi

  set_rules "$(rule e2e2 com.android.settings "" "$TEXT" true "$ACTIVITY" true)"
  adb shell input keyevent KEYCODE_HOME; sleep 2
  adb shell am start -a android.settings.SETTINGS >/dev/null
  wait_overlay 20 || fail "No cover on the target '$TEXT'"
  echo "Invisible cover shown for '$TEXT'"
  if [ "$CONTROL_OK" = true ]; then
    sleep 2
    before=$(top_activity)
    adb shell input tap 540 1300; sleep 3
    after=$(top_activity)
    [ "$before" = "$after" ] || fail "A tap went through the invisible cover and opened another screen"
    echo "Tap was absorbed by the invisible cover"
  fi
  echo "Scrolling the target out of view"
  for _ in 1 2 3; do adb shell input swipe 540 1900 540 500 250; sleep 1; done
  sleep 2
  overlay_showing || fail "The cover disappeared after the target scrolled out of view"
  echo "Cover survived scrolling; scrolling back"
  for _ in 1 2 3 4; do adb shell input swipe 540 500 540 1900 250; sleep 1; done
  sleep 2
  overlay_showing || fail "The cover is gone after scrolling back to the target"
  adb shell input keyevent KEYCODE_HOME
  wait_no_overlay 10 || fail "Cover left over after leaving the app"
  echo "Scroll test passed"
fi

echo "== 4) Instrumented tests"
set_rules '[]'
adb shell settings delete secure enabled_accessibility_services
out=$(adb shell am instrument -w "$PKG.test/androidx.test.runner.AndroidJUnitRunner")
echo "$out" | tail -20
echo "$out" > emulator-logs/instrumentation.txt
if ! echo "$out" | grep -q "^OK (" || echo "$out" | grep -q "FAILURES!!!"; then
  fail "Instrumented tests failed"
fi
echo "Instrumented tests passed"
