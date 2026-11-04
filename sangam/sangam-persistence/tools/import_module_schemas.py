# SPDX-License-Identifier: Apache-2.0
"""Converts the modules' portable *Schema.java DDL into generator catalogue entries and checks them.

usage: python -I convert.py <sangam root> <out block file> <report file>
"""
import os, re, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import import_java_ddl as java_ddl

MODULES = [  # (file, changeset prefix, label, token remap)
    ("sangam-config/src/main/java/org/sangam/config/store/ConfigSchema.java", "0018", "sangam-config ConfigSchema", {}),
    ("sangam-ledger/src/main/java/org/sangam/ledger/LedgerSchema.java", "0019", "sangam-ledger LedgerSchema (also the billing tables)", {}),
    ("sangam-registry/src/main/java/org/sangam/registry/RegistrySchema.java", "0020", "sangam-registry RegistrySchema", {}),
    ("sangam-resources/src/main/java/org/sangam/resources/ResourcesSchema.java", "0021", "sangam-resources ResourcesSchema", {}),
    ("sangam-geo/src/main/java/org/sangam/geo/GeoSchema.java", "0022", "sangam-geo GeoSchema", {}),
    ("sangam-field/src/main/java/org/sangam/field/FieldSchema.java", "0023", "sangam-field FieldSchema", {}),
    ("sangam-case/src/main/java/org/sangam/casefile/CaseFileSchema.java", "0024", "sangam-case CaseFileSchema", {}),
    ("sangam-programme/src/main/java/org/sangam/programme/ProgrammeSchema.java", "0025", "sangam-programme ProgrammeSchema",
     {"{dec}": "{num24_6}"}),  # ProgrammeSchema.expand: {dec} = DECIMAL(24,6) / NUMBER(24,6), {date} = DATE
]

RAW = {"NUMERIC(24,6)": "{num24_6}", "NUMERIC(12,6)": "{num12_6}", "DECIMAL(24,6)": "{num24_6}",
       "DECIMAL(12,6)": "{num12_6}"}

# bytes per type in an index key
MYSQL_B = {"{str}": 1022, "{str64}": 258, "{str32}": 130, "{str1k}": 4002, "{long}": 8, "{int}": 4, "{small}": 2,
           "{bytes32}": 33, "{ts}": 8, "{date}": 3, "{dec}": 9, "{num24_6}": 11, "{num12_6}": 6, "{identity}": 8,
           "{vc8}": 34, "{vc16}": 66, "{vc40}": 162, "{asciistr}": 257, "{text}": 99999, "{blob}": 99999}
MSSQL_B = {"{str}": 510, "{str64}": 128, "{str32}": 64, "{str1k}": 2000, "{long}": 8, "{int}": 4, "{small}": 2,
           "{bytes32}": 32, "{ts}": 10, "{date}": 3, "{dec}": 9, "{num24_6}": 13, "{num12_6}": 9, "{identity}": 8,
           "{vc8}": 16, "{vc16}": 32, "{vc40}": 80, "{asciistr}": 510, "{text}": 99999, "{blob}": 99999}

