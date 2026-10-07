#!/usr/bin/env bash
# Runs the pure-logic unit tests on a plain JVM, without the Android SDK or Gradle.
#
# The sandbox Claude Code works in cannot reach Google's Maven, so Gradle cannot build the app there.
# This compiles the Android-free source files with kotlinc and runs the JVM tests that cover them, so
# a change to the lock, day or AI logic is checked before it is pushed. CI stays the judge: it runs
# every test, including Robolectric ones, and builds both APKs.
#
#   tools/pure-tests.sh                 # the default set below
#   tools/pure-tests.sh LockModesTest   # just these test classes
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CACHE="${PURE_TESTS_CACHE:-$HOME/.cache/dechainer-pure-tests}"
KOTLIN_VERSION=2.2.20
M="$ROOT/app/src/main/java/io/github/warleysr/dechainer"
T="$ROOT/app/src/test/java/io/github/warleysr/dechainer"

mkdir -p "$CACHE"
fetch() { # url file: retried, since Maven Central rate-limits
  [ -s "$CACHE/$2" ] && [ "$(stat -c %s "$CACHE/$2")" -gt 1000 ] && return
  for i in 1 2 3 4 5; do
    curl -sSL -o "$CACHE/$2" "$1" && [ "$(stat -c %s "$CACHE/$2")" -gt 1000 ] && return
    sleep $((i * 4))
  done
  echo "Could not download $1" >&2; exit 1
}
if [ ! -x "$CACHE/kotlinc/bin/kotlinc" ]; then
  fetch "https://github.com/JetBrains/kotlin/releases/download/v$KOTLIN_VERSION/kotlin-compiler-$KOTLIN_VERSION.zip" kotlinc.zip
  (cd "$CACHE" && unzip -q -o kotlinc.zip)
fi
fetch https://repo1.maven.org/maven2/junit/junit/4.13.2/junit-4.13.2.jar junit.jar
fetch https://repo1.maven.org/maven2/org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar hamcrest.jar
fetch https://repo1.maven.org/maven2/org/json/json/20240303/json-20240303.jar json.jar

# The Android-free main sources. Add a file here when a new pure rule is written.
MAIN=(
  Rules.kt models/BlockSchedule.kt data/LockSafety.kt
  lock/LockModel.kt lock/LockPlanner.kt lock/LockAllow.kt lock/UrgeLockRule.kt lock/FocusStartRule.kt lock/FocusTimetable.kt
  urge/UrgeFlowRules.kt urge/UrgeModel.kt urge/Breathing.kt urge/UrgeJson.kt
  focus/FocusFlow.kt focus/PomodoroCore.kt
  ai/AiPrompts.kt ai/DeepDiveHistory.kt ai/AiModels.kt ai/AiParsers.kt ai/MarkdownBlocks.kt
  store/Migrations.kt
)
DEFAULT_TESTS=(
  LockModesTest LockPlannerTest BrickTargetsTest UrgeLockRuleTest FocusStartRuleTest FocusTimetableTest UrgeFlowRulesTest
  AiPromptsTest DeepDiveHistoryTest MigrationsTest ResourceReferencesTest DebugControlsTest AiParsersTest
)
TESTS=("$@"); [ ${#TESTS[@]} -eq 0 ] && TESTS=("${DEFAULT_TESTS[@]}")

OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
CP="$CACHE/junit.jar:$CACHE/hamcrest.jar:$CACHE/json.jar"
SRC=(); for f in "${MAIN[@]}"; do SRC+=("$M/$f"); done
for t in "${TESTS[@]}"; do SRC+=("$T/$t.kt"); done
"$CACHE/kotlinc/bin/kotlinc" -nowarn -cp "$CP" -d "$OUT" "${SRC[@]}"
CLASSES=(); for t in "${TESTS[@]}"; do CLASSES+=("io.github.warleysr.dechainer.$t"); done
cd "$ROOT/app"
java -cp "$OUT:$CP:$CACHE/kotlinc/lib/kotlin-stdlib.jar" org.junit.runner.JUnitCore "${CLASSES[@]}"
