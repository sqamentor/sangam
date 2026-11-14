import re, sys, os, collections
root = sys.argv[1]
yaml = open(os.path.join(root, 'sangam-rest/api/sangam-api.yaml'), encoding='utf-8').read().splitlines()
paths = [l.strip().rstrip(':') for l in yaml if re.match(r'^  /', l)]
# ops per path
ops = collections.Counter()
cur = None
for l in yaml:
    if re.match(r'^  /', l): cur = l.strip().rstrip(':')
    elif cur and re.match(r'^    (get|post|put|patch|delete):', l): ops[cur] += 1
def norm(p):
    p = re.sub(r'\{[^}]*\}', '{}', p)
    p = re.sub(r'\$\{[^}]*\}', '{}', p)
    p = p.split('?')[0].rstrip('/')
    return p
spec = {norm(p): p for p in paths}
ui_dir = os.path.join(root, 'sangam-ui/src')
lits = collections.defaultdict(set)
for dp, _, fs in os.walk(ui_dir):
    for f in fs:
        if not f.endswith(('.ts', '.tsx')) or '.test.' in f: continue
        txt = open(os.path.join(dp, f), encoding='utf-8').read()
        txt = re.sub(r'\$\{[^}]*\}', '{x}', txt)
        for m in re.finditer(r"""['"`](/[A-Za-z][A-Za-z0-9_\-/{}$.:]*?)['"`?]""", txt):
            s = m.group(1)
            if s.startswith(('/engine-rest', '/fonts', '/assets', '/auth', '/config.json')): continue
            lits[norm(s)].add(os.path.relpath(os.path.join(dp, f), ui_dir))
# Match ui literal to spec path: exact, or spec path whose normalized form equals with {} wildcard
def matches(u, s):
    us, ss = u.split('/'), s.split('/')
    if len(us) != len(ss): return False
    return all(a == b or a == '{}' or b == '{}' for a, b in zip(us, ss))
covered = set()
ui_unmatched = {}
for u, files in lits.items():
    hit = [s for s in spec if matches(u, s)]
    if hit: covered.update(hit)
    else: ui_unmatched[u] = files
# prefix grouping
grp = collections.defaultdict(lambda: [0, 0, 0, 0])
for s, orig in spec.items():
    g = s.split('/')[1]
    grp[g][0] += 1; grp[g][1] += ops[orig]
    if s in covered: grp[g][2] += 1; grp[g][3] += ops[orig]
tp = sum(v[0] for v in grp.values()); to = sum(v[1] for v in grp.values())
cp = sum(v[2] for v in grp.values()); co = sum(v[3] for v in grp.values())
print(f'TOTAL paths {tp} ops {to}; UI-referenced paths {cp} ops {co}')
for g, v in sorted(grp.items(), key=lambda x: -x[1][0]):
    print(f'{g}\tpaths={v[0]}\tops={v[1]}\tui_paths={v[2]}\tui_ops={v[3]}\t{100*v[2]//v[0]}%')
print('\nUNCOVERED SPEC PATHS:')
for s, orig in sorted(spec.items()):
    if s not in covered: print('  ', orig)
print('\nUI LITERALS NOT IN SPEC (candidate drift / non-api):')
for u, f in sorted(ui_unmatched.items()):
    print('  ', u, sorted(f))
