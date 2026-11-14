"""Targeted re-sync by modification time: copy every integration-tree file changed after SINCE (UTC) that differs
from the worktree. Skips build output (target-*, module-root target/), node_modules and sangam-ui/dist, so the walk
stays cheap on a busy disk. Prints each copied file.

Usage: python a401_recent.py <WT> <IT> <SINCE as 2026-11-09T23:40> [--apply]
"""
import datetime
import hashlib
import os
import shutil
import sys

WT, IT, SINCE = sys.argv[1], sys.argv[2], sys.argv[3]
APPLY = '--apply' in sys.argv
since = datetime.datetime.fromisoformat(SINCE).replace(tzinfo=datetime.timezone.utc).timestamp()


def sha(p):
    with open(p, 'rb') as fh:
        return hashlib.sha256(fh.read()).hexdigest()


n = 0
for dirpath, dirnames, filenames in os.walk(IT):
    keep = []
    for d in dirnames:
        if d.startswith('target-') or d in ('node_modules', '.git'):
            continue
        if d == 'target' and os.path.isfile(os.path.join(dirpath, 'pom.xml')):
            continue
        if d == 'dist' and os.path.basename(dirpath) == 'sangam-ui':
            continue
        keep.append(d)
    dirnames[:] = keep
    for f in filenames:
        src = os.path.join(dirpath, f)
        if os.path.getmtime(src) < since:
            continue
        rel = os.path.relpath(src, IT)
        dst = os.path.join(WT, rel)
        if os.path.isfile(dst) and sha(dst) == sha(src):
            continue
        n += 1
        print(('COPY ' if APPLY else 'DIFF ') + rel.replace('\\', '/'))
        if APPLY:
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            shutil.copy2(src, dst)
print(f'{n} file(s) {"copied" if APPLY else "differ"}')
