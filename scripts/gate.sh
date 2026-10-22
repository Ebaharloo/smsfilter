#!/usr/bin/env bash
# Regression gate. Run before/after every feature: nothing ships unless this passes.
#
#   scripts/gate.sh            # unit rules + filter power + build
#   scripts/gate.sh --install  # ... and push to the phone
#
# Layers:
#   1. :app:testDebugUnitTest  - pure inbox rules (archive, pin order, mute, OTP, links)
#   2. tools/gate_eval.py      - filter power on real corpora and your own labeled inbox
#   3. :app:assembleDebug      - the app compiles and packages
set -euo pipefail

cd "$(dirname "$0")/.."
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"
GRADLE="$HOME/tools/gradle/gradle-8.9/bin/gradle"
DEVICE="${DEVICE:-be1a246a}"

echo "== 1. unit rules"
"$GRADLE" --no-daemon :app:testDebugUnitTest

echo "== 2. filter power"
python3 tools/gate_eval.py

echo "== 3. build"
"$GRADLE" --no-daemon :app:assembleDebug

APK="app/build/outputs/apk/debug/app-debug.apk"
echo "APK: $APK ($(stat -c%s "$APK") bytes)"

if [[ "${1:-}" == "--install" ]]; then
  "$ANDROID_HOME/platform-tools/adb" -s "$DEVICE" install -r "$APK"
  "$ANDROID_HOME/platform-tools/adb" -s "$DEVICE" shell am force-stop ir.smsfilter
  "$ANDROID_HOME/platform-tools/adb" -s "$DEVICE" shell am start -n ir.smsfilter/.MainActivity
  echo "installed on $DEVICE"
fi
