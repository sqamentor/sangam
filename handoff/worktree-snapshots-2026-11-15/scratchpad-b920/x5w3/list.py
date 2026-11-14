L=open(r'D:\GITHUB  Project\sangam-handoff\WAVE3-SYNC-BOARD.md',encoding='utf-8').read().split('\n')
import collections
d=collections.defaultdict(list)
for i,s in enumerate(L,1):
    if i<60: continue
    p=[x.strip() for x in s.split('|')]
    if len(p)<4: continue
    d[p[2]].append(f"{i}:{p[1].split('/')[0]}")
for k in ('REQUEST','BROKEN','CHANGE','CHANGE+DONE'):
    print(k,len(d[k]),' '.join(d[k]))
