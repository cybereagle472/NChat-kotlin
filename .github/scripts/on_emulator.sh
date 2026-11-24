#!/usr/bin/env bash
#
# What runs while the emulator is up: the encryption tests, then a launch of
# the app as the release builds it.
#
#   on_emulator.sh <api level>
#
# Both always run — a failing test must not hide whether the app starts, nor
# the other way round — and the exit status is 0 only when both went well.
# The logs stay in /tmp for the steps that report them.
set -u

API_LEVEL="${1:?api level}"
APK=app/build/outputs/apk/smoke/app-smoke.apk
PACKAGE=com.nchat.app.smoke
ACTIVITY=com.nchat.app.MainActivity
OUT=/tmp/app-start

gradle --no-daemon --stacktrace :app:connectedDebugAndroidTest > /tmp/device-run.log 2>&1
tests=$?

start_app() {
  rm -rf "$OUT" && mkdir -p "$OUT"
  if ! adb install -r "$APK" > "$OUT/install.txt" 2>&1; then
    echo "::error title=App start on Android API $API_LEVEL::the app could not be installed: $(tail -n 1 "$OUT/install.txt")"
    cat "$OUT/install.txt"
    return 1
  fi
  # Android 13 and later ask before an app may notify; the question would be the window in front.
  adb shell pm grant "$PACKAGE" android.permission.POST_NOTIFICATIONS > /dev/null 2>&1 || true
  adb logcat -c > /dev/null 2>&1 || true
  adb shell am start -W -n "$PACKAGE/$ACTIVITY" > "$OUT/launch.txt" 2>&1 || true

  # Up to two minutes for the first screen: an emulator without a graphics card is slow.
  for attempt in $(seq 1 24); do
    sleep 5
    adb shell "pidof $PACKAGE" > "$OUT/pid.txt" 2>&1 || true
    adb shell "uiautomator dump /sdcard/nchat-window.xml" > "$OUT/dump.txt" 2>&1 || true
    adb shell "cat /sdcard/nchat-window.xml" > "$OUT/window.xml" 2> /dev/null || true
    if grep -q "Welcome Back" "$OUT/window.xml" 2> /dev/null; then break; fi
    if adb logcat -d 2> /dev/null | grep -q "Process: $PACKAGE,"; then break; fi
  done
  # A crash that comes just after the first frame counts as well.
  sleep 10
  adb shell "pidof $PACKAGE" > "$OUT/pid.txt" 2>&1 || true
  adb shell "ps -A 2> /dev/null || ps" > "$OUT/processes.txt" 2>&1 || true
  adb shell "dumpsys activity activities" 2> /dev/null | grep -E "mResumedActivity|ResumedActivity:|topResumedActivity" > "$OUT/resumed.txt" || true
  adb logcat -d > "$OUT/logcat.txt" 2>&1 || true
  [ -s "$OUT/window.xml" ] || cp "$OUT/dump.txt" "$OUT/window.xml" 2> /dev/null || true

  python3 .github/scripts/app_start_check.py "$PACKAGE" "$API_LEVEL" \
    "$OUT/pid.txt" "$OUT/processes.txt" "$OUT/logcat.txt" "$OUT/window.xml" "$OUT/resumed.txt" \
    "Welcome Back" "Sign In"
}

start_app > /tmp/app-start.log 2>&1
started=$?
cat /tmp/app-start.log
if [ "$started" -ne 0 ]; then
  { echo "--- end of the log"; tail -n 150 "$OUT/logcat.txt" 2> /dev/null; } >> /tmp/app-start.log
fi

[ "$tests" -eq 0 ] && [ "$started" -eq 0 ]
