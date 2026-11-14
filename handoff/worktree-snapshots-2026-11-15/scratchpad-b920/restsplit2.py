"""For the candidate packages: references back into the rest of sangam-rest, and the org.sangam modules they import."""
import collections
import os
import re
import sys

root = sys.argv[1]
rest = os.path.join(root, "sangam-rest", "src", "main", "java")
CAND = {"org.sangam.rest.port", "org.sangam.rest.adapter.jdbc", "org.sangam.rest.adapter.memory",
        "org.sangam.rest.adapter.operaton", "org.sangam.rest.support", "org.sangam.rest.extension"}

classes = {}
for d, _, fs in os.walk(rest):
    for f in fs:
        if f.endswith(".java"):
            p = os.path.join(d, f)
            classes[os.path.relpath(p, rest)[:-5].replace(os.sep, ".")] = open(p, encoding="utf-8").read()


def strip(t):
    t = re.sub(r"/\*.*?\*/", " ", t, flags=re.S)
    t = re.sub(r"//[^\n]*", " ", t)
    return re.sub(r'"(?:\\.|[^"\\])*"', '""', t)


# map org.sangam packages to modules
pkg_mod = {}
for m in os.listdir(root):
    base = os.path.join(root, m, "src", "main", "java")
    if m == "sangam-rest" or not os.path.isdir(base):
        continue
    for d, _, fs in os.walk(base):
        if any(f.endswith(".java") for f in fs):
            pkg_mod.setdefault(os.path.relpath(d, base).replace(os.sep, "."), set()).add(m)

back = collections.defaultdict(set)
mods = collections.Counter()
ext = collections.Counter()
for fqn, t in classes.items():
    pkg = fqn.rsplit(".", 1)[0]
    if pkg not in CAND:
        continue
    code = strip(t)
    for other in classes:
        opkg = other.rsplit(".", 1)[0]
        if opkg not in CAND and other in code:
            back[fqn].add(other)
    for imp in set(re.findall(r"\b(org\.sangam\.[a-z0-9_.]+)\.[A-Z]", code)):
        if imp.startswith("org.sangam.rest"):
            continue
        for m in pkg_mod.get(imp, {"?" + imp}):
            mods[m] += 1
    for imp in set(re.findall(r"^import (?:static )?((?:com|io|org|jakarta|javax|net)\.[a-z0-9]+(?:\.[a-z0-9]+)?)", code, re.M)):
        if not imp.startswith("org.sangam"):
            ext[imp] += 1
print("--- references from candidate packages to the rest of sangam-rest")
for k, v in sorted(back.items()):
    print(k, "->", sorted(v))
print("--- org.sangam modules used (files)")
for m, c in mods.most_common():
    print(m, c)
print("--- third-party import roots (files)")
for m, c in ext.most_common():
    print(m, c)
print("--- module-info present:", any(os.path.exists(os.path.join(root, m, "src", "main", "java", "module-info.java")) for m in os.listdir(root)))
