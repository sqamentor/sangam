# Re-sync wt-dst with the CURRENT integration tree, keeping the SL-53 change set.
#   base   = committed 1105d9c snapshot (what wt-dst was synced from)
#   theirs = current integration tree
#   mine   = wt-dst
# Files outside my change set: copied from theirs (added / updated / deleted). Files in my change set: kept, but a
# CONFLICT is reported when theirs differs from base (the integration tree changed a file I changed) - nothing is
# overwritten then. Build outputs (target-*, module-root target/, node_modules, dist, bin, obj) are never touched.
import os, sys, shutil
S = r"C:\Users\LOKEND~2\AppData\Local\Temp\claude\b920001b93f0ae3b\6e98c2a7-cdac-4783-9098-832546572812\scratchpad"
BASE = os.path.join(S, "base-1105d9c", "sangam")
THEIRS = r"D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524\sangam"
MINE = r"C:\Users\LOKEND~2\AppData\Local\Temp\claude\5cef910d4d286f64\898f1ff7-0dcc-4d95-b425-830a9a5ead44\scratchpad\wt-dst\sangam"
dry = "--dry" in sys.argv
SKIP = {"node_modules", "dist", "bin", "obj", ".pytest_cache"}

def skipdir(root, d):
    if d.startswith("target-") or d in SKIP:
        return True
    return d == "target" and os.path.exists(os.path.join(root, "pom.xml"))

def files(b):
    out = set()
    for root, dirs, fs in os.walk(b):
        dirs[:] = [d for d in dirs if not skipdir(root, d)]
        for f in fs:
            out.add(os.path.relpath(os.path.join(root, f), b))
    return out

def norm(p):
    with open(p, "rb") as fh:
        return fh.read().replace(b"\r\n", b"\n")

def same(a, b):
    return os.path.exists(a) and os.path.exists(b) and norm(a) == norm(b)

mine_set = {}
for line in open(os.path.join(S, "mychanges.txt"), encoding="utf-8"):
    line = line.strip()
    if line:
        kind, rel = line.split(" ", 1)
        mine_set[os.path.normcase(rel)] = (kind, rel)

T, M = files(THEIRS), files(MINE)
tn = {os.path.normcase(r): r for r in T}
mn = {os.path.normcase(r): r for r in M}
copied = deleted = 0
conflicts = []
for key, (kind, rel) in mine_set.items():
    b, t = os.path.join(BASE, rel), os.path.join(THEIRS, rel)
    if kind == "M" and not same(b, t):
        conflicts.append(f"M {rel}: changed in the integration tree too")
    elif kind == "D" and os.path.exists(t) and not same(b, t):
        conflicts.append(f"D {rel}: changed in the integration tree")
    elif kind == "A" and os.path.exists(t):
        conflicts.append(f"A {rel}: also added in the integration tree")
for key, rel in sorted(tn.items()):
    if key in mine_set:
        continue
    src, dst = os.path.join(THEIRS, rel), os.path.join(MINE, rel)
    if not os.path.exists(dst) or open(src, "rb").read() != open(dst, "rb").read():
        copied += 1
        if dry:
            print("COPY", rel)
        else:
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            shutil.copy2(src, dst)
for key, rel in sorted(mn.items()):
    if key in mine_set or key in tn:
        continue
    deleted += 1
    if dry:
        print("DEL ", rel)
    else:
        os.remove(os.path.join(MINE, rel))
for c in conflicts:
    print("CONFLICT", c)
print(f"copied {copied} deleted {deleted} conflicts {len(conflicts)} (my change set: {len(mine_set)} files)")
