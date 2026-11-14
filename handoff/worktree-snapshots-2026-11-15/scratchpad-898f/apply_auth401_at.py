"""AT data: business scenarios act as a person of the new tenant, so the onboarding request appoints that person
(and a second, distinct administrator: TWO_ADMINS_REQUIRED) as tenant administrators bound to the token subjects.
Without it the officer's login is 401 NOT_LINKED (R67: only listed tenants provision persons just in time)."""
import json, os, sys

ROOT = sys.argv[1]
ADMINS = [{"personId": "acc-pnp-officer", "externalSubject": "acc-pnp-officer", "displayName": "AT officer"},
          {"personId": "acc-pnp-admin2", "externalSubject": "acc-pnp-admin2", "displayName": "AT second administrator"}]
for d in ('sangam-server/src/test/resources/acceptance/plugandplay', 'sangam-onboarding/src/test/resources/acceptance'):
    for n in ('AT-1.json', 'AT-3.json', 'AT-4.json', 'AT-6.json'):
        p = os.path.join(ROOT, d, n)
        raw = open(p, encoding='utf-8', newline='').read()
        s = json.loads(raw)
        if 'business' not in s:
            continue
        sec = s['request'].setdefault('security', {})
        if sec.get('tenantAdmins') == ADMINS:
            print('already', d, n)
            continue
        assert 'tenantAdmins' not in sec, (p, sec)
        sec['tenantAdmins'] = ADMINS
        out = json.dumps(s, ensure_ascii=False, separators=(',', ':'))
        open(p, 'w', encoding='utf-8', newline='').write(out + ('\n' if raw.endswith('\n') else ''))
        print('ok', d, n)
