# SPDX-License-Identifier: Apache-2.0
"""Generates the per-dialect Liquibase formatted-SQL changelogs of the SCALE modules (control plane, roll-up)
from one portable catalogue (same approach as sangam-persistence/tools). Runs from any directory (outputs are
anchored to this file, SL-128), e.g. from sangam/:
    python -E -X utf8 sangam-control-plane/tools/generate_scale_changelogs.py
    python -E -X utf8 sangam-control-plane/tools/generate_scale_changelogs.py --check   (writes nothing; exit 1 on drift)
"""
import os
import sys

DIALECTS = ["postgresql", "oracle", "mssql", "mysql", "h2"]

def coltype(d, t):
    kind, _, n = t.partition(":")
    if kind == "s":
        return {"postgresql": f"VARCHAR({n})", "oracle": f"VARCHAR2({n} CHAR)", "mssql": f"NVARCHAR({n})",
                "mysql": f"VARCHAR({n})", "h2": f"VARCHAR({n})"}[d]
    if kind == "big":
        return {"oracle": "NUMBER(19)"}.get(d, "BIGINT")
    if kind == "int":
        return {"oracle": "NUMBER(10)", "mssql": "INT"}.get(d, "INTEGER")
    if kind == "dbl":
        return {"postgresql": "DOUBLE PRECISION", "oracle": "BINARY_DOUBLE", "mssql": "FLOAT", "mysql": "DOUBLE",
                "h2": "DOUBLE PRECISION"}[d]
    raise ValueError(t)

CONTROL_PLANE = ("sangam-control-plane", "db/changelog/controlplane", "cp", [
    ("CP_TENANT", "Tenant registry: n-level hierarchy, jurisdiction, residency, assigned cell (SCALE 5)", [
        ("tenant_code", "s:64", True), ("parent_code", "s:64", False), ("path", "s:512", True), ("depth", "int", True),
        ("level_kind", "s:32", True), ("name", "s:255", True), ("jurisdiction", "s:32", True),
        ("residency", "s:32", True), ("sector", "s:64", False), ("expected_starts_per_sec", "dbl", True),
        ("cell_id", "s:16", False), ("status", "s:16", True), ("route_epoch", "big", True),
        ("created_at_ms", "big", True), ("updated_at_ms", "big", True)],
     ["tenant_code"], [("CP_TENANT_PARENT", ["parent_code"]), ("CP_TENANT_CELL", ["cell_id"]),
                       ("CP_TENANT_PATH", ["path"])]),
    ("CP_CELL", "Cell directory with health and capacity (SCALE 5)", [
        ("cell_id", "s:16", True), ("region", "s:32", True), ("jurisdiction", "s:32", True), ("residency", "s:32", True),
        ("api_base_url", "s:512", True), ("admin_base_url", "s:512", False), ("db_vendor", "s:32", False),
        ("capacity_starts_per_sec", "int", True), ("capacity_tenants", "int", True), ("status", "s:16", True),
        ("placement_open", "int", True), ("starts_per_sec", "dbl", True), ("p99_command_ms", "dbl", True),
        ("active_nodes", "int", True), ("db_pool_usage_pct", "dbl", True), ("outbox_fill_pct", "dbl", True),
        ("tenants_reported", "big", True), ("version", "s:64", False), ("last_heartbeat_ms", "big", True),
        ("registered_at_ms", "big", True)],
     ["cell_id"], [("CP_CELL_STATUS", ["status"])]),
    ("CP_TENANT_MOVE", "Tenant move between cells: drain, copy, verify, cut-over (SCALE 7)", [
        ("move_id", "s:64", True), ("tenant_code", "s:64", True), ("source_cell", "s:16", True),
        ("target_cell", "s:16", True), ("state", "s:24", True), ("process_instance_id", "s:64", False),
        ("requested_by", "s:255", True), ("reason", "s:1000", True), ("rows_copied", "big", True),
        ("checksum_source", "s:128", False), ("checksum_target", "s:128", False), ("source_watermark", "s:128", False),
        ("started_at_ms", "big", True), ("finished_at_ms", "big", False), ("last_error", "s:2000", False)],
     ["move_id"], [("CP_MOVE_TENANT", ["tenant_code", "state"])]),
    ("CP_AUDIT", "Control-plane audit (placement, routing, moves, cell lifecycle)", [
        ("audit_id", "s:64", True), ("at_ms", "big", True), ("actor", "s:255", True), ("action", "s:64", True),
        ("subject", "s:128", True), ("detail", "s:2000", False), ("content_hash", "s:64", True)],
     ["audit_id"], [("CP_AUDIT_SUBJECT", ["subject", "at_ms"])]),
], [
    # M-016 (dedicated cell per tenant, owner decision Q-02 = A): columns added to applied tables, so they come as
    # appended ALTER changesets; the CREATE TABLE changesets above keep their text and checksums.
    # (table, comment, column, type, not null, default literal or None)
    ("CP_CELL", "M-016: a cell dedicated to one tenant hosts only that tenant (NULL = shared cell)",
     "dedicated_tenant", "s:64", False, None),
    ("CP_TENANT", "M-016: tenant isolation SHARED, SCHEMA or DATABASE (existing rows SHARED)",
     "isolation", "s:16", True, "SHARED"),
])

