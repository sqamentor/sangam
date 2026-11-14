import csv, sys, collections
rows = list(csv.DictReader(open(r"D:\GITHUB  Project\sangam-handoff\compliance\MAVEN-INVENTORY.csv", encoding="utf-8")))
mode = sys.argv[1]
if mode == "multi":
    for r in rows:
        d = r["declared_licence"]; s = r["spdx"]
        if ("|" in d or " OR " in s or "/" in s) and "distribution" in r["scopes"]:
            print(r["gav"], "||", d, "||", s, "||", r["decision"], "||", r["scopes"][:90])
elif mode == "multiall":
    for r in rows:
        d = r["declared_licence"]; s = r["spdx"]
        if ("|" in d or " OR " in s):
            print(r["gav"], "||", s, "||", r["decision"], "||", r["scopes"][:60])
elif mode == "build":
    for r in rows:
        if r["decision"] not in ("ACCEPTED FOR DEFINED USE", "ACCEPTED WITH CONDITIONS"):
            print(r["gav"], "||", r["spdx"][:70], "||", r["decision"], "||", r["scopes"][:70])
elif mode == "scopes":
    c = collections.Counter()
    for r in rows:
        for part in r["scopes"].split(";"):
            c[part.strip()[:80]] += 1
    for k, v in c.most_common(40): print(v, k)
elif mode == "decisions":
    print(collections.Counter(r["decision"] for r in rows))
