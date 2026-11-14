import json, sys, re
path, out, tsre, textre = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
tsr = re.compile(tsre)
txr = re.compile(textre, re.I)
res = []
with open(path, encoding="utf-8") as f:
    for line in f:
        try:
            o = json.loads(line)
        except Exception:
            continue
        ts = str(o.get("timestamp", ""))
        if not tsr.search(ts):
            continue
        t = o.get("type")
        if t == "assistant":
            continue
        blob = json.dumps(o, ensure_ascii=False)
        if not txr.search(blob):
            continue
        # extract readable text pieces
        pieces = []
        m = o.get("message") or {}
        c = m.get("content")
        if isinstance(c, str):
            pieces.append(c)
        elif isinstance(c, list):
            for b in c:
                if isinstance(b, dict) and b.get("type") == "text":
                    pieces.append(b.get("text", ""))
        if not pieces:
            for k in ("content", "operation", "attachment"):
                v = o.get(k)
                if v:
                    pieces.append(json.dumps(v, ensure_ascii=False)[:600])
        res.append(f"{ts} type={t} side={o.get('isSidechain')} :: " + " || ".join(p.replace(chr(10), ' ')[:500] for p in pieces))
with open(out, "w", encoding="utf-8") as w:
    w.write("\n\n".join(res))
print(len(res))
