"""Snapshot the integration-tree versions of my files (merge base for the apply) with SHA-256 hashes."""
import hashlib
import json
import os
import shutil
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from filelist import CHANGED, ADDED  # noqa: E402

INT = r"D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524\sangam"
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'base-int')

manifest = {}
for rel in CHANGED + ADDED:
    p = os.path.join(INT, rel)
    if not os.path.exists(p):
        manifest[rel] = None
        continue
    b = open(p, 'rb').read()
    manifest[rel] = hashlib.sha256(b).hexdigest()
    d = os.path.join(OUT, rel)
    os.makedirs(os.path.dirname(d), exist_ok=True)
    shutil.copy2(p, d)
json.dump(manifest, open(os.path.join(OUT, 'manifest.json'), 'w'), indent=1)
print('snapshotted', sum(1 for v in manifest.values() if v), 'files;', sum(1 for v in manifest.values() if not v), 'absent')