ROLLUP = ("sangam-rollup", "db/changelog/rollup", "ru", [
    ("RU_EVENT_SEEN", "Idempotent consumption: one row per CloudEvents id (VI.2.3 rule 4)", [
        ("event_id", "s:64", True), ("cell_id", "s:16", True), ("tenant_code", "s:64", True),
        ("received_at_ms", "big", True)],
     ["event_id"], [("RU_SEEN_RECEIVED", ["received_at_ms"])]),
    ("RU_COUNTER", "Sharded counters per hierarchy node, metric and day (no hot global row)", [
        ("node_path", "s:256", True), ("metric", "s:96", True), ("day_bucket", "int", True), ("shard", "int", True),
        ("counter_value", "big", True), ("updated_at_ms", "big", True)],
     ["node_path", "metric", "day_bucket", "shard"], [("RU_COUNTER_METRIC_DAY", ["metric", "day_bucket"])]),
    ("RU_NODE", "Hierarchy nodes seen in the stream (district, state, nation ...)", [
        ("node_path", "s:256", True), ("parent_path", "s:256", False), ("depth", "int", True),
        ("first_seen_ms", "big", True)],
     ["node_path"], [("RU_NODE_PARENT", ["parent_path"])]),
    ("RU_CELL_FEED", "Per-cell feed freshness, sharded to avoid a hot row per cell", [
        ("cell_id", "s:16", True), ("shard", "int", True), ("events", "big", True), ("last_event_at_ms", "big", True)],
     ["cell_id", "shard"], []),
    # M-063 (SCALE-ARCHITECTURE 8.4): state-level tier. Appended changesets only (05, 06): the applied 01-04 keep
    # their text and checksums.
    ("RU_UPSTREAM_PENDING", "Tier forwarding: counter deltas not yet shipped to the upper roll-up, sharded like RU_COUNTER", [
        ("node_path", "s:256", True), ("metric", "s:96", True), ("day_bucket", "int", True), ("shard", "int", True),
        ("pending_delta", "big", True), ("updated_at_ms", "big", True)],
     ["node_path", "metric", "day_bucket", "shard"], []),
    ("RU_UPSTREAM_OUTBOX", "Tier forwarding: explicit metric events to the upper roll-up, written with the pending decrement", [
        ("event_id", "s:64", True), ("payload", "s:2000", True), ("created_at_ms", "big", True),
        ("attempts", "int", True), ("next_attempt_ms", "big", True), ("parked", "int", True),
        ("last_error", "s:1000", False)],
     ["event_id"], [("RU_UPSTREAM_DUE", ["parked", "next_attempt_ms"])]),
], [])

def add_column(d, table, col, t, nn, default):
    """ALTER TABLE ... ADD of one column and its rollback, per dialect. A NOT NULL column needs a default, which also
    fills the existing rows; SQL Server gets a named default constraint so the rollback can drop it."""
    if nn and default is None:
        raise ValueError(f"{table}.{col}: a NOT NULL column added to an applied table needs a default")
    lit = "" if default is None else "'" + default.replace("'", "''") + "'"
    if d == "mssql":
        dflt = f" CONSTRAINT DF_{table}_{col.upper()} DEFAULT {lit}" if default is not None else ""
        add = f"ALTER TABLE {table} ADD {col} {coltype(d, t)}{dflt}{' NOT NULL' if nn else ' NULL'};"
        back = (f"ALTER TABLE {table} DROP CONSTRAINT DF_{table}_{col.upper()}; " if default is not None else "") \
            + f"ALTER TABLE {table} DROP COLUMN {col};"
        return add, back
    dflt = f" DEFAULT {lit}" if default is not None else ""
    add = f"ALTER TABLE {table} ADD {col} {coltype(d, t)}{dflt}{' NOT NULL' if nn else ''};"
    return add, f"ALTER TABLE {table} DROP COLUMN {col};"

