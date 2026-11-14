"""List files that differ between the worktree and the integration tree (bytes), skipping build outputs and caches.
Usage: a401_delta.py <WT> <IT> [--hash]"""
import hashlib, os, sys

WT, IT = sys.argv[1], sys.argv[2]
SHOW_HASH = '--hash' in sys.argv
SKIP_PARTS = {'__pycache__', '.pytest_cache', 'node_modules'}


def walk(root):
    out = {}
    for dirpath, dirnames, filenames in os.walk(root):
        keep = []
        for d in dirnames:
            if d.startswith('target-') or d in SKIP_PARTS:
                continue
            if d == 'target' and os.path.isfile(os.path.join(dirpath, 'pom.xml')):
                continue
            keep.append(d)
        dirnames[:] = keep
        for f in filenames:
            full = os.path.join(dirpath, f)
            rel = os.path.relpath(full, root).replace('\\', '/')
            if rel.startswith('sangam-ui/dist/') or '/bin/Debug/' in rel or '/obj/' in rel:
                continue
            out[rel] = full
    return out


def sha(p):
    with open(p, 'rb') as fh:
        return hashlib.sha256(fh.read()).hexdigest()


w, i = walk(WT), walk(IT)
for rel in sorted(set(w) | set(i)):
    a = sha(w[rel]) if rel in w else None
    b = sha(i[rel]) if rel in i else None
    if a != b:
        tag = 'WT-ONLY' if b is None else 'IT-ONLY' if a is None else 'DIFF'
        print(f'{tag:8} {rel}' + (f'  wt={a} it={b}' if SHOW_HASH else ''))
