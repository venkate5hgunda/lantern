#!/usr/bin/env python3
"""Package a verified, non-debuggable signed APK and source/checksum receipts."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def run(*args):
    return subprocess.check_output(args, cwd=ROOT, text=True).strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, default=ROOT / "android/app/build/outputs/apk/release/app-release.apk")
    parser.add_argument("--output", type=Path, default=ROOT / "build/releases")
    args = parser.parse_args()
    if run("git", "status", "--porcelain"):
        parser.error("Commit source changes before packaging a release.")
    sdk = Path(os.environ.get("ANDROID_HOME") or os.environ["ANDROID_SDK_ROOT"])
    candidates = [p for p in (sdk / "build-tools").iterdir() if (p / "apksigner").is_file() and re.fullmatch(r"\d+(?:\.\d+)+", p.name)]
    tools = max(candidates, key=lambda p: tuple(map(int, p.name.split("."))))
    cert = run(str(tools / "apksigner"), "verify", "--verbose", "--print-certs", str(args.apk))
    badging = run(str(tools / "aapt"), "dump", "badging", str(args.apk))
    package = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging)
    if not package or package[1] != "dev.lantern.peer" or "application-debuggable" in badging:
        parser.error("Expected a signed, non-debuggable dev.lantern.peer APK.")
    minimum_api = re.search(r"sdkVersion:'(\d+)'", badging)
    if not minimum_api:
        parser.error("APK minimum Android API is missing.")
    version = package[3]
    if not re.fullmatch(r"\d+\.\d+\.\d+(?:-[a-zA-Z0-9.-]+)?", version):
        parser.error("APK version must be a safe semantic version.")
    gradle = (ROOT / "android/app/build.gradle.kts").read_text()
    if f'versionName = "{version}"' not in gradle or f'versionCode = {package[2]}' not in gradle:
        parser.error("APK version does not match committed source.")
    # A release must embed exactly the reviewed static bundle.
    import zipfile
    with zipfile.ZipFile(args.apk) as archive:
        for asset in (ROOT / "web/dist").rglob("*"):
            if asset.is_file() and archive.read("assets/" + asset.relative_to(ROOT / "web/dist").as_posix()) != asset.read_bytes():
                parser.error(f"APK contains stale viewer asset: {asset.name}")
    destination = args.output / f"v{version}"
    destination.mkdir(parents=True, exist_ok=True)
    apk = destination / f"lantern-{version}.apk"
    shutil.copyfile(args.apk, apk)
    (destination / "signing-certificate.txt").write_text(cert + "\n")
    (destination / "build-info.json").write_text(json.dumps({
        "repository": "https://github.com/venkate5hgunda/lantern",
        "commit": run("git", "rev-parse", "HEAD"), "version": version,
        "versionCode": int(package[2]), "applicationId": package[1],
        "minimumAndroidApi": int(minimum_api[1]), "variant": "release", "debuggable": False,
    }, indent=2) + "\n")
    files = [apk, destination / "signing-certificate.txt", destination / "build-info.json"]
    (destination / "SHA256SUMS").write_text("".join(f"{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.name}\n" for p in files))
    print(destination)


if __name__ == "__main__":
    main()
