import os, re, sys, collections
import xml.dom.minidom as md

root = sys.argv[1]
MODEL = {"http://www.omg.org/spec/BPMN/20100524/MODEL", "http://www.omg.org/spec/BPMN/20100524/DI",
         "http://www.omg.org/spec/DD/20100524/DC", "http://www.omg.org/spec/DD/20100524/DI",
         "http://www.omg.org/spec/CMMN/20151109/MODEL", "http://www.omg.org/spec/CMMN/20151109/CMMNDI",
         "http://www.omg.org/spec/CMMN/20151109/DI", "http://www.omg.org/spec/CMMN/20151109/DC",
         "http://www.w3.org/2001/XMLSchema-instance", "http://www.w3.org/2000/xmlns/",
         "http://www.w3.org/XML/1998/namespace"}
java = open(os.path.join(root, 'sangam-engine/src/main/java/org/sangam/engine/deploy/ModelCodeAllowList.java'), encoding='utf-8').read()
own = set(re.findall(r'"(org\.sangam\.[\w.$]+)"', java.split('SANGAM_CLASSES = Set.of(')[1].split(');')[0]))
beans = {"sangamOnboardingStep"}
bean_re = re.compile(r'^\s*[$#]\{\s*([A-Za-z_][A-Za-z0-9_]*)\s*}\s*$')
models = []
for dp, dn, fn in os.walk(root):
    dn[:] = [d for d in dn if not d.startswith('target') and d not in ('node_modules', '.git')]
    for f in fn:
        p = os.path.join(dp, f).replace(chr(92), '/')
        n = f.lower()
        if '/src/main/' in p and (n.endswith('.bpmn') or n.endswith('.bpmn20.xml') or n.endswith('.cmmn') or n.endswith('.cmmn11.xml') or n.endswith('.cmmn.xml')):
            models.append(p)
bad = []
seen = collections.Counter()
def walk(e, p):
    ext_el = e.namespaceURI is not None and e.namespaceURI not in MODEL
    for i in range(e.attributes.length):
        a = e.attributes.item(i)
        ns = a.namespaceURI
        ext = (ns not in MODEL) if ns is not None else ext_el
        if not ext:
            continue
        local = a.localName or a.name
        if local == 'class':
            seen[a.value.strip()] += 1
            if a.value.strip() not in own:
                bad.append((p, 'CLASS', a.value))
        elif local == 'delegateExpression':
            m = bean_re.match(a.value)
            if not m or m.group(1) not in beans:
                bad.append((p, 'DELEGATE', a.value))
    for c in e.childNodes:
        if c.nodeType == c.ELEMENT_NODE:
            walk(c, p)
for p in models:
    walk(md.parse(p).documentElement, p)
print('models', len(models), 'own list', len(own))
print('classes seen', len(seen))
print('BAD', bad[:20], len(bad))
# source scan
DEL = re.compile(r'(?:class)\s+(\w+)[^{]*\bimplements\s+[^{]*\b(?:JavaDelegate|ExecutionListener|TaskListener|ActivityBehavior|SignallableActivityBehavior|CaseExecutionListener)\b')
found = set()
for dp, dn, fn in os.walk(root):
    dn[:] = [d for d in dn if not d.startswith('target') and d not in ('node_modules', '.git')]
    for f in fn:
        p = os.path.join(dp, f).replace(chr(92), '/')
        rel = p[len(root):].lstrip('/')
        if '/src/main/java/' in p and f.endswith('.java') and not rel.startswith('examples/'):
            t = open(p, encoding='utf-8').read()
            m = re.search(r'^package\s+([\w.]+);', t, re.M)
            if not m or not m.group(1).startswith('org.sangam'):
                continue
            outer = f[:-5]
            for d in DEL.finditer(t):
                s = d.group(1)
                found.add(m.group(1) + '.' + (outer if s == outer else outer + '$' + s))
print('missing from list', sorted(found - own))
print('extra in list', sorted(own - found))
