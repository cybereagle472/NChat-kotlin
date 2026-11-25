"""Tests for on_emulator.sh — what runs while the emulator is up.

The script asks an emulator a dozen questions through `adb` and runs the tests
through `gradle`. Either can stop answering, and on 2026-11-24 one of them did:
the job went on for half an hour, was cancelled by hand, and left no log. So
the script is run here against stand-ins for both — an `adb` and a `gradle`
that answer as the real ones do, or never return — and has to end by itself,
say which command went unanswered, and say what the emulator still answered.

The judge of the app's start (app_start_check.py) is the real one.
"""
import os
import re
import shutil
import signal
import stat
import subprocess
import tempfile
import time
import unittest
from pathlib import Path

import test_app_start_check as shapes

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
SCRIPT = HERE / "on_emulator.sh"
WORKFLOW = ROOT / ".github" / "workflows" / "android-device-tests.yaml"

APK = "app/build/outputs/apk/smoke/app-smoke.apk"
STARTED = "App start on Android API 24: started"
STILL_ANSWERS = "--- what the emulator still answers"

# Stands in for adb. FAKE_ADB_SILENT is a regular expression: a command line it matches
# never returns (and with FAKE_DEAF does not let itself be stopped either). FAKE_ADB_SLOW is
# how many seconds an answer takes — every answer, or the ones FAKE_ADB_SLOW_AT matches.
ADB = r"""#!/usr/bin/env bash
echo "$$" >> "$FAKE/pids"
echo "adb $*" >> "$FAKE/asked"
if [ -n "${FAKE_ADB_SILENT:-}" ] && [[ "$*" =~ $FAKE_ADB_SILENT ]]; then
  [ -n "${FAKE_DEAF:-}" ] && trap '' TERM
  exec sleep 600
fi
if [ -n "${FAKE_ADB_SLOW:-}" ] && [[ "$*" =~ ${FAKE_ADB_SLOW_AT:-.} ]]; then sleep "$FAKE_ADB_SLOW"; fi
case "$*" in
  devices) printf 'List of devices attached\nemulator-5554\tdevice\n\n' ;;
  "install -r "*)
    if [ -n "${FAKE_INSTALL_FAILS:-}" ]; then
      echo "Performing Streamed Install"
      echo "adb: failed to install app-smoke.apk: Failure [INSTALL_FAILED_OLDER_SDK]" >&2
      exit 1
    fi
    printf 'Performing Streamed Install\nSuccess\n' ;;
  "shell am start -W -n "*)
    printf 'Starting: Intent { cmp=com.nchat.app.smoke/com.nchat.app.MainActivity }\nStatus: ok\nLaunchState: COLD\nTotalTime: 2345\nComplete\n' ;;
  "shell pidof "*) echo 4321 ;;
  "shell uiautomator dump "*) echo "UI hierchary dumped to: /sdcard/nchat-window.xml" ;;
  "shell cat /sdcard/nchat-window.xml") cat "$FAKE/window.xml" ;;
  "shell ps -A"*) echo "u0_a99    4321  1234  1 S com.nchat.app.smoke" ;;
  "shell dumpsys activity activities"*)
    echo "  mResumedActivity: ActivityRecord{1a2b3c u0 com.nchat.app.smoke/com.nchat.app.MainActivity t12}" ;;
  "shell ls -l /data/anr"*) echo "total 0" ;;
  "logcat -d"*) cat "$FAKE/logcat.txt" ;;
esac
exit 0
"""

# Stands in for gradle: with FAKE_GRADLE_SILENT it starts the tests and never returns; with
# FAKE_GRADLE_KILLED it is killed at once, as by a machine that has run out of memory.
GRADLE = r"""#!/usr/bin/env bash
echo "$$" >> "$FAKE/pids"
echo "gradle $*" >> "$FAKE/asked"
echo "> Task :app:connectedDebugAndroidTest"
if [ -n "${FAKE_GRADLE_SILENT:-}" ]; then
  echo "Starting 290 tests on test(AVD) - 7.0"
  exec sleep 600
fi
if [ -n "${FAKE_GRADLE_KILLED:-}" ]; then
  kill -KILL $$
fi
if [ "${FAKE_GRADLE_STATUS:-0}" -eq 0 ]; then
  echo "BUILD SUCCESSFUL in 1m 2s"
else
  echo "FAILURE: Build failed with an exception."
fi
exit "${FAKE_GRADLE_STATUS:-0}"
"""

