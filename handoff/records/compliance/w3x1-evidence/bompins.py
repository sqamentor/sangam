"""Compare explicit version pins in the reactor POMs with the versions managed by the imported BOMs (read from ~/.m2)."""
import os, re, sys, xml.etree.ElementTree as ET
NS = '{http://maven.apache.org/POM/4.0.0}'
M2 = os.path.join(os.path.expanduser('~'), '.m2', 'repository')
root = sys.argv[1]

def pom(g, a, v):
    return os.path.join(M2, *g.split('.'), a, v, f'{a}-{v}.pom')

def load(path):
    return ET.parse(path).getroot()

def props_chain(g, a, v):
    props, chain = {}, []
    while True:
        p = pom(g, a, v)
        if not os.path.exists(p):
            break
        r = load(p); chain.append(r)
        pr = r.find(NS + 'properties')
        if pr is not None:
            for c in pr:
                props.setdefault(c.tag.replace(NS, ''), (c.text or '').strip())
        par = r.find(NS + 'parent')
        if par is None:
            break
        g, a, v = par.findtext(NS + 'groupId'), par.findtext(NS + 'artifactId'), par.findtext(NS + 'version')
    props.setdefault('project.version', v)
    return props, chain

def interp(s, props, depth=0):
    if s is None:
        return s
    for _ in range(10):
        n = re.sub(r'\$\{([^}]+)\}', lambda m: props.get(m.group(1), m.group(0)), s)
        if n == s:
            break
        s = n
    return s

def managed(g, a, v, seen=None):
    """flattened dependencyManagement of a BOM, with nested imports (first wins)"""
    seen = seen or set()
    out = {}
    props, chain = props_chain(g, a, v)
    props['project.version'] = v
    for r in chain:
        dm = r.find(NS + 'dependencyManagement')
        if dm is None:
            continue
        for d in dm.iter(NS + 'dependency'):
            dg, da, dv = interp(d.findtext(NS + 'groupId'), props), interp(d.findtext(NS + 'artifactId'), props), interp(d.findtext(NS + 'version'), props)
            if d.findtext(NS + 'scope') == 'import':
                if (dg, da, dv) not in seen:
                    seen.add((dg, da, dv))
                    for k, val in managed(dg, da, dv, seen).items():
                        out.setdefault(k, val)
            else:
                out.setdefault(f'{dg}:{da}', (dv, f'{a}:{v}'))
    return out

boms = [('org.operaton.bpm', 'operaton-bom', '2.2.0'), ('com.fasterxml.jackson', 'jackson-bom', '2.22.3'), ('org.junit', 'junit-bom', '6.0.3'),
        ('org.testcontainers', 'testcontainers-bom', '1.21.4'), ('org.springframework.boot', 'spring-boot-dependencies', '4.1.2')]
per = {}
for b in boms:
    per[b[1]] = managed(*b)
    print('BOM', b, 'manages', len(per[b[1]]))
# operaton-bom contents beyond org.operaton
ext = {k: v for k, v in per['operaton-bom'].items() if not k.startswith('org.operaton')}
print('operaton-bom non-operaton entries:', len(ext))
for k, v in sorted(ext.items())[:80]:
    print('   ', k, v)
# overlaps: what the order-sensitive import resolves
order = ['operaton-bom', 'jackson-bom', 'junit-bom', 'testcontainers-bom', 'spring-boot-dependencies']
eff = {}
for b in order:
    for k, v in per[b].items():
        eff.setdefault(k, (v[0], b))
shadow = []
for k in eff:
    vals = {b: per[b][k][0] for b in order if k in per[b]}
    if len(set(vals.values())) > 1:
        shadow.append((k, vals, eff[k]))
print('BOM OVERLAPS WITH DIFFERENT VERSIONS:', len(shadow))
for s in sorted(shadow):
    print('   ', s)
# explicit pins in the reactor
props = {}
rp = load(os.path.join(root, 'pom.xml'))
for c in rp.find(NS + 'properties'):
    props[c.tag.replace(NS, '')] = (c.text or '').strip()
props['project.version'] = '0.1.0-SNAPSHOT'
print('EXPLICIT PINS vs BOM:')
for dp, dns, fns in os.walk(root):
    dns[:] = [d for d in dns if not d.startswith('target') and d not in ('node_modules', '.git', 'spike', 'deploy')]
    if 'pom.xml' not in fns:
        continue
    p = os.path.join(dp, 'pom.xml'); rel = os.path.relpath(p, root)
    r = load(p)
    lp = dict(props)
    pr = r.find(NS + 'properties')
    if pr is not None:
        for c in pr:
            lp[c.tag.replace(NS, '')] = (c.text or '').strip()
    for d in r.iter(NS + 'dependency'):
        g, a, v = d.findtext(NS + 'groupId'), d.findtext(NS + 'artifactId'), d.findtext(NS + 'version')
        if not v or d.findtext(NS + 'scope') == 'import' or g == 'org.sangam':
            continue
        v = interp(v, lp)
        k = f'{g}:{a}'
        hits = {b: per[b][k][0] for b in order if k in per[b]}
        if hits and any(x != v for x in hits.values()):
            print(f'   {rel}: {k} pinned {v} ; BOM {hits} ; effective-without-pin {eff[k]}')
        elif hits:
            print(f'   {rel}: {k} pinned {v} == BOM {hits} (redundant pin)')
    # plugin versions
    for pl in r.iter(NS + 'plugin'):
        g = pl.findtext(NS + 'groupId') or 'org.apache.maven.plugins'; a = pl.findtext(NS + 'artifactId'); v = interp(pl.findtext(NS + 'version'), lp)
        if not v:
            continue
        sb = props_chain('org.springframework.boot', 'spring-boot-dependencies', '4.1.2')[0]
        # spring-boot-dependencies pluginManagement
        for c in props_chain('org.springframework.boot', 'spring-boot-dependencies', '4.1.2')[1]:
            pm = c.find(NS + 'build/' + NS + 'pluginManagement')
            if pm is None:
                continue
            for q in pm.iter(NS + 'plugin'):
                if q.findtext(NS + 'artifactId') == a:
                    bv = interp(q.findtext(NS + 'version'), sb)
                    print(f'   PLUGIN {rel}: {g}:{a} pinned {v} ; spring-boot-dependencies pluginManagement {bv}' + ('' if bv == v else '  <-- differs'))
