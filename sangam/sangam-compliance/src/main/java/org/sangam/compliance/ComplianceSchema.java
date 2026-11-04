// SPDX-License-Identifier: Apache-2.0
package org.sangam.compliance;

import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;

/**
 * Reference DDL of the compliance tables, in the core {@link Dialect} type tokens (A-15). The integrator turns this into
 * sangam-persistence Liquibase changesets for the four Tier-1 dialects; {@link #create} is for H2/dev and tests.
 * Booleans are SMALLINT 0/1. Every table is tenant-scoped and carries {@code tenant_id} first in its key.
 */
public final class ComplianceSchema {

    private ComplianceSchema() {
    }

    private static final String[] TABLES = {
            """
            CREATE TABLE CMP_PURPOSE (tenant_id {str64} NOT NULL, purpose_code {str64} NOT NULL, names_json {text}, descriptions_json {text},
              data_items_json {text}, basis {str64} NOT NULL, legal_ref {str}, sub_ground {str64}, register_ref {str}, standards_attested {small} NOT NULL,
              exemptions_json {text}, exemption_ref {str}, child_exemptions_json {text}, flags_json {text}, retention_trigger {str64},
              retention_seconds {long}, retention_class {str64}, record_class {str64}, record_retention_seconds {long}, record_retention_ref {str},
              version {int} NOT NULL, active {small} NOT NULL, defined_by {str}, defined_at {ts} NOT NULL, PRIMARY KEY (tenant_id, purpose_code))""",
            """
            CREATE TABLE CMP_OBJECTION (tenant_id {str64} NOT NULL, principal_ref {str} NOT NULL, purpose_code {str64} NOT NULL, channel {str},
              objected_at {ts} NOT NULL, PRIMARY KEY (tenant_id, principal_ref, purpose_code))""",
            """
            CREATE TABLE CMP_PROCESSING_ACTIVITY (tenant_id {str64} NOT NULL, activity_id {str64} NOT NULL, purpose_code {str64} NOT NULL,
              process_key {str}, description {text}, data_categories_json {text}, processors_json {text}, recipients_json {text},
              storage_country {str64}, transfers_json {text}, automated_decision {small} NOT NULL, algorithmic_software {small} NOT NULL,
              high_risk {small} NOT NULL, updated_by {str}, updated_at {ts} NOT NULL, PRIMARY KEY (tenant_id, activity_id))""",
            """
            CREATE TABLE CMP_DISCLOSURE (tenant_id {str64} NOT NULL, disclosure_id {str64} NOT NULL, principal_ref {str} NOT NULL, recipient {str} NOT NULL,
              recipient_kind {str64} NOT NULL, purpose_code {str64}, data_categories_json {text}, legal_ref {str}, withheld_from_access {small} NOT NULL,
              disclosed_at {ts} NOT NULL, PRIMARY KEY (tenant_id, disclosure_id))""",
            """
            CREATE TABLE CMP_LAWFUL_REQUEST (tenant_id {str64} NOT NULL, request_id {str64} NOT NULL, authority {str} NOT NULL, officer_name {str},
              officer_designation {str}, legal_basis {str64} NOT NULL, reference {str} NOT NULL, scope {text}, status {str64} NOT NULL, received_by {str},
              received_at {ts} NOT NULL, due_at {ts} NOT NULL, late {small} NOT NULL, verified_by {str}, verified_at {ts}, rank_ok {small},
              approved_by {str}, approved_at {ts}, responded_by {str}, responded_at {ts}, response_ref {str}, rejection_reason {text},
              PRIMARY KEY (tenant_id, request_id))""",
            """
            CREATE TABLE CMP_CONTACT (tenant_id {str64} NOT NULL, contact_id {str64} NOT NULL, role {str64} NOT NULL, name {str} NOT NULL, designation {str},
              organisation {str}, email {str} NOT NULL, phone {str64}, mobile {str64}, address {text}, resident {small} NOT NULL, valid_from {ts} NOT NULL,
              valid_to {ts}, set_by {str}, PRIMARY KEY (tenant_id, contact_id))""",
            """
            CREATE TABLE CMP_TIER_STATUS (tenant_id {str64} NOT NULL, designated {small} NOT NULL, notification_ref {str}, notified_on {ts},
              recorded_by {str}, recorded_at {ts} NOT NULL, PRIMARY KEY (tenant_id))""",
            """
            CREATE TABLE CMP_IMPACT_ASSESSMENT (tenant_id {str64} NOT NULL, assessment_id {str64} NOT NULL, activity_id {str64}, scope {text},
              risks_json {text}, measures_json {text}, residual_risk {text}, assessor {str}, independent {small} NOT NULL, assessed_at {ts} NOT NULL,
              next_due_at {ts} NOT NULL, authority_report_ref {str}, evidence_hash {str64}, PRIMARY KEY (tenant_id, assessment_id))""",
            """
            CREATE TABLE CMP_AUDIT_EVIDENCE (tenant_id {str64} NOT NULL, audit_id {str64} NOT NULL, kind {str64} NOT NULL, period_from {ts}, period_to {ts},
              auditor {str}, accreditation_ref {str}, independent {small} NOT NULL, completed_at {ts} NOT NULL, findings_json {text}, report_hash {str64},
              next_due_at {ts} NOT NULL, authority_report_ref {str}, recorded_by {str}, PRIMARY KEY (tenant_id, audit_id))""",
            """
            CREATE TABLE CMP_NOTICE (tenant_id {str64} NOT NULL, notice_key {str64} NOT NULL, version {int} NOT NULL, language {str64} NOT NULL,
              purposes_json {text}, body_text {text}, body_html {text}, content_hash {str64} NOT NULL, published_by {str}, published_at {ts} NOT NULL,
              PRIMARY KEY (tenant_id, notice_key, version, language))""",
            """
            CREATE TABLE CMP_NOTICE_DELIVERY (tenant_id {str64} NOT NULL, delivery_id {str64} NOT NULL, principal_ref {str} NOT NULL, notice_key {str64},
              version {int}, language {str64}, kind {str64} NOT NULL, channel {str}, delivery_ref {str}, delivered_at {ts} NOT NULL,
              PRIMARY KEY (tenant_id, delivery_id))""",
            """
            CREATE TABLE CMP_CONSENT (tenant_id {str64} NOT NULL, consent_id {str64} NOT NULL, principal_ref {str} NOT NULL, notice_key {str64},
              notice_version {int}, language {str64}, status {str64} NOT NULL, channel {str}, given_at {ts} NOT NULL, withdrawn_at {ts},
              consent_manager_id {str64}, cm_artefact_id {str}, guardian_verification_id {str64}, given_by {str}, legacy {small} NOT NULL,
              evidence_hash {str64}, PRIMARY KEY (tenant_id, consent_id))""",
            """
            CREATE TABLE CMP_CONSENT_PURPOSE (tenant_id {str64} NOT NULL, consent_id {str64} NOT NULL, purpose_code {str64} NOT NULL,
              principal_ref {str} NOT NULL, status {str64} NOT NULL, given_at {ts} NOT NULL, withdrawn_at {ts},
              PRIMARY KEY (tenant_id, consent_id, purpose_code))""",
            """
            CREATE TABLE CMP_GUARDIAN_VERIFICATION (tenant_id {str64} NOT NULL, verification_id {str64} NOT NULL, principal_ref {str} NOT NULL,
              guardian_ref {str} NOT NULL, kind {str64} NOT NULL, method {str64} NOT NULL, issuer {str}, token_ref {str}, appointment {str64},
              order_ref {str}, status {str64} NOT NULL, verified_at {ts} NOT NULL, valid_until {str64}, verified_by {str}, detail {text},
              PRIMARY KEY (tenant_id, verification_id))""",
            """
            CREATE TABLE CMP_RETENTION_ITEM (tenant_id {str64} NOT NULL, item_id {str64} NOT NULL, root_id {str64} NOT NULL, principal_ref {str},
              purpose_code {str64} NOT NULL, state {str64} NOT NULL, anchor_at {ts}, last_processed_at {ts}, last_approach_at {ts}, record_created_at {ts},
              erase_due_at {ts}, notice_due_at {ts}, notice_sent_at {ts}, withdrawn {small} NOT NULL, attempts {int} NOT NULL, last_error {str},
              updated_at {ts} NOT NULL, PRIMARY KEY (tenant_id, item_id))""",
            """
            CREATE TABLE CMP_RIGHTS_REQUEST (tenant_id {str64} NOT NULL, request_id {str64} NOT NULL, principal_ref {str} NOT NULL, right_code {str64} NOT NULL,
              category {str64} NOT NULL, status {str64} NOT NULL, identifier_type {str64}, channel {str}, acting_nominee_ref {str}, received_at {ts} NOT NULL,
              due_at {ts} NOT NULL, closed_at {ts}, process_instance_id {str64}, details_json {text}, outcome {text}, outcome_hash {str64},
              response_text {text}, escalated {small} NOT NULL, late {small} NOT NULL, PRIMARY KEY (tenant_id, request_id))""",
            """
            CREATE TABLE CMP_NOMINATION (tenant_id {str64} NOT NULL, nomination_id {str64} NOT NULL, principal_ref {str} NOT NULL, nominee_ref {str} NOT NULL,
              nominee_name {str}, relationship {str64}, contact_ref {str}, status {str64} NOT NULL, registered_at {ts} NOT NULL, revoked_at {ts},
              invoked_at {ts}, invocation_event {str64}, evidence_ref {str}, verified_by {str}, PRIMARY KEY (tenant_id, nomination_id))""",
            """
            CREATE TABLE CMP_INCIDENT (tenant_id {str64} NOT NULL, incident_id {str64} NOT NULL, authority {str64} NOT NULL, incident_type {str64} NOT NULL,
              occurred_at {ts}, noticed_at {ts} NOT NULL, due_at {ts}, warn_at {ts}, status {str64} NOT NULL, reported_at {ts}, late {small} NOT NULL,
              systems_json {text}, description {text}, indicators_json {text}, actions_taken {text}, breach_id {str64}, report_refs_json {text},
              follow_ups {int} NOT NULL, registered_by {str}, registered_at {ts} NOT NULL, process_instance_id {str64},
              PRIMARY KEY (tenant_id, incident_id))""",
            """
            CREATE TABLE CMP_BREACH (tenant_id {str64} NOT NULL, breach_id {str64} NOT NULL, detected_at {ts}, aware_at {ts} NOT NULL, facts_json {text},
              status {str64} NOT NULL, authority_notified_at {ts}, authority_ref {str}, report_due_at {ts}, extension_until {ts}, extension_ref {str},
              reported_at {ts}, report_ref {str}, report_late {small} NOT NULL, subjects_affected {long} NOT NULL, subjects_notified {long} NOT NULL,
              incident_ids_json {text}, registered_by {str}, registered_at {ts} NOT NULL, closed_at {ts}, process_instance_id {str64},
              PRIMARY KEY (tenant_id, breach_id))""",
            """
            CREATE TABLE CMP_BREACH_NOTICE (tenant_id {str64} NOT NULL, breach_id {str64} NOT NULL, principal_ref {str} NOT NULL, delivery_ref {str},
              sent_at {ts}, PRIMARY KEY (tenant_id, breach_id, principal_ref))""",
            """
            CREATE TABLE CMP_BREACH_ROOT (tenant_id {str64} NOT NULL, breach_id {str64} NOT NULL, root_id {str64} NOT NULL,
              PRIMARY KEY (tenant_id, breach_id, root_id))""",
            """
            CREATE TABLE CMP_LOG_SEGMENT (tenant_id {str64} NOT NULL, segment_id {str64} NOT NULL, from_ts {ts} NOT NULL, to_ts {ts} NOT NULL,
              event_count {long} NOT NULL, sha256 {str64} NOT NULL, prev_sha256 {str64}, storage_uri {text} NOT NULL, country {str64}, sealed_at {ts} NOT NULL,
              purge_after {ts} NOT NULL, purged_at {ts}, sealed_by {str}, PRIMARY KEY (tenant_id, segment_id))""",
            """
            CREATE TABLE CMP_LOG_SOURCE (tenant_id {str64} NOT NULL, source_id {str64} NOT NULL, kind {str64}, description {text}, country {str64},
              retention_days {int} NOT NULL, forwarder {str}, updated_by {str}, updated_at {ts} NOT NULL, PRIMARY KEY (tenant_id, source_id))""",
            """
            CREATE TABLE CMP_NTP_CHECK (tenant_id {str64} NOT NULL, check_id {str64} NOT NULL, checked_at {ts} NOT NULL, ok {small} NOT NULL,
              jvm_offset_ms {long}, db_offset_ms {long}, best_server {str}, results_json {text}, detail {str}, PRIMARY KEY (tenant_id, check_id))""",
            """
            CREATE TABLE CMP_TRANSFER_DECISION (tenant_id {str64} NOT NULL, decision_id {str64} NOT NULL, destination_country {str64}, recipient {str},
              purpose_code {str64}, data_categories_json {text}, approval_ref {str}, allowed {small} NOT NULL, rule_id {str64} NOT NULL, reason {text},
              decided_by {str}, decided_at {ts} NOT NULL, PRIMARY KEY (tenant_id, decision_id))""",
            """
            CREATE TABLE CMP_REPORT (tenant_id {str64} NOT NULL, report_id {str64} NOT NULL, period_from {ts} NOT NULL, period_to {ts} NOT NULL,
              report_hash {str64} NOT NULL, generated_by {str}, generated_at {ts} NOT NULL, PRIMARY KEY (tenant_id, report_id))"""
    };

