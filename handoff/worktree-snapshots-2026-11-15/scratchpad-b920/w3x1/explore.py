import json, os, sys, collections
sys.path.insert(0, os.path.dirname(__file__))
W = os.path.dirname(os.path.abspath(__file__))
d = json.load(open(os.path.join(W, 'inventory.json'), encoding='utf-8'))
C = d['components']
from importlib.machinery import SourceFileLoader
spdx = collections.Counter(); repos = collections.Counter(); nofile = []
import re
exec(open(os.path.join(W, 'spdxmap.py')).read()) if os.path.exists(os.path.join(W, 'spdxmap.py')) else None
for k, e in C.items():
    repos[','.join(sorted(set(e['repos']))) or 'NONE'] += 1
    if not e['file']:
        nofile.append((k, sorted({u['scope'] for u in e['uses']})))
    lic = e['pom']['licences']
    spdx[tuple(sorted({n for n, u in lic})) if lic else ('<none>',)] += 1
print('REPOS', repos.most_common())
print('NOFILE', len(nofile))
for x in nofile: print('  ', x)
for l, n in spdx.most_common(): print(n, l)