RESERVED = {
    "postgresql": "ALL ANALYSE ANALYZE AND ANY ARRAY AS ASC ASYMMETRIC BOTH CASE CAST CHECK COLLATE COLUMN CONSTRAINT "
                  "CREATE CURRENT_CATALOG CURRENT_DATE CURRENT_ROLE CURRENT_TIME CURRENT_TIMESTAMP CURRENT_USER DEFAULT "
                  "DEFERRABLE DESC DISTINCT DO ELSE END EXCEPT FALSE FETCH FOR FOREIGN FROM GRANT GROUP HAVING IN "
                  "INITIALLY INTERSECT INTO LATERAL LEADING LIMIT LOCALTIME LOCALTIMESTAMP NOT NULL OFFSET ON ONLY OR "
                  "ORDER PLACING PRIMARY REFERENCES RETURNING SELECT SESSION_USER SOME SYMMETRIC TABLE THEN TO TRAILING "
                  "TRUE UNION UNIQUE USER USING VARIADIC WHEN WHERE WINDOW WITH",
    "oracle": "ACCESS ADD ALL ALTER AND ANY AS ASC AUDIT BETWEEN BY CHAR CHECK CLUSTER COLUMN COMMENT COMPRESS CONNECT "
              "CREATE CURRENT DATE DECIMAL DEFAULT DELETE DESC DISTINCT DROP ELSE EXCLUSIVE EXISTS FILE FLOAT FOR FROM "
              "GRANT GROUP HAVING IDENTIFIED IMMEDIATE IN INCREMENT INDEX INITIAL INSERT INTEGER INTERSECT INTO IS LEVEL "
              "LIKE LOCK LONG MAXEXTENTS MINUS MLSLABEL MODE MODIFY NOAUDIT NOCOMPRESS NOT NOWAIT NULL NUMBER OF OFFLINE "
              "ON ONLINE OPTION OR ORDER PCTFREE PRIOR PUBLIC RAW RENAME RESOURCE REVOKE ROW ROWID ROWNUM ROWS SELECT "
              "SESSION SET SHARE SIZE SMALLINT START SUCCESSFUL SYNONYM SYSDATE TABLE THEN TO TRIGGER UID UNION UNIQUE "
              "UPDATE USER VALIDATE VALUES VARCHAR VARCHAR2 VIEW WHENEVER WHERE WITH",
    "mssql": "ADD ALL ALTER AND ANY AS ASC AUTHORIZATION BACKUP BEGIN BETWEEN BREAK BROWSE BULK BY CASCADE CASE CHECK "
             "CHECKPOINT CLOSE CLUSTERED COALESCE COLLATE COLUMN COMMIT COMPUTE CONSTRAINT CONTAINS CONTAINSTABLE "
             "CONTINUE CONVERT CREATE CROSS CURRENT CURRENT_DATE CURRENT_TIME CURRENT_TIMESTAMP CURRENT_USER CURSOR "
             "DATABASE DBCC DEALLOCATE DECLARE DEFAULT DELETE DENY DESC DISK DISTINCT DISTRIBUTED DOUBLE DROP DUMP ELSE "
             "END ERRLVL ESCAPE EXCEPT EXEC EXECUTE EXISTS EXIT EXTERNAL FETCH FILE FILLFACTOR FOR FOREIGN FREETEXT "
             "FREETEXTTABLE FROM FULL FUNCTION GOTO GRANT GROUP HAVING HOLDLOCK IDENTITY IDENTITY_INSERT IDENTITYCOL IF "
             "IN INDEX INNER INSERT INTERSECT INTO IS JOIN KEY KILL LEFT LIKE LINENO LOAD MERGE NATIONAL NOCHECK "
             "NONCLUSTERED NOT NULL NULLIF OF OFF OFFSETS ON OPEN OPENDATASOURCE OPENQUERY OPENROWSET OPENXML OPTION OR "
             "ORDER OUTER OVER PERCENT PIVOT PLAN PRECISION PRIMARY PRINT PROC PROCEDURE PUBLIC RAISERROR READ READTEXT "
             "RECONFIGURE REFERENCES REPLICATION RESTORE RESTRICT RETURN REVERT REVOKE RIGHT ROLLBACK ROWCOUNT ROWGUIDCOL "
             "RULE SAVE SCHEMA SECURITYAUDIT SELECT SEMANTICKEYPHRASETABLE SESSION_USER SET SETUSER SHUTDOWN SOME "
             "STATISTICS SYSTEM_USER TABLE TABLESAMPLE TEXTSIZE THEN TO TOP TRAN TRANSACTION TRIGGER TRUNCATE "
             "TRY_CONVERT TSEQUAL UNION UNIQUE UNPIVOT UPDATE UPDATETEXT USE USER VALUES VARYING VIEW WAITFOR WHEN WHERE "
             "WHILE WITH WITHIN WRITETEXT",
    "mysql": "ACCESSIBLE ADD ALL ALTER ANALYZE AND AS ASC ASENSITIVE BEFORE BETWEEN BIGINT BINARY BLOB BOTH BY CALL "
             "CASCADE CASE CHANGE CHAR CHARACTER CHECK COLLATE COLUMN CONDITION CONSTRAINT CONTINUE CONVERT CREATE CROSS "
             "CUBE CUME_DIST CURRENT_DATE CURRENT_TIME CURRENT_TIMESTAMP CURRENT_USER CURSOR DATABASE DATABASES "
             "DAY_HOUR DAY_MICROSECOND DAY_MINUTE DAY_SECOND DEC DECIMAL DECLARE DEFAULT DELAYED DELETE DENSE_RANK DESC "
             "DESCRIBE DETERMINISTIC DISTINCT DISTINCTROW DIV DOUBLE DROP DUAL EACH ELSE ELSEIF EMPTY ENCLOSED ESCAPED "
             "EXCEPT EXISTS EXIT EXPLAIN FALSE FETCH FIRST_VALUE FLOAT FLOAT4 FLOAT8 FOR FORCE FOREIGN FROM FULLTEXT "
             "FUNCTION GENERATED GET GRANT GROUP GROUPING GROUPS HAVING HIGH_PRIORITY HOUR_MICROSECOND HOUR_MINUTE "
             "HOUR_SECOND IF IGNORE IN INDEX INFILE INNER INOUT INSENSITIVE INSERT INT INT1 INT2 INT3 INT4 INT8 INTEGER "
             "INTERSECT INTERVAL INTO IO_AFTER_GTIDS IO_BEFORE_GTIDS IS ITERATE JOIN JSON_TABLE KEY KEYS KILL LAG "
             "LAST_VALUE LATERAL LEAD LEADING LEAVE LEFT LIKE LIMIT LINEAR LINES LOAD LOCALTIME LOCALTIMESTAMP LOCK LONG "
             "LONGBLOB LONGTEXT LOOP LOW_PRIORITY MANUAL MASTER_BIND MASTER_SSL_VERIFY_SERVER_CERT MATCH MAXVALUE "
             "MEDIUMBLOB MEDIUMINT MEDIUMTEXT MIDDLEINT MINUTE_MICROSECOND MINUTE_SECOND MOD MODIFIES NATURAL NOT "
             "NO_WRITE_TO_BINLOG NTH_VALUE NTILE NULL NUMERIC OF ON OPTIMIZE OPTIMIZER_COSTS OPTION OPTIONALLY OR ORDER "
             "OUT OUTER OUTFILE OVER PARALLEL PARTITION PERCENT_RANK PRECISION PRIMARY PROCEDURE PURGE QUALIFY RANGE RANK "
             "READ READS READ_WRITE REAL RECURSIVE REFERENCES REGEXP RELEASE RENAME REPEAT REPLACE REQUIRE RESIGNAL "
             "RESTRICT RETURN REVOKE RIGHT RLIKE ROW ROWS ROW_NUMBER SCHEMA SCHEMAS SECOND_MICROSECOND SELECT SENSITIVE "
             "SEPARATOR SET SHOW SIGNAL SMALLINT SPATIAL SPECIFIC SQL SQLEXCEPTION SQLSTATE SQLWARNING SQL_BIG_RESULT "
             "SQL_CALC_FOUND_ROWS SQL_SMALL_RESULT SSL STARTING STORED STRAIGHT_JOIN SYSTEM TABLE TERMINATED THEN "
             "TINYBLOB TINYINT TINYTEXT TO TRAILING TRIGGER TRUE UNDO UNION UNIQUE UNLOCK UNSIGNED UPDATE USAGE USE USING "
             "UTC_DATE UTC_TIME UTC_TIMESTAMP VALUES VARBINARY VARCHAR VARCHARACTER VARYING VIRTUAL WHEN WHERE WHILE "
             "WINDOW WITH WRITE XOR YEAR_MONTH ZEROFILL",
    "h2": "ALL AND ANY ARRAY AS ASYMMETRIC AUTHORIZATION BETWEEN BOTH CASE CAST CHECK CONSTRAINT CROSS CURRENT_CATALOG "
          "CURRENT_DATE CURRENT_PATH CURRENT_ROLE CURRENT_SCHEMA CURRENT_TIME CURRENT_TIMESTAMP CURRENT_USER DAY DEFAULT "
          "DISTINCT ELSE END EXCEPT EXISTS FALSE FETCH FOR FOREIGN FROM FULL GROUP GROUPS HAVING HOUR IF ILIKE IN INNER "
          "INTERSECT INTERVAL IS JOIN KEY LEADING LEFT LIKE LIMIT LOCALTIME LOCALTIMESTAMP MINUS MINUTE MONTH NATURAL "
          "NOT NULL OFFSET ON OR ORDER OVER PARTITION PRIMARY QUALIFY RANGE REGEXP RIGHT ROW ROWNUM ROWS SECOND SELECT "
          "SESSION_USER SET SOME SYMMETRIC SYSTEM_USER TABLE TO TOP TRAILING TRUE UESCAPE UNION UNIQUE UNKNOWN USER "
          "USING VALUE VALUES WHEN WHERE WINDOW WITH YEAR _ROWID_",
}
RESERVED = {k: set(v.split()) for k, v in RESERVED.items()}


