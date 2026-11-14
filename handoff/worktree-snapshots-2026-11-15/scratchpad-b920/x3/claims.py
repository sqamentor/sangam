import subprocess, re, os, sys, json, hashlib, tarfile, fnmatch, collections, io

REPO = r"D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524"
HAND = r"D:\GITHUB  Project\sangam-handoff"
OUT = os.path.dirname(os.path.abspath(__file__))
BASE, CP = "1105d9c", "0e53f91b"
CP_TIME = "2026-11-12T07:07:55Z"

def git(*a, binary=False):
    r = subprocess.run(["git", "-C", REPO, *a], capture_output=True)
    if r.returncode != 0:
        raise RuntimeError(r.stderr.decode(errors="replace"))
    return r.stdout if binary else r.stdout.decode("utf-8", errors="replace")

# ---------- changed files ----------
changed = []  # (status, path, oldpath)
for ln in git("diff", "--name-status", "-M", BASE, CP).splitlines():
    parts = ln.split("\t")
    st = parts[0]
    if st.startswith("R"):
        changed.append((st, parts[2], parts[1]))
    else:
        changed.append((st, parts[1], None))
paths = [c[1] for c in changed]
pathset = set(paths)

# ---------- blob contents at CP ----------
blob = {}
for st, p, _ in changed:
    if st == "D":
        continue
    blob[p] = git("show", f"{CP}:{p}", binary=True)

def variants(b):
    lf = b.replace(b"\r\n", b"\n")
    crlf = lf.replace(b"\n", b"\r\n")
    out = {}
    for name, v in (("raw", b), ("lf", lf), ("crlf", crlf)):
        out[name] = hashlib.sha256(v).hexdigest().upper()
    return out

hashes = {p: variants(b) for p, b in blob.items()}

# ---------- per-file diff vs snapshots ----------
def split_patch(text):
    res = {}
    cur = None
    buf = []
    for line in text.split("\n"):
        if line.startswith("diff --git "):
            if cur:
                res[cur] = buf
            m = re.match(r"diff --git a/(.*?) b/(.*)$", line)
            cur = m.group(2) if m else line
            buf = []
            continue
        if cur is None:
            continue
        if line.startswith("index ") or line.startswith("similarity") or line.startswith("rename ") or line.startswith("new file mode") or line.startswith("deleted file mode") or line.startswith("old mode") or line.startswith("new mode"):
            continue
        buf.append(line.rstrip("\r"))
    if cur:
        res[cur] = buf
    return res

cp_patch = split_patch(git("diff", "--no-renames", BASE, CP))

snap = {}
for name in ("20261111-1924", "20261112-0923"):
    d = os.path.join(HAND, "snapshots", name)
    with open(os.path.join(d, "tracked.patch"), "rb") as f:
        tp = split_patch(f.read().decode("utf-8", errors="replace"))
    untracked = {}
    tf = os.path.join(d, "untracked.tar")
    with tarfile.open(tf) as t:
        for m in t.getmembers():
            if m.isfile():
                n = m.name.replace("\\", "/")
                for pre in ("./",):
                    if n.startswith(pre):
                        n = n[len(pre):]
                untracked[n] = t.extractfile(m).read()
    snap[name] = (tp, untracked)

def norm(b):
    return b.replace(b"\r\n", b"\n")

def state_vs_snapshot(p, name):
    """'same' if file at CP equals file at snapshot; 'diff' otherwise; 'absent' if snapshot did not touch it."""
    tp, un = snap[name]
    # untracked new file at snapshot
    cand = [k for k in un if k == p or k.endswith("/" + p)]
    if cand:
        b = un[cand[0]]
        if p in blob and norm(b) == norm(blob[p]):
            return "same"
        return "diff"
    if p in tp:
        if p in cp_patch and tp[p] == cp_patch[p]:
            return "same"
        return "diff"
    return "absent"  # snapshot had it unchanged vs base

file_age = {}
for st, p, _ in changed:
    a = state_vs_snapshot(p, "20261111-1924")
    b = state_vs_snapshot(p, "20261112-0923")
    # changed_after_1924: CP differs from 1924 snapshot state
    after1924 = a != "same"
    after0923 = b != "same"
    file_age[p] = {"s1924": a, "s0923": b, "after1924": after1924, "after0923": after0923}

