// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.schema;

import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;

import java.sql.Connection;
import java.util.List;

/**
 * Reference DDL of the tables this module adds on top of the core product tables ({@code org.sangam.core.layer.Schema}:
 * OUTBOX, OUTBOX_DELIVERY, INBOX, IDEMPOTENCY_RECORD, OPERATION_LEDGER, RECONCILIATION_ITEM, TOMBSTONE, ROOT_SEQ,
 * AUDIT_*). WS-ENGINE ports these statements into the per-dialect Liquibase changelogs of sangam-persistence (D-7);
 * {@link #create} exists for tests and development databases only. Portable type tokens as in the core schema.
 */
public final class IntegrationSchema {

    private IntegrationSchema() {
    }

    public static final List<String> DDL = List.of(
            // R37 durable call intent with provider handles
            """
            CREATE TABLE CONNECTOR_CALL (
              operation_id  {str64} NOT NULL PRIMARY KEY,
              tenant_id     {str64} NOT NULL,
              connector_id  {str64} NOT NULL,
              operation     {str64} NOT NULL,
              root_id       {str64} NOT NULL,
              business_key  {str},
              request_json  {text},
              provider_ref  {str},
              last_result   {str64} NOT NULL,
              last_error    {text},
              attempts      {int} NOT NULL,
              created_at    {ts} NOT NULL,
              updated_at    {ts} NOT NULL
            )""",
            "CREATE INDEX CONNECTOR_CALL_ROOT ON CONNECTOR_CALL (tenant_id, root_id)",
            // VI.2.3 rule 5 / VI.2.6 rule 3 dead letters
            """
            CREATE TABLE INTEGRATION_DEAD_LETTER (
              id           {identity} PRIMARY KEY,
              tenant_id    {str64} NOT NULL,
              kind         {str64} NOT NULL,
              ref_id       {str} NOT NULL,
              destination  {str},
              root_id      {str64},
              event_type   {str},
              payload      {text},
              error        {text},
              state        {str64} NOT NULL,
              resolved_by  {str},
              resolution   {text},
              created_at   {ts} NOT NULL,
              resolved_at  {ts}
            )""",
            "CREATE INDEX INTEGRATION_DEAD_LETTER_OPEN ON INTEGRATION_DEAD_LETTER (tenant_id, state)",
            "CREATE INDEX INTEGRATION_DEAD_LETTER_REF ON INTEGRATION_DEAD_LETTER (kind, ref_id)",
            // R29 leader election
            """
            CREATE TABLE INTEGRATION_LEADER (
              name        {str64} NOT NULL PRIMARY KEY,
              holder      {str64},
              epoch       {long} NOT NULL,
              expires_at  {ts} NOT NULL
            )""",
            // VI.2.6 rule 4 uniqueStart
            """
            CREATE TABLE UNIQUE_START (
              tenant_id       {str64} NOT NULL,
              definition_key  {str} NOT NULL,
              business_key    {str} NOT NULL,
              instance_id     {str64},
              source          {str64},
              message_id      {str},
              created_at      {ts} NOT NULL,
              PRIMARY KEY (tenant_id, definition_key, business_key)
            )""",
            // reconciliation engine bookkeeping
            """
            CREATE TABLE RECONCILIATION_ATTEMPT (
              item_id            {long} NOT NULL PRIMARY KEY,
              attempts           {int} NOT NULL,
              not_found_streak   {int} NOT NULL,
              next_attempt_at    {ts} NOT NULL,
              last_result        {text},
              escalated_task_id  {str64},
              created_at         {ts} NOT NULL,
              updated_at         {ts} NOT NULL
            )""",
            // VI.5.1 / R61 legal holds
            """
            CREATE TABLE LEGAL_HOLD (
              tenant_id    {str64} NOT NULL,
              root_id      {str64} NOT NULL,
              hold_id      {str64} NOT NULL,
              reason       {text},
              held_by      {str} NOT NULL,
              created_at   {ts} NOT NULL,
              released_at  {ts},
              released_by  {str},
              PRIMARY KEY (tenant_id, root_id, hold_id)
            )""",
            // indexes this module needs on core tables
            "CREATE INDEX OPERATION_LEDGER_ACTIVITY ON OPERATION_LEDGER (activity_instance_id, state)",
            "CREATE INDEX TOMBSTONE_TASK ON TOMBSTONE (task_id, state)",
            "CREATE INDEX OUTBOX_CREATED ON OUTBOX (created_at)",
            "CREATE INDEX INBOX_CORRELATION ON INBOX (tenant_id, correlation_key)",
            "CREATE INDEX IDEMPOTENCY_RESOURCE ON IDEMPOTENCY_RECORD (tenant_id, resource_ref)");

    /** Creates the tables (development/tests). */
    public static void create(Connection c, Dialect dialect) {
        for (String sql : DDL) {
            String s = dialect.expand(sql);
            Jdbc.update(c, s.startsWith("CREATE TABLE") ? s + dialect.createTableSuffix() : s);
        }
    }
}
