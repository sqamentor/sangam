import sys
root = sys.argv[1]
s = None


def rep(a, b):
    global s
    a = a.replace('\n', '\r\n'); b = b.replace('\n', '\r\n')
    assert s.count(a) == 1, (s.count(a), a[:90])
    s = s.replace(a, b)


p = root + '/sangam-persistence/tools/generate_changelogs.py'
s = open(p, 'rb').read().decode('utf-8')
rep('''SHARED_TENANT_FIRST_ID = 1  # changesets 0036-01 .. 0036-NN, in list order
''', '''SHARED_TENANT_FIRST_ID = 1  # changesets 0036-01 .. 0036-NN, in list order

# Columns widened in place, applied last: (changeset id, comment, table, column, old type token, new type token).
# Nullability and primary-key membership come from TABLES, and the statement keeps them in each dialect's own form.
# SQL Server cannot ALTER COLUMN a primary-key column: the key constraint is dropped and added back around it.
WIDEN = [
    # W2-D 2026-11-12: an executor node identity is the job executor's lock owner: Operaton's UUID, 'sangam-async-'
    # + UUID, or the free-text SANGAM_JOB_LOCK_OWNER (a Kubernetes pod name reaches 253); Operaton keeps the same value
    # in ACT_RU_JOB.LOCK_OWNER_ varchar(255). Every EXEC_* column holding it gets that width.
    ("0042-01", "R39 partition owner node id up to 255 chars (= ACT_RU_JOB.LOCK_OWNER_)", "EXEC_PARTITION", "owner_node",
     "{str64}", "{str}"),
    ("0042-02", "R39 rebalance coordinator node id up to 255 chars (= ACT_RU_JOB.LOCK_OWNER_)", "EXEC_MAP", "coordinator_node",
     "{str64}", "{str}"),
    ("0042-03", "R39 executor node id up to 255 chars (= ACT_RU_JOB.LOCK_OWNER_)", "EXEC_NODE", "node_id", "{str64}", "{str}"),
]
''')
rep('''    for n, (table, include_null) in enumerate(SHARED_TENANT, start=SHARED_TENANT_FIRST_ID):
        out.extend(shared_tenant_changeset(dbms, f"0036-{n:02d}", table, include_null))
    return "\\n".join(out)
''', '''    for n, (table, include_null) in enumerate(SHARED_TENANT, start=SHARED_TENANT_FIRST_ID):
        out.extend(shared_tenant_changeset(dbms, f"0036-{n:02d}", table, include_null))
    for cs_id, comment, table, column, old, new in WIDEN:
        out.append(f"--changeset sangam:{cs_id}-{table.lower()}-{column.lower()}-widen dbms:{dbms}")
        out.append(f"--comment: {comment}")
        out.append(widen_sql(dbms, table, column, new))
        out.append("--rollback " + widen_sql(dbms, table, column, old))
        out.append("")
    return "\\n".join(out)


def widen_sql(dbms, table, column, token):
    """Changes the type of one catalogued column to token, keeping its nullability (and its primary key)."""
    for _, _, t, cols, pk, _ in TABLES:
        if t == table:
            break
    else:
        raise SystemExit(f"WIDEN: {table} is not in TABLES")
    col = dict((c[0], c) for c in cols)[column]
    not_null = "NOT NULL" in (col[2] or "")
    if "DEFAULT" in (col[2] or ""):
        raise SystemExit(f"WIDEN: {table}.{column} has a default; extend widen_sql first")
    t = DIALECTS[dbms][token]
    null = " NOT NULL" if not_null else " NULL"
    if dbms == "postgresql":
        return f"ALTER TABLE {table} ALTER COLUMN {column} TYPE {t};"
    if dbms == "h2":
        return f"ALTER TABLE {table} ALTER COLUMN {column} SET DATA TYPE {t};"
    if dbms == "oracle":
        return f"ALTER TABLE {table} MODIFY ({column} {t});"
    if dbms == "mysql":
        return f"ALTER TABLE {table} MODIFY COLUMN {column} {t}{null};"
    # mssql: ALTER COLUMN restates nullability; a key column needs its constraint dropped and re-added
    alter = f"ALTER TABLE {table} ALTER COLUMN {column} {t}{null};"
    if column not in pk:
        return alter
    name = f"PK_{table[:27]}"
    return (f"ALTER TABLE {table} DROP CONSTRAINT {name}; " + alter
            + f" ALTER TABLE {table} ADD CONSTRAINT {name} PRIMARY KEY ({', '.join(pk)});")
''')
rep('''    print(f"{len(TABLES)} tables + {len(ALTERS)} alter + {len(DATA)} data + {len(NULLABLE)} nullable + {len(SHARED_TENANT)} shared-tenant changesets x {len(DIALECTS)} dialects written under "''',
    '''    print(f"{len(TABLES)} tables + {len(ALTERS)} alter + {len(DATA)} data + {len(NULLABLE)} nullable + {len(SHARED_TENANT)} shared-tenant + {len(WIDEN)} widen changesets x {len(DIALECTS)} dialects written under "''')
open(p, 'wb').write(s.encode('utf-8'))

p = root + '/sangam-persistence/src/main/java/org/sangam/persistence/SangamSchema.java'
s = open(p, 'rb').read().decode('utf-8')
rep('''     * CMP_PURPOSE.special_condition and CMP_RIGHTS_REQUEST extension/exception columns (0041-01..04).
     * The changelog holds {@code PRODUCT_TABLES.size() + ADDITIONAL_CHANGESETS} changesets.
     */
    public static final int ADDITIONAL_CHANGESETS = 74;''', '''     * CMP_PURPOSE.special_condition and CMP_RIGHTS_REQUEST extension/exception columns (0041-01..04); plus the
     * generator {@code WIDEN} changesets: executor node ids EXEC_PARTITION.owner_node, EXEC_MAP.coordinator_node and
     * EXEC_NODE.node_id to 255 characters (0042-01..03).
     * The changelog holds {@code PRODUCT_TABLES.size() + ADDITIONAL_CHANGESETS} changesets.
     */
    public static final int ADDITIONAL_CHANGESETS = 77;''')
open(p, 'wb').write(s.encode('utf-8'))
print('ok')
