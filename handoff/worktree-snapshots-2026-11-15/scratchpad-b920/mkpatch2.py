# Unified diff (LF-normalised) of the SL-53 change set: the CURRENT integration tree -> wt-dst, so every hunk applies as a
# targeted Edit on the integration tree as it is now. Usage: python -I mkpatch2.py <out.patch>
import difflib, os, sys
S = r"C:\Users\LOKEND~2\AppData\Local\Temp\claude\b920001b93f0ae3b\6e98c2a7-cdac-4783-9098-832546572812\scratchpad"
BASE = r"D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524\sangam"  # current integration tree
WT = r"C:\Users\LOKEND~2\AppData\Local\Temp\claude\5cef910d4d286f64\898f1ff7-0dcc-4d95-b425-830a9a5ead44\scratchpad\wt-dst\sangam"

def lines(p):
    if not os.path.exists(p):
        return []
    with open(p, encoding="utf-8") as fh:
        return fh.read().replace("\r\n", "\n").splitlines(keepends=True)

n = 0
with open(sys.argv[1], "w", encoding="utf-8", newline="\n") as out:
    for line in open(os.path.join(S, "mychanges.txt"), encoding="utf-8"):
        line = line.strip()
        if not line:
            continue
        kind, rel = line.split(" ", 1)
        p = "sangam/" + rel.replace("\\", "/")
        a = [] if kind == "A" else lines(os.path.join(BASE, rel))
        b = [] if kind == "D" else lines(os.path.join(WT, rel))
        out.writelines(difflib.unified_diff(a, b, "/dev/null" if kind == "A" else "a/" + p,
                                            "/dev/null" if kind == "D" else "b/" + p, n=3))
        n += 1
print(n, "files ->", sys.argv[1])
