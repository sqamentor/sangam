"""Spring coupling of the modules a Quarkus host would assemble. Usage: coupling.py <sangam-root>"""
import os
import re
import sys

root = sys.argv[1]
SPRING = re.compile(r"^import (static )?org\.springframework[\w.]*", re.M)


def java_files(module):
    base = os.path.join(root, module, "src", "main", "java")
    for d, _, fs in os.walk(base):
        for f in fs:
            if f.endswith(".java"):
                yield os.path.join(d, f)


mods = sorted(m for m in os.listdir(root) if m.startswith("sangam-") and os.path.isdir(os.path.join(root, m, "src", "main", "java")))
print("module files spring_files")
for m in mods:
    fs = list(java_files(m))
    sp = [f for f in fs if SPRING.search(open(f, encoding="utf-8", errors="replace").read())]
    print(f"{m} {len(fs)} {len(sp)}")

print("--- named classes")
for name in sys.argv[2:]:
    for m in mods:
        for f in java_files(m):
            if os.path.basename(f) == name + ".java":
                text = open(f, encoding="utf-8", errors="replace").read()
                imps = sorted(set(x.group(0).replace("import ", "") for x in SPRING.finditer(text)))
                print(f"{name} [{m}] lines={text.count(chr(10))} spring={imps}")
