// SPDX-License-Identifier: Apache-2.0
package org.sangam.programme;

import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;

/**
 * Reference DDL of the programme tables (N15), in the core {@link Dialect} type tokens (A-15) plus two module tokens:
 * {@code {num24_6}} (exact decimal: {@code NUMBER(24,6)} on Oracle, {@code {num24_6}} elsewhere) and {@code {date}}
 * ({@code DATE}). The integrator turns this into sangam-persistence Liquibase changesets for the four Tier-1 dialects;
 * {@link #create} is for H2/dev and tests. Booleans are SMALLINT 0/1. Every table is tenant-scoped with
 * {@code tenant_id} first in its key ("" = no tenant).
 */
public final class ProgrammeSchema {

    private ProgrammeSchema() {
    }

    private static final String[] TABLES = {
            // versioned, hash-pinned tenant configuration (the whole JSON); indicators and returns are also materialised below
            """
            CREATE TABLE PG_CONFIG (tenant_id {str64} NOT NULL, version {int} NOT NULL, config_json {text} NOT NULL, content_hash {str64} NOT NULL,
              current_flag {small} NOT NULL, defined_by {str}, defined_at {ts} NOT NULL, PRIMARY KEY (tenant_id, version))""",
            // programme / scheme / component / project hierarchy; kind is data from the configuration
            """
            CREATE TABLE PG_PROGRAMME (tenant_id {str64} NOT NULL, programme_id {str64} NOT NULL, parent_id {str64}, kind {str64} NOT NULL,
              code {str64} NOT NULL, name {str} NOT NULL, owner_org {str64}, starts_on {date}, ends_on {date}, status {str64} NOT NULL,
              budget {num24_6}, currency {str64}, indicators_json {text}, attributes_json {text}, row_version {int} NOT NULL,
              created_by {str}, created_at {ts} NOT NULL, updated_at {ts} NOT NULL, PRIMARY KEY (tenant_id, programme_id))""",
            """
            CREATE TABLE PG_INDICATOR (tenant_id {str64} NOT NULL, indicator_key {str64} NOT NULL, version {int} NOT NULL, name {str} NOT NULL,
              unit {str64} NOT NULL, frequency {str64} NOT NULL, kind {str64} NOT NULL, aggregation {str64} NOT NULL, formula {text},
              weight_indicator {str64}, direction {str64} NOT NULL, cumulative {small} NOT NULL, decimals {int} NOT NULL, validation_json {text},
              parent_indicator_key {str64}, weight {num24_6}, active {small} NOT NULL, content_hash {str64} NOT NULL, defined_by {str},
              defined_at {ts} NOT NULL, PRIMARY KEY (tenant_id, indicator_key, version))""",
            """
            CREATE TABLE PG_PERIOD (tenant_id {str64} NOT NULL, period_id {str64} NOT NULL, frequency {str64} NOT NULL, starts_on {date} NOT NULL,
              ends_on {date} NOT NULL, label {str64} NOT NULL, fiscal_year {str64} NOT NULL, seq_in_year {int} NOT NULL, due_on {date},
              state {str64} NOT NULL, state_reason {str}, changed_by {str}, changed_at {ts}, PRIMARY KEY (tenant_id, period_id))""",
            """
            CREATE TABLE PG_TARGET (tenant_id {str64} NOT NULL, target_id {str64} NOT NULL, programme_id {str64} NOT NULL, indicator_key {str64} NOT NULL,
              period_id {str64} NOT NULL, org_unit {str64} NOT NULL, target_value {num24_6} NOT NULL, parent_target_id {str64}, state {str64} NOT NULL,
              version {int} NOT NULL, proposed_by {str}, proposed_at {ts}, approved_by {str}, approved_at {ts}, reason {str},
              row_version {int} NOT NULL, created_at {ts} NOT NULL, PRIMARY KEY (tenant_id, target_id))""",
            """
            CREATE TABLE PG_MILESTONE (tenant_id {str64} NOT NULL, milestone_id {str64} NOT NULL, programme_id {str64} NOT NULL, org_unit {str64},
              name {str} NOT NULL, seq {int} NOT NULL, planned_start {date}, planned_end {date} NOT NULL, actual_start {date}, actual_end {date},
              weight {num24_6} NOT NULL, percent_complete {num24_6} NOT NULL, status {str64} NOT NULL, depends_on_json {text}, evidence_json {text},
              delay_days {int}, row_version {int} NOT NULL, updated_by {str}, updated_at {ts} NOT NULL, PRIMARY KEY (tenant_id, milestone_id))""",
            """
            CREATE TABLE PG_RETURN_DEF (tenant_id {str64} NOT NULL, return_key {str64} NOT NULL, version {int} NOT NULL, name {str} NOT NULL,
              frequency {str64} NOT NULL, programme_id {str64}, indicators_json {text} NOT NULL, rules_json {text}, form_key {str64}, form_version {int},
              validation_dmn_key {str64}, due_offset_days {int} NOT NULL, reminders_json {text}, approval_levels {int} NOT NULL,
              reporting_level {str64}, process_key {str64}, active {small} NOT NULL, content_hash {str64} NOT NULL, defined_by {str},
              defined_at {ts} NOT NULL, PRIMARY KEY (tenant_id, return_key, version))""",
            """
            CREATE TABLE PG_RETURN (tenant_id {str64} NOT NULL, return_id {str64} NOT NULL, return_key {str64} NOT NULL, return_version {int} NOT NULL,
              period_id {str64} NOT NULL, org_unit {str64} NOT NULL, programme_id {str64}, state {str64} NOT NULL, approval_level {int} NOT NULL,
              due_on {date}, reminders_sent {int} NOT NULL, last_reminder_at {ts}, submitted_by {str}, submitted_at {ts}, validated_at {ts},
              accepted_by {str}, accepted_at {ts}, returned_by {str}, returned_reason {str}, frozen_at {ts}, reopened_reason {str},
              late {small} NOT NULL, process_instance_id {str64}, content_hash {str64}, row_version {int} NOT NULL, created_at {ts} NOT NULL,
              updated_at {ts} NOT NULL, PRIMARY KEY (tenant_id, return_id))""",
            """
            CREATE TABLE PG_RETURN_VALUE (tenant_id {str64} NOT NULL, return_id {str64} NOT NULL, indicator_key {str64} NOT NULL, num_value {num24_6},
              text_value {text}, remark {str}, updated_by {str}, updated_at {ts} NOT NULL, PRIMARY KEY (tenant_id, return_id, indicator_key))""",
            """
            CREATE TABLE PG_RETURN_FINDING (tenant_id {str64} NOT NULL, return_id {str64} NOT NULL, seq {int} NOT NULL, indicator_key {str64},
              severity {str64} NOT NULL, rule_code {str64} NOT NULL, message {str} NOT NULL, acknowledged_by {str}, acknowledged_at {ts},
              created_at {ts} NOT NULL, PRIMARY KEY (tenant_id, return_id, seq))""",
            """
            CREATE TABLE PG_RETURN_EVENT (tenant_id {str64} NOT NULL, return_id {str64} NOT NULL, seq {int} NOT NULL, from_state {str64},
              to_state {str64} NOT NULL, approval_level {int} NOT NULL, actor {str}, reason {str}, at_time {ts} NOT NULL,
              PRIMARY KEY (tenant_id, return_id, seq))""",
            """
            CREATE TABLE PG_ACHIEVEMENT (tenant_id {str64} NOT NULL, programme_id {str64} NOT NULL, indicator_key {str64} NOT NULL,
              period_id {str64} NOT NULL, org_unit {str64} NOT NULL, period_value {num24_6}, cumulative_value {num24_6}, weight_value {num24_6},
              source {str64} NOT NULL, source_ref {str64}, contributors {int} NOT NULL, computed_at {ts} NOT NULL,
              PRIMARY KEY (tenant_id, programme_id, indicator_key, period_id, org_unit))""",
            """
            CREATE TABLE PG_FINANCIAL (tenant_id {str64} NOT NULL, programme_id {str64} NOT NULL, period_id {str64} NOT NULL, org_unit {str64} NOT NULL,
              head {str64} NOT NULL, allocated {num24_6}, released {num24_6}, committed_amt {num24_6}, expended {num24_6}, currency {str64}, source {str64} NOT NULL,
              updated_by {str}, updated_at {ts} NOT NULL, PRIMARY KEY (tenant_id, programme_id, period_id, org_unit, head))""",
            """
            CREATE TABLE PG_ALERT (tenant_id {str64} NOT NULL, alert_id {str64} NOT NULL, kind {str64} NOT NULL, programme_id {str64},
              indicator_key {str64}, period_id {str64}, org_unit {str64}, subject_ref {str64}, severity {str64} NOT NULL, message {str} NOT NULL,
              measure {num24_6}, threshold {num24_6}, state {str64} NOT NULL, dedup_key {str} NOT NULL, created_at {ts} NOT NULL, acknowledged_by {str},
              acknowledged_at {ts}, PRIMARY KEY (tenant_id, alert_id))""",
            """
            CREATE TABLE PG_DASHBOARD_SNAPSHOT (tenant_id {str64} NOT NULL, programme_id {str64} NOT NULL, period_id {str64} NOT NULL,
              org_unit {str64} NOT NULL, physical_pct {num24_6}, financial_pct {num24_6}, gap_pct {num24_6}, returns_due {int}, returns_accepted {int},
              returns_overdue {int}, open_alerts {int}, payload_json {text}, content_hash {str64} NOT NULL, computed_at {ts} NOT NULL,
              PRIMARY KEY (tenant_id, programme_id, period_id, org_unit))"""
    };

