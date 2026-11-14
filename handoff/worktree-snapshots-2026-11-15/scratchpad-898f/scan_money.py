import re,glob,sys,html,os
root=sys.argv[1]
sys.path.insert(0,os.path.join(root,'tools','packgen'))
import money
for f in glob.glob(os.path.join(root,'src/main/resources/packs/*/*.bpmn')):
    s=html.unescape(open(f,encoding='utf-8').read())
    for e in re.findall(r'\$\{([^}]*)\}', s):
        vs=[v for v in re.findall(r'\b[a-z]\w*\b', e) if money.is_money(v)]
        nums=[n for n in re.findall(r'(?<![\w.])\d+(?:\.\d+)?(?![\w.])', e) if n not in ('0','1','100')]
        if vs and nums: print('EXPR', os.path.basename(f)[:50], '|', e[:200])
for f in glob.glob(os.path.join(root,'src/main/resources/packs/**/*.dmn'), recursive=True):
    s=open(f,encoding='utf-8').read()
    if re.search(r'typeRef="(double|float)"', s): print('DOUBLE', f)
    for m in re.finditer(r'<outputEntry[^>]*><text>([^<]*)</text>', s):
        t=html.unescape(m.group(1))
        if re.search(r'[a-z]\w*\s*[*/+-]', t) and re.search(r'\d', t): print('DMNEXPR', os.path.basename(f), '|', t[:160])
