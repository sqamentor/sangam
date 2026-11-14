import re, sys, importlib.util
sys.path.insert(0, 'sangam-persistence/tools')
p = 'sangam-persistence/tools/generate_changelogs.py'
s = open(p, encoding='utf-8').read()
m = re.search(r"\('CH_MESSAGE_CLIENT', True, \[(.*?)\]\)", s)
cols = [c.strip(" '") for c in m.group(1).split(',')]
others = [c for c in cols if c != 'client_key']
expr = ', '.join(f"CASE WHEN client_key IS NOT NULL THEN {c} END" for c in others) + ', client_key'
new = (f"('CH_MESSAGE_CLIENT', True, {cols!r}, {{"
       f"'mssql': 'CREATE UNIQUE INDEX CH_MESSAGE_CLIENT ON CH_MESSAGE ({', '.join(cols)}) WHERE client_key IS NOT NULL;', "
       f"'oracle': 'CREATE UNIQUE INDEX CH_MESSAGE_CLIENT ON CH_MESSAGE ({expr});'}})")
s = s.replace(m.group(0), new)
open(p, 'w', encoding='utf-8', newline='\n').write(s)
spec = importlib.util.spec_from_file_location('g', p)
g = importlib.util.module_from_spec(spec); spec.loader.exec_module(g)
import import_module_schemas as mm
for x in g.TABLES:
    table, c, pk, idx = x[2], x[3], x[4], x[5]
    for k in c:
        for d, w in mm.RESERVED.items():
            if k[0].upper() in w:
                print('reserved', table, k[0], d)
    ty = {k[0]: k[1] for k in c}
    for name, kc in [('PK', pk)] + [(i[0], i[2]) for i in idx]:
        b = sum(mm.MYSQL_B[ty[k]] for k in kc); q = sum(mm.MSSQL_B[ty[k]] for k in kc)
        if b > 3072 or q > 1700:
            print('OVER', table, name, b, q)
print('catalogue', len(g.TABLES))
p = 'sangam-persistence/src/main/java/org/sangam/persistence/SangamSchema.java'
j = open(p, encoding='utf-8').read()
a = '            "SANGAM_FEEL_BINDING", // R10\n'
names = [x[2] for x in g.TABLES if x[0].startswith('0029-')]
j = j.replace(a, a + '            // ChannelsSchema, ModellerSchema, OpsSchema\n            '
              + ', '.join(f'"{n}"' for n in names) + ',\n', 1)
open(p, 'w', encoding='utf-8', newline='\n').write(j)
p = 'sangam-persistence/README.md'
r = open(p, encoding='utf-8').read()
assert 'generated DDL, 264 tables' in r
r = r.replace('generated DDL, 264 tables', 'generated DDL, 284 tables')
open(p, 'w', encoding='utf-8', newline='\n').write(r)
