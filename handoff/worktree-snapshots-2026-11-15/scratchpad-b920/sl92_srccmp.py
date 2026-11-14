import hashlib
import os
import sys

A = r"D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524"
B = r"C:\Users\LOKEND~2\AppData\Local\Temp\claude\b920001b93f0ae3b\6e98c2a7-cdac-4783-9098-832546572812\scratchpad\wt-sl92"
SUB = sys.argv[1] if len(sys.argv) > 1 else "sangam"


def scan(root):
    out = {}
    base = os.path.join(root, SUB)
    for dp, dn, fn in os.walk(base):
        parts = os.path.relpath(dp, base).replace("\\", "/").split("/")
        inside_src = "src" in parts
        dn[:] = [d for d in dn if inside_src or not (d.startswith("target") or d in ("node_modules", ".git", "dist"))]
        if not inside_src:
            continue
        rel = "/".join(parts)
        for f in fn:
            with open(os.path.join(dp, f), "rb") as h:
                out[rel + "/" + f] = hashlib.sha256(h.read()).hexdigest()
    return out


a, b = scan(A), scan(B)
print("integration", SUB, "src files:", len(a), "| wt-sl92:", len(b))
print("missing in wt-sl92:", sorted(set(a) - set(b)))
print("extra in wt-sl92:", sorted(set(b) - set(a)))
print("content differs:", sorted(k for k in set(a) & set(b) if a[k] != b[k]))
