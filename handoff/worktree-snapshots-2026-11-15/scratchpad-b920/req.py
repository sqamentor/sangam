import re, json, os, collections
S = os.path.dirname(os.path.abspath(__file__))
E = [e for e in json.load(open(os.path.join(S, "entries.json"), encoding="utf-8")) if e["board"] == "WAVE4"]
res = collections.Counter()
rows = []
for i, r in enumerate(E):
    if r["type"] != "REQUEST": continue
    t = r["ts"][11:19]  # HH:MM:SS
    d = r["ts"][:10]
    later = E[i + 1:]
    refs = [e for e in later if t in e["text"]]
    ans = [e for e in refs if e["type"] in ("ACK", "DONE", "CHANGE+DONE", "RELEASE")]
    info = [e for e in refs if e["type"] not in ("ACK", "DONE", "CHANGE+DONE", "RELEASE")]
    if ans: st = "ANSWERED"
    elif info: st = "REFERENCED_ONLY"
    else: st = "NO_REFERENCE"
    res[st] += 1
    if st != "ANSWERED":
        rows.append(dict(st=st, line=r["line"], ts=r["ts"], who=r["who"], target=r["target"], text=r["text"][:230],
                         refs=[(e["ts"][11:19], e["who"], e["type"], e["text"][:120]) for e in info[:2]]))
print(res)
json.dump(rows, open(os.path.join(S, "openreq.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=0)
for x in rows:
    print("%s L%d %s %s -> %s :: %s" % (x["st"], x["line"], x["ts"][5:19], x["who"], x["target"][:40], x["text"][:200]))
    for rr in x["refs"]: print("      ref:", rr)
