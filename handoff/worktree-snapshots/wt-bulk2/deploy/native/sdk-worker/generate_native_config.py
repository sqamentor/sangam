# SPDX-License-Identifier: Apache-2.0
"""Generates the GraalVM reachability metadata for the native-light Java SDK worker (SANGAM.md R7).

Scans sangam/sdks/java/src/main/java/org/sangam/sdk/model (every record, including nested records/enums) and writes
  src/main/resources/META-INF/native-image/org.sangam/sangam-sdk-worker-native/reflect-config.json
  src/main/resources/org/sangam/sdk/nativeimage/model-classes.txt   (read by NativeSmoke)
Jackson maps these records by reflection (canonical constructor + accessors), which native-image must know about.

  python -I deploy/native/sdk-worker/generate_native_config.py          # write
  python -I deploy/native/sdk-worker/generate_native_config.py --check  # CI: fail if the files are stale
"""
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
SANGAM = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
MODEL = os.path.join(SANGAM, "sdks", "java", "src", "main", "java", "org", "sangam", "sdk", "model")
PKG = "org.sangam.sdk.model"
REFLECT = os.path.join(HERE, "src", "main", "resources", "META-INF", "native-image", "org.sangam",
                       "sangam-sdk-worker-native", "reflect-config.json")
LIST = os.path.join(HERE, "src", "main", "resources", "org", "sangam", "sdk", "nativeimage", "model-classes.txt")

DECL = re.compile(r"^(\s*)(?:public\s+|protected\s+|private\s+)?(?:static\s+)?(?:final\s+)?(record|enum|class)\s+(\w+)")


def scan():
    classes = []
    for f in sorted(os.listdir(MODEL)):
        if not f.endswith(".java"):
            continue
        top = f[:-5]
        with open(os.path.join(MODEL, f), encoding="utf-8") as fh:
            for line in fh:
                m = DECL.match(line)
                if not m:
                    continue
                indent, kind, name = m.groups()
                binary = f"{PKG}.{top}" if name == top and not indent else f"{PKG}.{top}${name}"
                if (binary, kind) not in classes:
                    classes.append((binary, kind))
    return classes


def render(classes):
    reflect = []
    for name, kind in classes:
        entry = {"name": name, "allDeclaredConstructors": True, "allPublicConstructors": True,
                 "allDeclaredMethods": True, "allPublicMethods": True, "allDeclaredFields": True,
                 "queryAllDeclaredMethods": True, "queryAllDeclaredConstructors": True}
        reflect.append(entry)
    # Jackson's record support reads RecordComponent metadata.
    reflect.append({"name": "java.lang.Record", "allPublicMethods": True})
    reflect_text = json.dumps(reflect, indent=2) + "\n"
    list_text = "".join(f"{n}\n" for n, k in classes if k == "record")
    return reflect_text, list_text


def main():
    check = "--check" in sys.argv
    reflect_text, list_text = render(scan())
    stale = []
    for path, text in ((REFLECT, reflect_text), (LIST, list_text)):
        old = open(path, encoding="utf-8").read() if os.path.exists(path) else None
        if old != text:
            stale.append(path)
            if not check:
                os.makedirs(os.path.dirname(path), exist_ok=True)
                with open(path, "w", encoding="utf-8", newline="\n") as fh:
                    fh.write(text)
    if check and stale:
        print("stale native metadata (run generate_native_config.py):", *stale, sep="\n  ")
        return 1
    print(f"{len(list_text.splitlines())} model records; {'up to date' if not stale else 'written'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
