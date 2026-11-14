import os, sys, collections
REPO = sys.argv[1]
S = os.path.dirname(os.path.abspath(__file__))
added = [l.strip() for l in open(os.path.join(S, "x4_added.txt"), encoding="utf-8") if l.strip()]
changed = [l.strip() for l in open(os.path.join(S, "x4_changed.txt"), encoding="utf-8") if l.strip()]
added = sorted(set(added))
print("added/untracked", len(added), "changed tracked", len(changed))
NOCOMMENT = {".json", ".png", ".jpg", ".svg", ".ico", ".lock", ".csv", ".jar", ".gz", ".zip", ".pdf", ".woff", ".woff2", ".snap", ".bin", ".class", ".txt", ".properties-not"}
by_ext = collections.Counter(); missing = collections.defaultdict(list); javamiss = []
for r in added:
    p = os.path.join(REPO, r)
    if not os.path.isfile(p):
        continue
    ext = os.path.splitext(r)[1].lower() or os.path.basename(r)
    by_ext[ext] += 1
    try:
        head = open(p, "rb").read(4000).decode("utf-8", "replace")
    except Exception as e:
        missing[ext].append(r + " (unreadable)"); continue
    first = "\n".join(head.splitlines()[:5])
    if "SPDX-License-Identifier" not in first:
        missing[ext].append(r)
        if ext == ".java":
            javamiss.append(r)
print("by ext", dict(by_ext))
print("JAVA missing (ArchitectureGuardTest scope):", javamiss)
for ext, l in sorted(missing.items()):
    print("== no SPDX in first 5 lines:", ext, len(l))
    for x in l[:400]:
        print("   ", x)
