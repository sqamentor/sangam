import sys, json, collections
c = collections.Counter(); ex = {}
with open(sys.argv[1], encoding="utf-8", errors="replace") as f:
    for line in f:
        if "no authenticated tenant" not in line:
            continue
        try:
            e = json.loads(line); m = e.get("message", ""); th = (e.get("process") or {}).get("thread", {}).get("name", "")
        except ValueError:
            continue
        i = m.find("Cannot "); key = m[i:i+40] if i >= 0 else m[:40]
        kind = "job-executor" if "JobExecutor" in th or "job" in th.lower() else "http"
        c[(kind, key)] += 1
        ex.setdefault((kind, key), (e.get("@timestamp"), th, m[m.find("ENGINE-"):][:200]))
for k, n in sorted(c.items()):
    print(f"{n:3d} {k[0]:12s} {k[1]}")
for k, v in ex.items():
    if k[0] == "job-executor":
        print("   ", v)
