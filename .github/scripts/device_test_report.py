"""What the emulator run did, said where it can be read.

The tests run inside an emulator and their logs stay on the build machine. This
reads the JUnit XML that Gradle writes for a connected test run and says, as a
notice on the job, how many tests ran; when something failed — or nothing ran
at all — it writes the names, the messages and the end of the build logs to
/tmp/device-report.log, which the workflow commits to `.ci/`.

    device_test_report.py <results dir> <api level> [<log> ...]

Exit status: 0 only when at least one test ran and none failed.
"""
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

REPORT = Path("/tmp/device-report.log")
LOG_TAIL_LINES = 120
DETAIL_LINES = 12


def summarise(results_dir):
    """(tests, skipped, failures) over every JUnit XML under results_dir; failures as (name, text)."""
    tests = 0
    skipped = 0
    failures = []
    for path in sorted(Path(results_dir).rglob("*.xml")):
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError:
            failures.append((path.name, "the result file could not be read"))
            continue
        for case in root.iter("testcase"):
            tests += 1
            name = f"{case.get('classname', '?')}.{case.get('name', '?')}"
            if case.find("skipped") is not None:
                skipped += 1
            for kind in ("failure", "error"):
                for problem in case.findall(kind):
                    text = (problem.get("message") or "") + "\n" + (problem.text or "")
                    failures.append((name, "\n".join(text.strip().splitlines()[:DETAIL_LINES])))
    return tests, skipped, failures


def interesting(log_text):
    """The lines of a Gradle log that say what went wrong, then its end."""
    lines = log_text.splitlines()
    marked = [line for line in lines if line.startswith(("e: ", "FAILURE", "* What went wrong")) or "FAILED" in line]
    return marked[:60] + ["…"] + lines[-LOG_TAIL_LINES:]


def report(results_dir, api_level, logs):
    tests, skipped, failures = summarise(results_dir)
    ok = tests > 0 and not failures
    summary = f"{tests} tests, {len(failures)} failed, {skipped} skipped on Android API {api_level}"
    out = [summary, ""]
    for name, text in failures:
        out += [f"FAILED {name}", text, ""]
    if not ok:
        for log in logs:
            path = Path(log)
            if path.is_file():
                out += [f"--- {path.name}", *interesting(path.read_text(encoding="utf-8", errors="replace")), ""]
    return ok, summary, "\n".join(out) + "\n"


def main(argv):
    if len(argv) < 3:
        print(__doc__)
        return 2
    ok, summary, text = report(argv[1], argv[2], argv[3:])
    REPORT.write_text(text, encoding="utf-8")
    level = "notice" if ok else "error"
    print(f"::{level} title=Encryption tests on Android API {argv[2]}::{summary}")
    print(text)
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