def entries(root):
    out, report, all_tables = [], [], []
    for path, prefix, label, remap in MODULES:
        src = open(os.path.join(root, path), encoding='utf-8').read()
        # normalise raw numeric types into tokens before parsing
        for raw, tok in RAW.items():
            src = re.sub(r'\b' + re.escape(raw).replace(r'\,', r'\s*,\s*'), tok, src)
            src = src.replace(raw, tok)
        for a, b in remap.items():
            src = src.replace(a, b)
        tmp = os.path.join(os.path.dirname(os.path.abspath(__file__)), "_tmp_schema.java")
        open(tmp, "w", encoding="utf-8").write(src)
        order, tables, problems = java_ddl.parse(tmp)
        problems = [p for p in problems if p != "unhandled statement: CREATE TABLE"]
        report += [f"{label}: {p}" for p in problems]
        out.append(f"    # ------------------------------------------------------------------ {label}: shapes converted "
                   f"by script from\n    # {path}")
        for n, t in enumerate(order, 1):
            tb = tables[t]
            idx = list(tb["idx"])
            for k, u in enumerate(tb["uniques"], 1):
                idx.append((f"{t[:26]}_UQ{k}", True, u))
            cols = ", ".join(f'("{c}", "{tok}", "{rest}")' for c, tok, rest in tb["cols"])
            out.append(f'    ("{prefix}-{n:02d}", "{label}", "{t}", [{cols}], {tb["pk"]!r}, '
                       f'[{", ".join(repr(i) for i in idx)}]),')
            all_tables.append((t, tb, label))
    return "\n".join(out) + "\n", report, all_tables


