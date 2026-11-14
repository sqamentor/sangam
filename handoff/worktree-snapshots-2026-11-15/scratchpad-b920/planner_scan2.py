import json, sys, re
path, out, tsre = sys.argv[1], sys.argv[2], sys.argv[3]
tsr = re.compile(tsre)
res = []
with open(path, encoding="utf-8") as f:
    for line in f:
        try:
            o = json.loads(line)
        except Exception:
            continue
        ts = str(o.get("timestamp", ""))
        if not tsr.search(ts) or o.get("type") != "user":
            continue
        m = o.get("message") or {}
        c = m.get("content")
        if isinstance(c, list):
            for b in c:
                if isinstance(b, dict) and b.get("type") == "tool_result":
                    cc = b.get("content")
                    txt = cc if isinstance(cc, str) else " ".join(x.get("text", "") for x in (cc or []) if isinstance(x, dict))
                    if re.search(r"answer|User has answered|selected|user chose|user said", txt, re.I):
                        res.append(f"{ts} :: {txt.replace(chr(10), ' ')[:900]}")
with open(out, "w", encoding="utf-8") as w:
    w.write("\n\n".join(res))
print(len(res))