def render(module, cs_prefix, tables, alters, d):
    out = ["--liquibase formatted sql", "",
           "-- GENERATED by sangam-control-plane/tools/generate_scale_changelogs.py. Do not edit by hand;",
           "-- add a new changeset for any change to an applied table.", ""]
    for i, (name, comment, cols, pk, idx) in enumerate(tables, 1):
        out.append(f"--changeset sangam-scale:{cs_prefix}-0001-{i:02d}-{name.lower()} dbms:{d}")
        out.append(f"--comment: {comment}")
        out.append(f"CREATE TABLE {name} (")
        lines = [f"  {c} {coltype(d, t)}{' NOT NULL' if nn else ''}" for c, t, nn in cols]
        lines.append(f"  CONSTRAINT PK_{name} PRIMARY KEY ({', '.join(pk)})")
        out.append(",\n".join(lines))
        out.append(");")
        for iname, icols in idx:
            out.append(f"CREATE INDEX {iname} ON {name} ({', '.join(icols)});")
        out.append(f"--rollback DROP TABLE {name};")
        out.append("")
    for i, (table, comment, col, t, nn, default) in enumerate(alters, len(tables) + 1):
        add, back = add_column(d, table, col, t, nn, default)
        out.append(f"--changeset sangam-scale:{cs_prefix}-0001-{i:02d}-{table.lower()}-add-{col} dbms:{d}")
        out.append(f"--comment: {comment}")
        out.append(add)
        out.append(f"--rollback {back}")
        out.append("")
    return "\n".join(out)

def master(dirname, prefix):
    inc = "\n".join(f'  <include file="{d}/{prefix}-0001-baseline.sql" relativeToChangelogFile="true"/>' for d in DIALECTS)
    return f'''<?xml version="1.0" encoding="UTF-8"?>
<!-- SPDX-License-Identifier: Apache-2.0 -->
<!-- Self-contained schema of this module (Liquibase 4.33.0, D-7). Each changeset carries dbms:<name>, so exactly one
     dialect applies: postgresql, oracle, mssql, mysql (8.4, utf8mb4) and h2 (development only). -->
<databaseChangeLog
    xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.33.xsd">

{inc}

</databaseChangeLog>
'''

# SL-128: outputs are anchored to this script (sangam/<module>/tools/), never to the working directory, so a run from
# the repository root (as CI does) writes the committed files instead of stray copies outside sangam/.
SANGAM_DIR = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def outputs():
    """Every generated file: (absolute path, exact content)."""
    for module, base, prefix, tables, alters in (CONTROL_PLANE, ROLLUP):
        root = os.path.join(SANGAM_DIR, module, "src", "main", "resources", base)
        for d in DIALECTS:
            yield os.path.join(root, d, f"{prefix}-0001-baseline.sql"), render(module, prefix, tables, alters, d)
        yield os.path.join(root, f"{prefix}-master.xml"), master(base, prefix)


def main(argv):
    """Writes the changelogs; with --check, writes nothing and exits 1 when a file is missing or differs."""
    if argv and argv != ["--check"]:
        print("usage: generate_scale_changelogs.py [--check]", file=sys.stderr)
        return 2
    if argv == ["--check"]:
        stale = []
        for path, content in outputs():
            try:
                with open(path, "r", encoding="utf-8", newline="") as f:
                    current = f.read()
            except FileNotFoundError:
                current = None
            if current != content:
                stale.append(os.path.relpath(path, SANGAM_DIR))
        for p in stale:
            print("differs from the generator:", p, file=sys.stderr)
        print("check:", "FAILED" if stale else "OK")
        return 1 if stale else 0
    roots = []
    for path, content in outputs():
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8", newline="\n") as f:
            f.write(content)
        if os.path.dirname(path) not in roots and path.endswith("-master.xml"):
            roots.append(os.path.dirname(path))
    for r in roots:
        print("wrote", r)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
