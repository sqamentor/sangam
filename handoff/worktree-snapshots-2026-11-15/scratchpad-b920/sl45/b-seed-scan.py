import sys, json
n = 0
with open(sys.argv[1], encoding="utf-8", errors="replace") as f:
    for line in f:
        if "sl45-b-admin" not in line or not line.startswith("{"):
            continue
        try:
            e = json.loads(line)
        except ValueError:
            continue
        if (e.get("principal") or "") != "sl45-b-admin":
            continue
        m = e.get("message", "")
        lvl = (e.get("log") or {}).get("level")
        if lvl in ("WARN", "ERROR") or "Resolved [" in m:
            print(e.get("@timestamp"), lvl, (e.get("log") or {}).get("logger"), "|", m[:260].replace("\n", " "))
            n += 1
            if n >= 8:
                break
print("lines:", n)
