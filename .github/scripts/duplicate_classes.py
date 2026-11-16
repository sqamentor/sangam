# SPDX-License-Identifier: Apache-2.0
"""Duplicate-class check over each Spring Boot fat jar (RM-31, X1 V-9, V-10, G-9).

A class that two nested jars of one fat jar both carry is loaded from whichever comes first on the class path, so
two crypto providers or two Commons Logging implementations in one jar make the running code depend on jar order.
For every --jar, the check reads each BOOT-INF/lib/*.jar and BOOT-INF/classes, and reports every class under a
watched --package that more than one of them holds. A class held twice with the same bytes is reported too: the
rule is one owner per class.

  python3 -I .github/scripts/duplicate_classes.py --jar sangam-server/target/sangam-server-0.1.0.jar \
      --package org.bouncycastle --package org.apache.commons.logging

Exit codes: 0 no duplicate, 1 duplicates found, 2 unreadable input or a jar that is not a fat jar (never a pass).
Standard library only.
"""
import argparse
import io
import sys
import zipfile


class InputError(Exception):
    pass


def owners(jar_path):
    """{class entry: [owner, ...]} where an owner is a nested jar name or BOOT-INF/classes."""
    found = {}
    try:
        outer = zipfile.ZipFile(jar_path)
    except (OSError, zipfile.BadZipFile) as e:
        raise InputError(f"cannot open {jar_path}: {e}") from e
    with outer:
        names = outer.namelist()
        nested = [n for n in names if n.startswith("BOOT-INF/lib/") and n.endswith(".jar")]
        if not nested:
            raise InputError(f"{jar_path} has no BOOT-INF/lib (not a Spring Boot fat jar)")
        for n in names:
            if n.startswith("BOOT-INF/classes/") and n.endswith(".class"):
                found.setdefault(n[len("BOOT-INF/classes/"):], []).append("BOOT-INF/classes")
        for n in nested:
            try:
                with zipfile.ZipFile(io.BytesIO(outer.read(n))) as inner:
                    for c in inner.namelist():
                        # multi-release copies (META-INF/versions/N/...) belong to the same jar, not a second owner
                        if c.endswith(".class") and not c.startswith("META-INF/"):
                            found.setdefault(c, []).append(n[len("BOOT-INF/lib/"):])
            except zipfile.BadZipFile as e:
                raise InputError(f"{jar_path}!{n}: not a jar ({e})") from e
    return found


def duplicates(jar_path, packages):
    prefixes = tuple(p.replace(".", "/").rstrip("/") + "/" for p in packages)
    out = []
    for cls, who in sorted(owners(jar_path).items()):
        if cls.startswith(prefixes) and len(set(who)) > 1:
            out.append((cls, sorted(set(who))))
    return out


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--jar", action="append", required=True, help="Spring Boot fat jar (repeatable)")
    ap.add_argument("--package", action="append", required=True,
                    help="watched package, e.g. org.bouncycastle (repeatable)")
    a = ap.parse_args(argv)
    total = 0
    try:
        for jar in a.jar:
            found = duplicates(jar, a.package)
            pairs = sorted({tuple(w) for _, w in found})
            for owners_ in pairs:
                classes = [c for c, w in found if tuple(w) == owners_]
                print(f"::error title=duplicate classes::{jar}: {len(classes)} class(es) in {' and '.join(owners_)}, "
                      f"e.g. {classes[0]}")
            print(f"{jar}: {len(found)} duplicated class(es) under {', '.join(a.package)}")
            total += len(found)
    except InputError as e:
        print(f"::error title=duplicate classes BLOCKED::{e}")
        return 2
    print(f"duplicate classes: {'FAIL' if total else 'PASS'}, {total} finding(s)")
    return 1 if total else 0


if __name__ == "__main__":
    sys.exit(main())
