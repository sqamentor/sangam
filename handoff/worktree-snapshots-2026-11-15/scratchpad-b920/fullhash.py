import io, os, re, subprocess, hashlib, datetime
H = r"D:\GITHUB  Project\sangam-handoff"
W = r"D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524"
now = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
st = subprocess.run(["git", "status", "--porcelain", "-uall"], cwd=W, capture_output=True, text=True, encoding="utf-8").stdout.splitlines()
paths = [l[3:].strip().strip('"') for l in st]
byname = {}
for p in paths:
    byname.setdefault(os.path.basename(p), []).append(p)
def sha(rel):
    return hashlib.sha256(open(os.path.join(W, rel), "rb").read()).hexdigest().upper()
board = io.open(H + r"\WAVE3-SYNC-BOARD.md", encoding="utf-8").read().split("\n")
out = []
TOK = re.compile(r"([A-Za-z][A-Za-z0-9_./-]*?)(?:\.java)?\s+\(?([0-9A-F]{8,64})\b")
report = []
for n in (133, 380, 394, 395, 396, 444, 457, 458):
    line = board[n - 1]
    items = []
    for m in TOK.finditer(line):
        name, pref = m.group(1).replace("\\", "/"), m.group(2).upper()
        b = os.path.basename(name)
        cands = byname.get(b, []) or byname.get(b + ".java", [])
        if len(cands) != 1:
            report.append(f"line {n}: {name} {pref[:8]} -> {len(cands)} candidates")
            continue
        h = sha(cands[0])
        items.append(f"{cands[0]} {h}" + ("" if h.startswith(pref[:8]) else f" (changed since {pref[:8]})"))
    if items:
        out.append(f"{now} | integrator | INFO | full SHA-256 for board line {n} (audit X5 item 6), computed at the Wave 3 close-out: " + "; ".join(items))
out = [x for x in out]
WD_SKIP = True
# W3-D 28 files: everything changed under sangam-security and deploy/compose/keycloak
wd = [p for p in paths if p.replace("\\", "/").startswith(("sangam/sangam-security/", "sangam/deploy/compose/keycloak/")) and os.path.isfile(os.path.join(W, p))]
if not WD_SKIP: out.append(f"{now} | integrator | INFO | full SHA-256 at the Wave 3 close-out for the W3-D release FILE-CLAIMS 1217 scope (sangam-security/** and deploy/compose/keycloak/**, {len(wd)} changed files incl. later W3-D rounds and W3-M compose edits): " + "; ".join(f"{p} {sha(p)}" for p in sorted(wd)))
for x in out:
    assert "|" not in x.split(" | ", 3)[3]
io.open(H + r"\WAVE3-SYNC-BOARD.md", "a", encoding="utf-8", newline="").write("".join(x + "\n" for x in out))
print("\n".join(report))
print("lines written", len(out), "W3-D files", len(wd))
