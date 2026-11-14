import os
import shutil
import sys

os.chdir(sys.argv[1])
snap = sys.argv[2]
base = 'sangam-persistence/src/main/resources/db/changelog'
for d in ['postgresql', 'oracle', 'mssql', 'mysql', 'h2']:
    os.makedirs(os.path.join(snap, d), exist_ok=True)
    shutil.copyfile(os.path.join(base, d, 'sangam-0001-baseline.sql'), os.path.join(snap, d, 'before.sql'))


def edit(p, pairs):
    s = open(p, encoding='utf-8').read()
    for o, n in pairs:
        assert s.count(o) == 1, (p, o[:80], s.count(o))
        s = s.replace(o, n)
    open(p, 'w', encoding='utf-8', newline='').write(s)


edit('sangam-persistence/tools/generate_changelogs.py', [
    ('''    # (MySQL fills its implicit '' and 0, an id and a sequence no approved enrolment has). The fourth element None
    # (no CHECK) names the SQL Server default, so the rollback can drop it before the column.
    ("0045-11", "decision 241: approval sequence of a DSC certificate enrolment, +1 on every move to APPROVED",
     "INTEGRATION_DSC_CERTIFICATE", [("approval_seq", "{long}", "DEFAULT 0 NOT NULL", None)], []),''',
     '''    # (MySQL fills its implicit '' and 0, an id and a sequence no approved enrolment has).
    ("0045-11", "decision 241: approval sequence of a DSC certificate enrolment, +1 on every move to APPROVED",
     "INTEGRATION_DSC_CERTIFICATE", [("approval_seq", "{long}", "DEFAULT 0 NOT NULL")], []),'''),
    ('''    """Rollback: the named CHECK (and on SQL Server the named DEFAULT) of an added column, before DROP COLUMN."""
    if len(col) < 4:
        return []
    out = []
    if col[3]:''', '''    """Rollback: the named CHECK (and on SQL Server the named DEFAULT) of an added column, before DROP COLUMN."""
    out = []
    if len(col) > 3 and col[3]:'''),
    ('''    is named DF_<table>_<column> too, so the rollback can drop both before the column; a fourth element None names the
    default without a CHECK."""
    name, token, nullability = col[0], col[1], col[2]
    check = col[3] if len(col) > 3 else None
    nullability = nullability or ""
    if len(col) > 3 and dbms == "mssql" and nullability.startswith("DEFAULT"):''',
     '''    is named DF_<table>_<column>. Every SQL Server DEFAULT is named (integrator ruling 2026-11-13: SQL Server refuses
    DROP COLUMN while a default constraint exists, and a system-generated name cannot be written in the rollback), so
    the rollback drops it before the column; the other dialects drop a column default with the column."""
    name, token, nullability = col[0], col[1], col[2]
    check = col[3] if len(col) > 3 else None
    nullability = nullability or ""
    if dbms == "mssql" and nullability.startswith("DEFAULT"):'''),
])
print('ok')
