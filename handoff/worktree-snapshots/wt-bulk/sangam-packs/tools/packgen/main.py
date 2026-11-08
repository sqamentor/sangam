# SPDX-License-Identifier: Apache-2.0
"""Regenerates the sangam-packs resources and README.

Usage (from any directory):
  python sangam-packs/tools/packgen/main.py            write into src/main/resources and README.md
  python sangam-packs/tools/packgen/main.py --check    generate into a temporary directory and compare with the
                                                       committed files; exit 1 on any difference or stale file
  python sangam-packs/tools/packgen/main.py --out DIR  write into DIR/resources and DIR/README.md
"""
import argparse
import filecmp
import os
import shutil
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
MODULE = os.path.normpath(os.path.join(HERE, '..', '..'))
sys.path.insert(0, HERE)

import packs  # noqa: E402
import core  # noqa: E402
import gov1  # noqa: E402
import gov2  # noqa: E402
import gov3  # noqa: E402
import gov_ifms  # noqa: E402
import billing_pack  # noqa: E402
import universal  # noqa: E402
import sector  # noqa: E402
import readme  # noqa: E402

GENERATED_ROOTS = ('packs', os.path.join('META-INF', 'sangam'))


def generate(resources, readme_path):
    core.build()
    for mod in (gov1, gov2, gov3, gov_ifms, universal, billing_pack, sector):
        mod.build()
    packs.write_all(resources)  # form titles, labels and options are resolved while writing
    if packs.MISSING:
        sys.exit('missing labels / options / titles: ' + ', '.join(sorted(packs.MISSING)))
    readme.write(readme_path, packs.ALL)
    print('packs:', len(packs.ALL), 'processes:', sum(len(p.procs) for p in packs.ALL),
          'dmn:', sum(len(p.dmns) for p in packs.ALL) + sum(len(p.overrides) for p in packs.ALL),
          'cmmn:', sum(len(p.cmmns) for p in packs.ALL))


def files_under(root):
    out = set()
    for sub in GENERATED_ROOTS:
        base = os.path.join(root, sub)
        for d, _, fs in os.walk(base):
            for f in fs:
                out.add(os.path.relpath(os.path.join(d, f), root).replace('\\', '/'))
    return out


def check():
    tmp = tempfile.mkdtemp(prefix='packgen-')
    try:
        res = os.path.join(tmp, 'resources')
        generate(res, os.path.join(tmp, 'README.md'))
        committed_res = os.path.join(MODULE, 'src', 'main', 'resources')
        new, old = files_under(res), files_under(committed_res)
        problems = [f'missing in repo: {f}' for f in sorted(new - old)]
        problems += [f'stale in repo (not generated): {f}' for f in sorted(old - new)]
        for f in sorted(new & old):
            if not filecmp.cmp(os.path.join(res, f), os.path.join(committed_res, f), shallow=False):
                problems.append(f'differs: {f}')
        if not filecmp.cmp(os.path.join(tmp, 'README.md'), os.path.join(MODULE, 'README.md'), shallow=False):
            problems.append('differs: README.md')
        if problems:
            print('\n'.join(problems))
            print(f'{len(problems)} difference(s)')
            return 1
        print(f'OK: {len(new) + 1} generated files identical to the repository')
        return 0
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--check', action='store_true')
    ap.add_argument('--out')
    a = ap.parse_args()
    if a.check:
        sys.exit(check())
    if a.out:
        generate(os.path.join(a.out, 'resources'), os.path.join(a.out, 'README.md'))
    else:
        generate(os.path.join(MODULE, 'src', 'main', 'resources'), os.path.join(MODULE, 'README.md'))


if __name__ == '__main__':
    main()
