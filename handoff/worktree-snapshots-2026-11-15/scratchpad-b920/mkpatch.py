import os, difflib, subprocess, sys
base = r"D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524\sangam"
wt = r"C:\Users\LOKEND~2\AppData\Local\Temp\claude\5cef910d4d286f64\898f1ff7-0dcc-4d95-b425-830a9a5ead44\scratchpad\wt-dst\sangam"
out = sys.argv[1]
changes = subprocess.run([sys.executable, "-I", sys.argv[2]], capture_output=True, text=True).stdout.splitlines()
def lines(p):
    if not os.path.exists(p): return []
    with open(p, encoding="utf-8") as fh: return fh.read().replace("\r\n", "\n").splitlines(keepends=True)
with open(out, "w", encoding="utf-8", newline="\n") as o:
    for c in changes:
        kind, rel = c.split(" ", 1)
        a = lines(os.path.join(base, rel)) if kind != "A" else []
        b = lines(os.path.join(wt, rel)) if kind != "D" else []
        relp = "sangam/" + rel.replace("\\", "/")
        o.writelines(difflib.unified_diff(a, b, "a/" + relp if kind != "A" else "/dev/null", "b/" + relp if kind != "D" else "/dev/null", n=3))
print(len(changes), "files ->", out)
