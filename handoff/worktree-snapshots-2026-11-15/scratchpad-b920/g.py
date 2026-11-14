import os,re,sys
root=r"D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524"
pat=re.compile(sys.argv[1], re.I if '-i' in sys.argv else 0)
sub=sys.argv[2] if len(sys.argv)>2 and not sys.argv[2].startswith('-') else ''
mode='-l' in sys.argv
skip={'node_modules','target','.git','dist','handoff','spike','.idea','build','coverage','.claude'}
base=os.path.join(root,sub)
n=0
for dp,dn,fn in os.walk(base):
    dn[:]=[d for d in dn if d not in skip]
    for f in fn:
        if f.endswith(('.jar','.png','.ttf','.woff','.woff2','.class','.zip','.gz','.pdf','.ico','.jpg','.otf')): continue
        p=os.path.join(dp,f)
        try:
            with open(p,encoding='utf-8',errors='ignore') as fh:
                for i,l in enumerate(fh,1):
                    if pat.search(l):
                        n+=1
                        rel=os.path.relpath(p,root)
                        if mode: print(rel); break
                        print(f"{rel}:{i}: {l.strip()[:220]}")
                        if n>400: sys.exit()
        except Exception: pass
