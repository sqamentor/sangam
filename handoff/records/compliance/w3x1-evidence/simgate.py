"""Simulate check_licences.py on the shipped components with the licence NAMES exactly as the POMs declare them
(i.e. assuming CycloneDX does NOT map them to SPDX ids). Read-only: loads the checker by path, writes nothing in the tree."""
import importlib.util, json, os, sys, collections
W = os.path.dirname(os.path.abspath(__file__))
chk_path, pol_path = sys.argv[1], sys.argv[2]
spec = importlib.util.spec_from_file_location('chk', chk_path)
chk = importlib.util.module_from_spec(spec); spec.loader.exec_module(chk)
C = json.load(open(os.path.join(W, 'inventory.json'), encoding='utf-8'))['components']
pol = json.load(open(pol_path, encoding='utf-8'))
for strict in (False, True):
    ch = chk.Checker(pol, strict)
    n = 0
    for k, e in C.items():
        if e['g'] == 'org.sangam':
            continue
        shipped = any(u['scope'] in ('compile', 'runtime') and not u['module'].startswith('plugin:') for u in e['uses'])
        if not shipped:
            continue
        lic = e['pom']['licences'] or []
        comp = {'type': 'library', 'group': e['g'], 'name': e['a'], 'version': e['v'], 'purl': f"pkg:maven/{e['g']}/{e['a']}@{e['v']}?type=jar",
                'licenses': [{'license': {'name': nm}} for nm, u in lic]}
        ch.check_component(comp, 'simulated-from-poms')
        n += 1
    print(f'strict={strict} components={n} errors={len(ch.errors)} warnings={len(ch.warnings)} notes={len(ch.notes)}')
    for x in sorted(set(ch.errors))[:40]:
        print('  ERR ', x)
    for x in sorted(set(ch.warnings))[:60]:
        print('  WARN', x)
