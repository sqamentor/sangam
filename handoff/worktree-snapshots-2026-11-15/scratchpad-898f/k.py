import re, sys, importlib.util
sys.path.insert(0, 'sangam-persistence/tools')
p = 'sangam-persistence/tools/generate_changelogs.py'
s = open(p, encoding='utf-8').read()
a = '''("source_id", "{asciistr}", "NOT NULL"), ("perms", "{long}", "NOT NULL"),
    ], ["tenant_id", "principal_id", "resource_type", "resource_id", "deny_flag", "cond_key", "source_id"],'''
assert a in s
s = s.replace(a, '''("source_id", "{asciistr}", "NOT NULL"), ("source_key", "{str64}", "NOT NULL"), ("perms", "{long}", "NOT NULL"),
    ], ["tenant_id", "principal_id", "resource_type", "resource_id", "deny_flag", "cond_key", "source_key"],''')
open(p, 'w', encoding='utf-8', newline='\n').write(s)
p = 'sangam-persistence/README.md'
r = open(p, encoding='utf-8').read()
a = '| AUTH_EFFECTIVE key + source_id |'
assert a in r
r = r.replace(a, '| AUTH_EFFECTIVE key + source_key; source_id column |')
r, n = re.subn(r"- SQL Server: `AUTH_GRANT_SOURCE`'s primary key can exceed.*?(?=\n\n|\n- |\n#)\n?", '', r, count=1, flags=re.S)
assert n == 1
a = " `AUTH_GRANT_SOURCE`'s primary key can reach 1,916 bytes on SQL Server (over the 1,700-byte limit): a warning at creation, and only rows with very long ids fail."
assert a in r
r = r.replace(a, '')
open(p, 'w', encoding='utf-8', newline='\n').write(r)
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
