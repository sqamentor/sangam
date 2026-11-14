import os, subprocess, hashlib, json, sys, collections
I = r"D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524"
S = r"C:\Users\Lokendra Singh\AppData\Local\Temp\claude\5cef910d4d286f64\898f1ff7-0dcc-4d95-b425-830a9a5ead44\scratchpad"
SNAP = os.path.join(I, "handoff", "worktree-snapshots")
SKIPD = {".git", "node_modules", "out", "dist", "build", "__pycache__", "worktree-snapshots", ".claude", "venv", ".venv", "coverage", ".gradle", ".idea", ".vite"}
SKIPEXT = (".class", ".jar", ".log", ".jfr", ".lst", ".war")
def skipdir(d): return d in SKIPD or d.startswith("target") or d.startswith("logs-") or d.startswith(".m2")
def skippath(p):
    parts = p.replace("\\", "/").split("/")
    return any(skipdir(x) for x in parts[:-1]) or p.lower().endswith(SKIPEXT)
def norm(b): return b.replace(b"\r\n", b"\n")
def blob(b):
    b = norm(b); return hashlib.sha1(b"blob %d\0" % len(b) + b).hexdigest()
def rawblob(b): return hashlib.sha1(b"blob %d\0" % len(b) + b).hexdigest()
def rd(p):
    try:
        with open(p, "rb") as f: return f.read()
    except Exception: return None
def lines(b): return [l.strip() for l in norm(b).decode("utf-8", "replace").split("\n") if l.strip()]
def tree(repo, ref):
    out = subprocess.run(["git", "-C", repo, "ls-tree", "-r", ref], capture_output=True).stdout.decode("utf-8", "replace")
    m = {}
    for l in out.splitlines():
        meta, path = l.split("\t", 1); m[path] = meta.split()[2]
    return m
class Cat:
    def __init__(s, repo):
        s.p = subprocess.Popen(["git", "-C", repo, "cat-file", "--batch"], stdin=subprocess.PIPE, stdout=subprocess.PIPE)
    def get(s, oid):
        s.p.stdin.write((oid + "\n").encode()); s.p.stdin.flush()
        hdr = s.p.stdout.readline().split()
        if len(hdr) < 3: return None
        n = int(hdr[2]); data = s.p.stdout.read(n); s.p.stdout.read(1); return data
known = set(); known_snap = set()
for ref in ["origin/main", "9c831d0", "15e4135", "0196b57", "2aaf44f", "3736f8f", "16e0e11c", "f553b43", "7cac6c4e"]:
    for pth, oid in tree(I, ref).items():
        if pth.startswith("handoff/"): known_snap.add(oid)
        else: known.add(oid)
idx = {}
for root, dirs, files in os.walk(I):
    dirs[:] = [d for d in dirs if not skipdir(d)]
    for fn in files:
        if fn.lower().endswith(SKIPEXT): continue
        p = os.path.join(root, fn); b = rd(p)
        if b is not None and len(b) < 30_000_000: idx.setdefault(blob(b), os.path.relpath(p, I))
print("idx", len(idx), flush=True)
def contain(wtb, base, ib):
    added = set(lines(wtb)) - (set(lines(base)) if base else set())
    if not added: return "REMOVALS_ONLY"
    il = set(lines(ib)); c = sum(1 for l in added if l in il) / len(added)
    return f"DIFF:{c:.3f}:{len(added)}:{len([l for l in added if l not in il])}"
def judge(rel, wtb, baseb, snapname):
    bh = blob(wtb)
    ib = rd(os.path.join(I, rel))
    if ib is not None and blob(ib) == bh: return None
    if baseb is not None and blob(baseb) == bh: return "EOL_ONLY_OR_UNCHANGED"
    if bh in idx: return "MOVED:" + idx[bh]
    if bh in known and ib is not None: return "OLD_COMMITTED_VERSION"
    sb = rd(os.path.join(SNAP, snapname, rel))
    snap = " [snapSAME]" if sb is not None and blob(sb) == bh else ""
    if bh in known_snap: snap += " [inSnapshotCommit]"
    if ib is None: return ("MISSING_OLDCOMMITTED" if bh in known else "MISSING") + snap
    return contain(wtb, baseb, ib) + snap
res = {}
mode = sys.argv[2]
for t in sys.argv[3:]:
    d = t if os.path.isabs(t) else os.path.join(S, t)
    name = os.path.basename(d.rstrip("\\/"))
    r = []
    if mode == "git":
        head = tree(d, "HEAD"); cat = Cat(d)
        raw = subprocess.run(["git", "-C", d, "status", "--porcelain", "-z", "-uall"], capture_output=True).stdout.split(b"\0")
        i = 0
        while i < len(raw):
            e = raw[i].decode("utf-8", "replace"); i += 1
            if not e: continue
            st, path = e[:2], e[3:]
            if st[0] in "RC": i += 1
            if skippath(path): continue
            wtb = rd(os.path.join(d, path))
            if wtb is None:
                r.append((st, path, "DEL_ALSO_IN_INT" if rd(os.path.join(I, path)) is None else "DEL_NOT_APPLIED_IN_INT")); continue
            baseb = cat.get(head[path]) if path in head else None
            j = judge(path, wtb, baseb, name)
            if j: r.append((st, path, j))
    else:
        for root, dirs, files in os.walk(d):
            dirs[:] = [x for x in dirs if not skipdir(x)]
            for fn in files:
                if fn.lower().endswith(SKIPEXT): continue
                p = os.path.join(root, fn); rel = os.path.relpath(p, d)
                b = rd(p)
                if b is None or len(b) > 30_000_000: r.append(("f", rel, "BIG_OR_UNREADABLE")); continue
                mb = None
                j = judge(rel, b, None, name)
                if j: r.append(("f", rel, j))
    res[t] = r
    cnt = collections.Counter(x[2].split(":")[0].split(" ")[0] for x in r)
    print(name, dict(cnt), flush=True)
json.dump(res, open(sys.argv[1], "w"), indent=0)
