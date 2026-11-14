import re,sys
t=open(sys.argv[1],encoding='utf-8').read()
tz={}
for m in re.finditer(r'CREATE TABLE (\w+) \((.*?)\n\);',t,re.S):
    name,body=m.group(1),m.group(2)
    cols={c.group(1).lower() for c in re.finditer(r'^\s+(\w+) TIMESTAMP(?:\(\d\))? WITH TIME ZONE',body,re.M)}
    tz[name]=cols
    pk=re.search(r'PRIMARY KEY \(([^)]*)\)',body)
    if pk:
        bad=[c.strip().lower() for c in pk.group(1).split(',') if c.strip().lower() in cols]
        if bad: print('PK',name,bad)
    for u in re.finditer(r'UNIQUE \(([^)]*)\)',body):
        bad=[c.strip().lower() for c in u.group(1).split(',') if c.strip().lower() in cols]
        if bad: print('UQ',name,bad)
for m in re.finditer(r'CREATE UNIQUE INDEX (\w+) ON (\w+) \(([^)]*)\)',t):
    cols=tz.get(m.group(2),set())
    bad=[c.strip().lower() for c in m.group(3).split(',') if c.strip().lower() in cols]
    if bad: print('UIX',m.group(1),m.group(2),bad)
