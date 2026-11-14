import os,sys,re
root=sys.argv[1]; pats=sys.argv[2:]
for r,d,f in os.walk(root):
    d[:]=[x for x in d if not (x.startswith('target') or x in ('node_modules','.git','handoff','dist','build','bin','obj','.venv'))]
    for x in f:
        if any(re.search(p,x) for p in pats): print(os.path.join(r,x))
