import re,glob,os
gen=open('sangam-persistence/tools/generate_changelogs.py',encoding='utf-8').read()
have=set(re.findall(r'^\s*(?:\("[0-9-]+", "[^\n]*?", )?"([A-Z][A-Z0-9_]+)", \[',gen,re.M))
for f in glob.glob('sangam-*/src/main/**/*.java',recursive=True):
    n=f.replace('\','/')
    if 'control-plane' in n or 'rollup' in n or 'core/layer/Schema' in n: continue
    s=open(f,encoding='utf-8').read()
    ts=re.findall(r'CREATE TABLE (?:IF NOT EXISTS )?([A-Za-z_0-9]+)',s)
    miss=[t for t in ts if t.upper() not in have and t.startswith(t.upper())]
    if miss: print(n,miss)
