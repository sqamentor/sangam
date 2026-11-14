"""Copy the approve-401 worktree's sangam/ tree to a second private directory for a run that must not share build
output or sources with the canonical worktree (the PostgreSQL run while the H2/red runs use wt-auth401).

Skips build output exactly as a401_sync.py does: directories named target-* anywhere and a module-root target/ (a
directory named 'target' whose parent holds a pom.xml). sangam-programme/.../programme/target is SOURCE and is copied.

Usage: python a401_copy.py <SRC sangam dir> <DEST sangam dir>   (DEST must not exist)
"""
import os
import shutil
import sys

SRC, DEST = sys.argv[1], sys.argv[2]
if os.path.exists(DEST):
    sys.exit(f'destination exists: {DEST}')
n = 0
for dirpath, dirnames, filenames in os.walk(SRC):
    keep = []
    for d in dirnames:
        if d.startswith('target-'):
            continue
        if d == 'target' and os.path.isfile(os.path.join(dirpath, 'pom.xml')):
            continue
        keep.append(d)
    dirnames[:] = keep
    rel = os.path.relpath(dirpath, SRC)
    out = os.path.join(DEST, rel) if rel != '.' else DEST
    os.makedirs(out, exist_ok=True)
    for f in filenames:
        shutil.copy2(os.path.join(dirpath, f), os.path.join(out, f))
        n += 1
print(f'copied {n} files to {DEST}')
prog = os.path.join(DEST, 'sangam-programme', 'src', 'main', 'java', 'org', 'sangam', 'programme', 'target')
print('programme/target source present:', os.path.isdir(prog), len(os.listdir(prog)) if os.path.isdir(prog) else 0)