    private static final String[] INDEXES = {
            "CREATE INDEX PG_PROGRAMME_PARENT ON PG_PROGRAMME (tenant_id, parent_id)",
            "CREATE UNIQUE INDEX PG_PROGRAMME_CODE ON PG_PROGRAMME (tenant_id, code)",
            "CREATE UNIQUE INDEX PG_PERIOD_RANGE ON PG_PERIOD (tenant_id, frequency, starts_on)",
            "CREATE INDEX PG_TARGET_KEY ON PG_TARGET (tenant_id, programme_id, indicator_key, period_id, org_unit, state)",
            "CREATE INDEX PG_TARGET_PARENT ON PG_TARGET (tenant_id, parent_target_id)",
            "CREATE INDEX PG_MILESTONE_PROG ON PG_MILESTONE (tenant_id, programme_id, seq)",
            "CREATE UNIQUE INDEX PG_RETURN_OFFICE ON PG_RETURN (tenant_id, return_key, period_id, org_unit)",
            "CREATE INDEX PG_RETURN_STATE ON PG_RETURN (tenant_id, state, due_on)",
            "CREATE INDEX PG_ACHIEVEMENT_NODE ON PG_ACHIEVEMENT (tenant_id, org_unit, period_id)",
            "CREATE UNIQUE INDEX PG_ALERT_DEDUP ON PG_ALERT (tenant_id, dedup_key)",
            "CREATE INDEX PG_ALERT_STATE ON PG_ALERT (tenant_id, state, kind, created_at)"
    };

    public static String expand(Dialect dialect, String template) {
        String dec = dialect == Dialect.ORACLE ? "NUMBER(24,6)" : "{num24_6}";
        return dialect.expand(template.replace("{num24_6}", dec).replace("{date}", "DATE"));
    }

    public static List<String> ddl(Dialect dialect) {
        List<String> out = new ArrayList<>();
        for (String t : TABLES) {
            out.add(expand(dialect, t.strip()) + dialect.createTableSuffix());
        }
        out.addAll(List.of(INDEXES));
        return out;
    }

    public static List<String> tables() {
        List<String> names = new ArrayList<>();
        for (String t : TABLES) {
            String s = t.strip().substring("CREATE TABLE ".length());
            names.add(s.substring(0, s.indexOf(' ')));
        }
        return names;
    }

    /** Creates the tables (development / tests; production uses Liquibase). */
    public static void create(Connection c, Dialect dialect) {
        for (String s : ddl(dialect)) {
            Jdbc.update(c, s);
        }
    }
}
