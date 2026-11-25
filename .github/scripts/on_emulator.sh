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
#
# Nothing here waits without a limit. An emulator can stop answering, and so
# can the test run; a job that waits for either runs until somebody cancels it
# and leaves no log (that happened on 2026-11-24). So every command has a
# limit of its own, the run as a whole has one, and when one runs out the
# script says which command it was, asks what the emulator still answers,
# writes that where the workflow commits it, and ends with a failure.
set -u

API_LEVEL="${1:?api level}"
APK=app/build/outputs/apk/smoke/app-smoke.apk
PACKAGE=com.nchat.app.smoke
ACTIVITY=com.nchat.app.MainActivity
TMP="${NCHAT_CI_TMP:-/tmp}"
OUT="$TMP/app-start"
RUN_LOG="$TMP/device-run.log"
START_LOG="$TMP/app-start.log"

# The limits, in whole seconds. A limit is a statement about the machine: each of these is
# several times what the command takes on GitHub's runners, and every run says in a notice
# ("Emulator commands on Android API …") how long the slowest of each kind took, so that a
# limit which comes close is seen before it fails a run. The environment can set each one;
# the tests do, and so does a fire drill (see the workflow).
TOTAL_LIMIT="${NCHAT_TOTAL_LIMIT:-1500}"    # everything below together; the workflow's own limits rest on it
TESTS_LIMIT="${NCHAT_TESTS_LIMIT:-900}"     # the test run
INSTALL_LIMIT="${NCHAT_INSTALL_LIMIT:-300}" # installing the app; Android 7 compiles it while it installs
LAUNCH_LIMIT="${NCHAT_LAUNCH_LIMIT:-120}"   # `am start -W`, which waits for the first frame
ANSWER_LIMIT="${NCHAT_ANSWER_LIMIT:-60}"    # any other question to the emulator
PROBE_LIMIT="${NCHAT_PROBE_LIMIT:-20}"      # a question about an emulator that has gone silent
SCREEN_LIMIT="${NCHAT_SCREEN_LIMIT:-120}"   # for the first screen: an emulator without a graphics card is slow
POLL_SECONDS="${NCHAT_POLL_SECONDS:-5}"     # between two looks at the screen
SETTLE_SECONDS="${NCHAT_SETTLE_SECONDS:-10}" # after the first screen: a crash that comes just after it counts
KILL_AFTER="${NCHAT_KILL_AFTER:-15}"        # a command that does not end when told to is killed after this

LAST_LIMIT=0
COMMANDS="$OUT/commands.tsv"      # kind, seconds taken, limit, exit status, command — one line per command
UNANSWERED="$OUT/unanswered.txt"  # the commands that were stopped at their limit

rm -rf "$OUT" && mkdir -p "$OUT"
: > "$COMMANDS"
: > "$UNANSWERED"
: > "$START_LOG"

now_ms() {
  local now="${EPOCHREALTIME:-}"
  if [ -n "$now" ]; then
    now="${now/[.,]/}"
    echo "$((now / 1000))"
  else
    echo "$(($(date +%s%N) / 1000000))"
  fi
}

unanswered() { [ -s "$UNANSWERED" ]; }

