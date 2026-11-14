import re, sys, importlib.util
sys.path.insert(0, 'sangam-persistence/tools')
p = 'sangam-persistence/tools/import_module_schemas.py'
s = open(p, encoding='utf-8').read()
a = s.index('MYSQL_B = {'); b = s.index('MSSQL_B = {')
s = s[:a] + '''MYSQL_B = {"{str}": 1020, "{str64}": 256, "{str32}": 128, "{str1k}": 4000, "{long}": 8, "{int}": 4, "{small}": 2,
           "{bytes32}": 32, "{ts}": 8, "{date}": 3, "{dec}": 9, "{num24_6}": 11, "{num12_6}": 6, "{identity}": 8,
           "{vc8}": 32, "{vc16}": 64, "{vc40}": 160, "{vc80}": 320, "{asciistr}": 255, "{text}": 99999, "{blob}": 99999}
''' + s[b:]
s = s.replace('MSSQL_B = {"{str}": 510,', 'MSSQL_B = {"{vc80}": 160, "{str}": 510,', 1)
s = s.replace('# bytes per type in an index key', '# bytes per type in an index key (InnoDB limit counts data bytes at the maximum character width, no length prefix)')
open(p, 'w', encoding='utf-8', newline='\n').write(s)
spec = importlib.util.spec_from_file_location('g', 'sangam-persistence/tools/generate_changelogs.py')
g = importlib.util.module_from_spec(spec); spec.loader.exec_module(g)
import import_module_schemas as m
for x in g.TABLES:
    table, cols, pk, idx = x[2], x[3], x[4], x[5]
    for c in cols:
        for d, w in m.RESERVED.items():
            if c[0].upper() in w:
                print('reserved', table, c[0], d)
    ty = {c[0]: c[1] for c in cols}
    for name, kc in [('PK', pk)] + [(i[0], i[2]) for i in idx]:
        b = sum(m.MYSQL_B[ty[k]] for k in kc); q = sum(m.MSSQL_B[ty[k]] for k in kc)
        if table == 'AUTH_GRANT_SOURCE' and name == 'PK':
            print(table, 'PK mysql', b, 'mssql', q)
        if b > 3072 or q > 1700:
            print('OVER', table, name, b, q)
print('checked', len(g.TABLES))
