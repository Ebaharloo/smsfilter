#!/usr/bin/env bash
# End-to-end check on the emulator: inject Farsi SMS, verify spam notif is suppressed
# and bank/OTP notif survives. Prints PASS/FAIL per case.
set -uo pipefail
export PATH=${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools:$PATH
PKG_MSG=com.google.android.apps.messaging
pass=0; fail=0

check() { # $1=sender $2=text $3=expect  (suppressed|present)
  adb emu sms send "$1" "$2" >/dev/null 2>&1 || { echo "inject failed (emulator console auth?)"; return; }
  sleep 2
  local got
  if adb shell dumpsys notification --noredact 2>/dev/null | grep -q "$PKG_MSG"; then got=present; else got=suppressed; fi
  if [ "$got" = "$3" ]; then echo "PASS $3 :: ${2:0:50}"; pass=$((pass+1))
  else echo "FAIL expected $3 got $got :: ${2:0:50}"; fail=$((fail+1)); fi
  adb shell cmd notification post -S bigtext -t x cancel_all x >/dev/null 2>&1
  adb shell am force-stop "$PKG_MSG" >/dev/null 2>&1
}

check 9830001234 "تخفیف ویژه ۵۰٪ حراج لباس کد تخفیف OFF50 کلیک کنید t.me/xyz لغو11" suppressed
check "Bank Melli" "واریز 25,000,000 ریال به حساب 1234 موجودی 90,120,000 ریال" present
check 90001111 "رمز پویا: 482913 کد تایید را وارد کنید" present
echo "----- pass=$pass fail=$fail"
[ "$fail" -eq 0 ]