# limited <kind> <command…> — runs the command, and stops it when its time is up.
# <kind> says which limit holds: tests, install, launch, or answer for anything else.
# LAST_LIMIT is the limit the command was given, for whoever reports that it ran out.
#
# NCHAT_DRILL_STUCK is for trying this out on a real emulator: a command that has that word
# in it is not run, and the script waits in its place for an answer that does not come.
limited() {
  local kind="$1" limit
  shift
  case "$kind" in
    tests) limit="$TESTS_LIMIT" ;;
    install) limit="$INSTALL_LIMIT" ;;
    launch) limit="$LAUNCH_LIMIT" ;;
    *) limit="$ANSWER_LIMIT" ;;
  esac
  local left=$((TOTAL_LIMIT - SECONDS)) whole=""
  if [ "$left" -le 0 ]; then
    echo "the $TOTAL_LIMIT s for the whole run were used up before: $*" >> "$UNANSWERED"
    return 124
  fi
  if [ "$limit" -gt "$left" ]; then
    limit="$left"
    whole=yes
  fi
  LAST_LIMIT="$limit"

  local began status taken
  began=$(now_ms)
  if [ -n "${NCHAT_DRILL_STUCK:-}" ] && [[ "$*" == *"$NCHAT_DRILL_STUCK"* ]]; then
    timeout -k "$KILL_AFTER" "$limit" sleep 86400 < /dev/null
  else
    timeout -k "$KILL_AFTER" "$limit" "$@" < /dev/null
  fi
  status=$?
  taken=$(($(now_ms) - began))
  printf '%s\t%d.%d\t%s\t%s\t%s\n' "$kind" "$((taken / 1000))" "$((taken % 1000 / 100))" "$limit" "$status" "$*" >> "$COMMANDS"
  # 124: stopped at the limit. 137: killed — by `timeout` when stopping was not enough, which
  # is only the case when the time was up; a command killed earlier by something else failed.
  if [ "$status" -eq 124 ] || { [ "$status" -eq 137 ] && [ "$taken" -ge "$((limit * 1000))" ]; }; then
    if [ -n "$whole" ]; then
      echo "the $TOTAL_LIMIT s for the whole run were used up during: $*" >> "$UNANSWERED"
    else
      echo "no answer within $limit s: $*" >> "$UNANSWERED"
    fi
  fi
  return "$status"
}

# ask <kind> <file for the answer> <what adb is asked…>
#
# Once a question has gone unanswered no other is put: each would be waited for in turn, and
# what was read before stays as it was.
ask() {
  local kind="$1" answer="$2"
  shift 2
  unanswered && return 1
  limited "$kind" adb "$@" > "$answer" 2>&1
}

# probe <what is asked> <lines to keep> <pattern, or nothing for every line> <command…>
#
# A question about an emulator that has gone silent, with a short limit of its own. After two
# in a row without an answer the rest are not asked.
SILENT=0
probe() {
  local what="$1" keep="$2" pattern="$3" status
  shift 3
  [ "$SILENT" -ge 2 ] && return 0
  echo "- $what"
  timeout -k "$KILL_AFTER" "$PROBE_LIMIT" "$@" > "$OUT/probe.txt" 2>&1 < /dev/null
  status=$?
  if [ "$status" -eq 124 ] || [ "$status" -eq 137 ]; then
    SILENT=$((SILENT + 1))
    echo "    (no answer within $PROBE_LIMIT s)"
    if [ "$SILENT" -ge 2 ]; then echo "    (nothing more was asked: two questions in a row went unanswered)"; fi
    return 0
  fi
  SILENT=0
  if [ -n "$pattern" ]; then
    grep -E "$pattern" "$OUT/probe.txt" | tail -n "$keep" > "$OUT/probe-kept.txt"
  else
    grep -v '^[[:space:]]*$' "$OUT/probe.txt" | tail -n "$keep" > "$OUT/probe-kept.txt"
  fi
  if [ -s "$OUT/probe-kept.txt" ]; then sed 's/^/    /' "$OUT/probe-kept.txt"; else echo "    (nothing)"; fi
}

