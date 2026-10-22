#!/usr/bin/env bash
# Build + install + arm the listener. Run scripts/avd.sh first.
set -euo pipefail
cd "$(dirname "$0")/.."
export ANDROID_HOME=${ANDROID_HOME:-$HOME/Android/Sdk}
export JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}
GRADLE=${GRADLE:-$HOME/tools/gradle/gradle-8.9/bin/gradle}

"$GRADLE" --no-daemon :app:testDebugUnitTest :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
# start the app first: NotificationManagerService drops a listener that is too slow to connect
adb shell am start -n ir.smsfilter/.MainActivity >/dev/null
sleep 6
adb shell cmd notification disallow_listener ir.smsfilter/.FilterService >/dev/null 2>&1
adb shell cmd notification allow_listener ir.smsfilter/.FilterService || \
  echo "grant notification access manually (Settings > Notifications > Device & app notifications)"
sleep 3
adb logcat -d -s SMSFilter | grep -q CONNECTED && echo "listener connected" || echo "WARN: listener not connected yet (re-run allow_listener)"
echo "installed"
