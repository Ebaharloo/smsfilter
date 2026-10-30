#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"
GRADLE="${GRADLE:-$HOME/tools/gradle/gradle-8.9/bin/gradle}"

echo "== JVM feature tests =="
"$GRADLE" --no-daemon :app:testDebugUnitTest

echo "== Build debug APK =="
"$GRADLE" --no-daemon :app:assembleDebug

if [ "${RUN_UI:-0}" = "1" ]; then
  echo "== UI screenshot agent =="
  python3 scripts/ui_screenshot_agent.py
fi

echo "ALL DONE"
