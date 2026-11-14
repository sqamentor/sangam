"""Sanity: unique ids per generated compliance file; the packs output (other than README.md) is unchanged."""
import collections
import filecmp
import os
import sys
import xml.etree.ElementTree as ET

out, repo = sys.argv[1], sys.argv[2]
for f in sorted(os.listdir(os.path.join(out, 'compliance'))):
    ids = collections.Counter(e.get('id') for e in ET.parse(os.path.join(out, 'compliance', f)).getroot().iter() if e.get('id'))
    dup = [k for k, v in ids.items() if v > 1]
    print(f, 'ids', sum(ids.values()), 'duplicates', dup)
res_out = os.path.join(out, 'resources')
res_repo = os.path.join(repo, 'sangam-packs', 'src', 'main', 'resources')
diffs = 0
n = 0
for sub in ('packs', os.path.join('META-INF', 'sangam')):
    for d, _, fs in os.walk(os.path.join(res_out, sub)):
        for x in fs:
            p = os.path.join(d, x)
            rel = os.path.relpath(p, res_out)
            n += 1
            if not filecmp.cmp(p, os.path.join(res_repo, rel), shallow=False):
                diffs += 1
                print('differs', rel)
print('pack resources compared', n, 'differing', diffs)
print('README identical:', filecmp.cmp(os.path.join(out, 'README.md'), os.path.join(repo, 'sangam-packs', 'README.md'), shallow=False))
