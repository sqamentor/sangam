import re,json,sys
S=sys.argv[1]
for r in ["WORK"]:
    c=set(re.findall(r'^ {6}operationId: (\S+)$',open(f'{S}/api_{r}.yaml',encoding='utf-8').read(),re.M))
    m={x['operationId'] for x in json.load(open(f'{S}/man_{r}.json',encoding='utf-8'))['surface']}
    print(r,len(c),len(m),'contract-only',sorted(c-m)[:10],'manifest-only',sorted(m-c)[:10])
