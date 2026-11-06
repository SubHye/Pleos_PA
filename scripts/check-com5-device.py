#!/usr/bin/env python3
"""CI-owned Android emulator checks; does not claim Pleos SDK/Car UX evidence."""
import argparse
import json
import os
import re
from pathlib import Path
import subprocess
import time
import uuid

PACKAGE = "com.example.pleos_pa.com5"
PROBE = "com.example.pleos_pa.ui.DriverStateProbeActivity"


def adb(serial, *args, timeout=30, combined_output=False):
    result = subprocess.run(["adb", "-s", serial, *args], capture_output=True, text=True,
                            timeout=timeout, check=True)
    return (result.stdout + (result.stderr if combined_output else "")).strip()


def boot(serial, emulator_pid=None):
    deadline = time.monotonic() + 360
    while time.monotonic() < deadline:
        if emulator_pid is not None:
            try:
                os.kill(emulator_pid, 0)
            except ProcessLookupError as error:
                raise RuntimeError("CI emulator exited before boot; inspect its log") from error
        try:
            if adb(serial, "shell", "getprop", "sys.boot_completed", timeout=10) == "1":
                return
        except subprocess.SubprocessError:
            pass
        time.sleep(2)
    raise RuntimeError("Selected emulator did not complete boot in 360 seconds")


def probe(serial, operation):
    nonce = str(uuid.uuid4())
    started = adb(serial, "shell", "am", "start", "-W", "-n", f"{PACKAGE}/{PROBE}",
                  "--es", "operation", operation, "--es", "nonce", nonce, combined_output=True)
    if any(marker in started for marker in ("Error:", "Exception", "Permission Denial")):
        raise RuntimeError("Diagnostic activity could not start: " + started)
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        logs = adb(serial, "logcat", "-d", "-s", "COM5_STATE:I", "*:S")
        for line in reversed(logs.splitlines()):
            if nonce in line and "{" in line:
                return json.loads(line[line.index("{"):])
        time.sleep(0.2)
    raise RuntimeError("No matching count-only probe result; diagnostic APK required")


def restart_check(serial):
    if adb(serial, "get-state") != "device":
        raise RuntimeError("Explicitly selected emulator is not online")
    before = probe(serial, "seed")
    if before["records"] < 1:
        raise RuntimeError("Synthetic record was not present before process restart")
    adb(serial, "shell", "am", "force-stop", PACKAGE)
    after = probe(serial, "inspect")
    if before["processId"] == after["processId"] or after["records"] != 0:
        raise RuntimeError("Process did not change or shared journal survived restart")
    if not after["connectionStopped"]:
        raise RuntimeError("New process did not start in stopped connection state")
    return {"scope": "Android shared journal; synthetic input, no SDK or navigation",
            "serial": serial, "before": before, "after": after, "passed": True}


def inspect_non_automotive(serial):
    nonce = str(uuid.uuid4())
    component = f"{PACKAGE}/com.example.pleos_pa.ui.Com22InspectionActivity"
    started = adb(serial, "shell", "am", "start", "-W", "-n", component,
                  "--es", "com22SessionId", nonce, combined_output=True)
    if any(marker in started for marker in ("Error:", "Exception", "Permission Denial")):
        raise RuntimeError("Inspection activity could not start: " + started)
    deadline = time.monotonic() + 10
    while time.monotonic() < deadline:
        logs = adb(serial, "logcat", "-d", "-s", "COM22_LIVE:I", "*:S")
        matches = [json.loads(line[line.index("{"):]) for line in logs.splitlines()
                   if nonce in line and "{" in line]
        if matches:
            event = matches[-1]
            if event["automotive"] or event["event"] != "UNAVAILABLE":
                raise RuntimeError("This CI check expects a generic non-Automotive device")
            if event["requiresDo"] is not None or event["activeRestrictions"] is not None or event["historyAllowed"]:
                raise RuntimeError("Missing platform data must not become fabricated UX evidence")
            return {"scope": "Generic Android unsupported-path check; no Pleos acceptance",
                    "event": event, "passed": True}
        time.sleep(0.2)
    raise RuntimeError("No correlated live-inspection event")


def permission_check(serial):
    # Host-side revocation precedes instrumentation: Android can kill the affected app.
    # Only the isolated diagnostic app is affected; no vehicle permissions are changed.
    adb(serial, "shell", "pm", "revoke", "--user", "current", PACKAGE,
        "android.permission.POST_NOTIFICATIONS", combined_output=True)
    adb(serial, "shell", "pm", "clear-permission-flags", "--user", "current", PACKAGE,
        "android.permission.POST_NOTIFICATIONS", "user-set", "user-fixed", combined_output=True)
    result = adb(serial, "shell", "am", "instrument", "--user", "current", "-w", "-r",
                 "-e", "class", "com.example.pleos_pa.connection.NotificationPermissionRuntimeTest",
                 f"{PACKAGE}.test/androidx.test.runner.AndroidJUnitRunner",
                 timeout=180, combined_output=True)
    if not re.search(r"OK \(1 test\)", result) or any(marker in result for marker in (
            "FAILURES!!!", "INSTRUMENTATION_FAILED", "INSTRUMENTATION_ABORTED",
            "Process crashed", "shortMsg=")):
        raise RuntimeError("Expected one successful real permission-dialog test:\n" + result)
    return {"scope": "Actual diagnostic notification permission dialog/denial/app stop; no vehicle requests",
            "serial": serial, "tests": 1, "instrumentation": result, "passed": True}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--wait-for-boot", action="store_true")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--emulator-pid", type=int)
    parser.add_argument("--inspect-non-automotive", action="store_true")
    parser.add_argument("--permission-check", action="store_true")
    args = parser.parse_args()
    if not args.serial.startswith("emulator-") or not args.serial[9:].isdigit():
        parser.error("Only an explicitly named emulator is supported")
    if args.wait_for_boot:
        boot(args.serial, args.emulator_pid)
        return
    if args.permission_check:
        result = permission_check(args.serial)
    else:
        result = inspect_non_automotive(args.serial) if args.inspect_non_automotive else restart_check(args.serial)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
