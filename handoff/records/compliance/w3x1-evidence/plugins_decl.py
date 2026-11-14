import os, sys, xml.etree.ElementTree as ET
root = sys.argv[1]
NS = '{http://maven.apache.org/POM/4.0.0}'
rows = set()
props_all = {}
for dp, dns, fns in os.walk(root):
    dns[:] = [d for d in dns if not d.startswith('target') and d not in ('node_modules', '.git', 'spike')]
    if 'pom.xml' in fns:
        p = os.path.join(dp, 'pom.xml')
        rel = os.path.relpath(p, root)
        t = ET.parse(p).getroot()
        pr = t.find(NS + 'properties')
        if pr is not None:
            for c in pr:
                props_all.setdefault(c.tag.replace(NS, ''), (c.text, rel))
        parent_map = {c: pa for pa in t.iter() for c in pa}
        for pl in t.iter(NS + 'plugin'):
            g = pl.findtext(NS + 'groupId') or 'org.apache.maven.plugins'
            a = pl.findtext(NS + 'artifactId')
            v = pl.findtext(NS + 'version') or ''
            prof = ''
            n = pl
            while n in parent_map:
                n = parent_map[n]
                if n.tag == NS + 'profile':
                    prof = 'profile:' + (n.findtext(NS + 'id') or '?')
                    break
            deps = ','.join((d.findtext(NS + 'groupId') or '') + ':' + (d.findtext(NS + 'artifactId') or '') + ':' + (d.findtext(NS + 'version') or '') for d in pl.findall(NS + 'dependencies/' + NS + 'dependency'))
            rows.add((g, a, v, rel, prof, deps))
for r in sorted(rows):
    print('\t'.join(r))
print('---PROPS')
for k, (v, rel) in sorted(props_all.items()):
    print(k, '=', v, '\t', rel)
