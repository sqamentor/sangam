# SPDX-License-Identifier: Apache-2.0
"""Rewrites existing generator entries from module Java DDL after a shape change, keeping changeset id and comment.

Run from sangam/: python -I sangam-persistence/tools/sync_module_tables.py <java file>...
Only for the unreleased baseline: an applied changeset must never change.
"""
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import import_java_ddl as ddl

GEN = 'sangam-persistence/tools/generate_changelogs.py'


def main():
    gen = open(GEN, encoding='utf-8').read()
    changed = []
    for f in sys.argv[1:]:
        order, tables, problems = ddl.parse(f)
        problems = [p for p in problems if p != 'unhandled statement: CREATE TABLE']
        if problems:
            sys.exit(f"{f}: {problems}")
        for t in order:
            m = re.search(r'^    \("([0-9-]+)", "([^"\n]*)", "' + t + r'", \[.*\]\),$', gen, re.M)
            if not m:
                continue  # new table: tools/add_missing_tables.py
            tb = tables[t]
            idx = list(tb['idx']) + [(f"{t[:26]}_UQ{k}", True, u) for k, u in enumerate(tb['uniques'], 1)]
            cols = ', '.join(f'("{c}", "{tok}", "{rest}")' for c, tok, rest in tb['cols'])
            new = (f'    ("{m.group(1)}", "{m.group(2)}", "{t}", [{cols}], {tb["pk"]!r}, '
                   f'[{", ".join(repr(i) for i in idx)}]),')
            if new != m.group(0):
                gen = gen.replace(m.group(0), new)
                changed.append(t)
    open(GEN, 'w', encoding='utf-8', newline='\n').write(gen)
    print('rewritten:', changed)


if __name__ == '__main__':
    main()
