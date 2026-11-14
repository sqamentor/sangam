import json, sys
path = sys.argv[1]
needle = sys.argv[3]
found = []
with open(path, encoding="utf-8") as f:
    for line in f:
        try:
            o = json.loads(line)
        except Exception:
            continue
        m = o.get("message") or {}
        c = m.get("content")
        if o.get("type") == "assistant" and isinstance(c, list):
            for b in c:
                if isinstance(b, dict) and b.get("type") == "tool_use" and b.get("name") == "SendMessage":
                    msg = str((b.get("input") or {}).get("message", ""))
                    if needle in msg[:300]:
                        found.append((o.get("timestamp"), msg))
with open(sys.argv[2], "w", encoding="utf-8") as w:
    for ts, msg in found:
        w.write(f"===== {ts}\n{msg}\n\n")
print(len(found))