    private static final String[] INDEXES = {
            "CREATE INDEX CMP_CONSENT_PRINCIPAL ON CMP_CONSENT (tenant_id, principal_ref)",
            "CREATE INDEX CMP_CONSENT_ARTEFACT ON CMP_CONSENT (tenant_id, consent_manager_id, cm_artefact_id)",
            "CREATE INDEX CMP_CONSENT_PURPOSE_P ON CMP_CONSENT_PURPOSE (tenant_id, principal_ref, purpose_code, status)",
            "CREATE INDEX CMP_DISCLOSURE_P ON CMP_DISCLOSURE (tenant_id, principal_ref)",
            "CREATE INDEX CMP_RETENTION_ROOT ON CMP_RETENTION_ITEM (tenant_id, root_id, purpose_code)",
            "CREATE INDEX CMP_RETENTION_DUE ON CMP_RETENTION_ITEM (tenant_id, state, erase_due_at)",
            "CREATE INDEX CMP_RETENTION_P ON CMP_RETENTION_ITEM (tenant_id, principal_ref)",
            "CREATE INDEX CMP_RIGHTS_P ON CMP_RIGHTS_REQUEST (tenant_id, principal_ref)",
            "CREATE INDEX CMP_RIGHTS_DUE ON CMP_RIGHTS_REQUEST (tenant_id, closed_at, due_at)",
            "CREATE INDEX CMP_NOMINATION_P ON CMP_NOMINATION (tenant_id, principal_ref)",
            "CREATE INDEX CMP_GUARDIAN_P ON CMP_GUARDIAN_VERIFICATION (tenant_id, principal_ref)",
            "CREATE INDEX CMP_CONTACT_ROLE ON CMP_CONTACT (tenant_id, role, valid_to)",
            "CREATE INDEX CMP_NOTICE_DELIVERY_P ON CMP_NOTICE_DELIVERY (tenant_id, principal_ref, kind)",
            "CREATE INDEX CMP_INCIDENT_DUE ON CMP_INCIDENT (tenant_id, status, due_at)",
            "CREATE INDEX CMP_LOG_SEGMENT_TO ON CMP_LOG_SEGMENT (tenant_id, to_ts)",
            "CREATE INDEX CMP_AUDIT_EVIDENCE_K ON CMP_AUDIT_EVIDENCE (tenant_id, kind, completed_at)"
    };

    public static List<String> ddl(Dialect dialect) {
        List<String> out = new ArrayList<>();
        for (String t : TABLES) {
            out.add(dialect.expand(t.strip()) + dialect.createTableSuffix());
        }
        for (String i : INDEXES) {
            out.add(i);
        }
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
