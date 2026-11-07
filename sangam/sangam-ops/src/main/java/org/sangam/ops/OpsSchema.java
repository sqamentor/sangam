// SPDX-License-Identifier: Apache-2.0
package org.sangam.ops;

import org.sangam.core.layer.Dialect;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/** Portable DDL of the operations tables. The integrator copies it into the sangam-persistence changelogs, per dialect. */
public final class OpsSchema {

    private OpsSchema() {
    }

    public static final List<String> TEMPLATES = List.of(
            """
            CREATE TABLE OPS_MIG_CHECKPOINT (
              job_id       {str64} NOT NULL,
              source_table {str64} NOT NULL,
              last_id      {str},
              copied       {long} NOT NULL,
              updated_at   {ts} NOT NULL,
              failed       {long} DEFAULT 0 NOT NULL,
              mismatched   {long} DEFAULT 0 NOT NULL,
              status       {str64} DEFAULT 'RUNNING' NOT NULL,
              PRIMARY KEY (job_id, source_table)
            )""",
            """
            CREATE TABLE OPS_MIG_ROW (
              job_id       {str64} NOT NULL,
              source_table {str64} NOT NULL,
              source_id    {str} NOT NULL,
              tenant_id    {str64} NOT NULL,
              payload      {text} NOT NULL,
              migrated_at  {ts} NOT NULL,
              PRIMARY KEY (job_id, source_table, source_id)
            )""",
            """
            CREATE TABLE OPS_USAGE_DAILY (
              tenant_id {str64} NOT NULL,
              day_utc   {str64} NOT NULL,
              metric    {str64} NOT NULL,
              value_num {long} NOT NULL,
              PRIMARY KEY (tenant_id, day_utc, metric)
            )""",
            """
            CREATE TABLE OPS_SEARCH_DOC (
              generation  {long} NOT NULL,
              tenant_id   {str64} NOT NULL,
              doc_type    {str64} NOT NULL,
              id          {str} NOT NULL,
              title       {str},
              body        {text},
              PRIMARY KEY (generation, tenant_id, doc_type, id)
            )""",
            """
            CREATE TABLE OPS_SEARCH_ALIAS (
              tenant_id  {str64} NOT NULL PRIMARY KEY,
              generation {long} NOT NULL,
              switched_at {ts} NOT NULL
            )""",
            """
            CREATE TABLE OPS_INDEX_JOB (
              generation {long} NOT NULL PRIMARY KEY,
              tenant_id  {str64} NOT NULL,
              state      {str64} NOT NULL,
              indexed    {long} NOT NULL,
              expected   {long} NOT NULL,
              message    {text},
              updated_at {ts} NOT NULL,
              reason     {text}
            )""",
            """
            CREATE TABLE OPS_VARIABLE (
              scope_id   {str64} NOT NULL,
              name       {str} NOT NULL,
              value_json {text} NOT NULL,
              updated_by {str} NOT NULL,
              updated_at {ts} NOT NULL,
              scope_type {str64} DEFAULT 'TENANT' NOT NULL,
              tenant_id  {str64},
              version    {long} DEFAULT 1 NOT NULL,
              reason     {text},
              PRIMARY KEY (scope_id, name)
            )""",
            """
            CREATE TABLE OPS_SECRET (
              tenant_id  {str64} NOT NULL,
              ref_name   {str} NOT NULL,
              envelope   {text} NOT NULL,
              version    {int} NOT NULL,
              updated_by {str} NOT NULL,
              updated_at {ts} NOT NULL,
              reason     {text},
              PRIMARY KEY (tenant_id, ref_name)
            )""",
            """
            CREATE TABLE OPS_RETENTION_RUN (
              run_id      {str64} NOT NULL PRIMARY KEY,
              tenant_id   {str64} NOT NULL,
              plan_id     {str64} NOT NULL,
              plan_hash   {str64} NOT NULL,
              reason      {text} NOT NULL,
              purged      {int} NOT NULL,
              executed_by {str} NOT NULL,
              executed_at {ts} NOT NULL
            )""",
            "CREATE INDEX OPS_RETENTION_RUN_TENANT ON OPS_RETENTION_RUN (tenant_id, executed_at)");

    public static void create(Connection c, Dialect d) throws SQLException {
        try (Statement s = c.createStatement()) {
            for (String t : TEMPLATES) {
                String sql = d.expand(t);
                s.execute(sql.startsWith("CREATE TABLE") ? sql + d.createTableSuffix() : sql);
            }
        }
    }
}
