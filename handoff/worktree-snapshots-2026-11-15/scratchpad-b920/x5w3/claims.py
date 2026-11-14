import re
L=open(r'D:\GITHUB  Project\sangam-handoff\FILE-CLAIMS.md',encoding='utf-8').read().split('\n')
E=[]
for i,s in enumerate(L,1):
    if i<1083 or not s.strip(): continue
    m=re.match(r'\s*(\S*Z)?\s*(\S+)\s+(CLAIM\+RELEASE|CLAIM|RELEASE|INFO|STANDING-CLAIM)\b(.*)',s)
    if not m: print('UNPARSED',i,s[:120]); continue
    E.append(dict(n=i,t=m.group(1) or '',w=m.group(2),ty=m.group(3),body=m.group(4)))
def paths(body):
    # expand braces
    out=[]
    for p in re.findall(r'[\w\-./{},*]+\.(?:java|py|yaml|yml|json|md|sql|xml|ts|tsx|sh|txt|ttf|Dockerfile|template|conf|properties|cs|php|csproj|toml|cfg|ini)\b|[\w\-./]+/\*\*|[\w\-.]+\.Dockerfile',body):
        m=re.match(r'(.*)\{([^}]*)\}(.*)',p)
        if m:
            for x in m.group(2).split(','): out.append(m.group(1)+x+m.group(3))
        else: out.append(p)
    return out
for e in E:
    if e['ty'] not in ('CLAIM',): continue
    ps=paths(e['body'])
    rel=[f for f in E if f['n']>e['n'] and f['w']==e['w'] and f['ty'] in ('RELEASE','CLAIM+RELEASE')]
    blanket=[f['n'] for f in rel if re.search(r'(?i)all (remaining )?claims|holds no claims|all W3-. claims|all claims',f['body'])]
    miss=[]
    for p in ps:
        b=p.rstrip('/*').split('/')[-1] if not p.endswith('**') else p[:-3].rstrip('/').split('/')[-1]
        if p.endswith('**'):
            key=p[:-3].rstrip('/')
            k2=key.split('/')[-1]
            hit=[f['n'] for f in rel if key in f['body'] or (k2+'/') in f['body'] or (k2+'/**') in f['body']]
        else:
            hit=[f['n'] for f in rel if b in f['body']]
        if not hit: miss.append(b)
    status='OK' if not miss else ('BLANKET '+','.join(map(str,blanket)) if blanket else 'MISSING')
    if status!='OK':
        print(f"C{e['n']} {e['t']} {e['w']} {status} miss={miss[:8]} :: {e['body'][:160]}")
