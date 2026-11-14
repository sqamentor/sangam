# resync.py <integration> <wt> <mine.txt> : copy every non-mine file that is missing/different (EOL-insensitive); report mine that changed upstream
import os, sys, shutil
I, W, M = sys.argv[1:4]
mine = {l.strip() for l in open(M) if l.strip()}
def norm(p): return open(p, 'rb').read().replace(b'\r\n', b'\n')
copied, conflict = [], []
for d, dirs, fs in os.walk(I):
    dirs[:] = [x for x in dirs if x not in ('node_modules', 'dist') and not x.startswith('target-') and not x.startswith('.')
               and not (x == 'target' and os.path.exists(os.path.join(d, 'pom.xml')))]
    for f in fs:
        p = os.path.join(d, f); r = '/'.join(os.path.relpath(p, I).split(os.sep)); q = os.path.join(W, r)
        if os.path.exists(q) and norm(p) == norm(q):
            continue
        if r in mine:
            conflict.append(r); continue
        os.makedirs(os.path.dirname(q), exist_ok=True); shutil.copy2(p, q); copied.append(r)
# files in wt not in integration (deleted upstream), not mine
deleted = []
for d, dirs, fs in os.walk(W):
    dirs[:] = [x for x in dirs if x not in ('node_modules', 'dist') and not x.startswith('target-') and not x.startswith('.')
               and not (x == 'target' and os.path.exists(os.path.join(d, 'pom.xml')))]
    for f in fs:
        q = os.path.join(d, f); r = '/'.join(os.path.relpath(q, W).split(os.sep))
        if r not in mine and not os.path.exists(os.path.join(I, r)):
            deleted.append(r)
print('COPIED', len(copied)); [print('  ', c) for c in copied]
print('MINE CHANGED UPSTREAM (needs merge):', conflict)
print('ONLY IN WT (not mine):', deleted)
