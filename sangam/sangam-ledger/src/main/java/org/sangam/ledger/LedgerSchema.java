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
 *   <li>{@code LDG_ACCOUNTING}: accounting entries produced by the event mapping, for export to the GL connector;</li>
 *   <li>{@code BILL_UNVERIFIED_PAYMENT} (sangam-billing, RM-03, owner decision 141): payments accepted without their
 *       channel's confirmation. One row per (tenant, channel, payment reference), the unique index refusing a second
 *       acceptance; the accepted columns are never updated, a resolution fills its own columns; {@code version} guards
 *       every transition; {@code receipt_no} is set when a VERIFIED payment is allocated;
 *       {@code resolution_evidence_ref} is the evidence of a manual VERIFIED (owner decision 196).</li>
 *   <li>{@code BILL_UNVERIFIED_ACCRUAL_PAUSE} (owner decision 195): penalty not levied because a payment was pending,
 *       one row per (payment, base account, levy date), written once under the payment's row lock; levied after all
 *       ({@code resumed_at}) when the payment is REJECTED or REVERSED, kept as forgiven when it is VERIFIED. Dates are
 *       ISO {@code yyyy-MM-dd} text (business dates, no zone).</li>
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
            "CREATE INDEX LDG_ACCOUNTING_EXPORT ON LDG_ACCOUNTING (tenant_id, exported_at)",
            """
            CREATE TABLE BILL_UNVERIFIED_PAYMENT (
              tenant_id    {str64} NOT NULL,
              payment_id   {str64} NOT NULL,
              ledger_id    {str64} NOT NULL,
              consumer     {str} NOT NULL,
              office_id    {str64} NOT NULL,
              channel      {str64} NOT NULL,
              payment_ref  {str} NOT NULL,
              amount       {money} NOT NULL,
              currency     {str64} NOT NULL,
              paid_at      {ts} NOT NULL,
              payer_name   {str},
              instrument   {str64},
              details_json {text} NOT NULL,
              reason       {text} NOT NULL,
              evidence_ref {str} NOT NULL,
              policy_version {str64} NOT NULL,
              downstream_json {text} NOT NULL,
              accepted_by  {str} NOT NULL,
              accepted_at  {ts} NOT NULL,
              state        {str64} NOT NULL,
              version      {long} NOT NULL,
              outcome_reason {text},
              verification_ref {str},
              resolved_by  {str},
              resolved_at  {ts},
              resolution_evidence_ref {str},
              receipt_no   {str64},
              PRIMARY KEY (tenant_id, payment_id)
            )""",
            "CREATE UNIQUE INDEX BILL_UNVERIFIED_REF ON BILL_UNVERIFIED_PAYMENT (tenant_id, channel, payment_ref)",
            "CREATE INDEX BILL_UNVERIFIED_STATE ON BILL_UNVERIFIED_PAYMENT (tenant_id, state, accepted_at)",
            "CREATE INDEX BILL_UNVERIFIED_CONSUMER ON BILL_UNVERIFIED_PAYMENT (tenant_id, ledger_id, consumer, state)",
            """
            CREATE TABLE BILL_UNVERIFIED_ACCRUAL_PAUSE (
              tenant_id    {str64} NOT NULL,
              payment_id   {str64} NOT NULL,
              base_account_id {str64} NOT NULL,
              as_of        {str64} NOT NULL,
              paused_from  {str64} NOT NULL,
              ledger_id    {str64} NOT NULL,
              period       {str64} NOT NULL,
              line_code    {str64} NOT NULL,
              consumer     {str} NOT NULL,
              stage        {str64} NOT NULL,
              sub          {str},
              amount       {money} NOT NULL,
              currency     {str64} NOT NULL,
              recorded_at  {ts} NOT NULL,
              resumed_at   {ts},
              PRIMARY KEY (tenant_id, payment_id, base_account_id, as_of)
            )""");

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
