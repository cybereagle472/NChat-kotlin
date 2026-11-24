"""Tests for app_start_check.py — the judge of "did the app start on the emulator"."""
import unittest

import app_start_check as check

PACKAGE = "com.nchat.app.smoke"

WINDOW = """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<hierarchy rotation="0">
  <node index="0" text="" class="android.widget.FrameLayout" package="com.nchat.app.smoke" content-desc="" bounds="[0,0][1080,1920]">
    <node index="0" text="Welcome Back" class="android.widget.TextView" package="com.nchat.app.smoke" content-desc="" bounds="[100,300][980,380]" />
    <node index="1" text="" class="android.view.View" package="com.nchat.app.smoke" content-desc="NChat logo" bounds="[440,120][640,280]" />
    <node index="2" text="Sign In" class="android.widget.TextView" package="com.nchat.app.smoke" content-desc="" bounds="[100,900][980,980]" />
  </node>
</hierarchy>
"""

PERMISSION_DIALOG = """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<hierarchy rotation="0">
  <node index="0" text="" class="android.widget.FrameLayout" package="com.android.permissioncontroller" content-desc="" bounds="[0,0][1080,1920]">
    <node index="0" text="Allow NChat to send you notifications?" class="android.widget.TextView" package="com.android.permissioncontroller" content-desc="" bounds="[100,800][980,900]" />
    <node index="1" text="Allow" class="android.widget.Button" package="com.android.permissioncontroller" content-desc="" bounds="[100,1000][980,1100]" />
  </node>
</hierarchy>
"""

QUIET_LOG = """11-23 10:00:01.100  1890  1890 I ActivityManager: Start proc 4321:com.nchat.app.smoke/u0a99 for activity com.nchat.app.smoke/com.nchat.app.MainActivity
11-23 10:00:02.500  4321  4321 D NChat   : started
"""

CRASH_LOG = """11-23 10:00:01.100  1890  1890 I ActivityManager: Start proc 4321:com.nchat.app.smoke/u0a99 for activity
11-23 10:00:02.700  4321  4321 E AndroidRuntime: FATAL EXCEPTION: main
11-23 10:00:02.700  4321  4321 E AndroidRuntime: Process: com.nchat.app.smoke, PID: 4321
11-23 10:00:02.700  4321  4321 E AndroidRuntime: java.lang.NoClassDefFoundError: Failed resolution of: Lokhttp3/internal/platform/Platform;
11-23 10:00:02.700  4321  4321 E AndroidRuntime: 	at com.nchat.app.network.ApiClient.<init>(ApiClient.kt:41)
11-23 10:00:02.700  4321  4321 E AndroidRuntime: 	at com.nchat.app.data.ServiceLocator.init(ServiceLocator.kt:107)
11-23 10:00:02.800  1890  1901 W ActivityManager:   Force finishing activity com.nchat.app.smoke/com.nchat.app.MainActivity
"""

SOMEBODY_ELSES_CRASH = """11-23 10:00:02.700  5555  5555 E AndroidRuntime: FATAL EXCEPTION: main
11-23 10:00:02.700  5555  5555 E AndroidRuntime: Process: com.android.phone, PID: 5555
11-23 10:00:02.700  5555  5555 E AndroidRuntime: java.lang.IllegalStateException: not ours
11-23 10:00:03.000  6666  6666 E AndroidRuntime: FATAL EXCEPTION: main
11-23 10:00:03.000  6666  6666 E AndroidRuntime: Process: com.nchat.app.smoke.other, PID: 6666
11-23 10:00:03.000  6666  6666 E AndroidRuntime: java.lang.IllegalStateException: a longer name is another app
"""


def judge(**changes):
    facts = dict(
        package=PACKAGE,
        pid_text="4321\n",
        process_list="u0_a99    4321  1234  1 S com.nchat.app.smoke\n",
        logcat=QUIET_LOG,
        window=WINDOW,
        resumed="mResumedActivity: ActivityRecord{1a2b3c u0 com.nchat.app.smoke/com.nchat.app.MainActivity t12}",
        expected=["Welcome Back", "Sign In"],
    )
    facts.update(changes)
    return check.judge(**facts)


class ScreenTexts(unittest.TestCase):
    def test_reads_what_is_written_and_what_is_described(self):
        self.assertEqual(
            check.screen_texts(WINDOW),
            [("com.nchat.app.smoke", "Welcome Back"), ("com.nchat.app.smoke", "NChat logo"), ("com.nchat.app.smoke", "Sign In")],
        )

    def test_a_file_that_is_not_a_screen_reads_as_no_screen(self):
        self.assertIsNone(check.screen_texts(""))
        self.assertIsNone(check.screen_texts("ERROR: could not get idle state."))
        self.assertIsNone(check.screen_texts("<hierarchy><node"))
        self.assertEqual(check.screen_texts("<hierarchy rotation='0'></hierarchy>"), [])


