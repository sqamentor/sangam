"""M-004 seed check: HOA seed rows vs the LMMH README counts, parent integrity, and the slip recorded everywhere."""
import collections
import csv
import json
import os
import sys

seeds = sys.argv[1]   # .../profiles/in/seeds
report = sys.argv[2]  # tools/lmmh/data/lmmh-report.json


def rows(name):
    with open(os.path.join(seeds, name), encoding='utf-8', newline='') as f:
        return list(csv.DictReader(line for line in f if not line.startswith('#')))


maj, sub, mnr, std = (rows('hoa-%s.csv' % n) for n in ('major-heads', 'sub-major-heads', 'minor-heads', 'standard-minor-heads'))
print('major', len(maj), 'subMajor', len(sub), 'minor', len(mnr), 'standard-file', len(std))
majors = {r['code'] for r in maj}
subs = {r['parent'] + '-' + r['code'] for r in sub}
bad_sub = [r['parent'] + '-' + r['code'] for r in sub if r['parent'] not in majors]
bad_min = []
for r in mnr:
    m, s = r['parent'].split('-', 1)
    if m not in majors or not (s == '00' or s.startswith('*') or r['parent'] in subs):
        bad_min.append(r['parent'] + '-' + r['code'])
print('sub-major with unknown major', len(bad_sub), bad_sub[:5])
print('minor with unknown parent', len(bad_min), bad_min[:5])
dup = [k for k, v in collections.Counter((r['parent'], r['code']) for r in mnr).items() if v > 1]
print('duplicate (parent, minor)', len(dup), dup[:5])
flags = collections.Counter('standard' if 'standard=true' in r['flags'] else ('index' if 'index=true' in r['flags'] else 'other')
                            for r in std)
print('standard-file rows by kind', dict(flags), 'with parent', sum(1 for r in std if r['parent']))
slips = collections.Counter(r['source'].split()[1] for r in maj + sub + mnr + std)
print('source slips', dict(slips))
status = collections.Counter(r['status'] for r in maj + sub + mnr + std)
print('status', dict(status))
rep = json.load(open(report, encoding='utf-8'))
print('report keys', sorted(rep)[:12])
print('report counts', rep.get('counts', {}).get('byLevel', rep.get('counts')) if isinstance(rep.get('counts'), dict) else rep.get('counts'))
print('report validation', rep.get('validation'))
