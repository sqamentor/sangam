import re,sys
L=open(r'D:\GITHUB  Project\sangam-handoff\WAVE3-SYNC-BOARD.md',encoding='utf-8').read().split('\n')
E=[]
for i,s in enumerate(L,1):
    if i<60: continue
    p=[x.strip() for x in s.split('|')]
    if len(p)<4: continue
    E.append(dict(n=i,t=p[0],lane=p[1],ty=p[2],s=s,tgt=p[-1] if len(p)>4 else ''))
want=sys.argv[1].split(',')
W=int(sys.argv[2]) if len(sys.argv)>2 else 300
for e in E:
    if e['ty'] not in want: continue
    m=re.search(r'T(\d\d:\d\d(?::\d\d)?)Z',e['t'])
    hm=m.group(1) if m else None
    print(f"=== L{e['n']} {e['t']} {e['lane']} {e['ty']} -> [{e['tgt'][:80]}]")
    print('   ', e['s'][len(e['t'])+3:][:W].replace('\n',' '))
    if not hm: print('    (no time)'); continue
    short=hm[:5]
    for f in E:
        if f['n']<=e['n']: continue
        body=f['s'][len(f['t']):]
        k=body.find(hm) if len(hm)==8 else -1
        if k<0:
            k2=[mm.start() for mm in re.finditer(re.escape(short)+r'(?!:\d)|'+re.escape(short)+r'Z|'+re.escape(short)+':'+hm[6:8] if len(hm)==8 else re.escape(short),body)]
            k=k2[0] if k2 else -1
        if k<0: continue
        lane=e['lane'].split('/')[0]
        ctx=body[max(0,k-90):k+110].replace('\n',' ')
        print(f"    -> L{f['n']} {f['lane']} {f['ty']}: ...{ctx}...")
