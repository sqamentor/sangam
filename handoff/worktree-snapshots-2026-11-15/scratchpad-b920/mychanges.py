# My SL-53 change set: wt-dst vs the committed 1105d9c snapshot (EOL-insensitive), without the two native-image files
# that were already modified (uncommitted) in the integration tree when wt-dst was synced.
import os, sys
S = r"C:\Users\LOKEND~2\AppData\Local\Temp\claude\b920001b93f0ae3b\6e98c2a7-cdac-4783-9098-832546572812\scratchpad"
base = os.path.join(S, "base-1105d9c", "sangam")
wt = r"C:\Users\LOKEND~2\AppData\Local\Temp\claude\5cef910d4d286f64\898f1ff7-0dcc-4d95-b425-830a9a5ead44\scratchpad\wt-dst\sangam"
IGNORE = {os.path.normcase(p) for p in (
    r"deploy\native\sdk-worker\src\main\resources\META-INF\native-image\org.sangam\sangam-sdk-worker-native\reflect-config.json",
    r"deploy\native\sdk-worker\src\main\resources\org\sangam\sdk\nativeimage\model-classes.txt")}
SKIP = {"node_modules", "dist", "bin", "obj", ".pytest_cache"}
def skipdir(root, d):
    if d.startswith("target-") or d in SKIP: return True
    if d == "target" and os.path.exists(os.path.join(root, "pom.xml")): return True
    return False
def files(b):
    out = set()
    for root, dirs, fs in os.walk(b):
        dirs[:] = [d for d in dirs if not skipdir(root, d)]
        for f in fs: out.add(os.path.relpath(os.path.join(root, f), b))
    return out
def norm(p):
    with open(p, "rb") as fh: return fh.read().replace(b"\r\n", b"\n")
B, W = files(base), files(wt)
res = []
for r in sorted(B & W):
    if os.path.normcase(r) in IGNORE: continue
    if norm(os.path.join(base, r)) != norm(os.path.join(wt, r)): res.append("M " + r)
for r in sorted(W - B):
    res.append("A " + r)
for r in sorted(B - W):
    res.append("D " + r)
print("\n".join(res))
