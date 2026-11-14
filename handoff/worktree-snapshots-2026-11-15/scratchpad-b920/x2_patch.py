import os, re, sys
R = sys.argv[1]
IMPL = re.compile(r"\borg\.operaton\.bpm\.engine\.impl\.")
SF = re.compile(r"(?i)\bcustomSessionFactories\b|CustomSessionFactories\(")
FJ = re.compile(r"implements\s+(?:[\w.]+\.)?FailedJobCommandFactory\b|setFailedJobCommandFactory\s*\(")
TM = re.compile(r"extends\s+(?:org\.operaton\.bpm\.engine\.impl\.persistence\.entity\.)?TaskManager\b")
ROW = re.compile(r"^\|\s*`([^`]+)`\s*\|")
led = open(os.path.join(R, "sangam-engine/PATCHES.md"), encoding="utf-8").read()
def al(h):
    out = set(); inn = False
    for l in re.split(r"\r?\n", led):
        if l.startswith("#"): inn = l.startswith(h); continue
        if inn:
            m = ROW.search(l)
            if m and m.group(1) != "impl": out.add(m.group(1))
    return out
impl, ses, fj, tm = set(), set(), set(), []
for d, dirs, fs in os.walk(R):
    dirs[:] = [x for x in dirs if not (x.startswith("target") or x in ("node_modules", ".git"))]
    for f in fs:
        p = os.path.join(d, f); r = os.path.relpath(p, R).replace("\\", "/")
        if not (r.endswith(".java") and "/src/main/" in r): continue
        t = open(p, encoding="utf-8").read()
        if IMPL.search(t): impl.add(r)
        if SF.search(t): ses.add(r)
        if FJ.search(t): fj.add(r)
        tm += [r for _ in TM.finditer(t)]
for name, listed, found in (("A/B impl", al("### Allow-list A:") | al("### Allow-list B:"), impl), ("C sessions", al("### Allow-list C:"), ses), ("D failedJob", al("### Allow-list D:"), fj)):
    print(name, "UNLISTED", sorted(found - listed), "STALE", sorted(listed - found))
print("TaskManagers", tm)
