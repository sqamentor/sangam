import json,yaml,re,sys,collections
S=sys.argv[1]; spec=sys.argv[2]
B=json.load(open(S+'/dump/bodies.json',encoding='utf-8')); R=B['records']
d=yaml.safe_load(open(spec,encoding='utf-8')); SC=d['components']['schemas']
def shape(p): return re.sub(r'\{[^}]*\}','{}',p)
def res(s):
    while isinstance(s,dict) and '$ref' in s: s=SC[s['$ref'].split('/')[-1]]
    if isinstance(s,dict) and 'allOf' in s and len(s['allOf'])==1: return res(s['allOf'][0])
    return s
idx={(m,shape(p)):(p,o) for p,ms in d['paths'].items() for m,o in ms.items()}
OK={'s':{'string'},'int':{'integer'},'long':{'integer'},'bool':{'boolean'},'num':{'number'},'decimal':{'string','number'},'bigint':{'string','integer'},
    'dt':{'string'},'ldt':{'string'},'date':{'string'},'time':{'string'},'duration':{'string'},'uuid':{'string'},'enum':{'string'},'map':{'object'},'list':{'array'},'record':{'object'},'bytes':{'string'}}
errs=[]; stats=collections.Counter()
def chk(where,t,ps):
    ps=res(ps)
    if ps is None: errs.append(where+': no schema'); return
    ty=ps.get('type')
    if ty is None or t['k'] in ('any','json'): return
    tys=set(ty) if isinstance(ty,list) else {ty}
    if not tys & OK.get(t['k'],set()): errs.append('%s: %s vs %s'%(where,t['k'],ty)); return
    if t['k']=='record' and ps.get('properties'):
        comps=R[t['name']]['components']; names=[c['json'] or c['name'] for c in comps]
        props=ps['properties']
        miss=[n for n in names if n not in props]; extra=[n for n in props if n not in names]
        if miss or extra: errs.append('%s: missing %s extra %s'%(where,miss,extra))
        for c in comps:
            n=c['json'] or c['name']
            if n in props: chk(where+'.'+n,c['type'],props[n])
    if t['k']=='list' and ps.get('items') is not None: chk(where+'[]',t['of'],ps['items'])
    if t['k']=='enum' and ps.get('enum') and set(ps['enum'])!=set(t['values']): errs.append('%s: enum %s vs %s'%(where,ps['enum'],t['values']))
for o in B['ops']:
    if o['type']['k']!='record': continue
    key=(o['verb'].lower(), shape(o['path'][len('/api/v1'):]))
    cp,op=idx[key]
    sch=[c['schema'] for ct,c in op['requestBody']['content'].items() if 'json' in ct][0]
    r=res(sch)
    if not r.get('properties'): stats['untyped']+=1; errs.append('UNTYPED %s %s'%(o['verb'],cp)); continue
    stats['typed']+=1
    chk(o['verb']+' '+cp,o['type'],sch)
print(stats); print(len(errs)); print('\n'.join(errs))
