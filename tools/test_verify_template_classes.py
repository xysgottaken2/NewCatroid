#!/usr/bin/env python3
"""Tests for tools/verify_template_classes.py.

The dex reader is the part that can silently be wrong: it has to find
``string_ids`` at the documented header offsets (0x38 / 0x3C) and follow the
uleb128 length prefix of every string. These tests build a minimal but
well-formed dex file so the offsets are actually exercised, and then check the
whole APK -> missing class report on top of it.
"""

from __future__ import annotations

import struct
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import verify_template_classes as vtc  # noqa: E402


def build_dex(strings: list[str]) -> bytes:
    """Minimal dex: header, string_ids table and string data."""
    header_size = 0x70
    offsets = []
    data = bytearray()
    for text in strings:
        offsets.append(header_size + len(offsets) * 4 + 0)  # patched below
        data.extend(b"\x00")

    # Layout: header | string_ids | string data
    string_ids_size = len(strings)
    data_start = header_size + string_ids_size * 4
    offsets = []
    payload = bytearray()
    for text in strings:
        offsets.append(data_start + len(payload))
        encoded = text.encode("utf-8")
        payload.append(0x80 | (len(encoded) & 0x7F) if len(encoded) > 0x7F else len(encoded))
        if len(encoded) > 0x7F:
            payload[-1] = 0x80 | (len(encoded) & 0x7F)
            payload.append(len(encoded) >> 7)
        payload.extend(encoded)
        payload.append(0x00)

    header = bytearray(header_size)
    header[0:8] = b"dex\n035\x00"
    struct.pack_into("<I", header, 0x20, data_start + len(payload))  # file_size
    struct.pack_into("<I", header, 0x24, header_size)  # header_size
    struct.pack_into("<I", header, 0x28, 0x12345678)  # endian_tag
    struct.pack_into("<I", header, 0x38, string_ids_size)
    struct.pack_into("<I", header, 0x3C, header_size)

    table = bytearray()
    for offset in offsets:
        table.extend(struct.pack("<I", offset))

    return bytes(header) + bytes(table) + bytes(payload)


def build_apk(path: Path, dex_strings: dict[str, list[str]]) -> Path:
    with zipfile.ZipFile(path, "w") as archive:
        for name, strings in dex_strings.items():
            archive.writestr(name, build_dex(strings))
    return path


class DexStringPoolTest(unittest.TestCase):
    def test_strings_are_read_from_the_pool(self):
        dex = build_dex(
            [
                "Lorg/catrobat/catroid/content/bricks/WaitBrick;",
                "<init>",
                "Ljava/lang/Object;",
            ]
        )
        strings = vtc.dex_string_pool(dex)
        self.assertIn("Lorg/catrobat/catroid/content/bricks/WaitBrick;", strings)
        self.assertIn("<init>", strings)

    def test_a_file_that_is_not_a_dex_is_rejected(self):
        with self.assertRaises(ValueError):
            vtc.dex_string_pool(b"PK\x03\x04 definitely not a dex")

    def test_every_dex_of_the_apk_is_inspected(self):
        with tempfile.TemporaryDirectory() as tmp:
            apk = build_apk(
                Path(tmp) / "template.apk",
                {
                    "classes.dex": ["Lorg/catrobat/catroid/content/bricks/WaitBrick;"],
                    "classes2.dex": [
                        "Lorg/catrobat/catroid/content/bricks/PcLaunchGameBrick;"
                    ],
                    "assets/project.zip": [],
                },
            )
            strings = vtc.apk_strings(apk)
            self.assertIn("Lorg/catrobat/catroid/content/bricks/WaitBrick;", strings)
            self.assertIn(
                "Lorg/catrobat/catroid/content/bricks/PcLaunchGameBrick;", strings
            )


class MissingClassesTest(unittest.TestCase):
    def test_a_complete_template_has_no_missing_classes(self):
        with tempfile.TemporaryDirectory() as tmp:
            apk = build_apk(
                Path(tmp) / "template.apk",
                {
                    "classes.dex": [
                        "Lorg/catrobat/catroid/content/bricks/WaitBrick;",
                        "Lorg/catrobat/catroid/content/StartScript;",
                    ]
                },
            )
            self.assertEqual(
                [], vtc.missing_classes(apk, ["WaitBrick", "StartScript"])
            )

    def test_a_class_from_another_package_is_not_accepted(self):
        with tempfile.TemporaryDirectory() as tmp:
            # `WaitBrick` exists in the template, but as part of a different
            # package that the class loader of the project will never look at.
            apk = build_apk(
                Path(tmp) / "template.apk",
                {"classes.dex": ["Lcom/example/WaitBrick;"]},
            )
            self.assertEqual(["WaitBrick"], vtc.missing_classes(apk, ["WaitBrick"]))

    def test_a_nested_class_is_found_under_its_own_name(self):
        with tempfile.TemporaryDirectory() as tmp:
            # `CatchBrick` and `FinallyBrick` are declared inside
            # `TryCatchFinallyBrick`, so their descriptor has a `$` in it:
            # `L.../bricks/TryCatchFinallyBrick$CatchBrick;`. `code.xml` still
            # names them by their simple name.
            apk = build_apk(
                Path(tmp) / "template.apk",
                {
                    "classes.dex": [
                        "Lorg/catrobat/catroid/content/bricks/TryCatchFinallyBrick;",
                        "Lorg/catrobat/catroid/content/bricks/TryCatchFinallyBrick$CatchBrick;",
                    ]
                },
            )
            self.assertEqual(
                [],
                vtc.missing_classes(apk, ["TryCatchFinallyBrick", "CatchBrick"]),
            )

    def test_the_report_names_every_missing_class(self):
        with tempfile.TemporaryDirectory() as tmp:
            apk = build_apk(
                Path(tmp) / "template.apk",
                {"classes.dex": ["Lorg/catrobat/catroid/content/bricks/WaitBrick;"]},
            )
            missing = vtc.missing_classes(
                apk, ["WaitBrick", "PcLaunchGameBrick", "PcRamCheckBrick"]
            )
            self.assertEqual(["PcLaunchGameBrick", "PcRamCheckBrick"], missing)


class ExpectedClassesTest(unittest.TestCase):
    def test_the_source_tree_has_the_pc_bricks_and_the_scripts(self):
        names = set(vtc.expected_classes(Path("catroid/src/main/java/org/catrobat/catroid/content")))
        # The PC layer and its RAM sensors - the reason the template has to be
        # rebuilt from this tree.
        for name in (
            "PcLaunchGameBrick",
            "PcShowLauncherBrick",
            "PcDownloadPackBrick",
            "PcRamCheckBrick",
            "StartScript",
            "WhenConditionScript",
        ):
            self.assertIn(name, names)


if __name__ == "__main__":
    unittest.main()
