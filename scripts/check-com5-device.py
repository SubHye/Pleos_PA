#!/usr/bin/env python3
"""CI-owned Android emulator checks; does not claim Pleos SDK/Car UX evidence."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import time
import uuid

PACKAGE = "com.example.pleos_pa.com5"
PROBE = "com.example.pleos_pa.ui.DriverStateProbeActivity"


def adb(serial, *args, timeout=30):
    result = subprocess.run(["adb", "-s", serial, *args], capture_output=True, text=True,
                            timeout=timeout, check=True)
    return result.stdout.strip()


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
    adb(serial, "shell", "am", "start", "-W", "-n", f"{PACKAGE}/{PROBE}",
        "--es", "operation", operation, "--es", "nonce", nonce)
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


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--wait-for-boot", action="store_true")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--emulator-pid", type=int)
    args = parser.parse_args()
    if not args.serial.startswith("emulator-") or not args.serial[9:].isdigit():
        parser.error("Only an explicitly named emulator is supported")
    if args.wait_for_boot:
        boot(args.serial, args.emulator_pid)
        return
    result = restart_check(args.serial)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
