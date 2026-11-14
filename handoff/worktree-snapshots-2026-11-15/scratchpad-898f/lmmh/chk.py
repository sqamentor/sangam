import json,sys,csv,re
d=sys.argv[1];r=json.load(open(d+'/lmmh-report.json',encoding='utf-8'));v=r['validation']
for k in ('duplicateCodes','minorsUnderUnknownSubMajor','majorsWithoutMinors'):print(k,v[k])
for a in r['anomalies']:print(a['page'],a['issue'][:30],a['text'][:90])
rows=list(csv.DictReader(l for l in open(d+'/lmmh-heads.csv',encoding='utf-8') if not l.startswith('#')))
print('SUSPECT')
for x in rows:
    n=x['name']
    if x['level'] in('minor','subMajor') and (len(n)>130 or re.search(r'\d{3}\s|^\W|[a-z]\d|Note|minor head|sub-head',n) or len(n)<3 or n[0].islower()):print(x['level'],x['major'],x['subMajor'],x['code'],x['page'],n[:130])
R=[x for x in rows if x['rule']];print('RULES',len(R))
for x in R[:45]:print(x['level'],x['major'],x['code'],x['rule'][:80])
