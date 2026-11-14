import subprocess, os, sys, collections
R = sys.argv[1]
files = subprocess.run(["git", "ls-files", "-z", "--", "sangam"], cwd=R, capture_output=True).stdout.decode().split("\0")
c = collections.Counter(); w = collections.Counter()
for f in files:
    if not f: continue
    b = os.path.basename(f)
    ext = os.path.splitext(f)[1].lower() or b
    if b in ("spring.factories",) or b.endswith(".imports"): ext = b if b == "spring.factories" else ".imports"
    if ext not in (".md", ".bpmn", ".dmn", ".imports", "spring.factories", ".gitignore", ".cs", ".php", ".ts", ".py", ".java"): continue
    p = os.path.join(R, f)
    if not os.path.isfile(p): continue
    c[ext] += 1
    try:
        h = open(p, "rb").read(3000).decode("utf-8", "replace")
    except Exception:
        continue
    if "SPDX-License-Identifier" in "\n".join(h.splitlines()[:5]): w[ext] += 1
for k in sorted(c): print(k, "tracked", c[k], "with SPDX", w[k])
