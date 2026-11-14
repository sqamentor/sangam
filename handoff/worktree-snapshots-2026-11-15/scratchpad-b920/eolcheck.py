"""Compare a packgen --out directory with a module's committed generated files, ignoring CRLF/LF only.

Usage: python eolcheck.py <OUT_DIR> <SANGAM_PACKS_MODULE_DIR>
Prints files that differ in content (after CRLF->LF), files missing on either side, and a summary.
"""
import os
import sys

OUT, MODULE = sys.argv[1], sys.argv[2]
ROOTS = ('packs', os.path.join('META-INF', 'sangam'))


def files_under(root):
    out = set()
    for sub in ROOTS:
        base = os.path.join(root, sub)
        for d, _, fs in os.walk(base):
            for f in fs:
                out.add(os.path.relpath(os.path.join(d, f), root).replace('\\', '/'))
    return out


def norm(p):
    with open(p, 'rb') as fh:
        return fh.read().replace(b'\r\n', b'\n')


gen_res = os.path.join(OUT, 'resources')
com_res = os.path.join(MODULE, 'src', 'main', 'resources')
new, old = files_under(gen_res), files_under(com_res)
problems = [f'missing in repo: {f}' for f in sorted(new - old)]
problems += [f'stale in repo: {f}' for f in sorted(old - new)]
eol_only = 0
for f in sorted(new & old):
    a, b = os.path.join(gen_res, f), os.path.join(com_res, f)
    if norm(a) != norm(b):
        problems.append(f'content differs: {f}')
    else:
        with open(a, 'rb') as x, open(b, 'rb') as y:
            if x.read() != y.read():
                eol_only += 1
ra, rb = os.path.join(OUT, 'README.md'), os.path.join(MODULE, 'README.md')
if norm(ra) != norm(rb):
    problems.append('content differs: README.md')
print('\n'.join(problems))
print(f'files={len(new) + 1} content-differences={len(problems)} eol-only-differences={eol_only}')
