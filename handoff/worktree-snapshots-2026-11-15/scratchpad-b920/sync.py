import os, sys, shutil, filecmp
src = r"D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524\sangam"
dst = r"C:\Users\LOKEND~2\AppData\Local\Temp\claude\5cef910d4d286f64\898f1ff7-0dcc-4d95-b425-830a9a5ead44\scratchpad\wt-dst\sangam"
keep = {os.path.normcase(r"sangam-engine\src\test\java\org\sangam\engine\DstFoldTimerH2Test.java")}
dry = "--dry" in sys.argv
def skipdir(root, d):
    if d.startswith("target-"): return True
    if d == "target" and os.path.exists(os.path.join(root, "pom.xml")): return True
    return False
def files(base):
    out = set()
    for root, dirs, fs in os.walk(base):
        dirs[:] = [d for d in dirs if not skipdir(root, d)]
        for f in fs: out.add(os.path.relpath(os.path.join(root, f), base))
    return out
S, D = files(src), files(dst)
copied = deleted = 0
for r in sorted(S):
    if os.path.normcase(r) in keep: continue
    a, b = os.path.join(src, r), os.path.join(dst, r)
    if r not in D or not filecmp.cmp(a, b, shallow=False):
        copied += 1
        if dry: print("COPY", r)
        else:
            os.makedirs(os.path.dirname(b), exist_ok=True); shutil.copy2(a, b)
for r in sorted(D - S):
    if os.path.normcase(r) in keep: continue
    if set(r.split(os.sep)) & {"node_modules","dist","bin","obj",".pytest_cache"}: continue
    deleted += 1
    if dry: print("DEL", r)
    else: os.remove(os.path.join(dst, r))
print("copied", copied, "deleted", deleted, "kept", [k for k in keep])
