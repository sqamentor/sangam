import os, glob, sys
sp = os.path.dirname(os.path.abspath(__file__))
root = r"D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524\sangam"
entries = []
seen = set()
# sibling module classes first (target-w4a)
for d in sorted(glob.glob(os.path.join(root, "sangam-*", "target-w4a", "classes"))):
    if "sangam-server" in d:
        continue
    entries.append(d); seen.add(d)
for f in glob.glob(os.path.join(sp, "cp-sangam-*.txt")) + [os.path.join(sp, "cp-server.txt"), os.path.join(sp, "cp-srv.txt")]:
    if not os.path.exists(f):
        continue
    for x in open(f).read().strip().split(";"):
        x = x.strip()
        if not x or x in seen:
            continue
        norm = x.replace("/", "\\").lower()
        if "\\org\\sangam\\" in norm or "\\target" in norm:
            continue  # use reactor classes instead of stale snapshots
        seen.add(x); entries.append(x)
open(os.path.join(sp, "w4a-cp.txt"), "w").write(";".join(entries))
print(len(entries))
srcs = []
excl = ("HistoryProjectionWiring.java", "SecretStoreReencryptionSource.java")
for p in glob.glob(os.path.join(root, "sangam-server", "src", "main", "java", "**", "*.java"), recursive=True):
    if os.path.basename(p) in excl:
        continue
    srcs.append(p)
with open(os.path.join(sp, "w4a-main-srcs.txt"), "w") as fh:
    for p in srcs:
        fh.write('"' + p.replace("\\", "/") + '"\n')
print(len(srcs))
