import yaml,sys,collections,json
d=yaml.safe_load(open(sys.argv[1],encoding='utf-8'))
S=d['components']['schemas']
cnt=collections.Counter(); where=collections.Counter()
def walk(o,path):
    if isinstance(o,dict):
        if o.get('additionalProperties') is True: cnt[path.split('/')[0]]+=1
        for k,v in o.items(): walk(v,path+'/'+str(k))
    elif isinstance(o,list):
        for i,v in enumerate(o): walk(v,path)
walk(d['paths'],'paths'); walk(S,'schemas')
print(cnt)
# request bodies
rb=collections.Counter(); kinds=collections.Counter(); ex=[]
for p,ms in d['paths'].items():
    for m,o in ms.items():
        if not isinstance(o,dict) or 'requestBody' not in o: continue
        for ct,c in o['requestBody'].get('content',{}).items():
            s=c.get('schema',{})
            if s.get('additionalProperties') is True and 'properties' not in s:
                kinds['inline-open']+=1; ex.append((m,p))
            elif '$ref' in s:
                n=s['$ref'].split('/')[-1]; t=S[n]
                if t.get('additionalProperties') is True: kinds['ref-open:'+('props' if 'properties' in t else 'noprops')]+=1
                else: kinds['ref-typed']+=1
            else: kinds['other:'+json.dumps(s)[:60]]+=1
print(kinds)
print(len(ex)); print(ex[:20])