# A window of the app that is not its first screen.
ANOTHER_SCREEN = shapes.WINDOW.replace("Welcome Back", "Loading").replace("Sign In", "Please wait")


class Run:
    def __init__(self, status, output, seconds, fake):
        self.status = status
        self.output = output
        self.seconds = seconds
        self.fake = fake

    def _text(self, *parts):
        path = self.fake.joinpath(*parts)
        return path.read_text(encoding="utf-8", errors="replace") if path.is_file() else ""

    @property
    def start_log(self):
        """What the workflow commits to `.ci/` when the job fails."""
        return self._text("tmp", "app-start.log")

    @property
    def run_log(self):
        return self._text("tmp", "device-run.log")

    @property
    def asked(self):
        """Every command the stand-ins were given, in order."""
        return self._text("asked").splitlines()

    @property
    def pids(self):
        return [int(line) for line in self._text("pids").split()]


def alive(pid):
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    # A process that has ended and is not yet collected by its parent still has a number.
    try:
        state = Path(f"/proc/{pid}/stat").read_text().rsplit(")", 1)[1].split()[0]
    except (OSError, IndexError):
        return False
    return state != "Z"


class OnEmulator(unittest.TestCase):
    # For the whole script in one test. Its own limits here are a second or two where one is
    # meant to run out and thirty where none is; the slowest test takes about six seconds.
    ENDS_WITHIN = 45

    def run_script(self, window=shapes.WINDOW, logcat=shapes.QUIET_LOG, **settings):
        fake = Path(tempfile.mkdtemp(prefix="on-emulator-"))
        self.addCleanup(shutil.rmtree, fake, True)
        (fake / "bin").mkdir()
        (fake / "tmp").mkdir()
        for name, text in (("adb", ADB), ("gradle", GRADLE)):
            program = fake / "bin" / name
            program.write_text(text, encoding="utf-8")
            program.chmod(program.stat().st_mode | stat.S_IXUSR)
        (fake / "window.xml").write_text(window, encoding="utf-8")
        (fake / "logcat.txt").write_text(logcat, encoding="utf-8")

        env = {name: value for name, value in os.environ.items() if not name.startswith(("NCHAT_", "FAKE"))}
        env.update(
            PATH=f"{fake / 'bin'}{os.pathsep}{env['PATH']}",
            FAKE=str(fake),
            NCHAT_CI_TMP=str(fake / "tmp"),
            # Generous for whatever answers at once, so that a busy machine fails no test;
            # a test shortens the one limit it means to see run out.
            NCHAT_TOTAL_LIMIT="40",
            NCHAT_TESTS_LIMIT="30",
            NCHAT_INSTALL_LIMIT="30",
            NCHAT_LAUNCH_LIMIT="30",
            NCHAT_ANSWER_LIMIT="30",
            NCHAT_PROBE_LIMIT="30",
            NCHAT_SCREEN_LIMIT="30",
            NCHAT_POLL_SECONDS="0.05",
            NCHAT_SETTLE_SECONDS="0.05",
            NCHAT_KILL_AFTER="1",
        )
        env.update({name: str(value) for name, value in settings.items()})

        began = time.monotonic()
        process = subprocess.Popen(
            ["bash", str(SCRIPT), "24"],
            cwd=ROOT,
            env=env,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            start_new_session=True,
        )
        run = Run(None, "", 0.0, fake)
        self.addCleanup(self.stop, run)
        try:
            output, _ = process.communicate(timeout=self.ENDS_WITHIN)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            self.stop(run)
            try:
                process.communicate(timeout=5)
            except subprocess.TimeoutExpired:
                pass
            self.fail(
                f"on_emulator.sh had not ended after {self.ENDS_WITHIN} s; the last it asked: "
                + " | ".join(run.asked[-3:])
            )
        run.status = process.returncode
        run.output = output
        run.seconds = time.monotonic() - began
        return run

    @staticmethod
    def stop(run):
        for pid in run.pids:
            try:
                os.kill(pid, signal.SIGKILL)
            except (ProcessLookupError, PermissionError):
                pass

    def assert_nothing_left_running(self, run):
        until = time.monotonic() + 3
        left = [pid for pid in run.pids if alive(pid)]
        while left and time.monotonic() < until:
            time.sleep(0.1)
            left = [pid for pid in left if alive(pid)]
        self.assertEqual(left, [], "a command the script started is still running after it ended")

    # ------------------------------------------------------------ all is well

    def test_everything_answers(self):
        run = self.run_script()

        self.assertEqual(run.status, 0, run.output)
        self.assertIn("gradle --no-daemon --stacktrace :app:connectedDebugAndroidTest", run.asked)
        self.assertIn(f"adb install -r {APK}", run.asked)
        self.assertIn("BUILD SUCCESSFUL", run.run_log)
        self.assertIn(STARTED, run.start_log)
        self.assertIn("::notice title=App start on Android API 24::started", run.output)
        self.assertNotIn("no answer", run.output)
        self.assertNotIn(STILL_ANSWERS, run.output)
        self.assertLess(run.seconds, 15, "the thirty seconds for the first screen were waited out although it was there")

    def test_how_long_the_slowest_command_of_each_kind_took_is_said(self):
        # A limit is a statement about the machine. How near a run that passed came to one is
        # only known if it is written where it can be read from outside: a notice on the job.
        run = self.run_script()

        notices = [line for line in run.output.splitlines() if line.startswith("::notice title=Emulator commands on Android API 24::")]
        self.assertEqual(len(notices), 1, run.output)
        parts = notices[0].split("::", 2)[2].split("; ")
        kinds = ["the tests", "the install", "the launch", "the first screen", "the slowest other answer"]
        self.assertEqual(len(parts), len(kinds), notices[0])
        for part, kind in zip(parts, kinds):
            self.assertRegex(part, rf"^{kind} \d+\.\d s of 30 s")
        self.assertRegex(parts[-1], r" \(adb \S.*\)$")

    def test_of_each_kind_it_is_the_slowest_that_is_named(self):
        run = self.run_script(FAKE_ADB_SLOW=1.2, FAKE_ADB_SLOW_AT="uiautomator")

        notice = next(line for line in run.output.splitlines() if line.startswith("::notice title=Emulator commands on Android API 24::"))
        self.assertRegex(notice, r"the slowest other answer 1\.\d s of 30 s \(adb shell uiautomator dump /sdcard/nchat-window\.xml\)$")

    def test_a_wait_that_did_not_happen_is_not_measured(self):
        # With an emulator that went silent before the app was launched there was no wait
        # for the first screen, and the notice does not invent one.
        run = self.run_script(FAKE_ADB_SILENT="pm grant", NCHAT_ANSWER_LIMIT=2)

        notice = next(line for line in run.output.splitlines() if line.startswith("::notice title=Emulator commands on Android API 24::"))
        self.assertIn("the install ", notice)
        self.assertNotIn("the first screen", notice)
        self.assertNotIn("the launch", notice)

    def test_failing_tests_do_not_hide_that_the_app_starts(self):
        run = self.run_script(FAKE_GRADLE_STATUS=1)

        self.assertNotEqual(run.status, 0)
        self.assertIn(STARTED, run.start_log)
        self.assertNotIn("did not end", run.output)

    def test_an_app_that_cannot_be_installed_is_said(self):
        run = self.run_script(FAKE_INSTALL_FAILS=1)

        self.assertNotEqual(run.status, 0)
        self.assertIn(
            "::error title=App start on Android API 24::the app could not be installed: "
            "adb: failed to install app-smoke.apk: Failure [INSTALL_FAILED_OLDER_SDK]",
            run.output,
        )
        # It answered — with a refusal. That is not an emulator that has gone silent.
        self.assertNotIn(STILL_ANSWERS, run.output)
        self.assertNotIn("adb shell am start", " ".join(run.asked))

    # ------------------------------------------------- the emulator goes silent

    def test_an_emulator_that_answers_nothing_ends_the_run(self):
        # Where nothing answers at all no answer can come late, so one second is limit enough.
        run = self.run_script(FAKE_ADB_SILENT=".", NCHAT_INSTALL_LIMIT=1, NCHAT_PROBE_LIMIT=1)

        self.assertNotEqual(run.status, 0)
        unanswered = f"no answer within 1 s: adb install -r {APK}"
        self.assertIn(f"::error title=App start on Android API 24::not judged — {unanswered}", run.output)
        self.assertIn(unanswered, run.start_log)
        self.assertNotIn(STARTED, run.output)
        # What it still answers is asked — and that asking ends too.
        self.assertIn(STILL_ANSWERS, run.start_log)
        self.assertIn("(no answer within 1 s)", run.start_log)
        self.assertIn("nothing more was asked", run.start_log)
        adb = [line for line in run.asked if line.startswith("adb ")]
        self.assertEqual(adb[:2], [f"adb install -r {APK}", "adb devices"])
        self.assertEqual(len(adb), 3, adb)
        self.assert_nothing_left_running(run)

    def test_one_question_without_an_answer_is_named_and_no_other_is_put_before_the_report(self):
        run = self.run_script(FAKE_ADB_SILENT="uiautomator", NCHAT_ANSWER_LIMIT=2, NCHAT_SETTLE_SECONDS=20)

        self.assertNotEqual(run.status, 0)
        unanswered = "no answer within 2 s: adb shell uiautomator dump /sdcard/nchat-window.xml"
        self.assertIn(f"::error title=App start on Android API 24::not judged — {unanswered}", run.output)
        self.assertNotIn(STARTED, run.output)
        # The emulator is there and the app is in front: both are in the report.
        log = run.start_log
        self.assertIn(STILL_ANSWERS, log)
        self.assertIn("emulator-5554\tdevice", log)
        self.assertIn("mResumedActivity: ActivityRecord{1a2b3c u0 com.nchat.app.smoke/com.nchat.app.MainActivity t12}", log)
        self.assertIn("Status: ok", log)
        report = log.split(STILL_ANSWERS, 1)[1]
        self.assertNotIn("(no answer within", report)
        # An answer's empty lines are not kept: the next question follows its last line.
        self.assertIn("emulator-5554\tdevice\n- has it started", report)
        # Android 7 has no `dumpsys activity lastanr` (seen on the emulator, 2026-11-25). What
        # an app that did not respond leaves behind is a file in /data/anr on every version.
        asked_then = " | ".join(run.asked[run.asked.index("adb devices"):])
        self.assertNotIn("lastanr", asked_then)
        self.assertIn("adb shell ls -l /data/anr", asked_then)
        # The app's own lines of the log were never fetched, so there is no heading for them.
        self.assertNotIn("--- what the log says of the app", log)
        # Nothing is asked between the question that went unanswered and the report.
        asked = run.asked
        at = asked.index("adb shell uiautomator dump /sdcard/nchat-window.xml")
        self.assertEqual(asked[at + 1], "adb devices", asked[at:at + 3])
        # And neither the thirty seconds for the first screen nor the twenty after it are
        # waited out first.
        self.assertLess(run.seconds, 15)
        self.assert_nothing_left_running(run)

    def test_a_command_that_will_not_be_stopped_is_killed(self):
        run = self.run_script(FAKE_ADB_SILENT="uiautomator", FAKE_DEAF=1, NCHAT_ANSWER_LIMIT=2, NCHAT_KILL_AFTER=1)

        self.assertNotEqual(run.status, 0)
        self.assertIn("no answer within 2 s: adb shell uiautomator dump /sdcard/nchat-window.xml", run.start_log)
        self.assertIn(STILL_ANSWERS, run.start_log)
        self.assert_nothing_left_running(run)

    def test_an_emulator_that_answers_nothing_and_will_not_be_stopped(self):
        run = self.run_script(FAKE_ADB_SILENT=".", FAKE_DEAF=1, NCHAT_INSTALL_LIMIT=1, NCHAT_PROBE_LIMIT=1, NCHAT_KILL_AFTER=1)

        self.assertNotEqual(run.status, 0)
        self.assertIn(f"no answer within 1 s: adb install -r {APK}", run.start_log)
        self.assertEqual(run.start_log.count("(no answer within 1 s)"), 2)
        self.assert_nothing_left_running(run)

    def test_a_launch_has_a_limit_of_its_own(self):
        run = self.run_script(FAKE_ADB_SILENT="am start", NCHAT_LAUNCH_LIMIT=2)

        self.assertNotEqual(run.status, 0)
        self.assertIn(
            "::error title=App start on Android API 24::not judged — no answer within 2 s: "
            "adb shell am start -W -n com.nchat.app.smoke/com.nchat.app.MainActivity",
            run.output,
        )
        self.assertLess(run.seconds, 15)

    def test_a_question_unanswered_at_the_very_end_leaves_the_start_unjudged(self):
        # Everything was read and the first screen was there; only the last look at the
        # processes got no answer. What the judge would say then is "started" — of an
        # emulator that has just gone silent. It is not asked.
        run = self.run_script(FAKE_ADB_SILENT="ps -A", NCHAT_ANSWER_LIMIT=2, NCHAT_PROBE_LIMIT=2)

        self.assertNotEqual(run.status, 0)
        self.assertIn(
            "::error title=App start on Android API 24::not judged — no answer within 2 s: adb shell ps -A 2> /dev/null || ps",
            run.output,
        )
        self.assertNotIn("::notice title=App start", run.output)
        self.assertNotIn(STARTED, run.output)

    def test_two_silences_that_are_not_in_a_row_do_not_end_the_asking(self):
        # The emulator answers some questions and not others. Only two in a row without an
        # answer mean that it is gone; until then every question is put.
        run = self.run_script(FAKE_ADB_SILENT="uiautomator|^devices$|data/anr", NCHAT_ANSWER_LIMIT=2, NCHAT_PROBE_LIMIT=2)

        self.assertNotEqual(run.status, 0)
        report = run.start_log.split(STILL_ANSWERS, 1)[1]
        self.assertEqual(report.count("(no answer within 2 s)"), 2)
        self.assertNotIn("nothing more was asked", report)
        self.assertIn("adb logcat -d -t 3000", run.asked)

    def test_the_screen_as_it_was_last_read_is_in_the_report(self):
        # The second look at the screen goes unanswered; what the first one read is what
        # was in front when the emulator went silent — a system dialog, say.
        run = self.run_script(
            window=shapes.PERMISSION_DIALOG,
            FAKE_ADB_SILENT="^logcat -d$",
            NCHAT_ANSWER_LIMIT=2,
        )

        self.assertNotEqual(run.status, 0)
        self.assertIn("no answer within 2 s: adb logcat -d", run.start_log)
        self.assertIn("com.android.permissioncontroller: Allow NChat to send you notifications?", run.start_log)

    # -------------------------------------------------- the tests do not end

    def test_a_test_run_that_does_not_end_is_stopped_and_the_app_is_started_all_the_same(self):
        run = self.run_script(FAKE_GRADLE_SILENT=1, NCHAT_TESTS_LIMIT=2)

        self.assertNotEqual(run.status, 0)
        said = "the test run did not end within 2 s and was stopped"
        self.assertIn(f"::error title=Encryption tests on Android API 24::{said}", run.output)
        self.assertIn("The test run did not end within 2 s and was stopped.", run.run_log)
        log = run.start_log
        self.assertIn(said, log)
        # Where it was: the end of what it had written, and what the emulator answered then.
        self.assertIn("Starting 290 tests on test(AVD) - 7.0", log)
        self.assertIn(STILL_ANSWERS, log)
        self.assertIn("emulator-5554\tdevice", log)
        # A test run that hangs must not hide whether the app starts.
        self.assertIn(STARTED, log)
        self.assertIn("::notice title=App start on Android API 24::started", run.output)
        self.assertLess(run.seconds, 20, "the test run was given more than its two seconds")
        self.assert_nothing_left_running(run)

    def test_a_test_run_stopped_by_the_limit_of_the_whole_run_says_that_limit(self):
        # Thirty seconds of its own and two for the whole run: two is what it got, and two
        # is what the report says. Nothing is left for the app's start, and that is said too.
        run = self.run_script(FAKE_GRADLE_SILENT=1, NCHAT_TESTS_LIMIT=30, NCHAT_TOTAL_LIMIT=2)

        self.assertNotEqual(run.status, 0)
        self.assertIn("::error title=Encryption tests on Android API 24::the test run did not end within 2 s and was stopped", run.output)
        self.assertIn(
            f"::error title=App start on Android API 24::not judged — the 2 s for the whole run were used up before: adb install -r {APK}",
            run.output,
        )
        self.assertNotIn(f"adb install -r {APK}", run.asked)

    def test_a_test_run_and_an_emulator_that_both_go_silent(self):
        # The emulator is gone, so nothing ends: not the test run, not a question about it.
        # Twice the script comes to a limit, and twice it asks what there is left to ask.
        run = self.run_script(FAKE_GRADLE_SILENT=1, FAKE_ADB_SILENT=".", NCHAT_TESTS_LIMIT=1, NCHAT_INSTALL_LIMIT=1, NCHAT_PROBE_LIMIT=1)

        self.assertNotEqual(run.status, 0)
        self.assertIn("::error title=Encryption tests on Android API 24::the test run did not end within 1 s and was stopped", run.output)
        self.assertIn(f"::error title=App start on Android API 24::not judged — no answer within 1 s: adb install -r {APK}", run.output)
        self.assertEqual(run.start_log.count(STILL_ANSWERS), 2)
        self.assertEqual(run.start_log.count("nothing more was asked"), 2)
        adb = [line for line in run.asked if line.startswith("adb ")]
        self.assertEqual(len(adb), 5, adb)
        self.assert_nothing_left_running(run)

    def test_a_test_run_that_was_killed_has_failed_and_not_gone_unanswered(self):
        # Killed before its time was up — by a machine out of memory, say. That is a test
        # run that failed, and its own log says how; it is not an emulator gone silent.
        run = self.run_script(FAKE_GRADLE_KILLED=1)

        self.assertNotEqual(run.status, 0)
        self.assertNotIn("did not end", run.output)
        self.assertNotIn(STILL_ANSWERS, run.output)
        self.assertIn(STARTED, run.start_log)

    # ------------------------------------------------------ waiting has an end

    def test_a_first_screen_that_never_comes_is_waited_for_only_so_long(self):
        run = self.run_script(window=ANOTHER_SCREEN, NCHAT_SCREEN_LIMIT=2)

        self.assertNotEqual(run.status, 0)
        self.assertIn("::error title=App start on Android API 24::the first screen lacks 'Welcome Back', 'Sign In'", run.output)
        self.assertNotIn("no answer", run.output)
        self.assertLess(run.seconds, 20)

    def test_a_crash_ends_the_wait(self):
        run = self.run_script(window=ANOTHER_SCREEN, logcat=shapes.CRASH_LOG, NCHAT_SCREEN_LIMIT=30)

        self.assertNotEqual(run.status, 0)
        self.assertIn("::error title=App start on Android API 24::crashed: java.lang.NoClassDefFoundError", run.output)
        self.assertLess(run.seconds, 15, "the thirty seconds for the first screen were waited out after a crash")
        # The log the workflow commits ends with the app's own lines of the emulator's log.
        own = run.start_log.split("--- what the log says of the app\n", 1)
        self.assertEqual(len(own), 2, run.start_log)
        self.assertIn("AndroidRuntime: FATAL EXCEPTION: main", own[1])
        self.assertIn("at com.nchat.app.network.ApiClient.<init>(ApiClient.kt:41)", own[1])

    def test_the_whole_run_has_a_limit_of_its_own(self):
        # No command is slow enough to run into its own limit, and together they still take
        # for ever: an emulator that crawls. The run ends when its own time is up.
        run = self.run_script(window=ANOTHER_SCREEN, FAKE_ADB_SLOW=0.4, NCHAT_TOTAL_LIMIT=3, NCHAT_SCREEN_LIMIT=30)

        self.assertNotEqual(run.status, 0)
        self.assertIn("the 3 s for the whole run were used up", run.start_log)
        self.assertIn("::error title=App start on Android API 24::not judged — ", run.output)
        self.assertIn(STILL_ANSWERS, run.start_log)
        self.assertLess(run.seconds, 25)
        # Three seconds are room for seven or eight answers of 0.4 s, and not for twice as many.
        adb = [line for line in run.asked if line.startswith("adb ")]
        self.assertLessEqual(adb.index("adb devices"), 10, adb)

    def test_a_command_is_given_no_more_than_the_time_the_run_has_left(self):
        # Thirty seconds of its own, and the run has three: it is stopped when the run's
        # time is up, and the report says that this was the limit.
        run = self.run_script(FAKE_ADB_SILENT="uiautomator", NCHAT_TOTAL_LIMIT=3, NCHAT_ANSWER_LIMIT=30)

        self.assertNotEqual(run.status, 0)
        self.assertIn(
            "::error title=App start on Android API 24::not judged — the 3 s for the whole run were used up during: "
            "adb shell uiautomator dump /sdcard/nchat-window.xml",
            run.output,
        )
        self.assertLess(run.seconds, 15)

    # --------------------------------------------------------------- the drill

    def test_the_drill_lets_a_command_go_unanswered_without_asking_the_emulator(self):
        # How it is tried on a real emulator that a stuck job ends red and leaves its log:
        # NCHAT_DRILL_STUCK names a word, and a command with that word is not run — the
        # script waits in its place for an answer that does not come.
        run = self.run_script(NCHAT_DRILL_STUCK="uiautomator", NCHAT_ANSWER_LIMIT=2)

        self.assertNotEqual(run.status, 0)
        self.assertIn("no answer within 2 s: adb shell uiautomator dump /sdcard/nchat-window.xml", run.start_log)
        self.assertNotIn("uiautomator", " ".join(run.asked))
        self.assertIn("emulator-5554\tdevice", run.start_log)
        self.assertNotIn(STARTED, run.output)

    # ------------------------------------------------------------ the workflow

    def test_the_workflow_leaves_time_to_report(self):
        # A stuck job has to end red with its log, and not when somebody cancels it: a job
        # that runs into its own limit is cancelled, and the step that commits the log does
        # not run then. So the longest the script can take has to fit into the limit of its
        # step, and the steps that can take long into the limit of the job, with time left
        # for the two that report.
        script = SCRIPT.read_text(encoding="utf-8")

        def default(name):
            found = re.search(rf'^{name}="\$\{{NCHAT_{name}:-(\d+)\}}"', script, re.MULTILINE)
            self.assertIsNotNone(found, f"{name} has no default in on_emulator.sh")
            return int(found.group(1))

        probes = len(re.findall(r"^\s*probe \"", script, re.MULTILINE))
        self.assertGreaterEqual(probes, 4)
        kill = default("KILL_AFTER")
        longest = (
            default("TOTAL_LIMIT") + kill                     # the last command may be stopped at the very end
            + default("POLL_SECONDS") + default("SETTLE_SECONDS")
            + 2 * probes * (default("PROBE_LIMIT") + kill)    # what it still answers is asked at most twice
        )

        workflow = WORKFLOW.read_text(encoding="utf-8")

        def minutes(pattern, what):
            found = re.search(pattern, workflow, re.MULTILINE)
            self.assertIsNotNone(found, f"{what} has no time limit")
            return int(found.group(1)) * 60

        def of_step(name):
            return rf"- name: {name}\n(?:        .*\n)*?        timeout-minutes: (\d+)\n"

        job = minutes(r"^    timeout-minutes: (\d+)$", "the job")
        build = minutes(of_step("Build the app and its tests"), "the step that builds")
        emulator = minutes(of_step("Run the tests on the emulator"), "the step that runs the emulator")
        boot = re.search(r"^          emulator-boot-timeout: (\d+)$", workflow, re.MULTILINE)
        self.assertIsNotNone(boot, "how long the emulator may take to start is not said")

        # Five minutes for the emulator to be fetched before it starts and taken down after.
        self.assertLessEqual(
            longest + int(boot.group(1)) + 300, emulator,
            "on_emulator.sh can take longer than its step allows: the step would be stopped before the script has reported",
        )
        # Ten minutes for what comes before the build and for the two steps that report.
        self.assertLessEqual(
            build + emulator + 600, job,
            "the job can run into its own limit: it would be cancelled, and a cancelled job commits no log",
        )


if __name__ == "__main__":
    unittest.main()
