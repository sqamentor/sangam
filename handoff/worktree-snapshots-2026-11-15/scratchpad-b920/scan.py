import os,re,sys,yaml
root=sys.argv[1]
d=yaml.safe_load(open(os.path.join(root,'sangam-rest/api/sangam-events.asyncapi.yaml'),encoding='utf-8'))
names=set()
def walk(o):
    if isinstance(o,dict):
        for k,v in o.items():
            if k=='enum' and isinstance(v,list) and len(v)>100: names.update(v)
            walk(v)
    elif isinstance(o,list):
        for v in o: walk(v)
walk(d)
lits=set()
for dp,dn,fn in os.walk(root):
    dn[:]=[x for x in dn if not (x.startswith('target') or x in('node_modules','generated') or x.startswith('.'))]
    if '/src/main/java' not in dp.replace(os.sep,'/'): continue
    for f in fn:
        if f.endswith('.java'):
            s=open(os.path.join(dp,f),encoding='utf-8',errors='replace').read()
            lits.update(re.findall(r'"([A-Z_][A-Z_]*)"',s)); lits.update(re.findall(r'\b([A-Z][A-Z_]+)\s*[,;(]',s))
print(len(names), sorted(n for n in names if n not in lits))
