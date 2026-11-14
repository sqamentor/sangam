import re, json, collections, os, sys
H = r"D:\GITHUB  Project\sangam-handoff"
OUT = os.path.dirname(os.path.abspath(__file__))
master = open(os.path.join(H, "BACKLOG-0.1.0-MASTER.md"), encoding="utf-8").read().splitlines()
items = []
epic = ""
for line in master:
    m = re.match(r"^### (E\d+)", line)
    if m: epic = m.group(1)
    if re.match(r"^\| M-\d{3}", line):
        c = [x.strip() for x in line.split("|")]
        mid = c[1].replace("⚠", "").strip()
        items.append(dict(mid=mid, epic=epic, title=c[2], scope=c[3], wave=c[-2], cells=c))
ids = [i["mid"] for i in items]
entries = []
for b in ["WAVE2-SYNC-BOARD.md", "WAVE3-SYNC-BOARD.md", "WAVE4-SYNC-BOARD.md"]:
    for n, l in enumerate(open(os.path.join(H, b), encoding="utf-8").read().splitlines(), 1):
        p = l.split(" | ")
        if len(p) >= 4 and re.match(r"^20\d\d-\d\d-\d\dT", p[0].strip()):
            entries.append(dict(board=b[:5], line=n, ts=p[0].strip(), who=p[1].strip(), type=p[2].strip(),
                                text=" | ".join(p[3:-1]) if len(p) > 4 else p[3], target=p[-1].strip() if len(p) > 4 else ""))
json.dump(entries, open(os.path.join(OUT, "entries.json"), "w", encoding="utf-8"))
json.dump(items, open(os.path.join(OUT, "items.json"), "w", encoding="utf-8"), ensure_ascii=False)
def pat(mid):
    if re.match(r"M-\d{3}$", mid):
        return re.compile(r"\b" + mid + r"(?![0-9a-z])")
    return re.compile(r"\b" + mid + r"\b")
dig = {}
for it in items:
    p = pat(it["mid"])
    hits = [e for e in entries if p.search(e["text"])]
    dig[it["mid"]] = hits
with open(os.path.join(OUT, "digest.txt"), "w", encoding="utf-8") as f:
    for it in items:
        hits = dig[it["mid"]]
        f.write("\n==== %s [%s wave %s] %s :: %s\n" % (it["mid"], it["epic"], it["wave"], it["title"][:80], re.sub(r"\s+", " ", it["scope"])[:160]))
        sel = [h for h in hits if h["type"] in ("DONE", "INFO", "CHANGE+DONE", "BROKEN", "RELEASE")]
        f.write("  hits=%d sel=%d\n" % (len(hits), len(sel)))
        for h in sel[-6:]:
            t = h["text"]
            p2 = pat(it["mid"]).search(t)
            s = max(0, p2.start() - 150)
            f.write("  %s %s %s %s: %s\n" % (h["board"], h["ts"][5:16], h["who"][:10], h["type"], t[s:s + 420]))
print(len(entries), "entries")
print(collections.Counter(len(dig[i]) == 0 for i in ids))
print("no hits:", [i for i in ids if not dig[i]])
