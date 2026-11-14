import os, sys, filecmp
base = r"D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524\sangam"
wt = r"C:\Users\LOKEND~2\AppData\Local\Temp\claude\5cef910d4d286f64\898f1ff7-0dcc-4d95-b425-830a9a5ead44\scratchpad\wt-dst\sangam"
SKIP = {"node_modules", "dist", "bin", "obj", ".pytest_cache"}
def skipdir(root, d):
    if d.startswith("target-") or d in SKIP: return True
    if d == "target" and os.path.exists(os.path.join(root, "pom.xml")): return True
    return False
def files(b):
    out = set()
    for root, dirs, fs in os.walk(b):
        dirs[:] = [d for d in dirs if not skipdir(root, d)]
        for f in fs: out.add(os.path.relpath(os.path.join(root, f), b))
    return out
def norm(p):
    with open(p, "rb") as fh: return fh.read().replace(b"\r\n", b"\n")
B, W = files(base), files(wt)
for r in sorted(B & W):
    if norm(os.path.join(base, r)) != norm(os.path.join(wt, r)): print("M", r)
for r in sorted(W - B): print("A", r)
for r in sorted(B - W): print("D", r)
