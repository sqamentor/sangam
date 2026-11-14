# usage: python rep.py file pairs.json  ; pairs: [[old,new],...]; keeps CRLF if file uses it
import sys,json
p=sys.argv[1]; pairs=json.load(open(sys.argv[2],encoding='utf-8'))
raw=open(p,'rb').read().decode('utf-8')
crlf='\r\n' in raw
t=raw.replace('\r\n','\n')
for a,b in pairs:
    n=t.count(a)
    assert n==1,(n,a[:80])
    t=t.replace(a,b)
if crlf: t=t.replace('\n','\r\n')
open(p,'wb').write(t.encode('utf-8'))
print('ok',p,'crlf' if crlf else 'lf')