# What there is to know when a command has gone unanswered: is the emulator still attached,
# what is in front, is the app there, did an app stop responding, and what the log says.
#
# Each of these was seen to answer on the emulators of Android 7 and 14 (the fire drill of
# 2026-11-25). An app that does not respond shows in three of them: its dialog is the window
# in front ("Application Not Responding: …"), the log says "ANR in …" and why, and the system
# writes where its threads were to a file in /data/anr — traces.txt on Android 7, one file
# per case later. `dumpsys activity lastanr` is not asked: Android 7 does not know it.
still_answers() {
  echo "--- what the emulator still answers"
  SILENT=0
  probe "is it attached" 6 "" adb devices
  probe "has it started, and how long has it run" 4 "" adb shell "getprop sys.boot_completed; uptime"
  probe "what is in front" 8 "mResumedActivity|ResumedActivity:|topResumedActivity|mCurrentFocus|mFocusedApp" \
    adb shell "dumpsys activity activities; dumpsys window windows"
  probe "the app's process" 4 "$PACKAGE" adb shell "ps -A 2> /dev/null || ps"
  probe "what an app that did not respond left behind" 120 "" \
    adb shell 'ls -l /data/anr 2>&1; for file in $(ls -t /data/anr 2> /dev/null | head -n 1); do head -n 110 "/data/anr/$file" 2>&1; done'
  probe "the log" 80 "ANR in|FATAL EXCEPTION|Fatal signal|not responding|Watchdog|$PACKAGE|AndroidRuntime: |ActivityManager: (PID:|Reason:|Load:|Start proc|Killing|Displayed|Process .* has (died|crashed))" \
    adb logcat -d -t 3000
  echo "- on this machine"
  ps -eo pid,etimes,args 2> /dev/null | grep -E "qemu|emulator|adb|[g]radle|java" | grep -v "grep -E" | cut -c1-160 | head -n 20 | sed 's/^/    /'
}

# The words on the screen as `uiautomator dump` last read them, one per line: "package: words".
on_screen() {
  grep -oE '<node [^>]*>' "$1" 2> /dev/null | sed -nE 's/.* text="([^"]+)".* package="([^"]+)".*/\2: \1/p' | head -n 30
}

# How long the slowest command of each kind took, against its limit.
measured() {
  awk -F'\t' '
    { if (!($1 in most) || $2 + 0 > most[$1] + 0) { most[$1] = $2; of[$1] = $3; what[$1] = $5 } }
    END {
      count = split("tests install launch screen answer", kinds, " ")
      name["tests"] = "the tests"; name["install"] = "the install"; name["launch"] = "the launch"
      name["screen"] = "the first screen"; name["answer"] = "the slowest other answer"
      said = ""
      for (at = 1; at <= count; at++) {
        kind = kinds[at]
        if (!(kind in most)) continue
        part = name[kind] " " most[kind] " s of " of[kind] " s"
        if (kind == "answer") part = part " (" what[kind] ")"
        said = said (said == "" ? "" : "; ") part
      }
      print said
    }' "$COMMANDS"
}

# ------------------------------------------------------------------ the tests

limited tests gradle --no-daemon --stacktrace :app:connectedDebugAndroidTest > "$RUN_LOG" 2>&1
tests=$?
if unanswered; then
  stopped="the test run did not end within $LAST_LIMIT s and was stopped"
  echo "::error title=Encryption tests on Android API $API_LEVEL::$stopped"
  {
    echo
    echo "The ${stopped#the }."
  } >> "$RUN_LOG"
  {
    echo "Encryption tests on Android API $API_LEVEL: $stopped."
    echo "--- the end of what it had written"
    tail -n 40 "$RUN_LOG"
    still_answers
    echo
  } >> "$START_LOG" 2>&1
  # The app is started all the same, and its questions begin afresh. What the test run left
  # behind — on this machine or on the emulator — may still be at work; the job's end clears it.
  : > "$UNANSWERED"
fi

# -------------------------------------------------------------- the app's start

