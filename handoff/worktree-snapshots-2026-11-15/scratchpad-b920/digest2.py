import re, json, os, collections
S = os.path.dirname(os.path.abspath(__file__))
H = r"D:\GITHUB  Project\sangam-handoff"
E = json.load(open(os.path.join(S, "entries.json"), encoding="utf-8"))
items = json.load(open(os.path.join(S, "items.json"), encoding="utf-8"))
D = open(os.path.join(H, "OWNER-DECISIONS-2026-11-12.md"), encoding="utf-8").read().splitlines()
A = open(os.path.join(H, "WAVE4B-ASSIGNMENTS.md"), encoding="utf-8").read()
dec2m = collections.defaultdict(set)
for l in D:
    m = re.match(r"^\| (\d+R?) \|", l) or re.match(r"^- \*\*(\d+)", l) or re.match(r"^## (\d+)\.", l)
    if not m: continue
    n = m.group(1)
    for mid in re.findall(r"M-\d{3}[a-d]?", l):
        dec2m[mid].add(n)
for m in re.finditer(r"\b(\d{3}) (M-\d{3})", A):
    dec2m[m.group(2)].add(m.group(1))
json.dump({k: sorted(v) for k, v in dec2m.items()}, open(os.path.join(S, "dec2m.json"), "w"), indent=0)
def mp(mid):
    return re.compile(r"\b" + mid + (r"(?![0-9a-z])" if re.match(r"M-\d{3}$", mid) else r"\b"))
def dp(n):
    return re.compile(r"(?i)(?:decisions?[^|.;]{0,40}?\b%s\b|\b%s (?:CODED|PARTIAL|BLOCKED|NOT_RUN))" % (n, n))
ST = re.compile(r"\b(CODED|PARTIAL|BLOCKED|NOT_RUN|NOT RUN|DEFERRED|OPEN|STOPPED|nothing coded|not coded)\b")
with open(os.path.join(S, "digest2.txt"), "w", encoding="utf-8") as f:
    for it in items:
        mid = it["mid"]
        decs = sorted(dec2m.get(mid, []))
        pats = [("m", mp(mid))] + [("d" + d, dp(d.rstrip("R"))) for d in decs if d.rstrip("R").isdigit() and int(d.rstrip("R")) >= 56]
        hits = []
        for e in E:
            if e["type"] not in ("DONE", "CHANGE+DONE", "INFO", "BROKEN"): continue
            for tag, p in pats:
                m = p.search(e["text"])
                if m:
                    hits.append((e, tag, m.start())); break
        f.write("\n==== %s [%s w%s] decs=%s :: %s\n" % (mid, it["epic"], it["wave"], ",".join(decs), re.sub(r"\s+", " ", it["scope"])[:150]))
        for e, tag, st in hits[-5:]:
            t = e["text"]; s = max(0, st - 60)
            seg = t[s:s + 380]
            f.write("  %s %s %s %s [%s] %s\n" % (e["board"][-1], e["ts"][5:16], e["who"][:11], e["type"], tag, seg))
print("ok")
