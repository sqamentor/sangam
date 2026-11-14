import json, sys
path = sys.argv[1]
rows = []
results = {}
with open(path, encoding="utf-8") as f:
    for line in f:
        try:
            o = json.loads(line)
        except Exception:
            continue
        ts = o.get("timestamp", "?")
        m = o.get("message") or {}
        c = m.get("content")
        if o.get("type") == "assistant" and isinstance(c, list):
            for b in c:
                if isinstance(b, dict) and b.get("type") == "tool_use" and b.get("name") in ("SendMessage",):
                    inp = b.get("input") or {}
                    msg = str(inp.get("message", ""))
                    rows.append((ts, b.get("id"), str(inp.get("to", "")), msg.split("\n")[0][:220], o.get("isSidechain", False)))
        if o.get("type") == "user" and isinstance(c, list):
            for b in c:
                if isinstance(b, dict) and b.get("type") == "tool_result":
                    tid = b.get("tool_use_id")
                    cc = b.get("content")
                    txt = ""
                    if isinstance(cc, str):
                        txt = cc
                    elif isinstance(cc, list):
                        txt = " ".join(x.get("text", "") for x in cc if isinstance(x, dict))
                    results[tid] = (ts, txt.replace("\n", " ")[:200])
with open(sys.argv[2], "w", encoding="utf-8") as w:
    for ts, tid, to, head, side in rows:
        r = results.get(tid, ("?", "?"))
        w.write(f"{ts} | sidechain={side} | to={to} | {head}\n    RESULT {r[0]}: {r[1]}\n\n")
print(len(rows), "sends")
