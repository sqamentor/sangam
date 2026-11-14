# Apply the SL-53 change set to the integration tree: write each file's bytes from wt-dst (EOL as in the tree),
# delete the D files; then report SHA-256 identity per file.
import hashlib, os, shutil, sys
S = r"C:\Users\LOKEND~2\AppData\Local\Temp\claude\b920001b93f0ae3b\6e98c2a7-cdac-4783-9098-832546572812\scratchpad"
IT = r"D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524\sangam"
WT = r"C:\Users\LOKEND~2\AppData\Local\Temp\claude\5cef910d4d286f64\898f1ff7-0dcc-4d95-b425-830a9a5ead44\scratchpad\wt-dst\sangam"
check = "--check" in sys.argv
sha = lambda p: hashlib.sha256(open(p, "rb").read()).hexdigest()
bad = 0
for line in open(os.path.join(S, "mychanges.txt"), encoding="utf-8"):
    line = line.strip()
    if not line: continue
    k, rel = line.split(" ", 1)
    it, wt = os.path.join(IT, rel), os.path.join(WT, rel)
    if not check:
        if k == "D":
            if os.path.exists(it): os.remove(it)
        else:
            os.makedirs(os.path.dirname(it), exist_ok=True)
            with open(wt, "rb") as src, open(it, "wb") as dst: dst.write(src.read())
    if k == "D":
        ok = not os.path.exists(it) and not os.path.exists(wt); h = "absent"
    else:
        ok = os.path.exists(it) and sha(it) == sha(wt); h = sha(it)[:16] if os.path.exists(it) else "missing"
    bad += not ok
    print("OK " if ok else "BAD", k, h, rel)
print("files:", sum(1 for l in open(os.path.join(S, "mychanges.txt")) if l.strip()), "mismatches:", bad)
