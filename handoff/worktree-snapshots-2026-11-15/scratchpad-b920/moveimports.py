"""Imports of the files that move to sangam-rest-core, grouped by top package; and references to non-moved sangam-rest classes."""
import os
import re
import sys
from collections import Counter

root = sys.argv[1]
rest = os.path.join(root, "sangam-rest")
PKGS = ["port", "adapter/jdbc", "adapter/memory", "adapter/operaton", "support", "extension"]
moved_pk = {"org.sangam.rest." + p.replace("/", ".") for p in PKGS}

# every sangam-rest class (main) -> package
rest_classes = {}
for scope in ("main", "test"):
    base = os.path.join(rest, "src", scope, "java")
    for d, _, fs in os.walk(base):
        for f in fs:
            if f.endswith(".java"):
                fq = os.path.relpath(os.path.join(d, f), base)[:-5].replace(os.sep, ".")
                rest_classes[fq] = scope

imports = {"main": Counter(), "test": Counter()}
bad = []
counts = Counter()
for scope in ("main", "test"):
    for p in PKGS:
        base = os.path.join(rest, "src", scope, "java", "org", "sangam", "rest", *p.split("/"))
        if not os.path.isdir(base):
            continue
        for f in sorted(os.listdir(base)):
            full = os.path.join(base, f)
            if not os.path.isfile(full):
                continue
            counts[scope] += 1
            text = open(full, encoding="utf-8").read()
            for m in re.finditer(r"^import\s+(?:static\s+)?([\w.]+)", text, re.M):
                name = m.group(1)
                parts = name.split(".")
                key = ".".join(parts[:3]) if parts[0] in ("org", "com", "io", "tools", "jakarta") else ".".join(parts[:2])
                if name.startswith("org.sangam."):
                    key = ".".join(parts[:3])
                imports[scope][key] += 1
                if name.startswith("org.sangam.rest."):
                    cls = name
                    while cls and cls not in rest_classes:
                        cls = cls.rsplit(".", 1)[0] if "." in cls else ""
                    if cls and cls.rsplit(".", 1)[0] not in moved_pk:
                        bad.append(f"{scope} {f} imports {name}")
            # fully-qualified uses of non-moved rest classes
            for m in re.finditer(r"org\.sangam\.rest\.([\w.]+)", text):
                fq = "org.sangam.rest." + m.group(1)
                cls = fq
                while cls and cls not in rest_classes:
                    cls = cls.rsplit(".", 1)[0] if "." in cls else ""
                if cls and cls.rsplit(".", 1)[0] not in moved_pk:
                    bad.append(f"{scope} {f} references {fq}")
print("counts", dict(counts))
for scope in imports:
    print("---", scope)
    for k, v in sorted(imports[scope].items()):
        print(v, k)
print("--- references to non-moved sangam-rest classes")
for b in sorted(set(bad)):
    print(b)
