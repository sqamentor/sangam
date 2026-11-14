"""Runs profilegen and reports byte changes under profiles/ (and restores the previous bytes). Run from repo root."""
import os, subprocess, sys

root = "sangam/sangam-packs/src/main/resources/profiles"
def snap():
    out = {}
    for d, _, fs in os.walk(root):
        for f in fs:
            p = os.path.join(d, f)
            out[p] = open(p, "rb").read()
    return out
before = snap()
r = subprocess.run([sys.executable, "sangam/sangam-packs/tools/profilegen/main.py"], capture_output=True, text=True)
after = snap()
changed = [p for p in after if before.get(p) != after[p]]
added = [p for p in after if p not in before]
removed = [p for p in before if p not in after]
for p in changed:
    if p in before:
        open(p, "wb").write(before[p])
for p in added:
    os.remove(p)
for p in removed:
    os.makedirs(os.path.dirname(p), exist_ok=True)
    open(p, "wb").write(before[p])
print("exit", r.returncode, r.stderr[-300:])
print(f"files {len(before)}; changed {len(changed) - len(added)}; added {len(added)}; removed {len(removed)}")
print(changed[:10], removed[:5])
