"""Print the licence(s) declared for each g:a:v by walking the POM parent chain in the local repo.
Usage: python -I pomlic.py <m2repo> g:a:v [g:a:v ...]   (or '-' to read coordinates from stdin)"""
import os, re, sys

REPO = sys.argv[1]


def pom_path(g, a, v):
    return os.path.join(REPO, *g.split('.'), a, v, f'{a}-{v}.pom')


def strip(t):
    return re.sub(r'<!--.*?-->', '', t, flags=re.S)


def lic(g, a, v, depth=0):
    p = pom_path(g, a, v)
    if not os.path.exists(p):
        return None, f'POM missing {g}:{a}:{v}'
    t = strip(open(p, encoding='utf-8', errors='replace').read())
    m = re.search(r'<licenses>(.*?)</licenses>', t, re.S)
    if m:
        out = []
        for l in re.findall(r'<license>(.*?)</license>', m.group(1), re.S):
            n = re.search(r'<name>(.*?)</name>', l, re.S)
            u = re.search(r'<url>(.*?)</url>', l, re.S)
            out.append(((n.group(1).strip() if n else '?') + ' (' + (u.group(1).strip() if u else 'no url') + ')'))
        return out, (f'{g}:{a}:{v}' if depth else 'own POM')
    pm = re.search(r'<parent>(.*?)</parent>', t, re.S)
    if not pm:
        return None, 'no licence, no parent'
    pg = re.search(r'<groupId>(.*?)</groupId>', pm.group(1)).group(1).strip()
    pa = re.search(r'<artifactId>(.*?)</artifactId>', pm.group(1)).group(1).strip()
    pv = re.search(r'<version>(.*?)</version>', pm.group(1)).group(1).strip()
    return lic(pg, pa, pv, depth + 1)


coords = sys.argv[2:]
if coords == ['-']:
    coords = [l.strip() for l in sys.stdin if l.strip()]
for c in coords:
    g, a, v = c.split(':')[:3]
    ls, src = lic(g, a, v)
    print(f'{g}:{a}:{v}\t{"; ".join(ls) if ls else "NONE"}\t[{src}]')
