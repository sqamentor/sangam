import re, os, sys, collections
root = r"D:\GITHUB  Project\Custom WorkFlow\sangam"
pat = re.compile(r"(?<![A-Za-z0-9-])(ADR-)?D-(\d+)\b")
by = collections.defaultdict(list)
for dp, dn, fn in os.walk(root):
    if "node_modules" in dp or "\\target" in dp or "\\src\\main" not in dp:
        continue
    for f in fn:
        if not f.endswith(".java"):
            continue
        p = os.path.join(dp, f)
        lines = open(p, encoding="utf-8", errors="replace").read().split("\n")
        for i, l in enumerate(lines):
            for m in pat.finditer(l):
                pre = l[max(0, m.start() - 25):m.start()]
                key = ("ADR-" if m.group(1) else "") + "D-" + m.group(2)
                if "FLOWABLE-INTO-OPERATON" in pre:
                    key = "FIO:" + key
                by[key].append((os.path.relpath(p, root), i + 1, l.strip()[:150]))
for k in sorted(by, key=lambda x: (len(x), x)):
    v = by[k]
    print(f"== {k} ({len(v)})")
    for s in v[: int(sys.argv[1]) if len(sys.argv) > 1 else 2]:
        print("   ", s[0].split("\\")[0], s[1], "|", s[2])
