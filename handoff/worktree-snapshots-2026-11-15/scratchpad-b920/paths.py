import re,sys
y=open('sangam-rest/api/sangam-api.yaml',encoding='utf8').read()
ps=[m.group(1) for m in re.finditer(r'^  (/[^:\s]*):\s*$',y,re.M)]
norm=lambda p: re.sub(r'\{[^}]+\}','{}',p)
S={norm(p) for p in ps}
for f in sys.argv[1:]:
  s=open(f,encoding='utf8').read()
  for m in re.finditer(r"['`](/(?:[a-z][\w\-./{}$]*))['`]",s):
    p=m.group(1).split('?')[0]
    p=re.sub(r'\$\{[^}]+\}','{}',p)
    if p.startswith('/engine-rest') or p.startswith('/m/') or p.startswith('/ops/') and False: continue
    n=norm(p)
    line=s[:m.start()].count('\n')+1
    print(('OK ' if n in S else 'NO ')+f"{f}:{line} {p}")
