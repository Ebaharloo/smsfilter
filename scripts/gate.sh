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
  ADB="$ANDROID_HOME/platform-tools/adb"
  # pm install (no PackageInstaller UI) instead of `adb install`: on this MIUI ROM the installer
  # path restarts SystemUI, which drops the user's LSPosed gesture-nav module (com.hcgstudio.*)
  # and leaves the phone without any navigation.
  ui_before=$("$ADB" -s "$DEVICE" shell pidof com.android.systemui | tr -d '\r')
  "$ADB" -s "$DEVICE" push "$APK" /data/local/tmp/smsfilter.apk >/dev/null
  "$ADB" -s "$DEVICE" shell pm install -r -d /data/local/tmp/smsfilter.apk
  "$ADB" -s "$DEVICE" shell rm -f /data/local/tmp/smsfilter.apk
  # if SystemUI was recreated, restart it once so LSPosed re-applies gesture hooks immediately
  ui_after=$("$ADB" -s "$DEVICE" shell pidof com.android.systemui | tr -d '\r')
  if [[ "$ui_before" != "$ui_after" ]]; then
    echo "SystemUI restarted ($ui_before -> $ui_after): reloading gesture hooks"
    "$ADB" -s "$DEVICE" shell "su -c 'killall com.android.systemui'" >/dev/null 2>&1 || true
    sleep 3
  fi
  "$ADB" -s "$DEVICE" shell am force-stop ir.smsfilter
  "$ADB" -s "$DEVICE" shell am start -n ir.smsfilter/.MainActivity
  echo "installed on $DEVICE"
fi
