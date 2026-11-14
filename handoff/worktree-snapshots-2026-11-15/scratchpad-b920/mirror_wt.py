"""Mirror the integration tree's sangam/ into the private worktree byte-for-byte (fresh re-copy of main).

Skips build outputs only (target-* anywhere, module-root target/, node_modules) and local junk that is never source
(sangam-ui/dist, sdks/python/.pytest_cache, .NET bin/obj). sangam-programme/.../programme/target is SOURCE and copied.
Files in the worktree that no longer exist in the integration tree are deleted (they were deleted or renamed upstream).

Usage: python mirror_wt.py <WT> <IT> [--dry-run]
"""
import hashlib
import os
import shutil
import sys

WT, IT = sys.argv[1], sys.argv[2]
DRY = '--dry-run' in sys.argv
JUNK = ('sangam-ui/dist/', 'sdks/python/.pytest_cache/')


def walk(root):
    out = {}
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if not d.startswith('target-') and d != 'node_modules'
                       and not (d == 'target' and os.path.isfile(os.path.join(dirpath, 'pom.xml')))]
        for f in filenames:
            full = os.path.join(dirpath, f)
            rel = os.path.relpath(full, root).replace('\\', '/')
            if rel.startswith(JUNK) or '/bin/Debug/' in rel or '/obj/' in rel:
                continue
            out[rel] = full
    return out


def sha(p):
    with open(p, 'rb') as fh:
        return hashlib.sha256(fh.read()).hexdigest()


wt, it = walk(WT), walk(IT)
upd = add = same = 0
for rel, src in sorted(it.items()):
    dst = os.path.join(WT, rel)
    if rel in wt and sha(src) == sha(wt[rel]):
        same += 1
        continue
    if rel in wt:
        upd += 1
    else:
        add += 1
        print(f'add    {rel}')
    if not DRY:
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        shutil.copy2(src, dst)
removed = 0
for rel in sorted(set(wt) - set(it)):
    print(f'delete {rel}')
    removed += 1
    if not DRY:
        os.remove(wt[rel])
print(f'same={same} updated={upd} added={add} deleted={removed}')
