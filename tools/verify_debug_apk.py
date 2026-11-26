#!/usr/bin/env python3
"""Verify the launcher and key packaged resources in the CI debug APK."""

from __future__ import annotations

import argparse
import hashlib
import io
import re
import subprocess
import zipfile
from pathlib import Path

REQUIRED_ENTRIES = (
    "AndroidManifest.xml",
    "resources.arsc",
    "assets/project.zip",
    "assets/catblocks/index.html",
    "assets/catblocks/CatBlocks.js",
    "assets/python3.12/__future__.py",
    "lib/arm64-v8a/libcatroid.so",
    "lib/arm64-v8a/libpython3.12.so",
    "lib/arm64-v8a/libcrypto.so",
    "lib/arm64-v8a/libssl.so",
    "lib/arm64-v8a/libz.so",
    "lib/arm64-v8a/libexpat.so",
    "lib/arm64-v8a/libopenblas.so",
    "lib/arm64-v8a/libjpeg.so",
    "lib/arm64-v8a/libpng.so",
    "lib/arm64-v8a/libonnxruntime.so",
    "lib/arm64-v8a/libMNN.so",
    "lib/arm64-v8a/libMNN_Express.so",
    "lib/arm64-v8a/libmnncore.so",
    "lib/arm64-v8a/libllm.so",
    "lib/arm64-v8a/libc++_shared.so",
)
REQUIRED_RESOURCES = (
    "activity_main_menu",
    "activity_main_menu_splashscreen",
    "activity_loading",
    "fragment_main_menu",
    "fragment_main_menu_pc",
    "ic_launcher_foreground",
)
EXPECTED_LAUNCHER = "org.catrobat.catroid.ui.MainMenuActivity"


class ApkVerificationError(RuntimeError):
    """Raised when the APK is missing a launcher or required packaged inputs."""


def inspect_archive(apk_path: Path) -> dict[str, object]:
    try:
        with zipfile.ZipFile(apk_path) as apk:
            corrupt_entry = apk.testzip()
            if corrupt_entry is not None:
                raise ApkVerificationError(f"APK ZIP CRC check failed at {corrupt_entry}")

            names = set(apk.namelist())
            missing = [name for name in REQUIRED_ENTRIES if name not in names]
            if missing:
                raise ApkVerificationError("APK is missing required entries: " + ", ".join(missing))

            empty = [name for name in REQUIRED_ENTRIES if apk.getinfo(name).file_size == 0]
            if empty:
                raise ApkVerificationError("APK contains empty required entries: " + ", ".join(empty))

            dex_files = [name for name in names if re.fullmatch(r"classes(?:\d+)?\.dex", name)]
            if not dex_files:
                raise ApkVerificationError("APK contains no classes.dex file")

            project_bytes = apk.read("assets/project.zip")
            try:
                with zipfile.ZipFile(io.BytesIO(project_bytes)) as project:
                    if "code.xml" not in project.namelist():
                        raise ApkVerificationError("Bundled assets/project.zip has no code.xml")
            except zipfile.BadZipFile as error:
                raise ApkVerificationError("Bundled assets/project.zip is not a valid ZIP") from error

            return {
                "entries": len(names),
                "dex_files": sorted(dex_files),
                "arm64_libraries": sorted(
                    name.removeprefix("lib/arm64-v8a/")
                    for name in names
                    if name.startswith("lib/arm64-v8a/") and name.endswith(".so")
                ),
            }
    except zipfile.BadZipFile as error:
        raise ApkVerificationError(f"Not a readable APK/ZIP: {apk_path}") from error


def inspect_aapt_output(badging: str, resources: str) -> dict[str, str]:
    package_match = re.search(r"^package: name='([^']+)'", badging, re.MULTILINE)
    if not package_match:
        raise ApkVerificationError("aapt did not report the APK package name")
    package_name = package_match.group(1)
    if not package_name.lower().startswith("org.danvexteam.newcatroid"):
        raise ApkVerificationError(f"Unexpected debug APK package: {package_name}")

    launch_match = re.search(r"^launchable-activity: name='([^']+)'", badging, re.MULTILINE)
    if not launch_match:
        raise ApkVerificationError("aapt found no launchable Activity")
    launcher = launch_match.group(1)
    if launcher != EXPECTED_LAUNCHER:
        raise ApkVerificationError(f"Expected launcher {EXPECTED_LAUNCHER}, found {launcher}")

    missing_resources = [name for name in REQUIRED_RESOURCES if name not in resources]
    if missing_resources:
        raise ApkVerificationError("APK resource table is missing: " + ", ".join(missing_resources))
    return {"package": package_name, "launcher": launcher}


def verify_apk(apk_path: Path, aapt_path: Path) -> dict[str, object]:
    archive = inspect_archive(apk_path)
    badging = subprocess.run(
        [str(aapt_path), "dump", "badging", str(apk_path)],
        check=True,
        capture_output=True,
        text=True,
    ).stdout
    resources = subprocess.run(
        [str(aapt_path), "dump", "resources", str(apk_path)],
        check=True,
        capture_output=True,
        text=True,
    ).stdout
    metadata = inspect_aapt_output(badging, resources)

    digest = hashlib.sha256()
    with apk_path.open("rb") as apk_file:
        for chunk in iter(lambda: apk_file.read(1024 * 1024), b""):
            digest.update(chunk)

    return {
        **archive,
        **metadata,
        "bytes": apk_path.stat().st_size,
        "sha256": digest.hexdigest(),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", required=True, type=Path, help="built danvexDebug APK")
    parser.add_argument("--aapt", required=True, type=Path, help="Android SDK build-tools aapt executable")
    args = parser.parse_args()

    report = verify_apk(args.apk, args.aapt)
    print(
        "Verified APK: package={package} launcher={launcher} entries={entries} "
        "dex={dex_files} arm64-libs={arm64_libraries} bytes={bytes} sha256={sha256}".format(**report)
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