# ---------- claims ----------
with open(os.path.join(HAND, "FILE-CLAIMS.md"), "rb") as f:
    raw_claims = f.read()
claims_text = raw_claims.decode("utf-8", errors="replace")
lines = claims_text.split("\n")

TS = re.compile(r"^(\d{4}-\d\d-\d\dT\d\d:\d\d(?::\d\d)?Z)\s+(.*)$")
def norm_ts(t):
    if len(t) == 17:  # 2026-11-11T18:46Z
        t = t[:-1] + ":00Z"
    return t

events = []
for i, ln in enumerate(lines, 1):
    m = TS.match(ln.strip())
    if not m:
        continue
    ts = norm_ts(m.group(1))
    rest = m.group(2)
    toks = rest.split()
    action = None
    for j, tk in enumerate(toks[:6]):
        if tk.rstrip(":") in ("CLAIM", "RELEASE", "NOTE", "PROPOSAL"):
            action = tk.rstrip(":")
            lane = " ".join(toks[:j])
            body = " ".join(toks[j + 1:])
            break
    if not action:
        continue
    events.append({"line": i, "ts": ts, "lane": lane, "action": action, "body": body, "text": ln})

def brace_expand(s):
    m = re.search(r"\{([^{}]*)\}", s)
    if not m:
        return [s]
    pre, post = s[:m.start()], s[m.end():]
    out = []
    for alt in m.group(1).split(","):
        alt = alt.strip()
        alt = re.sub(r"\(new[^)]*\)", "", alt)
        alt = re.sub(r"\s+[0-9A-Fa-f]{8,64}$", "", alt)
        alt = re.sub(r"\s*\(sha [0-9A-Fa-f]+\)", "", alt).strip()
        out.extend(brace_expand(pre + alt + post))
    return out

PATHTOK = re.compile(r"[\w.\-*{}/,()\s]+")

def path_tokens(body):
    """Return expanded path-like tokens."""
    toks = set()
    # find candidate regions: sequences containing '/' possibly with braces
    for m in re.finditer(r"[A-Za-z0-9_.\-*]*(?:/[A-Za-z0-9_.\-*]*|\{[^{}]*\})+[A-Za-z0-9_.\-*]*(?:\.[A-Za-z0-9]+)?", body):
        s = m.group(0).strip().strip(",;:)")
        if "/" not in s and "{" not in s:
            continue
        for e in brace_expand(s):
            e = e.replace("(new)", "").strip().rstrip(".,;:")
            if e:
                toks.add(e)
    return toks

DIR_OK = {}
STEM_RX = {}
def glob_match(tok, p):
    if tok.endswith("/"):
        if tok not in DIR_OK:
            DIR_OK[tok] = sum(1 for q in paths if q.startswith(tok) or ("/" + tok) in q) <= 30 and tok.count("/") >= 1 and tok not in ("sangam/",)
        return DIR_OK[tok] and (p.startswith(tok) or ("/" + tok) in p)
    if "*" in tok:
        rx = re.escape(tok).replace(r"\*\*", ".*").replace(r"\*", "[^/]*")
        if re.fullmatch(rx, p) or re.search("(^|/)" + rx + "$", p):
            return True
        return False
    return p == tok or p.endswith("/" + tok)

GENERIC = {"README", "pom", "application", "values", "en", "hi", "package", "package-lock", "index", "main", "common",
           "Layout", "App", "nav", "access", "storage", "crypto", "canonical", "session", "ai", "ops", "release", "security",
           "ci-build", "quality", "SANGAM", "HANDOFF", "lib", "goi", "states", "central", "delta", "skeletons", "money",
           "readme", "packs", "gov1", "gov2", "gov3", "rollup", "gateway", "controlplane", "onboarding", "modeller",
           "_v2_spring", "billtypes", "layers", "GOI", "RJ", "MH", "MP", "BR", "HP", "US", "IN", "GBR", "fieldSync",
           "governance", "CODEOWNERS", "licence-policy", "values.schema", "server-configmap", "ingress", "scale",
           "networkpolicy", "prometheus", "docker-compose", "registry", "endpoints", "MyWork", "pack", "dco_check",
           "AT-1", "proposal.form", "pack-requires"}

def module_of(p):
    parts = p.split("/")
    if parts[0] == "sangam" and len(parts) > 1:
        return parts[1]
    return parts[0]

stem_count = collections.Counter(os.path.basename(p).split(".")[0] for p in paths)

