import json,re,sys
def fl(t,p=''):
    o={}
    for k,v in t.items():
        kk=f"{p}.{k}" if p else k
        if isinstance(v,str): o[kk]=v
        else: o.update(fl(v,kk))
    return o
for en,hi in [(sys.argv[1],sys.argv[2]),(sys.argv[3],sys.argv[4])]:
    E=fl(json.load(open(en,encoding='utf-8')));H=fl(json.load(open(hi,encoding='utf-8')))
    ph=lambda s:sorted(x.replace(' ','') for x in re.findall(r'\{\{\s*[\w.]+\s*\}\}',s))
    print(en, len(E),len(H),'missing',[k for k in E if k not in H or not H[k].strip()][:10],'extra',[k for k in H if k not in E][:10],'ph',[k for k in E if k in H and ph(E[k])!=ph(H[k])][:10], 'deva', sum(1 for v in H.values() if re.search('[ऀ-ॿ]',v))/len(H))
