import os, re, sys
# Light stale-symbol check: every org.sangam import in a test source resolves to a type declared in some source file.
R = sys.argv[1]
SKIP = lambda n: n.startswith("target") or n in ("node_modules", ".git")
types = set()          # fully-qualified top-level and nested type names
pkgs = set()
members = {}           # fq type -> set of identifiers appearing in it (for static imports)
TYPE = re.compile(r"\b(?:class|interface|enum|record|@interface)\s+([A-Z]\w*)")
PKG = re.compile(r"(?m)^package\s+([\w.]+)\s*;")
tests = []
for d, ds, fs in os.walk(R):
    ds[:] = [x for x in ds if not SKIP(x)]
    for f in fs:
        if not f.endswith(".java"): continue
        p = os.path.join(d, f); r = os.path.relpath(p, R).replace("\\", "/")
        t = open(p, encoding="utf-8", errors="replace").read()
        m = PKG.search(t)
        if not m: continue
        pk = m.group(1); pkgs.add(pk)
        outer = f[:-5]
        fq = pk + "." + outer
        types.add(fq)
        ids = set(re.findall(r"\b[A-Za-z_]\w*\b", t))
        members[fq] = ids
        for n in TYPE.findall(t):
            if n != outer:
                types.add(fq + "." + n); members[fq + "." + n] = ids
        if "/src/test/" in r: tests.append((r, t))
IMP = re.compile(r"(?m)^\s*import\s+(static\s+)?(org\.sangam\.[\w.]+?)(\.\*)?\s*;")
bad = []
for r, t in tests:
    for m in IMP.finditer(t):
        static, name, star = m.group(1), m.group(2), m.group(3)
        line = t[:m.start()].count("\n") + 1
        if star and not static:
            if name not in pkgs and name not in types: bad.append(f"{r}:{line}: import {name}.*")
            continue
        if static:
            if star:
                if name not in types: bad.append(f"{r}:{line}: import static {name}.*")
                continue
            owner, _, mem = name.rpartition(".")
            if owner not in types: bad.append(f"{r}:{line}: import static {name} (no type {owner})")
            elif mem not in members.get(owner, ()): bad.append(f"{r}:{line}: import static {name} (no member {mem})")
            continue
        if name not in types: bad.append(f"{r}:{line}: import {name}")
print("tests", len(tests), "unresolved imports", len(bad))
for b in bad: print("  ", b)
