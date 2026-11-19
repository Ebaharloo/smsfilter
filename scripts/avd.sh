#!/usr/bin/env bash
# Create + boot the test AVD (headless). Idempotent.
set -euo pipefail
export ANDROID_HOME=${ANDROID_HOME:-$HOME/Android/Sdk}
export JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}
export PATH=$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH
AVD=smsfilter35

if ! avdmanager list avd -c 2>/dev/null | grep -qx "$AVD"; then
  # sdkmanager's own system-image install kept dying mid-download on this connection, so the
  # image is unzipped by hand and the AVD config is written directly. emulator boots it fine.
  if [ ! -f "$ANDROID_HOME/system-images/android-35/google_apis/x86_64/system.img" ]; then
    echo "system image not unpacked yet: $ANDROID_HOME/system-images/android-35/google_apis/x86_64" >&2
    exit 1
  fi
  mkdir -p "$HOME/.android/avd/$AVD.avd"
  printf 'avd.ini.encoding=UTF-8\npath=%s\npath.rel=avd/%s.avd\ntarget=android-35\n' \
    "$HOME/.android/avd/$AVD.avd" "$AVD" > "$HOME/.android/avd/$AVD.ini"
  printf 'avd.ini.encoding=UTF-8\nAvdId=%s\navd.ini.displayname=%s\nabi.type=x86_64\ntag.id=google_apis\ntag.display=Google APIs\nimage.sysdir.1=system-images/android-35/google_apis/x86_64/\nhw.cpu.arch=x86_64\nhw.cpu.ncore=4\nhw.ramSize=3072\nhw.gpu.enabled=yes\nhw.gpu.mode=swiftshader_indirect\nhw.keyboard=yes\nhw.lcd.density=420\nhw.lcd.width=1080\nhw.lcd.height=2280\ndisk.dataPartition.size=4096M\nshowDeviceFrame=no\nskin.dynamic=yes\n' \
    "$AVD" "$AVD" > "$HOME/.android/avd/$AVD.avd/config.ini"
fi

if ! adb devices | grep -q emulator; then
  nohup emulator -avd "$AVD" -no-window -no-audio -no-snapshot -gpu swiftshader_indirect \
    -no-boot-anim -memory 3072 -cores 4 > "${TMPDIR:-/tmp}/emulator.log" 2>&1 &
fi

echo "waiting for boot..."
adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 3; done
adb shell input keyevent 82 >/dev/null 2>&1 || true
echo "booted: $(adb shell getprop ro.build.version.release) $(adb shell wm size | tail -1)"
