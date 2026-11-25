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
# never returns (and with FAKE_DEAF does not let itself be stopped either).
ADB = r"""#!/usr/bin/env bash
echo "$$" >> "$FAKE/pids"
echo "adb $*" >> "$FAKE/asked"
if [ -n "${FAKE_ADB_SILENT:-}" ] && [[ "$*" =~ $FAKE_ADB_SILENT ]]; then
  [ -n "${FAKE_DEAF:-}" ] && trap '' TERM
  exec sleep 600
fi
[ -n "${FAKE_ADB_SLOW:-}" ] && sleep "$FAKE_ADB_SLOW"
case "$*" in
  devices) printf 'List of devices attached\nemulator-5554\tdevice\n' ;;
  "install -r "*)
    if [ -n "${FAKE_INSTALL_FAILS:-}" ]; then
      printf 'Performing Streamed Install\nadb: failed to install app-smoke.apk: Failure [INSTALL_FAILED_OLDER_SDK]\n'
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
  "shell dumpsys activity lastanr"*) echo "  <no ANR has occurred since boot>" ;;
  "logcat -d"*) cat "$FAKE/logcat.txt" ;;
esac
exit 0
"""

# Stands in for gradle: with FAKE_GRADLE_SILENT it starts the tests and never returns.
GRADLE = r"""#!/usr/bin/env bash
echo "$$" >> "$FAKE/pids"
echo "gradle $*" >> "$FAKE/asked"
echo "> Task :app:connectedDebugAndroidTest"
if [ -n "${FAKE_GRADLE_SILENT:-}" ]; then
  echo "Starting 290 tests on test(AVD) - 7.0"
  exec sleep 600
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
    # For the whole script in one test. Its own limits here are two seconds where one is
    # meant to run out and thirty where none is; the slowest test takes about eight seconds.
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

    def test_the_commands_closest_to_their_limits_are_named(self):
        # A limit is a statement about the machine. How near a run that passed came to one is
        # only known if it is written where it can be read from outside: a notice on the job.
        run = self.run_script()

        notices = [line for line in run.output.splitlines() if line.startswith("::notice title=Emulator commands on Android API 24::")]
        self.assertEqual(len(notices), 1, run.output)
        said = notices[0].split("::", 2)[2]
        self.assertTrue(said.startswith("closest to their limits — "), said)
        entries = said.split(" — ", 1)[1].split("; ")
        self.assertLessEqual(len(entries), 3, said)
        for entry in entries:
            self.assertRegex(entry, r"^\d+\.\d s of 30 s: (adb|gradle) \S")

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
        run = self.run_script(FAKE_ADB_SILENT=".", NCHAT_INSTALL_LIMIT=2, NCHAT_PROBE_LIMIT=2)

        self.assertNotEqual(run.status, 0)
        unanswered = f"no answer within 2 s: adb install -r {APK}"
        self.assertIn(f"::error title=App start on Android API 24::not judged — {unanswered}", run.output)
        self.assertIn(unanswered, run.start_log)
        self.assertNotIn(STARTED, run.output)
        # What it still answers is asked — and that asking ends too.
        self.assertIn(STILL_ANSWERS, run.start_log)
        self.assertIn("(no answer within 2 s)", run.start_log)
        self.assertIn("nothing more was asked", run.start_log)
        adb = [line for line in run.asked if line.startswith("adb ")]
        self.assertEqual(adb[:2], [f"adb install -r {APK}", "adb devices"])
        self.assertEqual(len(adb), 3, adb)
        self.assert_nothing_left_running(run)

    def test_one_question_without_an_answer_is_named_and_no_other_is_put_before_the_report(self):
        run = self.run_script(FAKE_ADB_SILENT="uiautomator", NCHAT_ANSWER_LIMIT=2)

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
        self.assertNotIn("(no answer within", log.split(STILL_ANSWERS, 1)[1])
        # Nothing is asked between the question that went unanswered and the report.
        asked = run.asked
        at = asked.index("adb shell uiautomator dump /sdcard/nchat-window.xml")
        self.assertEqual(asked[at + 1], "adb devices", asked[at:at + 3])
        self.assert_nothing_left_running(run)

    def test_a_command_that_will_not_be_stopped_is_killed(self):
        run = self.run_script(FAKE_ADB_SILENT="uiautomator", FAKE_DEAF=1, NCHAT_ANSWER_LIMIT=2, NCHAT_KILL_AFTER=1)

        self.assertNotEqual(run.status, 0)
        self.assertIn("no answer within 2 s: adb shell uiautomator dump /sdcard/nchat-window.xml", run.start_log)
        self.assertIn(STILL_ANSWERS, run.start_log)
        self.assert_nothing_left_running(run)

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
        self.assert_nothing_left_running(run)

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

    def test_the_whole_run_has_a_limit_of_its_own(self):
        # No command is slow enough to run into its own limit, and together they still take
        # for ever: an emulator that crawls. The run ends when its own time is up.
        run = self.run_script(window=ANOTHER_SCREEN, FAKE_ADB_SLOW=0.4, NCHAT_TOTAL_LIMIT=3, NCHAT_SCREEN_LIMIT=30)

        self.assertNotEqual(run.status, 0)
        self.assertIn("the 3 s for the whole run were used up", run.start_log)
        self.assertIn("::error title=App start on Android API 24::not judged — ", run.output)
        self.assertIn(STILL_ANSWERS, run.start_log)
        self.assertLess(run.seconds, 25)

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
        # A stuck job has to end red with its log, and not when somebody cancels it. So
        # the longest the script can take, plus the emulator's own start, has to fit into
        # the step's limit — and the step's limit into the job's, with time left for the
        # two steps that report.
        script = SCRIPT.read_text(encoding="utf-8")

        def default(name):
            found = re.search(rf'^{name}="\$\{{NCHAT_{name}:-(\d+)\}}"', script, re.MULTILINE)
            self.assertIsNotNone(found, f"{name} has no default in on_emulator.sh")
            return int(found.group(1))

        probes = len(re.findall(r"^\s*probe \"", script, re.MULTILINE))
        self.assertGreaterEqual(probes, 4)
        kill = default("KILL_AFTER")
        longest = (
            default("TOTAL_LIMIT") + kill              # the last command may be stopped at the very end
            + default("POLL_SECONDS") + default("SETTLE_SECONDS")
            + 2 * probes * (default("PROBE_LIMIT") + kill)   # what it still answers: asked at most twice
        )

        workflow = WORKFLOW.read_text(encoding="utf-8")
        job = re.search(r"^    timeout-minutes: (\d+)$", workflow, re.MULTILINE)
        step = re.search(
            r"- name: Run the tests on the emulator\n(?:        .*\n)*?        timeout-minutes: (\d+)\n",
            workflow,
        )
        self.assertIsNotNone(job, "the job has no time limit")
        self.assertIsNotNone(step, "the step that runs the emulator has no time limit of its own")
        job_seconds, step_seconds = int(job.group(1)) * 60, int(step.group(1)) * 60
        boot = 600  # reactivecircus/android-emulator-runner gives the emulator ten minutes to start
        self.assertLess(longest + boot, step_seconds, "the script can take longer than the step allows")
        self.assertLessEqual(step_seconds + 300, job_seconds, "no time is left for the steps that report")


if __name__ == "__main__":
    unittest.main()
