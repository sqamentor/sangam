"""Compare the private worktree (wt-ifmswf/sangam) with the integration tree (sangam).

Skips build outputs only: directories named target-* anywhere, and a module-root
target/ (a directory named 'target' whose parent holds a pom.xml). Never skips
sangam-programme/src/main/java/org/sangam/programme/target (source code).
Also skips node_modules (dependency installs, not source).

For each differing path, classifies it against git history of the integration tree
(line endings normalised) so we can tell "my change" from "main's change since the copy".
"""
import hashlib
import os
import subprocess
import sys

WT = sys.argv[1]
IT = sys.argv[2]
REPO = sys.argv[3]          # git worktree root of the integration tree
PREFIX = "sangam/"          # path prefix of IT inside REPO
COMMITS = ["9c831d0", "2aaf44f", "f553b43", "3736f8f", "0d04fea", "eba6e62", "460c95b", "16e0e11", "1105d9c"]


def walk(root):
    out = {}
    for dirpath, dirnames, filenames in os.walk(root):
        keep = []
        for d in dirnames:
            full = os.path.join(dirpath, d)
            if d.startswith("target-"):
                continue
            if d == "target" and os.path.isfile(os.path.join(dirpath, "pom.xml")):
                continue
            if d == "node_modules":
                continue
            keep.append(d)
        dirnames[:] = keep
        for f in filenames:
            full = os.path.join(dirpath, f)
            rel = os.path.relpath(full, root).replace("\\", "/")
            out[rel] = full
    return out


def norm(b):
    return b.replace(b"\r\n", b"\n")


def h(b):
    return hashlib.sha256(norm(b)).hexdigest()


def git_blob(commit, rel):
    try:
        return subprocess.run(["git", "-C", REPO, "show", f"{commit}:{PREFIX}{rel}"],
                              capture_output=True, check=True).stdout
    except subprocess.CalledProcessError:
        return None


wt = walk(WT)
it = walk(IT)
only_wt = sorted(set(wt) - set(it))
only_it = sorted(set(it) - set(wt))
both = sorted(set(wt) & set(it))
diff = []
for rel in both:
    a = open(wt[rel], "rb").read()
    b = open(it[rel], "rb").read()
    if a != b and norm(a) != norm(b):
        diff.append(rel)

print(f"wt files={len(wt)} it files={len(it)} only_wt={len(only_wt)} only_it={len(only_it)} differ={len(diff)}")


def classify(rel):
    wt_h = h(open(wt[rel], "rb").read()) if rel in wt else None
    it_h = h(open(it[rel], "rb").read()) if rel in it else None
    hist = []
    for c in COMMITS:
        blob = git_blob(c, rel)
        hist.append((c, None if blob is None else h(blob)))
    wt_m = [c for c, x in hist if x is not None and x == wt_h]
    it_m = [c for c, x in hist if x is not None and x == it_h]
    absent = [c for c, x in hist if x is None]
    return wt_m, it_m, absent


for label, items in (("ONLY_IN_WT", only_wt), ("ONLY_IN_IT", only_it), ("DIFFER", diff)):
    print(f"===== {label} ({len(items)}) =====")
    for rel in items:
        wt_m, it_m, absent = classify(rel)
        print(f"{rel}\n    wt==[{','.join(wt_m)}] it==[{','.join(it_m)}] absent_in=[{','.join(absent)}]")
