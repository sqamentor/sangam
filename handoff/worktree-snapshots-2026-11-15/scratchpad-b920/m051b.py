import json,yaml,re,sys,collections
S=sys.argv[1]
B=json.load(open(S+'/dump/bodies.json',encoding='utf-8'))
R=B['records']
d=yaml.safe_load(open('sangam-api.yaml',encoding='utf-8'))
SC=d['components']['schemas']
def shape(p): return re.sub(r'\{[^}]*\}','{}',p)
def res(s):
    while '$ref' in s: s=SC[s['$ref'].split('/')[-1]]
    if 'allOf' in s and len(s['allOf'])==1: return res(s['allOf'][0])
    return s
idx={}
for p,ms in d['paths'].items():
    for m,o in ms.items(): idx[(m,shape(p))]=(p,o)
cnt=collections.Counter(); out=[]
for o in B['ops']:
    if o['type']['k']!='record': continue
    p=o['path']; key=(o['verb'].lower(), shape(p[len('/api/v1'):]))
    if key not in idx: cnt['no-contract-op']+=1; out.append(('NOOP',o['verb'],p)); continue
    cp,op=idx[key]
    rb=op.get('requestBody')
    if not rb: cnt['contract-no-body']+=1; out.append(('NOBODY',o['verb'],cp,o['type']['name'])); continue
    sch=None
    for ct,c in rb['content'].items():
        if 'json' in ct: sch=res(c['schema']); break
    if sch is None: cnt['nonjson']+=1; out.append(('NONJSON',o['verb'],cp,list(rb['content']))); continue
    rec=R[o['type']['name']]
    names=[c['json'] or c['name'] for c in rec['components']]
    props=set((sch.get('properties') or {}).keys())
    if not props: cnt['open']+=1; continue
    missing=[n for n in names if n not in props]
    extra=[n for n in props if n not in names]
    if missing: cnt['missing']+=1; out.append(('MISSING',o['verb'],cp,o['type']['name'].split('.')[-1],missing,extra))
    else: cnt['ok' + ('+extra' if extra else '')]+=1
    if extra and not missing: out.append(('EXTRA',o['verb'],cp,o['type']['name'].split('.')[-1],extra))
print(cnt)
for x in out: print(x)
