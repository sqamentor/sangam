import sys, json
pats = ["may not be created in tenant", "belongs to no authenticated tenant", "needs exactly one tenant"]
seen = {p: 0 for p in pats}
ex = {}
with open(sys.argv[1], encoding="utf-8", errors="replace") as f:
    for line in f:
        for p in pats:
            if p in line:
                seen[p] += 1
                if p not in ex:
                    try:
                        e = json.loads(line); m = e.get("message", "")
                        ex[p] = (e.get("@timestamp"), (e.get("log") or {}).get("logger"), m[:330])
                    except ValueError:
                        ex[p] = line[:330]
for p in pats:
    print(f"{seen[p]:5d}  {p}")
    if p in ex: print("       ", ex[p])
