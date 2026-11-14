"""For each relative path: compare WT and IT content with CRLF normalised, and report each side's line-ending mix.

Usage: python samecontent.py <WT_SANGAM> <IT_SANGAM> <rel> [<rel> ...]
Exit 1 if any normalised content differs.
"""
import hashlib
import os
import sys

WT, IT = sys.argv[1], sys.argv[2]
bad = 0


def stats(b):
    crlf = b.count(b'\r\n')
    lf = b.count(b'\n') - crlf
    return f'crlf={crlf} lf-only={lf}'


for rel in sys.argv[3:]:
    a = open(os.path.join(WT, rel), 'rb').read()
    b = open(os.path.join(IT, rel), 'rb').read()
    same = a.replace(b'\r\n', b'\n') == b.replace(b'\r\n', b'\n')
    bad += 0 if same else 1
    print(f'{"SAME" if same else "DIFF"} {rel}\n   wt: {stats(a)} sha={hashlib.sha256(a).hexdigest()[:16]}'
          f'\n   it: {stats(b)} sha={hashlib.sha256(b).hexdigest()[:16]}')
sys.exit(1 if bad else 0)
