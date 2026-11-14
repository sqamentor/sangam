import re,os,json,collections
ROOT=r'D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524'
L=open(r'D:\GITHUB  Project\sangam-handoff\FILE-CLAIMS.md',encoding='utf-8').read().split('\n')
SKIP={'node_modules','target','.git','dist','bin','obj','.venv','__pycache__','.gradle','build','vendor','.mvn'}
idx=collections.defaultdict(list)
for dp,dn,fn in [x for r0 in (os.path.join(ROOT,'sangam'),os.path.join(ROOT,'.github')) for x in os.walk(r0)]:
    dn[:]=[d for d in dn if d not in SKIP and not d.startswith('target')]
    for f in fn: idx[f].append(os.path.join(dp,f))
pairs=[]; abbrev=0; abbrev_lines=collections.Counter()
for i,s in enumerate(L,1):
    if i<1097: continue
    m=re.match(r'\s*(\S*Z)?\s*(\S+)\s+(CLAIM\+RELEASE|RELEASE)\b(.*)',s)
    if not m: continue
    body=m.group(4)
    # abbreviated hashes: 8-16 hex standalone not part of 64
    for h in re.findall(r'(?<![0-9A-Fa-f])([0-9A-F]{8}|[0-9A-F]{16})(?![0-9A-Fa-f])',body):
        abbrev+=1; abbrev_lines[i]+=1
    for mm in re.finditer(r'([\w./$\-]+?)(?:\s*\(new\))?\s*(?:\(new\))?\s+(?:\(new\)\s+)?([0-9A-Fa-f]{64})(?![0-9A-Fa-f])',body):
        tok=mm.group(1).strip().rstrip(':')
        pairs.append((i,m.group(2),tok,mm.group(2).upper(),body[max(0,mm.start()-120):mm.start()]))
# resolve
res=[]
for (i,w,tok,h,ctx) in pairs:
    t=tok.replace('(new)','')
    cand=None; how=''
    if t.startswith('sangam/') or t.startswith('.github/'):
        p=os.path.join(ROOT,*t.split('/')); cand=[p] if os.path.isfile(p) else []; how='full'
    elif '/' in t and t.split('/')[0].startswith('sangam-'):
        p=os.path.join(ROOT,'sangam',*t.split('/')); cand=[p] if os.path.isfile(p) else []; how='mod'
    else:
        b=t.split('/')[-1]
        c=idx.get(b,[])
        if '/' in t: c=[x for x in c if x.replace('\\','/').endswith(t)]
        if len(c)>1:
            # disambiguate by module names mentioned in context or whole line
            line=L[i-1]
            mods=set(re.findall(r'(sangam-[a-z\-]+|sdks/[a-z\-]+|tools/[a-z\-]+|deploy/[a-z/\-]+)',ctx)) or set(re.findall(r'(sangam-[a-z\-]+|sdks/[a-z\-]+)',line))
            c2=[x for x in c if any(('\\'+mm.replace('/','\\')+'\\') in x for mm in mods)]
            if len(c2)>=1: c=c2
        cand=c; how='base'
    res.append(dict(line=i,w=w,tok=tok,hash=h,cand=cand,how=how))
json.dump(dict(res=res,abbrev=abbrev,abbrev_lines=abbrev_lines),open(r'%s\pairs.json'%os.environ.get('X5','.'),'w'),indent=0)
print('pairs',len(res),'abbrev',abbrev,'lines with abbrev',len(abbrev_lines))
print('unresolved',sum(1 for r in res if len(r['cand'])==0),'ambiguous',sum(1 for r in res if len(r['cand'])>1))

