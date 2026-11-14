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
conv=collections.defaultdict(collections.Counter)
def walk(t,ps):
    k=t['k']
    ps=res(ps) if ps else {}
    sig=json.dumps({x:ps.get(x) for x in ('type','format','$ref') if x in ps}) if ps else 'none'
    if k in ('record',):
        rec=R[t['name']]
        for c in rec['components']:
            pp=(ps.get('properties') or {}).get(c['json'] or c['name'])
            walk(c['type'],pp)
    elif k=='list':
        conv['list'][ps.get('type')]+=1
        if ps.get('items'): walk(t['of'],ps['items'])
    else:
        key=k+(':'+t['of']['k'] if k=='map' else '')
        conv[key][sig]+=1
for o in B['ops']:
    if o['type']['k']!='record': continue
    key=(o['verb'].lower(), shape(o['path'][len('/api/v1'):]))
    if key not in idx: continue
    cp,op=idx[key]; rb=op.get('requestBody')
    if not rb: continue
    sch=[res(c['schema']) for ct,c in rb['content'].items() if 'json' in ct]
    if not sch or not sch[0].get('properties'): continue
    walk(o['type'],sch[0])
for k,v in conv.items(): print(k, dict(v))
