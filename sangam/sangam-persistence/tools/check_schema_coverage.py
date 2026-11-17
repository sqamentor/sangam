# SPDX-License-Identifier: Apache-2.0
"""Lists every table created in module Java code (or module reference SQL) that no Sangam changelog creates.

Run from sangam/: python -I sangam-persistence/tools/check_schema_coverage.py    Exit 1 when something is missing.
sangam-control-plane and sangam-rollup keep their own changelogs and are excluded.
"""
import glob
import re
import sys

PATTERN = r'CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?([A-Za-z_0-9]+)'
RENAMED = r'(?m)^(?:ALTER\s+TABLE\s+\S+\s+RENAME|RENAME\s+TABLE\s+\S+)\s+TO\s+([A-Za-z_0-9]+);'
have = set()
for f in glob.glob('sangam-persistence/src/main/resources/db/changelog/**/*.sql', recursive=True):
    sql = open(f, encoding='utf-8').read()
    have |= {t.upper() for t in re.findall(PATTERN, sql)}
    # a table renamed by a later changeset (generator RENAMES, decision 388) exists under its new name
    have |= {t.upper() for t in re.findall(RENAMED, sql)}
missing = 0
files = glob.glob('sangam-*/src/main/**/*.java', recursive=True) + glob.glob('sangam-*/src/main/resources/**/*.sql', recursive=True)
for f in sorted(files):
    n = f.replace(chr(92), '/')
    if any(x in n for x in ('sangam-control-plane', 'sangam-rollup', 'sangam-persistence/')):
        continue
    found = re.findall(PATTERN, open(f, encoding='utf-8').read())
    miss = sorted({t for t in found if t.upper() not in have and t == t.upper()})
    if miss:
        missing += len(miss)
        print(n, miss)
print(f"{len(have)} tables in changelogs; {missing} missing")
sys.exit(1 if missing else 0)
