import json, sys, collections
lic_txt, lock_path, out_path = sys.argv[1:4]
pk = json.load(open(lock_path, encoding='utf-8'))['packages']
runtime = set()
for p, m in pk.items():
    if not p:
        continue
    n = m.get('name') or p.split('node_modules/')[-1]
    if not m.get('dev'):
        runtime.add((n, m.get('version')))
rows = []
for line in open(lic_txt, encoding='utf-8'):
    line = line.rstrip('\r\n')
    if '\t' not in line:
        continue
    nv, lic = line.split('\t', 1)
    lic = lic.split('  [')[0]
    i = nv.rfind('@')
    n, v = nv[:i], nv[i + 1:]
    rows.append((n, v, lic, 'runtime' if (n, v) in runtime else 'build'))
c = collections.Counter(r[3] for r in rows)
lics = collections.Counter(r[2] for r in rows)
with open(out_path, 'w', encoding='utf-8', newline='') as f:
    for n, v, lic, s in rows:
        f.write(f'| {n} | {v} | {lic} | {s} |\n')
print(len(rows), dict(c))
print(dict(lics))
print([r for r in rows if r[0] in ('component-props', 'caniuse-lite', '@csstools/color-helpers', 'axe-core', 'lightningcss')])
