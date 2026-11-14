import json,yaml,re,sys,collections,glob
S=sys.argv[1]
B=json.load(open(S+'/dump/bodies.json',encoding='utf-8'))
R=B['records']
d=yaml.safe_load(open('sangam-api.yaml',encoding='utf-8'))
SC=d['components']['schemas']
def shape(p): return re.sub(r'\{[^}]*\}','{}',p)
idx={}
for p,ms in d['paths'].items():
    for m,o in ms.items(): idx[(m,shape(p))]=(p,o)
src={f:open(f,encoding='utf-8').read() for f in ['gen_openapi.py']+glob.glob('routes/*.py')}
def T(t):
    k=t['k']
    if k=='record': return 'REC:'+t['name'].split('.')[-1]
    if k=='list': return 'list:'+T(t['of'])
    if k=='map': return 'map<'+T(t['of'])+'>'
    if k=='enum': return 'enum'+str(t['values'])
    if k in('class','other','json'): return k+':'+t['name']
    return k
seen=set()
for o in B['ops']:
    if o['type']['k']!='record': continue
    key=(o['verb'].lower(), shape(o['path'][len('/api/v1'):]))
    cp,op=idx[key]; rb=op['requestBody']
    sch=[c['schema'] for ct,c in rb['content'].items() if 'json' in ct][0]
    s2=SC[sch['$ref'].split('/')[-1]] if '$ref' in sch else sch
    if s2.get('properties'): continue
    files=[f for f,t in src.items() if '"%s"'%op['operationId'] in t]
    rn=o['type']['name']
    comps=[(c['json'] or c['name'])+':'+T(c['type']) for c in R[rn]['components']]
    print(op['operationId'], o['verb'], cp, files, 'ref='+(sch.get('$ref','inline').split('/')[-1]), rn.split('.')[-1], 'req=%s'%rb.get('required'))
    print('    ', ' '.join(comps), 'creator' if R[rn]['creator'] else '')
