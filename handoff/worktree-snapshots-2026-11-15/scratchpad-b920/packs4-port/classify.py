"""For each wt-packs4 file: which of its added lines (vs 9c831d0, or the whole file when untracked at the base) exist
in the integration tree (IT) file, by stripped-line multiset. Prints: ratio present, counts, IT path status, sample
missing lines.  usage: classify.py <list> <wt root> <it root> <out>"""
import subprocess
import sys
from collections import Counter

lst, wt, it, out = sys.argv[1:5]
files = [l.strip() for l in open(lst, encoding='utf-8-sig') if l.strip()]


def lines(path):
    try:
        return open(path, 'rb').read().decode('utf-8', 'replace').replace('\r\n', '\n').split('\n')
    except FileNotFoundError:
        return None


def base(rel):
    r = subprocess.run(['git', '-C', wt, 'show', '9c831d00:' + rel], capture_output=True)
    if r.returncode != 0:
        return None
    return r.stdout.decode('utf-8', 'replace').replace('\r\n', '\n').split('\n')


res = []
for rel in files:
    w = lines(wt + '/' + rel)
    i = lines(it + '/' + rel)
    b = base(rel)
    if w is None:
        res.append((rel, 'WT-DELETED', 0, 0, 0, []))
        continue
    if b is None:
        added = [x.strip() for x in w if x.strip()]
    else:
        bc = Counter(x.strip() for x in b if x.strip())
        added = []
        for x in w:
            s = x.strip()
            if not s:
                continue
            if bc[s] > 0:
                bc[s] -= 1
            else:
                added.append(s)
    if i is None:
        res.append((rel, 'IT-MISSING', len(added), 0, 0.0, added[:6]))
        continue
    ic = Counter(x.strip() for x in i if x.strip())
    missing = []
    for s in added:
        if ic[s] > 0:
            ic[s] -= 1
        else:
            missing.append(s)
    n = len(added)
    ratio = 1.0 if n == 0 else (n - len(missing)) / n
    res.append((rel, 'BASE' if b is not None else 'NEW', n, n - len(missing), ratio, missing[:8]))

with open(out, 'w', encoding='utf-8') as f:
    for rel, kind, n, pres, ratio, miss in res:
        f.write(f'{ratio:5.2f} {kind:10} added={n:4} present={pres:4}  {rel}\n')
        for m in miss:
            f.write('        MISSING: ' + m[:170] + '\n')
