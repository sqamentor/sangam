import json,yaml,re,sys,collections
S=sys.argv[1]
B=json.load(open(S+'/dump/bodies.json',encoding='utf-8'))
d=yaml.safe_load(open('sangam-api.yaml',encoding='utf-8'))
SC=d['components']['schemas']
def shape(p): return re.sub(r'\{[^}]*\}','{}',p)
handlers=collections.defaultdict(list)
for o in B['ops']:
    p=o['path']
    if not p.startswith('/api/v1'): continue
    handlers[(o['verb'].lower(), shape(p[len('/api/v1'):] or '/'))].append(o)
def is_open(s):
    if '$ref' in s:
        t=SC[s['$ref'].split('/')[-1]]
        return t.get('additionalProperties') is True and not t.get('properties'), s['$ref'].split('/')[-1]
    return s.get('additionalProperties') is True and not s.get('properties') and s.get('type')=='object', None
res=collections.Counter(); rows=[]
for p,ms in d['paths'].items():
    for m,o in ms.items():
        rb=o.get('requestBody')
        if not rb: continue
        for ct,c in rb.get('content',{}).items():
            if ct!='application/json': continue
            op,name=is_open(c.get('schema',{}))
            if not op: continue
            hs=handlers.get((m,shape(p)),[])
            kinds=[h['type']['k']+(':'+h['type'].get('name','') if h['type']['k'] in('record','class','other') else '') for h in hs]
            k = 'nohandler' if not hs else ('record' if all(x.startswith('record') for x in kinds) else ('map' if all(x.startswith('map') for x in kinds) else 'mixed:'+','.join(kinds)))
            res[k]+=1
            rows.append((k,m,p,o['operationId'],name,kinds, o['tags'][0]))
print(res)
by=collections.defaultdict(list)
for r in rows: by[r[0]].append(r)
for k in by:
    if k!='record': 
        for r in by[k]: print(r)
json.dump(rows,open(S+'/m051rows.json','w'),indent=1)
tags=collections.Counter(r[6] for r in rows if r[0]=='record'); print(tags)
