import re, os, sys, json
root = r"D:\GITHUB  Project\Custom WorkFlow\sangam"
KW = json.load(open(sys.argv[1], encoding="utf-8"))
pat = re.compile(r"(?<![A-Za-z0-9-])((?:FLOWABLE-INTO-OPERATON |ADR-)?D-\d+)\b")
miss = {}
for dp, dn, fn in os.walk(root):
    if "node_modules" in dp or "\\src\\main" not in dp:
        continue
    for f in fn:
        if not f.endswith(".java"):
            continue
        lines = open(os.path.join(dp, f), encoding="utf-8").read().split("\n")
        for i, l in enumerate(lines):
            for m in pat.finditer(l):
                c = m.group(1)
                if c.startswith("D-"):
                    continue  # bare: codes / rule D-0 / date; handled by the guard's own rule
                key = c.replace("FLOWABLE-INTO-OPERATON ", "FIO:").replace("ADR-", "ADR:")
                ctx = " ".join(lines[max(0, i - 3):i + 4]).lower()
                kws = KW.get(key)
                if kws is None or not any(k in ctx for k in kws):
                    miss.setdefault(key, []).append(f + ":" + str(i + 1) + " " + l.strip()[:110])
for k, v in sorted(miss.items()):
    print(k, len(v))
    for x in v[:3]:
        print("   ", x)
