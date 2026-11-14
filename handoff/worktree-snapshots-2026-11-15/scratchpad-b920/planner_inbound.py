import json, sys, re
path, out, since = sys.argv[1], sys.argv[2], sys.argv[3]
res = []
with open(path, encoding="utf-8") as f:
    for line in f:
        try:
            o = json.loads(line)
        except Exception:
            continue
        ts = str(o.get("timestamp", ""))
        if ts < since or o.get("type") != "user" or o.get("isSidechain"):
            continue
        m = o.get("message") or {}
        c = m.get("content")
        texts = []
        if isinstance(c, str):
            texts.append(c)
        elif isinstance(c, list):
            for b in c:
                if isinstance(b, dict) and b.get("type") == "text":
                    texts.append(b.get("text", ""))
        for t in texts:
            if "cross-session-message" in t:
                res.append(f"===== {ts}\n{t}\n")
with open(out, "w", encoding="utf-8") as w:
    w.write("\n".join(res))
print(len(res))
