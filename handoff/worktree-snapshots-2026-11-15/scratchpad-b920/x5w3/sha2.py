import re,os,json,collections,sys
ROOT=r'D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524'
X5=sys.argv[1]
L=open(r'D:\GITHUB  Project\sangam-handoff\FILE-CLAIMS.md',encoding='utf-8').read().split('\n')
idx=collections.defaultdict(list)
for r0 in (os.path.join(ROOT,'sangam'),os.path.join(ROOT,'.github')):
    for dp,dn,fn in os.walk(r0):
        dn[:]=[d for d in dn if d not in {'node_modules','.git','dist','obj','bin','.venv','__pycache__','vendor','.mvn','build'} and not d.startswith('target')]
        for f in fn: idx[f].append(os.path.join(dp,f))
NONFILE={'aggregate','manifest','lines'}
pairs=[]; abbrev=collections.Counter()
for i,s in enumerate(L,1):
    if i<1097: continue
    m=re.match(r'\s*(\S*Z)?\s*(\S+)\s+(CLAIM\+RELEASE|RELEASE)\b(.*)',s)
    if not m: continue
    body=m.group(4)
    for h in re.findall(r'(?<![0-9A-Fa-f])([0-9A-F]{8}|[0-9A-F]{16})(?![0-9A-Fa-f])',body): abbrev[i]+=1
    for mm in re.finditer(r'([\w./$\-]+?)(?:\s*\(new\))?\s+(?:\(new\)\s+)?([0-9A-Fa-f]{64})(?![0-9A-Fa-f])',body):
        pairs.append((i,m.group(2),mm.group(1).strip(),mm.group(2).upper(),body[:mm.start()]))
def resolve(i,w,t,ctx):
    if t in NONFILE: return None,'nonfile'
    if t in ('h2','mssql','mysql','oracle','postgresql'):
        p=os.path.join(ROOT,'sangam','sangam-persistence','src','main','resources','db','changelog',t,'sangam-0001-baseline.sql'); return [p],'dialect'
    if t=='SANGAM.md':
        c=[p for p in (os.path.join(ROOT,'SANGAM.md'),os.path.join(ROOT,'sangam','SANGAM.md')) if os.path.isfile(p)]; return c,'sangam.md'
    if t.startswith('sangam/') or t.startswith('.github/'):
        p=os.path.join(ROOT,*t.split('/')); return ([p] if os.path.isfile(p) else []),'full'
    if '/' in t and t.split('/')[0].startswith('sangam-'):
        p=os.path.join(ROOT,'sangam',*t.split('/')); return ([p] if os.path.isfile(p) else []),'mod'
    for cand_t in (t,t+'.java'):
        b=cand_t.split('/')[-1]
        c=idx.get(b,[])
        if '/' in cand_t: c=[x for x in c if x.replace('\\','/').endswith('/'+cand_t)]
        if c: break
    if len(c)>1:
        mods=re.findall(r'(sangam-[a-z\-]+|sdks/[a-z\-]+|tools/[a-z\-]+)',ctx[-400:])
        for mo in reversed(mods):
            c2=[x for x in c if ('\\'+mo.replace('/','\\')+'\\') in x]
            if c2: c=c2; break
    if len(c)>1 and t=='PATCHES.md' and w=='B47': c=[x for x in c if '\\sangam-engine\\' in x]
    return c,'base'
out=[]
for (i,w,t,h,ctx) in pairs:
    c,how=resolve(i,w,t,ctx)
    if c is None: continue
    out.append(dict(line=i,w=w,tok=t,hash=h,cand=c,how=how))
# last release per path (only when single candidate)
last={}
amb=[]; unres=[]
for r in out:
    if len(r['cand'])==1: last[r['cand'][0]]=r
    elif len(r['cand'])==0: unres.append(r)
    else: amb.append(r)
json.dump(dict(last=last,amb=amb,unres=unres,abbrev=abbrev,npairs=len(pairs)),open(X5+r'\pairs2.json','w'))
open(X5+r'\paths.txt','w',encoding='utf-8').write('\n'.join(sorted(set(list(last.keys())+[c for r in amb for c in r['cand']]))))
print('pairs',len(pairs),'resolved files',len(last),'ambiguous',len(amb),'unresolved',len(unres),'abbrev hashes',sum(abbrev.values()),'in lines',sorted(abbrev))
for r in unres: print('UNRES',r['line'],r['w'],r['tok'])
for r in amb: print('AMB',r['line'],r['w'],r['tok'],len(r['cand']))