def match_event(ev, p):
    body = ev["body"]
    for t in ev["_toks"]:
        if glob_match(t, p):
            return "path"
    base = os.path.basename(p)
    stem = base.split(".")[0]
    if stem not in body:
        return None
    rx = STEM_RX.get(stem)
    if rx is None:
        rx = STEM_RX[stem] = re.compile(r"(?<![\w\-])" + re.escape(stem) + r"(?![\w\-])")
    if not rx.search(body):
        return None
    mod = module_of(p)
    has_mod = mod in body or ("/" + mod + "/") in body
    # directory hint: parent dir name present
    parent = p.split("/")[-2] if "/" in p else ""
    if stem in GENERIC or len(stem) < 6:
        if has_mod and (parent in body or base in body):
            return "stem+mod"
        return None
    if has_mod:
        return "stem+mod"
    if stem_count[stem] == 1:
        return "stem-unique"
    return None

for ev in events:
    ev["_toks"] = path_tokens(ev["body"])

in_scope_events = [e for e in events if e["ts"] <= CP_TIME and e["action"] in ("CLAIM", "RELEASE")]

file_events = collections.defaultdict(list)
for p in paths:
    for ev in in_scope_events:
        mt = match_event(ev, p)
        if mt:
            file_events[p].append((ev, mt))

HEX = re.compile(r"(?<![0-9A-Za-z])([0-9A-Fa-f]{64}|[0-9A-Fa-f]{8})(?![0-9A-Za-z])")

def hash_for(ev, p):
    """hex token attributed to file p in a RELEASE line: first hex after the file mention."""
    body = ev["body"]
    base = os.path.basename(p)
    stem = base.split(".")[0]
    positions = [m.end() for m in re.finditer(r"(?<![\w\-])" + re.escape(stem) + r"(?![\w\-])", body)]
    # also a full path mention
    for t in ev["_toks"]:
        if glob_match(t, p) and "*" not in t:
            i = body.find(t.split("/")[-1])
            if i >= 0:
                positions.append(i + len(t.split("/")[-1]))
    hexes = [(m.start(), m.group(1).upper()) for m in HEX.finditer(body)]
    hexes = [h for h in hexes if not re.fullmatch(r"\d{8}", h[1])]  # drop pure-digit 8 tokens (dates etc.)
    best = None
    for pos in sorted(positions):
        after = [h for h in hexes if h[0] >= pos]
        if after:
            # also check hex appears before the next mention of another file name? keep simple
            # hex placed before name, e.g. "7566ACD6 billtypes.py"
            best = after[0][1]
            break
    before = None
    for pos in sorted(positions):
        bef = [h for h in hexes if h[0] < pos]
        if bef:
            before = bef[-1][1]
    return best, before, [h[1] for h in hexes]

report = {"files": {}, "summary": {}}
for st, p, old in changed:
    evs = sorted(file_events.get(p, []), key=lambda x: (x[0]["ts"], x[0]["line"]))
    recs = []
    for ev, mt in evs:
        r = {"line": ev["line"], "ts": ev["ts"], "lane": ev["lane"], "action": ev["action"], "match": mt}
        if ev["action"] == "RELEASE" and p in hashes:
            after, before, allh = hash_for(ev, p)
            hv = hashes[p]
            def ok(h):
                return h and any(v.startswith(h) for v in hv.values())
            r["hash_after"] = after
            r["hash_before"] = before
            r["hash_any_match"] = [h for h in allh if any(v.startswith(h) for v in hv.values())]
            r["n_hex"] = len(allh)
        recs.append(r)
    report["files"][p] = {"status": st, "old": old, "age": file_age[p], "events": recs,
                          "sha": hashes.get(p)}

with open(os.path.join(OUT, "claims_report.json"), "w", encoding="utf-8") as f:
    json.dump(report, f, indent=1)

# events list too
with open(os.path.join(OUT, "events.json"), "w", encoding="utf-8") as f:
    json.dump([{k: v for k, v in e.items() if k != "_toks"} | {"toks": sorted(e["_toks"])} for e in events], f, indent=1)
print("files", len(paths), "events", len(events), "inscope", len(in_scope_events))
c = collections.Counter()
for p, d in report["files"].items():
    c[(d["age"]["after1924"], d["age"]["after0923"], bool(d["events"]))] += 1
for k, v in sorted(c.items()):
    print(k, v)
