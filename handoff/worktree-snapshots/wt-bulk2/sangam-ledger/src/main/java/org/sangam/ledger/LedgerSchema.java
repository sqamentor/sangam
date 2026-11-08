// SPDX-License-Identifier: Apache-2.0
package org.sangam.ledger;

import org.sangam.core.layer.Dialect;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * DDL of the generic ledger (portable with {@link Dialect} tokens). Every amount column is {@code {money}} =
 * BIGINT holding integer minor units (SD-398): ISO 4217 minor units of the row's {@code currency} for money, or
 * {@link JdbcLedgerStore#QUANTITY_EXPONENT} decimal places for a quantity account (no currency). The integrator copies it into the Liquibase
 * changelogs.
 *
 * <ul>
 *   <li>{@code LDG_BALANCE}: one row per account (and stripe); updated only by guarded single-row UPDATEs, which take
 *       the row lock and evaluate the availability guard atomically (no oversell);</li>
 *   <li>{@code LDG_ENTRY}: one row per posting, unique per (tenant, reference): idempotency and the audit link
 *       (operation, audit reference, actor, time, legs as JSON);</li>
 *   <li>{@code LDG_HOLD}: requests held for funds / stock (pending queue), released on allocation;</li>
 *   <li>{@code LDG_FREEZE}: freeze orders (mass freeze by account predicate) with their release;</li>
 *   <li>{@code LDG_ACCOUNTING}: accounting entries produced by the event mapping, for export to the GL connector.</li>
 * </ul>
 */
public final class LedgerSchema {

    private LedgerSchema() {
    }

    public static final List<String> TEMPLATES = List.of(
            """
            CREATE TABLE LDG_BALANCE (
              account_id   {str64} NOT NULL PRIMARY KEY,
              tenant_id    {str64} NOT NULL,
              ledger_id    {str64} NOT NULL,
              period       {str64} NOT NULL,
              line_code    {str} NOT NULL,
              holder       {str} NOT NULL,
              stage        {str64} NOT NULL,
              sub          {str} NOT NULL,
              currency     {str64},
              total        {money} NOT NULL,
              distributed_amt {money} NOT NULL,
              committed    {money} NOT NULL,
              encumbered   {money} NOT NULL,
              consumed     {money} NOT NULL,
              state        {str64} NOT NULL,
              version      {long} NOT NULL,
              attrs_json   {text}
            )""",
            "CREATE INDEX LDG_BALANCE_FIND ON LDG_BALANCE (tenant_id, ledger_id, period, stage, line_code)",
            "CREATE INDEX LDG_BALANCE_HOLDER ON LDG_BALANCE (tenant_id, ledger_id, holder)",
            """
            CREATE TABLE LDG_ENTRY (
              tenant_id    {str64} NOT NULL,
              reference    {str} NOT NULL,
              operation    {str64} NOT NULL,
              audit_ref    {str},
              actor        {str},
              at_time      {ts} NOT NULL,
              legs_json    {text} NOT NULL,
              info_json    {text},
              PRIMARY KEY (tenant_id, reference)
            )""",
            """
            CREATE TABLE LDG_HOLD (
              tenant_id    {str64} NOT NULL,
              reference    {str} NOT NULL,
              account_id   {str64} NOT NULL,
              amount       {money} NOT NULL,
              request_json {text} NOT NULL,
              held_at      {ts} NOT NULL,
              released_at  {ts},
              PRIMARY KEY (tenant_id, reference)
            )""",
            "CREATE INDEX LDG_HOLD_ACCOUNT ON LDG_HOLD (account_id, held_at)",
            """
            CREATE TABLE LDG_FREEZE (
              tenant_id    {str64} NOT NULL,
              order_id     {str64} NOT NULL,
              predicate_json {text} NOT NULL,
              reason       {text},
              ordered_by   {str},
              ordered_at   {ts} NOT NULL,
              released_at  {ts},
              PRIMARY KEY (tenant_id, order_id)
            )""",
            """
            CREATE TABLE LDG_ACCOUNTING (
              entry_id     {identity} PRIMARY KEY,
              tenant_id    {str64} NOT NULL,
              reference    {str} NOT NULL,
              basis        {str64} NOT NULL,
              debit        {str} NOT NULL,
              credit       {str} NOT NULL,
              amount       {money} NOT NULL,
              currency     {str64},
              functional_amount {money},
              created_at   {ts} NOT NULL,
              exported_at  {ts}
            )""",
            "CREATE INDEX LDG_ACCOUNTING_EXPORT ON LDG_ACCOUNTING (tenant_id, exported_at)");

    public static List<String> ddl(Dialect d) {
        return TEMPLATES.stream().map(t -> d.expand(t) + (t.startsWith("CREATE TABLE") ? d.createTableSuffix() : "")).toList();
    }

    public static void create(Connection c, Dialect d) throws SQLException {
        try (Statement s = c.createStatement()) {
            for (String sql : ddl(d)) {
                s.execute(sql);
            }
        }
    }
}
