import os, subprocess, hashlib, json, sys, collections
I = r"D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524"
S = r"C:\Users\Lokendra Singh\AppData\Local\Temp\claude\5cef910d4d286f64\898f1ff7-0dcc-4d95-b425-830a9a5ead44\scratchpad"
SNAP = os.path.join(I, "handoff", "worktree-snapshots")
WTS = [r"D:\GITHUB  Project\Custom WorkFlow", r"D:\GITHUB  Project\cwf-docs-wt", r"D:\wt-fwt", r"D:\wt-scale"] + \
      [os.path.join(S, n) for n in "wt-auth401 wt-bulk wt-bulk2 wt-forms wt-fwtd wt-iso wt-packs4 wt-perf wt-perf-base wt-uiparity".split()]
SKIP = {".git", "target", "node_modules", "out", "dist", ".idea", ".mvn-cache", "build", ".gradle", "__pycache__", "worktree-snapshots", ".claude"}

def norm(b):
    return b.replace(b"\r\n", b"\n")
def h(b):
    return hashlib.sha1(norm(b)).hexdigest()
def rd(p):
    try:
        with open(p, "rb") as f: return f.read()
    except Exception: return None

# index integration tree by normalized hash
idx = collections.defaultdict(list)
for root, dirs, files in os.walk(I):
    dirs[:] = [d for d in dirs if d not in SKIP]
    for fn in files:
        p = os.path.join(root, fn)
        b = rd(p)
        if b is not None and len(b) < 20_000_000:
            idx[h(b)].append(os.path.relpath(p, I))

def lines(b):
    return [l.strip() for l in norm(b).decode("utf-8", "replace").split("\n") if l.strip()]

def git(wt, *a):
    return subprocess.run(["git", "-C", wt, *a], capture_output=True).stdout

def classify(rel, wtb, headb, intb):
    if intb is not None and h(intb) == h(wtb): return "SAME"
    if headb is not None and h(headb) == h(wtb): return "EOL_ONLY"
    if intb is None:
        hits = idx.get(h(wtb))
        return "MOVED:" + hits[0] if hits else "MISSING"
    added = set(lines(wtb)) - (set(lines(headb)) if headb else set())
    if not added: return "SUBSET_REMOVALS_ONLY"
    il = set(lines(intb))
    c = sum(1 for l in added if l in il) / len(added)
    return f"DIFF:{c:.3f}:{len(added)}"

out = {}
for wt in WTS:
    if not os.path.isdir(wt):
        out[wt] = "ABSENT"; continue
    raw = git(wt, "status", "--porcelain", "-z", "-uall")
    ents = raw.split(b"\0")
    res = []
    i = 0
    while i < len(ents):
        e = ents[i].decode("utf-8", "replace"); i += 1
        if not e: continue
        st, path = e[:2], e[3:]
        if st[0] in "RC": i += 1
        p = os.path.join(wt, path)
        if os.path.isdir(p): res.append((st, path, "DIR")); continue
        wtb = rd(p)
        intp = os.path.join(I, path)
        intb = rd(intp)
        if wtb is None:  # deleted
            res.append((st, path, "DEL_ALSO_IN_INT" if intb is None else "DEL_NOT_APPLIED"))
            continue
        headb = None
        if st != "??":
            headb = subprocess.run(["git", "-C", wt, "show", "HEAD:" + path.replace("\\", "/")], capture_output=True).stdout or None
        c = classify(path, wtb, headb, intb)
        # snapshot check
        sp = os.path.join(SNAP, os.path.basename(wt), path)
        sb = rd(sp)
        snap = "snapSAME" if sb is not None and h(sb) == h(wtb) else ("snapDIFF" if sb is not None else "snapNONE")
        res.append((st, path, c, snap))
    out[wt] = res

# sangam-wt-ci (non-git copy)
ci = r"D:\sangam-wt-ci"
res = []
for root, dirs, files in os.walk(ci):
    dirs[:] = [d for d in dirs if d not in SKIP]
    for fn in files:
        p = os.path.join(root, fn); rel = os.path.relpath(p, ci)
        b = rd(p); ib = rd(os.path.join(I, rel))
        if ib is not None and h(ib) == h(b): continue
        mainb = subprocess.run(["git", "-C", I, "show", "origin/main:" + rel.replace("\\", "/")], capture_output=True).stdout or None
        c = classify(rel, b, mainb, ib)
        if c == "EOL_ONLY": c = "EQ_ORIGIN_MAIN"
        res.append(("ci", rel, c))
out[ci] = res
json.dump(out, open(sys.argv[1], "w"), indent=0)
for k, v in out.items():
    if v == "ABSENT": print(k, "ABSENT"); continue
    cnt = collections.Counter(r[2].split(":")[0] for r in v)
    print(k, len(v), dict(cnt))
