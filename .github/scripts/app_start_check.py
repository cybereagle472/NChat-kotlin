"""Did the app start? — judged from what an emulator can be asked.

The unit tests prove the logic and the instrumented tests prove the encryption
on Android itself, but neither ever starts the app. This does: the build that
is made the way the release is made (same code, same shrinking) is installed
on an emulator and launched, and this script reads what came of it —

    the process:        is it still there (`pidof`, or the process list);
    the log:            a crash of the app, in Java or in native code, or an
                        "application not responding";
    the screen:         what `uiautomator dump` read off it — the words of the
                        first screen, and whose window is in front;
    the activity stack: which activity is resumed.

    app_start_check.py <package> <api level> <pid file> <process list> <logcat>
                       <window dump> <resumed activity> <expected text> ...

It says the outcome as a notice (or an error) on the job and prints the
details, and its exit status is 0 only when the app started.

What it cannot say: anything behind the first screen. Signing in needs a
server, and there is one server — the owner's.
"""
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

CRASH_LINES = 14

# `adb logcat` in its usual form: date, time, pid, tid, level, tag, then the message.
LOG_LINE = re.compile(r"^\d\d-\d\d \d\d:\d\d:\d\d\.\d+\s+(\d+)\s+\d+\s+[A-Z]\s+([^:]*?)\s*: ?(.*)$")


def screen_texts(window):
    """(package, text) of everything written or described on the screen, in order; None when `window` is no dump."""
    try:
        root = ET.fromstring(window.strip())
    except ET.ParseError:
        return None
    if root.tag != "hierarchy":
        return None
    found = []
    for node in root.iter("node"):
        for attribute in ("text", "content-desc"):
            value = (node.get(attribute) or "").strip()
            if value:
                found.append((node.get("package", ""), value))
    return found


def _parsed(logcat):
    """(pid, tag, message) per line; lines in another form keep their text and get no pid."""
    for line in logcat.splitlines():
        match = LOG_LINE.match(line)
        yield (match.group(1), match.group(2), match.group(3)) if match else ("", "", line)


def crashes(logcat, package):
    """What the log says went wrong with `package`: each crash as a few lines, without the log's own columns."""
    lines = list(_parsed(logcat))
    found = []
    index = 0
    while index < len(lines):
        pid, tag, message = lines[index]
        block = None
        if tag == "AndroidRuntime" and "FATAL EXCEPTION" in message:
            end = index + 1
            while end < len(lines) and lines[end][0] == pid and lines[end][1] == "AndroidRuntime":
                end += 1
            body = [text for _, _, text in lines[index:end]]
            if any(re.match(rf"Process: {re.escape(package)}(,|\s|$)", text) for text in body):
                block = body
            index = end
        elif tag == "DEBUG" and f">>> {package} <<<" in message:
            end = index + 1
            while end < len(lines) and lines[end][0] == pid and lines[end][1] == "DEBUG":
                end += 1
            block = ["native crash"] + [text for _, _, text in lines[index:end]]
            index = end
        elif re.search(rf"ANR in {re.escape(package)}(\s|$)", message):
            end = index + 1
            while end < len(lines) and lines[end][0] == pid and lines[end][1] == tag:
                end += 1
            block = [text for _, _, text in lines[index:end]]
            index = end
        else:
            index += 1
        if block:
            found.append("\n".join(block[:CRASH_LINES]))
    return found


def running(package, pid_text, process_list):
    """True when the package's process is there: `pidof` gave a number, or the process list names it."""
    if re.fullmatch(r"\d+(\s+\d+)*", pid_text.strip()):
        return True
    return any(line.split()[-1:] == [package] for line in process_list.splitlines())


def judge(package, pid_text, process_list, logcat, window, resumed, expected):
    """(ok, one-line summary, details)."""
    details = []
    problems = []
    notes = []

    crashed = crashes(logcat, package)
    if crashed:
        first = crashed[0].splitlines()
        what = next((line.strip() for line in first if "Exception" in line or "Error" in line), first[0].strip())
        if what.startswith("FATAL EXCEPTION") and len(first) > 2:
            what = first[2].strip()
        problems.append(f"crashed: {what[:160]}")
        for crash in crashed:
            details += ["--- crash", crash, ""]

    alive = running(package, pid_text, process_list)
    if not alive and not crashed:
        problems.append("not running after the launch")

    in_front = f"{package}/" in resumed
    texts = screen_texts(window)
    if texts is None:
        if alive and not crashed:
            if in_front:
                notes.append("the screen could not be read on this system")
            else:
                problems.append("not in front, and the screen could not be read")
                details += ["--- resumed activity", resumed.strip() or "(none)", ""]
    else:
        ours = [text for owner, text in texts if owner == package]
        others = sorted({owner for owner, _ in texts if owner and owner != package})
        details += ["--- on screen", *[f"{owner}: {text}" for owner, text in texts], ""]
        missing = [text for text in expected if text not in ours]
        if not ours and others:
            problems.append(f"the window in front belongs to {', '.join(others)}")
        elif missing and not crashed:
            problems.append("the first screen lacks " + ", ".join(f"'{text}'" for text in missing))
        elif not missing:
            notes.append("the first screen shows " + ", ".join(f"'{text}'" for text in expected))

    if problems:
        return False, "; ".join(problems), "\n".join(details)
    return True, "; ".join(["started"] + notes), "\n".join(details)


def _read(path):
    file = Path(path)
    return file.read_text(encoding="utf-8", errors="replace") if file.is_file() else ""


def main(argv):
    if len(argv) < 9:
        print(__doc__)
        return 2
    package, api_level = argv[1], argv[2]
    pid_text, process_list, logcat, window, resumed = (_read(path) for path in argv[3:8])
    ok, summary, details = judge(package, pid_text, process_list, logcat, window, resumed, argv[8:])
    print(f"::{'notice' if ok else 'error'} title=App start on Android API {api_level}::{summary}")
    print(f"App start on Android API {api_level}: {summary}")
    print(details)
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
