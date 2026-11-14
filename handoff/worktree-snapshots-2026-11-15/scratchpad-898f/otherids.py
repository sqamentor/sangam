import re, os, collections
base = r"D:\GITHUB  Project\Custom WorkFlow"
sangam = open(base + r"\SANGAM.md", encoding="utf-8").read()
docs = {}
for dp, dn, fn in os.walk(base):
    if any(x in dp for x in ("node_modules", "reference", "\\target", ".git")):
        continue
    for f in fn:
        if f.endswith(".md"):
            try:
                docs[os.path.relpath(os.path.join(dp, f), base)] = open(os.path.join(dp, f), encoding="utf-8").read()
            except Exception:
                pass
pat = re.compile(r"(?<![A-Za-z0-9-])([A-Z]{1,3})-(\d+)\b")
seen = collections.Counter()
for dp, dn, fn in os.walk(base + r"\sangam"):
    if "node_modules" in dp or "\\src\\main" not in dp:
        continue
    for f in fn:
        if f.endswith(".java"):
            for m in pat.finditer(open(os.path.join(dp, f), encoding="utf-8").read()):
                seen[m.group(1)] += 1
print(seen.most_common(40))
