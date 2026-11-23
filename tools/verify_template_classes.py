#!/usr/bin/env python3
"""Verify that an APK template contains every brick and script class of the tree.

Why this exists
---------------
The in-app "Build APK" dialog does not compile anything: it takes a finished
template APK, renames its package, swaps the icon and replaces
``assets/project.zip``. The dex files inside the template are therefore the only
code an exported app will ever have.

When a project uses a brick that the template was built without, XStream cannot
resolve the class while it parses ``code.xml``, ``ProjectLoader`` reports
``success=false`` and the exported app shows its splash screen and closes - the
"black screen, then it crashes" report.

The dialog now refuses to build such an APK (see ``BrickCoverage``), but a
template release that silently lost a brick would still break every export that
uses it. This script checks a template APK against the source tree, by reading
the **string pool** of every ``classes*.dex`` - the pool holds one entry per
referenced type, e.g. ``Lorg/catrobat/catroid/content/bricks/WaitBrick;``.

Usage
-----
    python3 tools/verify_template_classes.py --apk NewCatroid_APKTemplate_PC.apk

It exits non-zero and lists the missing classes when the template is incomplete.
"""

from __future__ import annotations

import argparse
import re
import struct
import sys
import zipfile
from pathlib import Path

DEFAULT_GAME_ROOT = Path("catroid/src/main/java/org/catrobat/catroid/content")
# Descriptors live in the `org/catrobat/catroid/content` package; bricks are in
# the `bricks` sub package, scripts directly in `content`.
DESCRIPTOR_PREFIXES = (
    "Lorg/catrobat/catroid/content/bricks/",
    "Lorg/catrobat/catroid/content/",
    "Lorg/catrobat/catroid/physics/content/bricks/",
    "Lorg/catrobat/catroid/physics/content/",
)

CLASS_DECLARATION = re.compile(
    r"(?:^|\n)\s*(?:@\w+\s*\n\s*)*"
    r"(?:public\s+|internal\s+|protected\s+|abstract\s+|open\s+|final\s+)*"
    r"class\s+([A-Za-z0-9_]+)"
)


def expected_classes(game_root: Path) -> list[str]:
    """Brick and script classes of the tree, by simple name.

    Both kinds are serialized through their ``type`` attribute (see
    ``XStreamBrickConverter`` / ``XStreamScriptConverter``), so both have to be
    present in the template.
    """
    names: set[str] = set()
    for path in sorted(game_root.rglob("*")):
        if path.suffix not in (".kt", ".java") or not path.is_file():
            continue
        text = path.read_text(encoding="utf-8", errors="replace")
        for match in CLASS_DECLARATION.finditer(text):
            name = match.group(1)
            if name.endswith("Brick") or name.endswith("Script"):
                names.add(name)
    return sorted(names)


def dex_string_pool(data: bytes) -> set[str]:
    """Strings of one dex file (the header's ``string_ids`` table)."""
    if len(data) < 0x70 or data[:4] != b"dex\n":
        raise ValueError("not a dex file")
    string_count, string_offsets_off = struct.unpack_from("<II", data, 0x38)
    strings: set[str] = set()
    for index in range(string_count):
        offset = struct.unpack_from("<I", data, string_offsets_off + index * 4)[0]
        # uleb128 length, then MUTF-8 bytes terminated by NUL. The code points
        # are not needed, the ASCII descriptors are copied as they are.
        position = offset
        while position < len(data) and data[position] & 0x80:
            position += 1
        position += 1
        end = data.find(b"\x00", position)
        if end < 0:
            continue
        strings.add(data[position:end].decode("utf-8", "replace"))
    return strings


def apk_strings(apk: Path) -> set[str]:
    strings: set[str] = set()
    with zipfile.ZipFile(apk) as archive:
        for name in archive.namelist():
            if not re.fullmatch(r"classes[0-9]*\.dex", name):
                continue
            strings |= dex_string_pool(archive.read(name))
    return strings


def missing_classes(apk: Path, names: list[str]) -> list[str]:
    strings = apk_strings(apk)
    present = {
        descriptor
        for descriptor in strings
        if descriptor.startswith(DESCRIPTOR_PREFIXES)
    }
    missing = []
    for name in names:
        if not any(
            descriptor.endswith("/" + name + ";") or descriptor == "L" + name + ";"
            for descriptor in present
        ):
            missing.append(name)
    return missing


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, help="template APK to inspect (required unless --list)")
    parser.add_argument(
        "--game-root",
        type=Path,
        default=DEFAULT_GAME_ROOT,
        help="root of the brick/script sources (default: %(default)s)",
    )
    parser.add_argument(
        "--list",
        action="store_true",
        help="only print the expected class names",
    )
    args = parser.parse_args(argv)

    names = expected_classes(args.game_root)
    if not names:
        print(f"No brick or script classes found under {args.game_root}", file=sys.stderr)
        return 2
    if args.list:
        for name in names:
            print(name)
        return 0

    if args.apk is None:
        print("--apk is required unless --list is used", file=sys.stderr)
        return 2

    if not args.apk.is_file():
        print(f"APK not found: {args.apk}", file=sys.stderr)
        return 2

    missing = missing_classes(args.apk, names)
    print(f"{args.apk.name}: {len(names) - len(missing)}/{len(names)} brick and script classes present")
    if missing:
        print("Missing classes (a project using them cannot load in this APK):", file=sys.stderr)
        for name in missing:
            print(f"  {name}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
