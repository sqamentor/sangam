"""For every packgen BPMN userTask with a literal candidateGroups, is the literal a seat declared in the pack's seed?"""
import json
import os
import sys
import xml.etree.ElementTree as ET

M = '{http://www.omg.org/spec/BPMN/20100524/MODEL}'
OP = '{http://operaton.org/schema/1.0/bpmn}'
root = sys.argv[1]
all_seats = set()
pack_seats = {}
for d, _, fs in os.walk(root):
    if 'seed.json' in fs and os.sep + 'overrides' + os.sep not in d:
        s = json.load(open(os.path.join(d, 'seed.json'), encoding='utf-8'))
        ids = {x['id'] for x in s.get('seats', [])}
        pack_seats[d] = ids
        all_seats |= ids
ok = in_other = missing = 0
for d, _, fs in os.walk(root):
    for f in fs:
        if not f.endswith('.bpmn'):
            continue
        r = ET.parse(os.path.join(d, f)).getroot()
        for ut in r.iter(M + 'userTask'):
            g = ut.get(OP + 'candidateGroups') or ''
            if g.startswith('${'):
                continue
            own = pack_seats.get(d, set())
            if g in own:
                ok += 1
            elif g in all_seats:
                in_other += 1
                print('OTHER-PACK', f, ut.get('id'), g)
            else:
                missing += 1
                print('NOT-A-SEAT', f, ut.get('id'), repr(g))
print('literal seats in own seed', ok, 'in another pack seed', in_other, 'not a seat', missing)
