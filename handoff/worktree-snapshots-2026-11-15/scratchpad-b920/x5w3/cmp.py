import json,sys,re
X5=sys.argv[1]
d=json.load(open(X5+r'\pairs2.json'))
H={}; MT={}
for ln in open(X5+r'\hashes.tsv',encoding='utf-8-sig'):
    p,h,t=(ln.rstrip('\n').split('\t')+['',''])[:3]; H[p]=h; MT[p]=t
R=r'D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524'
match=mis=0; rows=[]
for p,r in sorted(d['last'].items()):
    cur=H.get(p)
    if cur==r['hash']: match+=1
    else:
        mis+=1; rows.append((r['line'],r['w'],p.replace(R+'\\',''),r['hash'][:16],(cur or '?')[:16],MT.get(p,'')))
for r in d['amb']:
    hs=[(c,H.get(c)) for c in r['cand']]
    ok=[c for c,h in hs if h==r['hash']]
    print('AMB',r['line'],r['tok'],'match' if ok else 'nomatch',[c.replace(R,'') for c in ok])
cr=R+r'\sangam\sangam-compliance\README.md'
print('compliance README now',H.get(cr,'')[:16],MT.get(cr))
print('MATCH',match,'MISMATCH',mis)
for x in sorted(rows): print('MIS',*x)
