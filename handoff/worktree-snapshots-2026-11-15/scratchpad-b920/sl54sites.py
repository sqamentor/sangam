import collections, json, os, re, sys
res = sys.argv[1]
packs_dir = os.path.join(res, 'packs')
ctx = collections.Counter()
ex = {}
for d in sorted(os.listdir(packs_dir)):
    pj = os.path.join(packs_dir, d, 'pack.json')
    if not os.path.exists(pj):
        continue
    m = json.load(open(pj, encoding='utf-8'))
    v = m['version']
    paths = [r['path'] for r in m['resources']]
    for ov in (m.get('tenantOverrides') or []):
        paths += ov.get('paths', []) if isinstance(ov, dict) else []
    for path in paths:
        x = open(os.path.join(res, path), encoding='utf-8').read()
        ext = path.rsplit('.', 1)[1]
        for mm in re.finditer(re.escape(v), x):
            before = x[max(0, mm.start() - 45):mm.start()]
            key = ext + ' | ' + re.sub(r'[A-Za-z0-9_.:-]*$', '', before)[-35:] .replace('\n', ' ') + '<V>' + x[mm.end():mm.end() + 3]
            key = re.sub(r'calledElement="[^"]*"', 'calledElement="X"', key)
            key = re.sub(r'(name|id)="[^"]*"', r'\1="X"', key)
            ctx[key] += 1
            ex.setdefault(key, path)
        if 'decisionRefBinding' in x or 'caseBinding' in x or 'processBinding' in x:
            ctx[ext + ' has decisionRef/case/process binding'] += 1
for k, n in ctx.most_common():
    print(n, k, ' e.g.', ex.get(k))
print('overrides sample', [m.get('tenantOverrides') for m in [json.load(open(os.path.join(packs_dir, 'ifms-bill', 'pack.json'), encoding='utf-8'))]])
