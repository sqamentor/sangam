import sys,os,re,xml.etree.ElementTree as ET
B='{http://www.omg.org/spec/BPMN/20100524/MODEL}'
S='{http://sangam.org/schema/1.0/bpmn}'
roots=sys.argv[1:]
n=0
for r in roots:
  for dp,dn,fn in os.walk(r):
    if 'target' in dp.split(os.sep) or 'node_modules' in dp: continue
    for f in fn:
      if not f.endswith('.bpmn'): continue
      p=os.path.join(dp,f)
      try: t=ET.parse(p)
      except Exception as e: print('PARSEFAIL',p,e); continue
      for proc in t.iter():
        gws={g.get('id'):g for g in proc.findall(B+'exclusiveGateway')}
        if not gws: continue
        flows=proc.findall(B+'sequenceFlow')
        for gid,g in gws.items():
          out=[x for x in flows if x.get('sourceRef')==gid]
          if len(out)<2: continue
          d=g.get('default')
          rows=[]
          bad=False
          for x in out:
            c=x.find(B+'conditionExpression')
            ct=(c.text or '').strip() if c is not None else None
            if x.get('id')!=d and ct is None: bad=True
            rows.append((x.get('id'),ct,x.get(S+'priority')))
          n+=1
          print(f"{os.path.relpath(p)}|{gid}|default={d}|policy={g.get(S+'routingPolicy')}|uncond_nondefault={bad}|"+" ;; ".join(f"{i}: {c}" for i,c,_ in rows))
print('TOTAL',n, file=sys.stderr)
