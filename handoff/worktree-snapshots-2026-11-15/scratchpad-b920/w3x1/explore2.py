import json, os, re
W = os.path.dirname(os.path.abspath(__file__))
C = json.load(open(os.path.join(W, 'inventory.json'), encoding='utf-8'))['components']
for k, e in sorted(C.items()):
    lic = e['pom']['licences'] or []
    names = ' | '.join(n for n, u in lic)
    if re.search(r'gpl|lgpl|gnu|futc|oracle|cddl|mpl|mozilla|json|unicode|w3c|jquery|bsd licen|similar|^bsd$', names, re.I) or (len(lic) > 1):
        scopes = sorted({u['scope'] for u in e['uses']})
        mods = sorted({u['module'] for u in e['uses']})
        print(k, '|', names, '|', scopes, '|', mods[:6], '| jartext', (e['jar'] or {}).get('text_ids'))
