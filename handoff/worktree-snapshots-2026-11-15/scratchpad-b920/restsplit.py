"""Decision 277 analysis: which sangam-rest main classes are Spring-free once their references inside sangam-rest are
followed, per package; and which other modules use which sangam-rest classes. Usage: restsplit.py <sangam-root>"""
import collections
import os
import re
import sys

root = sys.argv[1]
rest = os.path.join(root, "sangam-rest", "src", "main", "java")
SPRING = re.compile(r"\borg\.springframework\.")
classes = {}  # fqn -> (path, text)
for d, _, fs in os.walk(rest):
    for f in fs:
        if f.endswith(".java"):
            p = os.path.join(d, f)
            rel = os.path.relpath(p, rest)[:-5].replace(os.sep, ".")
            classes[rel] = open(p, encoding="utf-8", errors="replace").read()

by_simple = collections.defaultdict(set)
for fqn in classes:
    by_simple[fqn.rsplit(".", 1)[1]].add(fqn)


def strip(text):
    text = re.sub(r"/\*.*?\*/", " ", text, flags=re.S)
    text = re.sub(r"//[^\n]*", " ", text)
    return re.sub(r'"(?:\\.|[^"\\])*"', '""', text)


refs = {}
for fqn, text in classes.items():
    code = strip(text)
    pkg = fqn.rsplit(".", 1)[0]
    out = set()
    for other in classes:
        if other == fqn:
            continue
        simple = other.rsplit(".", 1)[1]
        opkg = other.rsplit(".", 1)[0]
        if other in code:  # import or fully qualified use
            out.add(other)
        elif opkg == pkg and re.search(r"\b" + re.escape(simple) + r"\b", code):
            out.add(other)
        elif re.search(r"import\s+" + re.escape(opkg) + r"\.\*;", code) and re.search(r"\b" + re.escape(simple) + r"\b", code):
            out.add(other)
    refs[fqn] = out

direct = {f for f, t in classes.items() if SPRING.search(strip(t))}
tainted = set(direct)
changed = True
while changed:
    changed = False
    for f, rs in refs.items():
        if f not in tainted and rs & tainted:
            tainted.add(f)
            changed = True

free = sorted(set(classes) - tainted)
pk = collections.Counter(f.rsplit(".", 1)[0] for f in classes)
pf = collections.Counter(f.rsplit(".", 1)[0] for f in free)
print(f"classes={len(classes)} direct-spring={len(direct)} tainted={len(tainted)} free={len(free)}")
print("package total free")
for p in sorted(pk):
    print(f"{p} {pk[p]} {pf.get(p, 0)}")
print("--- free classes that reference nothing tainted but sit in a mixed package")
for p in sorted(pk):
    if 0 < pf.get(p, 0) < pk[p]:
        print(p, "free:", ", ".join(f.rsplit(".", 1)[1] for f in free if f.rsplit(".", 1)[0] == p))
print("--- tainted only through references (no Spring import of their own)")
for f in sorted(tainted - direct):
    via = sorted(r.rsplit(".", 1)[1] for r in refs[f] & tainted)
    print(f, "via", ", ".join(via[:5]))

# consumers: modules (other than sangam-rest) whose sources name a sangam-rest class
use = collections.defaultdict(lambda: collections.Counter())
for m in sorted(os.listdir(root)):
    if m == "sangam-rest" or not os.path.isdir(os.path.join(root, m)):
        continue
    for scope in ("main", "test"):
        base = os.path.join(root, m, "src", scope, "java")
        if not os.path.isdir(base):
            continue
        for d, _, fs in os.walk(base):
            for f in fs:
                if not f.endswith(".java"):
                    continue
                t = open(os.path.join(d, f), encoding="utf-8", errors="replace").read()
                for imp in set(re.findall(r"\borg\.sangam\.rest\.[\w.]+", t)):
                    k = imp if imp in classes else imp.rsplit(".", 1)[0] if imp.rsplit(".", 1)[0] in classes else None
                    if k:
                        use[(m, scope)]["tainted" if k in tainted else "free"] += 1
print("--- consumers (module scope: references to free / tainted sangam-rest classes)")
for (m, s), c in sorted(use.items()):
    print(f"{m} {s} free={c['free']} tainted={c['tainted']}")
