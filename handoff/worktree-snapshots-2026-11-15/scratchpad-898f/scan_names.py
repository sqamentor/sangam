import re,glob,sys,html,os,json
root=sys.argv[1]
sys.path.insert(0,os.path.join(root,'tools','packgen'))
import money
names={}
for f in glob.glob(os.path.join(root,'src/main/resources/packs/*/*.bpmn')):
    s=html.unescape(open(f,encoding='utf-8').read())
    for spec in re.findall(r'<operaton:field name="(?:defaults|values)"><operaton:string>([^<]*)<', s):
        for p in spec.split(';'):
            k,_,v=p.partition('=')
            if re.fullmatch(r'-?\d+(\.\d+)?',v.strip()): names.setdefault((k.strip(),money.is_money(k.strip())),set()).add(v)
for f in glob.glob(os.path.join(root,'src/main/resources/packs/*/pack.json')):
    j=json.load(open(f,encoding='utf-8'))
    for a in j.get('acceptance',[]):
        for k,v in a.get('startVariables',{}).items():
            if isinstance(v,(int,float)) and not isinstance(v,bool): names.setdefault((k,money.is_money(k)),set()).add(str(v))
for (k,m),v in sorted(names.items()):
    if not m: print('num ', k, sorted(v)[:4])
print('MONEY:', sorted(k for (k,m) in names if m))
