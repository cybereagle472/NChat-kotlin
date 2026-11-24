import tempfile
import unittest
from pathlib import Path

from device_test_report import report, slowest, slowest_line, summarise

PASSING = """<?xml version='1.0' encoding='UTF-8' ?>
<testsuite name="com.nchat.app.network" tests="3" failures="0" errors="0" skipped="1">
  <testcase name="opens" classname="com.nchat.app.network.E2eeProtocolTest" time="0.1" />
  <testcase name="seals" classname="com.nchat.app.network.E2eeProtocolTest" time="0.1" />
  <testcase name="later" classname="com.nchat.app.network.E2eeGroupTest" time="0.0"><skipped /></testcase>
</testsuite>
"""

FAILING = """<?xml version='1.0' encoding='UTF-8' ?>
<testsuite name="com.nchat.app.network" tests="2" failures="1" errors="0" skipped="0">
  <testcase name="opens" classname="com.nchat.app.network.E2eeProtocolTest" time="0.1" />
  <testcase name="keystore" classname="com.nchat.app.network.KeystoreStorageDeviceTest" time="0.2">
    <failure>java.lang.AssertionError: the Android Keystore is usable on this system
	at org.junit.Assert.fail(Assert.java:89)</failure>
  </testcase>
</testsuite>
"""


class DeviceTestReportTest(unittest.TestCase):
    def results(self, *documents):
        folder = Path(tempfile.mkdtemp())
        for index, document in enumerate(documents):
            target = folder / "connected" / "debug"
            target.mkdir(parents=True, exist_ok=True)
            (target / f"TEST-{index}.xml").write_text(document, encoding="utf-8")
        return folder

    def test_a_clean_run_is_counted_and_passes(self):
        ok, summary, text = report(self.results(PASSING), "24", [])
        self.assertTrue(ok)
        self.assertEqual("3 tests, 0 failed, 1 skipped on Android API 24", summary)
        self.assertNotIn("FAILED", text)

    def test_a_failure_is_named_with_its_message(self):
        ok, summary, text = report(self.results(PASSING, FAILING), "34", [])
        self.assertFalse(ok)
        self.assertEqual("5 tests, 1 failed, 1 skipped on Android API 34", summary)
        self.assertIn("FAILED com.nchat.app.network.KeystoreStorageDeviceTest.keystore", text)
        self.assertIn("the Android Keystore is usable on this system", text)

    def test_a_run_in_which_nothing_ran_does_not_pass(self):
        log = Path(tempfile.mkdtemp()) / "device-run.log"
        log.write_text("> Task :app:compileDebugAndroidTestKotlin FAILED\ne: file.kt:3:1 Unresolved reference: nope\nlast line\n", encoding="utf-8")
        ok, summary, text = report(self.results(), "24", [str(log), "/nonexistent.log"])
        self.assertFalse(ok)
        self.assertEqual("0 tests, 0 failed, 0 skipped on Android API 24", summary)
        self.assertIn("e: file.kt:3:1 Unresolved reference: nope", text)
        self.assertIn("last line", text)

    def test_a_result_file_that_cannot_be_read_is_a_failure(self):
        tests, skipped, failures = summarise(self.results("<testsuite><testcase"))
        self.assertEqual((0, 0), (tests, skipped))
        self.assertEqual(1, len(failures))

    def test_the_slowest_tests_are_named_with_their_time(self):
        # A time bound that holds on a desktop can fail on an old phone: how long the
        # slow ones took belongs in the report of every run, not only of a failed one.
        slow = PASSING.replace('name="seals" classname="com.nchat.app.network.E2eeProtocolTest" time="0.1"',
                               'name="seals" classname="com.nchat.app.network.E2eeProtocolTest" time="11.136"')
        results = self.results(slow, FAILING)
        self.assertEqual(
            [("E2eeProtocolTest.seals", 11.136), ("KeystoreStorageDeviceTest.keystore", 0.2)],
            slowest(results, 2),
        )
        self.assertEqual(
            "slowest: E2eeProtocolTest.seals 11.1 s, KeystoreStorageDeviceTest.keystore 0.2 s",
            slowest_line(results, 2),
        )

    def test_a_time_that_cannot_be_read_counts_as_none(self):
        odd = PASSING.replace('name="opens" classname="com.nchat.app.network.E2eeProtocolTest" time="0.1"',
                              'name="opens" classname="com.nchat.app.network.E2eeProtocolTest" time="soon"')
        self.assertEqual([("E2eeProtocolTest.seals", 0.1)], slowest(self.results(odd), 1))
        self.assertEqual("", slowest_line(self.results(), 3))
        self.assertEqual("", slowest_line(self.results("<testsuite><testcase"), 3))


if __name__ == "__main__":
    unittest.main()
