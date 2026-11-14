"""Byte identity, worktree vs integration tree, for every file IFMS-WF touched or regenerated.

Usage: python hashcheck.py <WT_SANGAM> <IT_SANGAM> [--align-edited]
--align-edited: first prove the 4 hand-edited files are content-identical with CRLF normalised, then make the worktree
                copies byte-identical to the integration tree (its git checkout writes CRLF; the edits kept it).
Prints the file count, the mismatch count and any mismatching or missing path. Exit 1 on any mismatch.
"""
import hashlib
import os
import shutil
import sys

WT, IT = sys.argv[1], sys.argv[2]
EDITED = [
    'sangam-packs/tools/packgen/packs.py',
    'sangam-flowable-ports/src/main/java/org/sangam/ports/bpmn/SangamModelValidator.java',
    'sangam-flowable-ports/src/test/java/org/sangam/ports/bpmn/SangamModelValidatorTest.java',
    'sangam-server/src/test/java/org/sangam/server/acceptance/ifms/IfmsPacksIT.java',
]
ADDED = [
    'sangam-packs/src/test/java/org/sangam/packs/CrossPackCallBindingTest.java',
    'sangam-server/src/test/resources/acceptance/e46/.gitattributes',
]
FIXTURES = [f'sangam-server/src/test/resources/acceptance/e46/{n}.bpmn.model' for n in
            ('e46-wf1', 'e46-wf2', 'e46-wf3', 'e46-wf4', 'e46-wf5', 'e46-wf6', 'e46-wf7', 'e46-wf8-child', 'e46-wf8',
             'e46-wf9')]


def generated(root):
    out = ['sangam-packs/README.md']
    res = os.path.join(root, 'sangam-packs', 'src', 'main', 'resources')
    for sub in ('packs', os.path.join('META-INF', 'sangam')):
        for d, _, fs in os.walk(os.path.join(res, sub)):
            for f in fs:
                out.append(os.path.relpath(os.path.join(d, f), root).replace('\\', '/'))
    return out


def read(p):
    with open(p, 'rb') as fh:
        return fh.read()


if '--align-edited' in sys.argv:
    for rel in EDITED:
        w, i = os.path.join(WT, rel), os.path.join(IT, rel)
        if read(w).replace(b'\r\n', b'\n') != read(i).replace(b'\r\n', b'\n'):
            sys.exit(f'content differs, not aligning: {rel}')
        shutil.copyfile(i, w)
        print(f'aligned (content-identical, CRLF from the integration tree checkout): {rel}')

gen_wt, gen_it = set(generated(WT)), set(generated(IT))
paths = sorted(set(EDITED + ADDED + FIXTURES) | gen_wt | gen_it)
mismatch, missing = [], []
for rel in paths:
    w, i = os.path.join(WT, rel), os.path.join(IT, rel)
    if not os.path.exists(w) or not os.path.exists(i):
        missing.append(rel)
        continue
    if hashlib.sha256(read(w)).digest() != hashlib.sha256(read(i)).digest():
        mismatch.append(rel)
print(f'files compared: {len(paths)} (edited {len(EDITED)}, added {len(ADDED)}, fixtures {len(FIXTURES)}, '
      f'generated {len(gen_it)})')
print(f'SHA-256 mismatches: {len(mismatch)}; missing on one side: {len(missing)}')
for rel in mismatch:
    print('  MISMATCH ' + rel)
for rel in missing:
    print('  MISSING  ' + rel)
sys.exit(1 if mismatch or missing else 0)
