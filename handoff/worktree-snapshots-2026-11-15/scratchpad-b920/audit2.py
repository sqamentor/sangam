import os, subprocess, hashlib, json, sys, collections
I = r"D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524"
S = r"C:\Users\Lokendra Singh\AppData\Local\Temp\claude\5cef910d4d286f64\898f1ff7-0dcc-4d95-b425-830a9a5ead44\scratchpad"
SNAP = os.path.join(I, "handoff", "worktree-snapshots")
SKIP = {".git", "target", "node_modules", "out", "dist", ".idea", "build", "__pycache__", "worktree-snapshots", ".claude", "venv", ".venv", "coverage"}
def norm(b): return b.replace(b"\r\n", b"\n")
def blob(b):
    b = norm(b); return hashlib.sha1(b"blob %d\0" % len(b) + b).hexdigest()
def rd(p):
    try:
        with open(p, "rb") as f: return f.read()
    except Exception: return None
def lines(b): return [l.strip() for l in norm(b).decode("utf-8", "replace").split("\n") if l.strip()]
# known historical blobs: every blob reachable in any commit's tree is expensive; use origin/main, 9c831d0, 15e4135, 0196b57 trees
known = set()
for ref in ["origin/main", "9c831d0", "15e4135", "0196b57", "2aaf44f", "3736f8f", "16e0e11c", "f553b43"]:
    out = subprocess.run(["git", "-C", I, "ls-tree", "-r", ref], capture_output=True).stdout.decode("utf-8", "replace")
    for l in out.splitlines():
        known.add(l.split()[2])
# integration working-tree index
idx = {}
for root, dirs, files in os.walk(I):
    dirs[:] = [d for d in dirs if d not in SKIP]
    for fn in files:
        p = os.path.join(root, fn); b = rd(p)
        if b is not None and len(b) < 30_000_000: idx.setdefault(blob(b), os.path.relpath(p, I))
targets = sys.argv[2:]
res = {}
for t in targets:
    d = t if os.path.isabs(t) else os.path.join(S, t)
    r = []
    for root, dirs, files in os.walk(d):
        dirs[:] = [x for x in dirs if x not in SKIP]
        for fn in files:
            p = os.path.join(root, fn); rel = os.path.relpath(p, d)
            b = rd(p)
            if b is None or len(b) > 30_000_000: r.append((rel, "BIG_OR_UNREADABLE")); continue
            bh = blob(b)
            ib = rd(os.path.join(I, rel))
            if ib is not None and blob(ib) == bh: continue  # SAME
            if bh in idx: r.append((rel, "MOVED:" + idx[bh])); continue
            if bh in known: r.append((rel, "OLD_COMMITTED_VERSION")); continue
            sb = rd(os.path.join(SNAP, os.path.basename(d), rel))
            snap = "snapSAME" if sb is not None and blob(sb) == bh else ""
            if ib is None: r.append((rel, "MISSING", snap)); continue
            il = set(lines(ib)); wl = set(lines(b))
            c = sum(1 for l in wl if l in il) / max(1, len(wl))
            r.append((rel, f"DIFF:{c:.3f}:{len(wl - il)}", snap))
    res[t] = r
    cnt = collections.Counter(x[1].split(":")[0] for x in r)
    print(t, dict(cnt), flush=True)
json.dump(res, open(sys.argv[1], "w"), indent=0)