def check(all_tables, existing_names):
    rep = []
    pk_names, idx_names = {}, {}
    for name, (kind, owner) in existing_names.items():
        (pk_names if kind == "pk" else idx_names)[name] = owner
    for t, tb, label in all_tables:
        types = {c[0]: c[1] for c in tb["cols"]}
        nulls = {c[0]: "NOT NULL" not in c[2].upper() and c[1] != "{identity}" for c in tb["cols"]}
        pkn = f"PK_{t[:27]}"
        if pkn in pk_names:
            rep.append(f"{t}: primary-key constraint name {pkn} collides with {pk_names[pkn]}")
        pk_names[pkn] = t
        if t in idx_names:
            rep.append(f"{t}: table name collides with index {t}")
        for c in tb["cols"]:
            for dbms, words in RESERVED.items():
                if c[0].upper() in words:
                    rep.append(f"{t}.{c[0]}: reserved word on {dbms}")
            if c[1] not in MYSQL_B:
                rep.append(f"{t}.{c[0]}: unknown token {c[1]}")
        keys = [("PK", tb["pk"], True)] + [(i[0], i[2], i[1]) for i in tb["idx"]] + \
               [(f"{t[:26]}_UQ{k}", u, True) for k, u in enumerate(tb["uniques"], 1)]
        for kname, kcols, unique in keys:
            missing = [k for k in kcols if k not in types]
            if missing:
                rep.append(f"{t}: key {kname} names unknown columns {missing}")
                continue
            if kname != "PK":
                if kname in idx_names:
                    rep.append(f"{t}: index name {kname} also used on {idx_names[kname]}")
                idx_names[kname] = t
                if len(kname) > 30:
                    rep.append(f"{t}: index name {kname} longer than 30 characters")
            mb = sum(MYSQL_B[types[k]] for k in kcols)
            sb = sum(MSSQL_B[types[k]] for k in kcols)
            if mb > 3072:
                rep.append(f"{t}: key {kname} {kcols} = {mb} bytes on MySQL utf8mb4 (> 3,072): creation fails")
            if sb > 1700:
                rep.append(f"{t}: key {kname} = {sb} bytes on SQL Server (> 1,700): warning at creation, "
                           f"rows with long values fail")
            elif kname == "PK" and sb > 900:
                rep.append(f"{t}: primary key {sb} bytes on SQL Server (> 900): generated NONCLUSTERED")
            if unique and kname != "PK" and any(nulls[k] for k in kcols):
                rep.append(f"{t}: unique key {kname} has nullable column(s) {[k for k in kcols if nulls[k]]}: "
                           f"SQL Server allows one NULL per key, Oracle treats partly-NULL keys as equal")
    return rep


if __name__ == "__main__":
    root, out_block, out_report = sys.argv[1:4]
    block, report, all_tables = entries(root)
    gen = open(os.path.join(root, "sangam-persistence/tools/generate_changelogs.py"), encoding="utf-8").read()
    existing = {}
    for m in re.finditer(r'^    \("[0-9-]+", "[^\n]*?", "(\w+)", \[', gen, re.M):
        existing[f"PK_{m.group(1)[:27]}"] = ("pk", m.group(1))
    for m in re.finditer(r'\("(\w+)", (?:True|False), \[', gen):
        existing[m.group(1)] = ("idx", "baseline")
    report += check(all_tables, existing)
    open(out_block, "w", encoding="utf-8").write(block)
    open(out_report, "w", encoding="utf-8").write("\n".join(report) + "\n")
    print(f"{len(all_tables)} tables converted; {len(report)} report lines")
