import re, sys, os
root = sys.argv[1]
yaml = open(os.path.join(root, 'sangam-rest/api/sangam-api.yaml'), encoding='utf-8').read().split('\n')
paths = []
inp = False
for l in yaml:
    if re.match(r'^paths:\s*$', l):
        inp = True; continue
    if inp and re.match(r'^\S', l):
        inp = False
    if inp:
        m = re.match(r"^  ['\"]?(/[^'\":]*)['\"]?:\s*$", l)
        if m:
            paths.append(m.group(1))
norm = lambda p: re.sub(r'\{[^}]+\}', '{}', p.rstrip('/'))
cset = {norm(p) for p in paths}
print('contract paths', len(paths))
listed = ['/ai/agent-runs', '/ai/agent-approvals', '/ai/knowledge-bases', '/ai/drafts', '/ai/classifications',
          '/ai/expressions/evaluate', '/ai/expressions/propose', '/channels/conversations', '/channels/opt-ins',
          '/channels/status', '/service-mocks', '/actions/{}/run', '/search', '/ops/usage', '/ops/outliers',
          '/ops/variables', '/ops/secrets', '/ops/sequences', '/ops/index-jobs', '/ops/retention/plan',
          '/ops/retention/execute', '/ops/migration-status', '/landscape', '/models/token-walk', '/models/{}/lease',
          '/templates', '/actions', '/catalogue', '/packages/{}/tests/run']
for p in listed:
    print(('IN ' if norm(p) in cset else 'MISSING ') + p)
# every static path literal in UI src (non-test)
src = os.path.join(root, 'sangam-ui/src')
lits = set()
for dp, dn, fn in os.walk(src):
    for f in fn:
        if not re.search(r'\.(ts|tsx)$', f) or '.test.' in f:
            continue
        t = open(os.path.join(dp, f), encoding='utf-8').read()
        for m in re.finditer(r"[`'\"](/(?:[a-z][\w.-]*|\$\{[^}]+\})(?:/(?:[\w.:-]+|\$\{[^}]+\}))*)(?:\?[^`'\"]*)?[`'\"]", t):
            lits.add(m.group(1))
api = [l for l in lits if not l.startswith(('/engine-rest', '/auth', '/config.json', '/fonts', '/x/', '/m/'))]
nl = lambda p: re.sub(r'\$\{[^}]+\}', '{}', p.rstrip('/'))
miss = sorted(p for p in api if nl(p) not in cset)
print('ui literals', len(api), 'not matched', len(miss))
for p in miss[:400]:
    print('  ', p)
