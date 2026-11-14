"""Fast two-sided check before applying: every file of the APPROVE-401 change set in the integration tree must still be
its base (the version my change was made on, ignoring CR), and my new files must not exist there yet.

Usage: python a401_twosided.py <IT sangam dir>
"""
import os
import subprocess
import sys

IT = sys.argv[1]
REPO = os.path.dirname(IT)
here = os.path.dirname(os.path.abspath(__file__))
src = open(os.path.join(here, 'a401_sync.py'), encoding='utf-8').read()
head = src.split('wt, it = walk(WT), walk(IT)')[0]
ns = {'__name__': 'a401_twosided', 'sys': type('S', (), {'argv': ['x', os.devnull, IT, os.devnull]})}
exec(compile(head.replace('import sys\n', ''), 'a401_sync_head', 'exec'), ns)
MODIFIED, NEW = ns['MODIFIED'], ns['NEW']


def git_show(rev, rel):
    r = subprocess.run(['git', '-C', REPO, '--no-optional-locks', 'show', f'{rev}:sangam/{rel}'], capture_output=True)
    return r.stdout if r.returncode == 0 else None


bad = 0
for rel, rev in sorted(MODIFIED.items()):
    p = os.path.join(IT, rel)
    cur = open(p, 'rb').read() if os.path.isfile(p) else None
    base = git_show(rev, rel)
    ok = cur is not None and base is not None and cur.replace(b'\r\n', b'\n') == base.replace(b'\r\n', b'\n')
    bad += not ok
    print(('BASE    ' if ok else 'CHANGED ') + rel + f' (base {rev})')
for rel in NEW:
    exists = os.path.exists(os.path.join(IT, rel))
    bad += exists
    print(('EXISTS  ' if exists else 'ABSENT  ') + rel)
print('two-sided problems:', bad)
sys.exit(1 if bad else 0)
