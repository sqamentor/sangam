"""Hash a .tgz (sha1, sha256, sha512 SRI) and read its package.json from inside the archive without extracting
or executing anything. Optionally compare with an expected SRI / sha1.
Usage: python -I tgzcheck.py <file.tgz> [expected-sri] [expected-sha1]"""
import base64, hashlib, json, sys, tarfile

f = sys.argv[1]
exp_sri = sys.argv[2] if len(sys.argv) > 2 and sys.argv[2] != '-' else None
exp_sha1 = sys.argv[3] if len(sys.argv) > 3 else None
data = open(f, 'rb').read()
sri = 'sha512-' + base64.b64encode(hashlib.sha512(data).digest()).decode()
s1 = hashlib.sha1(data).hexdigest()
s256 = hashlib.sha256(data).hexdigest()
print(f'file={f}\nsize={len(data)}\nsha1={s1}\nsha256={s256}\nintegrity={sri}')
if exp_sri:
    print('integrity vs expected:', 'MATCH' if sri == exp_sri else 'MISMATCH')
if exp_sha1:
    print('sha1 vs expected:', 'MATCH' if s1 == exp_sha1 else 'MISMATCH')
with tarfile.open(f, 'r:gz') as t:
    names = t.getnames()
    print('entries=', len(names))
    pj = [n for n in names if n.count('/') == 1 and n.endswith('/package.json')]
    for n in pj:
        m = json.load(t.extractfile(n))
        keys = {k: m.get(k) for k in ('name', 'version', 'license', 'licenses', 'fhirVersions', 'canonical', 'type', 'title', 'date')}
        print(n, json.dumps({k: v for k, v in keys.items() if v is not None}))
        print('dependencies=', json.dumps(m.get('dependencies')))
        sc = m.get('scripts') or {}
        print('lifecycle scripts=', {k: v for k, v in sc.items() if k in ('preinstall', 'install', 'postinstall', 'prepare', 'prepack', 'postpack')})
    lic = [n for n in names if n.split('/')[-1].upper().startswith(('LICENSE', 'LICENCE', 'COPYING'))]
    print('licence files=', lic[:10])
