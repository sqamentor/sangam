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
            "CREATE INDEX OPS_RETENTION_RUN_TENANT ON OPS_RETENTION_RUN (tenant_id, executed_at)",
            // SL-44 bulk ingest (BulkIngest): jobs, idempotent chunks, per-row status, per-(tenant, type, external id) keys
            """
            CREATE TABLE OPS_BULK_JOB (
              id              {str64} NOT NULL PRIMARY KEY,
              tenant_id       {str64} NOT NULL,
              object_type     {str64} NOT NULL,
              state           {str64} NOT NULL,
              expected_chunks {int},
              chunk_count     {int} NOT NULL,
              total_rows      {long} NOT NULL,
              created_by      {str},
              created_at      {ts} NOT NULL,
              updated_at      {ts} NOT NULL,
              closed_at       {ts},
              completed_at    {ts}
            )""",
            "CREATE INDEX OPS_BULK_JOB_TENANT ON OPS_BULK_JOB (tenant_id, state, created_at)",
            """
            CREATE TABLE OPS_BULK_CHUNK (
              job_id        {str64} NOT NULL,
              chunk_seq     {int} NOT NULL,
              row_count     {int} NOT NULL,
              invalid_count {int} NOT NULL,
              byte_count    {long} NOT NULL,
              chunk_hash    {str64} NOT NULL,
              uploaded_by   {str},
              uploaded_at   {ts} NOT NULL,
              PRIMARY KEY (job_id, chunk_seq)
            )""",
            """
            CREATE TABLE OPS_BULK_ROW (
              job_id       {str64} NOT NULL,
              chunk_seq    {int} NOT NULL,
              row_seq      {int} NOT NULL,
              external_id  {str} NOT NULL,
              payload      {text} NOT NULL,
              payload_hash {str64} NOT NULL,
              state        {str64} NOT NULL,
              attempts     {int} NOT NULL,
              error_code   {str64},
              error_text   {text},
              result_ref   {str},
              claimed_by   {str64},
              updated_at   {ts} NOT NULL,
              PRIMARY KEY (job_id, chunk_seq, row_seq)
            )""",
            "CREATE INDEX OPS_BULK_ROW_CLAIM ON OPS_BULK_ROW (state, job_id, chunk_seq, row_seq)",
            "CREATE INDEX OPS_BULK_ROW_JOB_STATE ON OPS_BULK_ROW (job_id, state)",
            """
            CREATE TABLE OPS_BULK_KEY (
              tenant_id    {str64} NOT NULL,
              object_type  {str64} NOT NULL,
              external_id  {str} NOT NULL,
              payload_hash {str64} NOT NULL,
              result_ref   {str},
              job_id       {str64} NOT NULL,
              chunk_seq    {int} NOT NULL,
              row_seq      {int} NOT NULL,
              updated_at   {ts} NOT NULL,
              PRIMARY KEY (tenant_id, object_type, external_id)
            )""");

    public static void create(Connection c, Dialect d) throws SQLException {
        try (Statement s = c.createStatement()) {
            for (String t : TEMPLATES) {
                String sql = d.expand(t);
                s.execute(sql.startsWith("CREATE TABLE") ? sql + d.createTableSuffix() : sql);
            }
        }
    }
}
