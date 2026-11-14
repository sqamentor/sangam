import re,sys,collections
L=open(r'D:\GITHUB  Project\sangam-handoff\WAVE3-SYNC-BOARD.md',encoding='utf-8').read().split('\n')
E=[]
for i,s in enumerate(L,1):
    if i<60: continue
    p=[x.strip() for x in s.split('|')]
    if len(p)<4: continue
    E.append((i,p[0],p[1],p[2],s))
c=collections.Counter(e[3] for e in E)
print(c)
for e in E:
    t=e[3]
    if t not in ('CHANGE','DONE','REQUEST','ACK','BROKEN','INFO','CHANGE+DONE'): print('ODD',e[0],t)
# multiple entries on one line?
for e in E:
    n=len(re.findall(r'\| (CHANGE|DONE|REQUEST|ACK|BROKEN|INFO|CHANGE\+DONE) \|',e[4]))
    if n>1: print('MULTI',e[0],n)
