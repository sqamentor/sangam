import json, sys

old = json.load(open(sys.argv[1], encoding='utf-8'))['packages']
new = json.load(open(sys.argv[2], encoding='utf-8'))['packages']


def name(path, meta):
    return meta.get('name') or path.split('node_modules/')[-1]


def index(pk):
    out = {}
    for p, m in pk.items():
        if not p:
            continue
        out[p] = (name(p, m), m.get('version'), m.get('license'), m.get('integrity'), m.get('resolved'), bool(m.get('hasInstallScript')), bool(m.get('optional')), m.get('os'), m.get('cpu'), bool(m.get('dev')))
    return out


o, n = index(old), index(new)
added = sorted(set(n) - set(o))
removed = sorted(set(o) - set(n))
changed = sorted(p for p in set(o) & set(n) if o[p][1] != n[p][1])
mode = sys.argv[3] if len(sys.argv) > 3 else 'summary'
print('added', len(added), 'removed', len(removed), 'changed', len(changed))
for p in added:
    x = n[p]
    print('ADD', x[0], x[1], x[2], 'script' if x[5] else '', 'optional' if x[6] else '', x[7] or '', x[8] or '', x[3])
for p in changed:
    print('CHG', n[p][0], o[p][1], '->', n[p][1], n[p][2], 'script' if n[p][5] else '', n[p][3])
for p in removed:
    print('DEL', o[p][0], o[p][1])
print('install scripts in new tree:', [n[p][0] + '@' + str(n[p][1]) for p in n if n[p][5]])
