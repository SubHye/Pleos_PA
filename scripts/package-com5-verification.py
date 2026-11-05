#!/usr/bin/env python3
"""Package verified .com5 diagnostic APKs; no signing keys or user data are included."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "com.example.pleos_pa.com5"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        parser.error("Output already exists; choose a new archive path")
    if subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT, text=True).strip():
        raise RuntimeError("Commit the source before creating a provenance-labelled kit")
    wrapper = ["cmd.exe", "/d", "/c", "gradlew.bat"] if os.name == "nt" else ["bash", "./gradlew"]
    subprocess.run(wrapper + ["-Pcom5Diagnostic=true", ":app:assembleDebug", ":app:assembleDebugAndroidTest", "--no-daemon", "--console=plain"], cwd=ROOT, check=True)
    if subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT, text=True).strip():
        raise RuntimeError("Source changed during packaging")
    sdk = Path(os.environ.get("ANDROID_HOME", ""))
    aapt = sdk / "build-tools/36.0.0" / ("aapt.exe" if os.name == "nt" else "aapt")
    files = {
        "app-debug.apk": ROOT / "app/build/outputs/apk/debug/app-debug.apk",
        "app-debug-androidTest.apk": ROOT / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk",
    }
    hashes = {}
    for name, path in files.items():
        badging = subprocess.check_output([str(aapt), "dump", "badging", str(path)], text=True)
        found = re.search(r"^package: name='([^']+)'", badging, re.M)
        expected = PACKAGE + (".test" if "androidTest" in name else "")
        if not found or found.group(1) != expected:
            raise RuntimeError(f"Refusing to package a different application: {name}")
        hashes[name] = hashlib.sha256(path.read_bytes()).hexdigest()
    manifest = {
        "commit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
        "package": PACKAGE, "testPackage": PACKAGE + ".test", "sha256": hashes,
        "scope": "Synthetic UI and shared journal. No actual Car UX/navigation verification.",
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(args.output, "x", compression=zipfile.ZIP_DEFLATED) as archive:
        for name, path in files.items():
            archive.write(path, "apks/" + name)
        archive.write(ROOT / "scripts/verify-com5-local.ps1", "scripts/verify-com5-local.ps1")
        archive.write(ROOT / "scripts/COM5-WINDOWS.md", "README.md")
        archive.writestr("kit-manifest.json", json.dumps(manifest, indent=2) + "\n")
    with zipfile.ZipFile(args.output) as archive:
        for name, expected in hashes.items():
            if hashlib.sha256(archive.read("apks/" + name)).hexdigest() != expected:
                raise RuntimeError("Archive APK checksum mismatch")
    print(json.dumps({"path": str(args.output), "bytes": args.output.stat().st_size,
                      "sha256": hashlib.sha256(args.output.read_bytes()).hexdigest()}))


if __name__ == "__main__":
    main()
