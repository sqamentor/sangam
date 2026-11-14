"""Compare local jar/pom hashes with the .sha1 (and .sha256 when published) files on Maven Central.
Usage: python -I centralsum.py <m2repo> <relpath> [<relpath> ...]   ('-' reads relpaths from stdin)"""
import hashlib, os, sys, urllib.request

REPO = sys.argv[1]
BASE = 'https://repo.maven.apache.org/maven2/'
paths = sys.argv[2:]
if paths == ['-']:
    paths = [l.strip() for l in sys.stdin if l.strip()]


def fetch(u):
    try:
        with urllib.request.urlopen(urllib.request.Request(u, headers={'User-Agent': 'maven-checksum-check'}), timeout=60) as r:
            return r.read().decode('ascii', 'replace').split()[0].strip().lower()
    except Exception as e:  # noqa: BLE001
        return 'ERR:' + type(e).__name__ + ':' + str(getattr(e, 'code', ''))


bad = 0
for p in paths:
    f = os.path.join(REPO, *p.split('/'))
    data = open(f, 'rb').read()
    s1 = hashlib.sha1(data).hexdigest()
    s256 = hashlib.sha256(data).hexdigest()
    r1 = fetch(BASE + p + '.sha1')
    r256 = fetch(BASE + p + '.sha256')
    ok1 = s1 == r1
    ok256 = 'n/a (not published)' if r256.startswith('ERR') else ('MATCH' if s256 == r256 else 'MISMATCH')
    if not ok1 or ok256 == 'MISMATCH':
        bad += 1
    print(f"{'MATCH' if ok1 else 'MISMATCH'}\t{p.rsplit('/', 1)[1]}\t{len(data)} B\tsha1={s1}\tcentral.sha1={r1}\tsha256={s256}\tcentral.sha256={ok256}")
print('MISMATCHES', bad)
sys.exit(1 if bad else 0)
