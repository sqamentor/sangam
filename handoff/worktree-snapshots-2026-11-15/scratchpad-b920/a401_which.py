"""List files that differ between the approve-401 worktree and the integration tree, outside my change set.

Usage: python a401_which.py <WT> <IT>
Prints one line per integration file that differs from (or is missing in) the worktree, with both sizes and mtimes.
"""
import datetime
import hashlib
import importlib.util
import os
import sys

WT, IT = sys.argv[1], sys.argv[2]
here = os.path.dirname(os.path.abspath(__file__))
src = open(os.path.join(here, 'a401_sync.py'), encoding='utf-8').read()
# reuse MODIFIED/NEW/walk without running the sync body
head = src.split('wt, it = walk(WT), walk(IT)')[0]
ns = {'__name__': 'a401_which', 'sys': type('S', (), {'argv': ['x', WT, IT, os.devnull]})}
exec(compile(head.replace('import sys\n', ''), 'a401_sync_head', 'exec'), ns)
walk, MINE = ns['walk'], ns['MINE']


def sha(p):
    with open(p, 'rb') as fh:
        return hashlib.sha256(fh.read()).hexdigest()


def mt(p):
    return datetime.datetime.fromtimestamp(os.path.getmtime(p), datetime.timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ')


wt, it = walk(WT), walk(IT)
for rel, p in sorted(it.items()):
    if rel in MINE:
        continue
    if rel not in wt:
        print(f'ADDED   {rel} it={os.path.getsize(p)}B {mt(p)}')
    elif sha(p) != sha(wt[rel]):
        print(f'UPDATED {rel} it={os.path.getsize(p)}B {mt(p)} wt={os.path.getsize(wt[rel])}B {mt(wt[rel])}')
