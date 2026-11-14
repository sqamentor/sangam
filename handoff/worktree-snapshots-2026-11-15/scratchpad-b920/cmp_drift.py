import re, sys
root = sys.argv[1]
sys.path.insert(0, root + '/sangam-persistence/tools')
import generate_changelogs as g

src = open(root + '/sangam-compliance/src/main/java/org/sangam/compliance/ComplianceSchema.java', encoding='utf-8').read()
tables = re.findall(r'CREATE TABLE (CMP_\w+) \((.*?)\)"""', src, re.S)
schema = {}
for name, body in tables:
    body = ' '.join(body.split())
    pk = re.search(r'PRIMARY KEY \(([^)]*)\)', body).group(1)
    body = body[:body.index('PRIMARY KEY')]
    cols = []
    for part in [p.strip() for p in body.split(',') if p.strip()]:
        m = re.match(r'(\w+) (\{\w+\})\s*(.*)', part)
        cols.append((m.group(1), m.group(2), m.group(3).strip()))
    schema[name] = (cols, [c.strip() for c in pk.split(',')])

gen = {}
for cs, comment, t, cols, pk, idx in g.TABLES:
    if t.startswith('CMP_'):
        gen[t] = ([(c[0], c[1], c[2]) for c in cols], pk)
for cs, comment, t, cols, idx in g.ALTERS:
    if t.startswith('CMP_'):
        gen[t][0].extend((c[0], c[1], c[2]) for c in cols)
nullable = {(t, c) for _, _, t, c, _ in g.NULLABLE}

issues = 0
for t in sorted(set(schema) | set(gen)):
    if t not in gen:
        print('MISSING TABLE in changelog', t); issues += 1; continue
    if t not in schema:
        print('TABLE only in changelog', t); issues += 1; continue
    sc, spk = schema[t]; gc, gpk = gen[t]
    if spk != gpk:
        print(t, 'PK', spk, gpk); issues += 1
    sd = {c[0]: c for c in sc}; gd = {c[0]: c for c in gc}
    for c in sc:
        if c[0] not in gd:
            print(t, 'MISSING COLUMN in changelog', c); issues += 1
        else:
            o = gd[c[0]]
            gnull = '' if (t, c[0]) in nullable else o[2]
            if o[1] != c[1] or gnull != c[2]:
                print(t, 'DIFF', c, '<- schema | changelog ->', o, '(nullable changeset)' if (t, c[0]) in nullable else ''); issues += 1
    for c in gc:
        if c[0] not in sd:
            print(t, 'EXTRA COLUMN in changelog', c); issues += 1
print('tables', len(schema), 'issues', issues)