class Crashes(unittest.TestCase):
    def test_finds_the_crash_of_the_app_with_what_it_says(self):
        found = check.crashes(CRASH_LOG, PACKAGE)
        self.assertEqual(len(found), 1)
        self.assertIn("NoClassDefFoundError", found[0])
        self.assertIn("ServiceLocator.init", found[0])
        self.assertNotIn("Force finishing", found[0])
        self.assertNotIn("11-23", found[0], "the crash is quoted without the log's own columns")

    def test_the_crash_of_another_app_is_not_this_one(self):
        self.assertEqual(check.crashes(SOMEBODY_ELSES_CRASH, PACKAGE), [])
        self.assertEqual(check.crashes(QUIET_LOG, PACKAGE), [])

    def test_a_crash_in_native_code_counts(self):
        log = (
            "11-23 10:00:03.000  4321  4400 F libc    : Fatal signal 11 (SIGSEGV), code 1, fault addr 0x0 in tid 4400 (signaling_thread)\n"
            "11-23 10:00:03.100  4500  4500 F DEBUG   : pid: 4321, tid: 4400, name: signaling_thread  >>> com.nchat.app.smoke <<<\n"
            "11-23 10:00:03.100  4500  4500 F DEBUG   : backtrace:\n"
            "11-23 10:00:03.100  4500  4500 F DEBUG   :     #00 pc 0001a2b3  /data/app/lib/x86/libjingle_peerconnection_so.so\n"
        )
        found = check.crashes(log, PACKAGE)
        self.assertEqual(len(found), 1)
        self.assertIn("libjingle_peerconnection_so", found[0])
        other = log.replace(">>> com.nchat.app.smoke <<<", ">>> com.android.systemui <<<")
        self.assertEqual(check.crashes(other, PACKAGE), [])

    def test_an_app_that_stopped_answering_counts(self):
        log = "11-23 10:00:20.000  1890  1901 E ActivityManager: ANR in com.nchat.app.smoke (com.nchat.app.smoke/com.nchat.app.MainActivity)\n" \
              "11-23 10:00:20.000  1890  1901 E ActivityManager: Reason: Input dispatching timed out\n"
        found = check.crashes(log, PACKAGE)
        self.assertEqual(len(found), 1)
        self.assertIn("Input dispatching timed out", found[0])
        self.assertEqual(check.crashes(log.replace("com.nchat.app.smoke", "com.android.settings"), PACKAGE), [])


class Judge(unittest.TestCase):
    def test_an_app_that_started_and_shows_its_first_screen_passes(self):
        ok, summary, details = judge()
        self.assertTrue(ok, details)
        self.assertIn("started", summary)
        self.assertIn("Welcome Back", summary)

    def test_a_crash_fails_and_is_quoted(self):
        ok, summary, details = judge(logcat=CRASH_LOG, pid_text="", process_list="", window="")
        self.assertFalse(ok)
        self.assertIn("crashed", summary)
        self.assertIn("NoClassDefFoundError", summary, "the notice alone says what kind of crash it was")
        self.assertIn("ServiceLocator.init", details)

    def test_a_crash_fails_even_when_the_app_came_back_up(self):
        # Android starts a crashed app again; "it is running" must not hide that it fell over.
        ok, summary, _ = judge(logcat=CRASH_LOG)
        self.assertFalse(ok)
        self.assertIn("crashed", summary)

    def test_an_app_that_is_gone_fails_without_a_crash_in_the_log(self):
        ok, summary, _ = judge(pid_text="\n", process_list="u0_a12  900  1 S com.android.phone\n", window="", resumed="")
        self.assertFalse(ok)
        self.assertIn("not running", summary)

    def test_the_process_list_is_enough_where_pidof_is_missing(self):
        ok, _, details = judge(pid_text="/system/bin/sh: pidof: not found\n")
        self.assertTrue(ok, details)
        # …but a longer package name in the list is another app.
        ok, summary, _ = judge(pid_text="", process_list="u0_a99  4321  1 S com.nchat.app.smoke.other\n", window="", resumed="")
        self.assertFalse(ok)
        self.assertIn("not running", summary)

    def test_a_first_screen_without_its_words_fails_and_says_what_was_there(self):
        ok, summary, details = judge(window=WINDOW.replace("Welcome Back", "Something went wrong"))
        self.assertFalse(ok)
        self.assertIn("Welcome Back", summary)
        self.assertIn("Something went wrong", details)

    def test_another_apps_window_in_front_fails_and_is_named(self):
        ok, summary, details = judge(window=PERMISSION_DIALOG, resumed="")
        self.assertFalse(ok)
        self.assertIn("com.android.permissioncontroller", summary)
        self.assertIn("Allow NChat to send you notifications?", details)

    def test_a_screen_that_could_not_be_read_is_said_not_passed_off(self):
        # The tool that reads the screen gives up on some systems. With the app running, in
        # front, and nothing in the log, that is not a failure of the app — and not proof either.
        ok, summary, _ = judge(window="ERROR: could not get idle state.")
        self.assertTrue(ok)
        self.assertIn("could not be read", summary)
        self.assertNotIn("shows", summary)
        # Without the app in front there is nothing left that says it started.
        ok, summary, _ = judge(window="", resumed="mResumedActivity: ActivityRecord{9 u0 com.android.launcher3/.Launcher t1}")
        self.assertFalse(ok)
        self.assertIn("not in front", summary)

    def test_the_summary_is_one_line(self):
        for facts in ({}, {"logcat": CRASH_LOG}, {"window": PERMISSION_DIALOG}, {"pid_text": "", "process_list": ""}):
            _, summary, _ = judge(**facts)
            self.assertNotIn("\n", summary)
            self.assertLess(len(summary), 400)


if __name__ == "__main__":
    unittest.main()
