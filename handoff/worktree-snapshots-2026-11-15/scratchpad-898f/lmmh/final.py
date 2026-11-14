import csv,re,sys,json,subprocess
pdf,data,before,after=sys.argv[1:5]
s=open(data+'/../extract_lmmh.py',encoding='utf-8').read()
x="'rule': b['rule'] if b else ''"
if x in s:
    s=s.replace(x,"'rule': b['rule'] if b and b['rule'] not in m['name'] else ''")
    open(data+'/../extract_lmmh.py','w',encoding='utf-8',newline='\n').write(s)
rd=lambda f:list(csv.DictReader(l for l in open(f,encoding='utf-8',newline='') if not l.startswith('#')))
rows=rd(data+'/lmmh-heads.csv')
pages=subprocess.run(['pdftotext','-simple','-enc','UTF-8','-q',pdf,'-'],capture_output=True).stdout.decode('utf-8','replace').split('\f')
norm=lambda t:re.sub(r'[^a-z0-9]+',' ',t.lower())
P=[norm(p) for p in pages]
bad=[]
for r in rows:
    if r['level']=='template':continue
    p=P[int(r['page'])-1]+' '+(P[int(r['page'])] if int(r['page'])<len(P) else '')
    w=norm(r['name']).split()[:4]
    if not re.search(r'(?<!\d)'+r['code']+r'(?!\d)',p) or not all(x in p for x in w): bad.append((r['level'],r['major'],r['subMajor'],r['code'],r['page'],r['name'][:60]))
print('all-row check fail',len(bad),'of',len(rows));print(bad[:25])
old={r['code']:r for r in rd(before+'/in/seeds/hoa-major-heads.csv')}
new={r['code']:r for r in rd(after+'/in/seeds/hoa-major-heads.csv')}
print('old',len(old),'new',len(new),'old U',sum(r['status']=='U' for r in old.values()))
print('removed',sorted(set(old)-set(new)));print('added',len(set(new)-set(old)))
ren=[(c,old[c]['name_en'],new[c]['name_en']) for c in old if c in new and old[c]['name_en'].lower()!=new[c]['name_en'].lower()]
print('renamed',len(ren));print(ren[:12])
