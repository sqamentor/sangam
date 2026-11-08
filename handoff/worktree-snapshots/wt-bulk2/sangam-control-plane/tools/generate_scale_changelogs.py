# SPDX-License-Identifier: Apache-2.0
"""Generates the per-dialect Liquibase formatted-SQL changelogs of the SCALE modules (control plane, roll-up)
from one portable catalogue (same approach as sangam-persistence/tools). Run from sangam/:
    python -E -X utf8 sangam-control-plane/tools/generate_scale_changelogs.py
"""
import os

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
])

def render(module, cs_prefix, tables, d):
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

for module, base, prefix, tables in (CONTROL_PLANE, ROLLUP):
    root = os.path.join(module, "src", "main", "resources", base)
    for d in DIALECTS:
        os.makedirs(os.path.join(root, d), exist_ok=True)
        with open(os.path.join(root, d, f"{prefix}-0001-baseline.sql"), "w", encoding="utf-8", newline="\n") as f:
            f.write(render(module, prefix, tables, d))
    with open(os.path.join(root, f"{prefix}-master.xml"), "w", encoding="utf-8", newline="\n") as f:
        f.write(master(base, prefix))
    print("wrote", root)
