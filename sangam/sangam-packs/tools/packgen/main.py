# SPDX-License-Identifier: Apache-2.0
"""Regenerates the sangam-packs resources and README, and the sangam-compliance BPMN models (compliance.py, M-010b).

Usage (from any directory):
  python sangam-packs/tools/packgen/main.py            write into src/main/resources and README.md, and the
                                                       compliance BPMN into sangam-compliance (packs/compliance)
  python sangam-packs/tools/packgen/main.py --check    generate into a temporary directory and compare with the
                                                       committed files; exit 1 on any difference or stale file
  python sangam-packs/tools/packgen/main.py --out DIR  write into DIR/resources, DIR/README.md and DIR/compliance
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
import gov_intl  # noqa: E402
import billing_pack  # noqa: E402
import universal  # noqa: E402
import sector  # noqa: E402
import readme  # noqa: E402
import compliance  # noqa: E402

GENERATED_ROOTS = ('packs', os.path.join('META-INF', 'sangam'))
# packgen owns the BPMN models of the compliance pack (ADR-D-20 single source); the other files of that pack directory
# (pack.json, seed.json, forms/) are not generated, so only *.bpmn directly in it is compared.
COMPLIANCE = os.path.normpath(os.path.join(MODULE, '..', 'sangam-compliance', 'src', 'main', 'resources', 'packs',
                                           'compliance'))
COMPLIANCE_REL = 'sangam-compliance/src/main/resources/packs/compliance'


def generate(resources, readme_path, compliance_dir):
    core.build()
    for mod in (gov1, gov2, gov3, gov_ifms, gov_intl, universal, billing_pack, sector):
        mod.build()
    packs.write_all(resources)  # form titles, labels and options are resolved while writing
    if packs.MISSING:
        sys.exit('missing labels / options / titles: ' + ', '.join(sorted(packs.MISSING)))
    readme.write(readme_path, packs.ALL)
    print('packs:', len(packs.ALL), 'processes:', sum(len(p.procs) for p in packs.ALL),
          'dmn:', sum(len(p.dmns) for p in packs.ALL) + sum(len(p.overrides) for p in packs.ALL),
          'cmmn:', sum(len(p.cmmns) for p in packs.ALL))
    print('compliance processes:', len(compliance.write_all(compliance_dir)))


def compliance_files(directory):
    """The BPMN models directly in the compliance pack directory (the files packgen owns there)."""
    if not os.path.isdir(directory):
        return set()
    return {f for f in os.listdir(directory) if f.endswith('.bpmn') and os.path.isfile(os.path.join(directory, f))}


def compare_compliance(generated, committed):
    """Problems between the generated compliance BPMN and the committed ones, in the format of check()."""
    new, old = compliance_files(generated), compliance_files(committed)
    problems = [f'missing in repo: {COMPLIANCE_REL}/{f}' for f in sorted(new - old)]
    problems += [f'stale in repo (not generated): {COMPLIANCE_REL}/{f}' for f in sorted(old - new)]
    for f in sorted(new & old):
        if not filecmp.cmp(os.path.join(generated, f), os.path.join(committed, f), shallow=False):
            problems.append(f'differs: {COMPLIANCE_REL}/{f}')
    return problems, len(new)


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
        cmp_dir = os.path.join(tmp, 'compliance')
        generate(res, os.path.join(tmp, 'README.md'), cmp_dir)
        committed_res = os.path.join(MODULE, 'src', 'main', 'resources')
        new, old = files_under(res), files_under(committed_res)
        problems = [f'missing in repo: {f}' for f in sorted(new - old)]
        problems += [f'stale in repo (not generated): {f}' for f in sorted(old - new)]
        for f in sorted(new & old):
            if not filecmp.cmp(os.path.join(res, f), os.path.join(committed_res, f), shallow=False):
                problems.append(f'differs: {f}')
        if not filecmp.cmp(os.path.join(tmp, 'README.md'), os.path.join(MODULE, 'README.md'), shallow=False):
            problems.append('differs: README.md')
        cmp_problems, ncmp = compare_compliance(cmp_dir, COMPLIANCE)
        problems += cmp_problems
        if problems:
            print('\n'.join(problems))
            print(f'{len(problems)} difference(s)')
            return 1
        print(f'OK: {len(new) + 1 + ncmp} generated files identical to the repository '
              f'({ncmp} of them in {COMPLIANCE_REL})')
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
        generate(os.path.join(a.out, 'resources'), os.path.join(a.out, 'README.md'), os.path.join(a.out, 'compliance'))
    else:
        generate(os.path.join(MODULE, 'src', 'main', 'resources'), os.path.join(MODULE, 'README.md'), COMPLIANCE)


if __name__ == '__main__':
    main()
