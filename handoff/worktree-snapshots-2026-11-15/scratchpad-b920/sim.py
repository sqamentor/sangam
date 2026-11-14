import re,sys,os
base=os.path.join(sys.argv[1],'sangam-persistence/src/main/resources/db/changelog')
ADD=re.compile(r'^ALTER TABLE (\w+) ADD (?:COLUMN )?\(?(\w+) (.*)$')
DF=re.compile(r'CONSTRAINT (DF_\w+) DEFAULT ');CK=re.compile(r'CONSTRAINT (CK_\w+) CHECK ')
for d in ['postgresql','oracle','mssql','mysql','h2']:
    sql=open(os.path.join(base,d,'sangam-0001-baseline.sql'),encoding='utf-8').read()
    parts=sql.split('\n--changeset ')
    nd=nc=0;multi=0
    for p in parts[1:]:
        lines=p.split('\n');rbs=[l for l in lines if l.strip().startswith('--rollback ')]
        if len(rbs)>1: multi+=1
        rb=rbs[-1].strip()[11:] if rbs else ''
        cid=lines[0].split(' ')[0]
        for l in lines[1:]:
            s=l.strip()
            if not s or s.startswith('--'): continue
            m=ADD.match(s)
            if not m: continue
            t,c,rest=m.groups()
            if d=='mssql' and 'DEFAULT' in rest:
                f=DF.search(rest)
                ok=f and 0<=rb.find(f'ALTER TABLE {t} DROP CONSTRAINT {f.group(1)};')<rb.find(f'ALTER TABLE {t} DROP COLUMN {c};')
                print(d,cid,c,'OK' if ok else 'FAIL '+s[:120]); nd+=1
            if ' ADD CONSTRAINT ' in s: continue
            if any(k in rest for k in ['UNIQUE','PRIMARY KEY','REFERENCES']): print('INLINEKEY',d,cid,s[:120])
            if ('CHECK' in rest)!=bool(CK.search(rest)): print('UNNAMEDCK',d,cid,s[:120])
            k=CK.search(rest)
            if k: nc+=1
    print(d,'defaults',nd,'checks',nc,'multi-rollback',multi)
