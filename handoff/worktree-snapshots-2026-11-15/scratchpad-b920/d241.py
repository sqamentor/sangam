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
    ('''     [("session_zone", "{str64}", "")], []),
]''', '''     [("session_zone", "{str64}", "")], []),
    # Decision 241 identity binding (W4-H REQUEST 2026-11-13T03:49:34Z), exactly as the updated reference DDL
    # sangam-dsc-enrolment-reference.sql (AE463B06...7109D2) and sangam-dsc-reference.sql (DD1EBD22...CEE45D).
    # The tables are new and unreleased: NOT NULL without backfill; a database that holds rows refuses the alter
    # (MySQL fills its implicit '' and 0, an id and a sequence no approved enrolment has). The fourth element None
    # (no CHECK) names the SQL Server default, so the rollback can drop it before the column.
    ("0045-11", "decision 241: approval sequence of a DSC certificate enrolment, +1 on every move to APPROVED",
     "INTEGRATION_DSC_CERTIFICATE", [("approval_seq", "{long}", "DEFAULT 0 NOT NULL", None)], []),
    ("0045-12", "decision 241: the approved enrolment and its approval sequence a prepared DSC signature is bound to",
     "INTEGRATION_DSC_PREPARED", [("enrolment_id", "{str64}", "NOT NULL"),
                                  ("enrolment_approval_seq", "{long}", "NOT NULL")], []),
]'''),
    ('''    if len(col) < 4:
        return []
    ck = check_name(table, col[0])
    out = [f"ALTER TABLE {table} DROP CHECK {ck};" if dbms == "mysql" else f"ALTER TABLE {table} DROP CONSTRAINT {ck};"]
    if dbms == "mssql" and "DEFAULT" in (col[2] or ""):''', '''    if len(col) < 4:
        return []
    out = []
    if col[3]:
        ck = check_name(table, col[0])
        out.append(f"ALTER TABLE {table} DROP CHECK {ck};" if dbms == "mysql" else f"ALTER TABLE {table} DROP CONSTRAINT {ck};")
    if dbms == "mssql" and "DEFAULT" in (col[2] or ""):'''),
    ('''    is named DF_<table>_<column> too, so the rollback can drop both before the column."""
    name, token, nullability = col[0], col[1], col[2]
    check = col[3] if len(col) > 3 else None
    nullability = nullability or ""
    if check and dbms == "mssql" and nullability.startswith("DEFAULT"):''', '''    is named DF_<table>_<column> too, so the rollback can drop both before the column; a fourth element None names the
    default without a CHECK."""
    name, token, nullability = col[0], col[1], col[2]
    check = col[3] if len(col) > 3 else None
    nullability = nullability or ""
    if len(col) > 3 and dbms == "mssql" and nullability.startswith("DEFAULT"):'''),
])
edit('sangam-persistence/src/main/java/org/sangam/persistence/SangamSchema.java', [
    ('plus OPS_UTC_RUN.session_zone (0045-09, decision 240).',
     'plus OPS_UTC_RUN.session_zone (0045-09, decision 240);\n     * plus the decision 241 identity binding: INTEGRATION_DSC_CERTIFICATE.approval_seq (0045-11) and\n     * INTEGRATION_DSC_PREPARED.enrolment_id and enrolment_approval_seq (0045-12).'),
    ('public static final int ADDITIONAL_CHANGESETS = 86;', 'public static final int ADDITIONAL_CHANGESETS = 88;'),
])
edit('sangam-persistence/README.md', [('+ 86 further changesets (alter,', '+ 88 further changesets (alter,')])
print('ok')
