# SPDX-License-Identifier: Apache-2.0
"""Adds tables from module Java DDL that the generator lacks, as new changesets at the end of TABLES.

Run from sangam/: python -I sangam-persistence/tools/add_missing_tables.py <prefix> <java file>... [--dry]
Prints parse problems plus reserved-word and key-size findings; with problems present it does not write.
"""
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import import_java_ddl as ddl
import import_module_schemas as mod

GEN = 'sangam-persistence/tools/generate_changelogs.py'
RAW = {r'NUMERIC\(24,\s*6\)': '{num24_6}', r'DECIMAL\(24,\s*6\)': '{num24_6}', r'NUMERIC\(12,\s*6\)': '{num12_6}',
       r'DECIMAL\(12,\s*6\)': '{num12_6}'}


def main():
    args = [a for a in sys.argv[1:] if a != '--dry']
    prefix, files = args[0], args[1:]
    gen = open(GEN, encoding='utf-8').read()
    have = set(re.findall(r'^\s*(?:\("[0-9-]+", "[^\n]*?", )?"([A-Z][A-Z0-9_]+)", \[', gen, re.M))
    entries, checks, problems = [], [], []
    n = 0
    for f in files:
        src = open(f, encoding='utf-8').read()
        for raw, tok in RAW.items():
            src = re.sub(raw, tok, src)
        tmp = os.path.join(os.path.dirname(os.path.abspath(__file__)), '_tmp.java')
        open(tmp, 'w', encoding='utf-8').write(src)
        try:
            order, tables, probs = ddl.parse(tmp)
        finally:
            os.remove(tmp)
        problems += [f"{f}: {p}" for p in probs if p != 'unhandled statement: CREATE TABLE']
        for t in order:
            if t in have:
                continue
            n += 1
            tb = tables[t]
            idx = list(tb['idx']) + [(f"{t[:26]}_UQ{k}", True, u) for k, u in enumerate(tb['uniques'], 1)]
            cols = ', '.join(f'("{c}", "{tok}", "{rest}")' for c, tok, rest in tb['cols'])
            entries.append(f'    ("{prefix}-{n:02d}", "{os.path.basename(f)}", "{t}", [{cols}], {tb["pk"]!r}, '
                           f'[{", ".join(repr(i) for i in idx)}]),')
            checks.append((t, tb, os.path.basename(f)))
    names = {f"PK_{t[:27]}": ('pk', t) for t in have}
    names.update({m: ('idx', 'existing') for m in re.findall(r'\("(\w+)", (?:True|False), \[', gen)})
    findings = mod.check(checks, names)
    print(f"{n} new tables:", [c[0] for c in checks])
    print("\n".join(problems + findings))
    if '--dry' in sys.argv or problems:
        return
    m = re.search(r'\n\]\n\n(?:#[^\n]*\n)*ALTERS = \[', gen)
    gen = gen[:m.start() + 1] + "\n".join(entries) + "\n" + gen[m.start() + 1:]
    open(GEN, 'w', encoding='utf-8', newline='\n').write(gen)
    print("generator updated")


if __name__ == '__main__':
    main()