start_app() {
  if ! ask install "$OUT/install.txt" install -r "$APK"; then
    if ! unanswered; then
      echo "::error title=App start on Android API $API_LEVEL::the app could not be installed: $(tail -n 1 "$OUT/install.txt")"
      cat "$OUT/install.txt"
      return 1
    fi
  fi
  # Two questions the app asks through the system on its first run are answered beforehand:
  # they would be the window in front. Android 13 and later ask before an app may notify; and
  # the app asks, once per version, to be left running in the background.
  ask answer /dev/null shell pm grant "$PACKAGE" android.permission.POST_NOTIFICATIONS || true
  ask answer /dev/null shell dumpsys deviceidle whitelist "+$PACKAGE" || true
  ask answer /dev/null logcat -c || true
  ask launch "$OUT/launch.txt" shell am start -W -n "$PACKAGE/$ACTIVITY" || true

  # Until the first screen is there, the app has crashed, the emulator has gone silent, or
  # the time for it is up.
  local began until
  began=$(now_ms)
  until=$((SECONDS + SCREEN_LIMIT))
  while ! unanswered && [ "$SECONDS" -lt "$until" ]; do
    sleep "$POLL_SECONDS"
    ask answer "$OUT/pid.txt" shell "pidof $PACKAGE" || true
    ask answer "$OUT/dump.txt" shell "uiautomator dump /sdcard/nchat-window.xml" || true
    ask answer "$OUT/window.xml" shell "cat /sdcard/nchat-window.xml" || true
    if grep -q "Welcome Back" "$OUT/window.xml" 2> /dev/null; then break; fi
    ask answer "$OUT/logcat.txt" logcat -d || true
    if grep -q "Process: $PACKAGE," "$OUT/logcat.txt" 2> /dev/null; then break; fi
  done
  if ! unanswered; then
    local waited=$(($(now_ms) - began))
    printf 'screen\t%d.%d\t%s\t0\tthe first screen\n' "$((waited / 1000))" "$((waited % 1000 / 100))" "$SCREEN_LIMIT" >> "$COMMANDS"
  fi

  # A crash that comes just after the first frame counts as well.
  unanswered || sleep "$SETTLE_SECONDS"
  ask answer "$OUT/pid.txt" shell "pidof $PACKAGE" || true
  ask answer "$OUT/processes.txt" shell "ps -A 2> /dev/null || ps" || true
  ask answer "$OUT/activities.txt" shell "dumpsys activity activities" || true
  grep -E "mResumedActivity|ResumedActivity:|topResumedActivity" "$OUT/activities.txt" > "$OUT/resumed.txt" 2> /dev/null || true
  ask answer "$OUT/logcat.txt" logcat -d || true
  [ -s "$OUT/window.xml" ] || cp "$OUT/dump.txt" "$OUT/window.xml" 2> /dev/null || true

  if unanswered; then
    # Not judged: what the judge would read is incomplete, and it would call a silent
    # emulator an app that does not run.
    local what
    what=$(head -n 1 "$UNANSWERED")
    echo "::error title=App start on Android API $API_LEVEL::not judged — $what"
    echo "App start on Android API $API_LEVEL: not judged — $what"
    echo "--- what the launch said"
    if [ -s "$OUT/launch.txt" ]; then cat "$OUT/launch.txt"; else echo "(it had not been launched)"; fi
    echo "--- the screen as it was last read"
    on_screen "$OUT/window.xml" > "$OUT/on-screen.txt"
    if [ -s "$OUT/on-screen.txt" ]; then cat "$OUT/on-screen.txt"; else echo "(it had not been read)"; fi
    still_answers
    return 1
  fi

  python3 .github/scripts/app_start_check.py "$PACKAGE" "$API_LEVEL" \
    "$OUT/pid.txt" "$OUT/processes.txt" "$OUT/logcat.txt" "$OUT/window.xml" "$OUT/resumed.txt" \
    "Welcome Back" "Sign In"
}

start_app >> "$START_LOG" 2>&1
started=$?
cat "$START_LOG"
# The app's own lines of the emulator's log — when the start was judged; of an emulator that
# went silent the log was asked above, and what there is of it is there.
if [ "$started" -ne 0 ] && ! unanswered; then
  {
    echo "--- what the log says of the app"
    grep -E "$PACKAGE|FATAL EXCEPTION|ANR in|Fatal signal|AndroidRuntime: (java|kotlin|Caused|	at )" "$OUT/logcat.txt" 2> /dev/null | tail -n 120
  } >> "$START_LOG"
fi

echo "::notice title=Emulator commands on Android API $API_LEVEL::$(measured)"

[ "$tests" -eq 0 ] && [ "$started" -eq 0 ]
