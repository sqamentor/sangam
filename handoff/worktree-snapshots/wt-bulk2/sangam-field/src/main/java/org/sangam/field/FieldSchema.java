// SPDX-License-Identifier: Apache-2.0
package org.sangam.field;

import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;

/**
 * Reference DDL of the field tables, in the core {@link Dialect} type tokens (A-15). The integrator turns this into
 * sangam-persistence Liquibase changesets for the four Tier-1 dialects; {@link #create} is for H2/dev and tests.
 * Booleans are SMALLINT 0/1. Every table is tenant-scoped and carries {@code tenant_id} first in its key. Coordinates
 * are BIGINT degrees × 10^7 ({@code lat_e7}/{@code lon_e7}, about 1 cm) so every dialect stores and compares them
 * exactly; accuracy is whole metres, speed cm/s; geometry beyond points is GeoJSON text (sangam-geo).
 */
public final class FieldSchema {

    private FieldSchema() {
    }

    private static final String[] TABLES = {
            // ---------------------------------------------------------------- templates (C03 forms, offline-capable)
            """
            CREATE TABLE FLD_TEMPLATE (tenant_id {str64} NOT NULL, template_key {str64} NOT NULL, version {int} NOT NULL,
              status {str64} NOT NULL, offline_capable {small} NOT NULL, doc_json {text} NOT NULL, content_hash {str64} NOT NULL,
              defined_by {str}, defined_at {ts} NOT NULL, PRIMARY KEY (tenant_id, template_key, version))""",
            // ---------------------------------------------------------------- scheduling and assignment
            """
            CREATE TABLE FLD_ASSIGNMENT (tenant_id {str64} NOT NULL, assignment_id {str64} NOT NULL, template_key {str64} NOT NULL,
              template_version {int} NOT NULL, target_ref {str} NOT NULL, target_label {str}, address {text}, lat_e7 {long}, lon_e7 {long},
              fence_json {text}, assignee {str}, seat_id {str64}, status {str64} NOT NULL, due_at {ts}, window_start {ts}, window_end {ts},
              schedule_id {str64}, selection_id {str64}, task_id {str64}, business_key {str}, submitted_packet_id {str64},
              submitted_device_id {str64}, score_json {text}, attributes_json {text}, version {int} NOT NULL, created_by {str},
              created_at {ts} NOT NULL, updated_at {ts} NOT NULL, PRIMARY KEY (tenant_id, assignment_id))""",
            """
            CREATE TABLE FLD_SCHEDULE (tenant_id {str64} NOT NULL, schedule_id {str64} NOT NULL, template_key {str64} NOT NULL,
              cadence_days {int} NOT NULL, due_days {int} NOT NULL, targets_json {text} NOT NULL, assignee_pool_json {text},
              rotation_exclude_last {int} NOT NULL, sample_size {int} NOT NULL, risk_decision_key {str64}, active {small} NOT NULL,
              next_run_at {ts} NOT NULL, last_run_at {ts}, created_by {str}, created_at {ts} NOT NULL, PRIMARY KEY (tenant_id, schedule_id))""",
            """
            CREATE TABLE FLD_SELECTION (tenant_id {str64} NOT NULL, selection_id {str64} NOT NULL, seed {str} NOT NULL, method {str64} NOT NULL,
              population_hash {str64} NOT NULL, population_size {int} NOT NULL, sample_size {int} NOT NULL, weights_json {text},
              selected_json {text} NOT NULL, drawn_by {str}, drawn_at {ts} NOT NULL, PRIMARY KEY (tenant_id, selection_id))""",
            // ---------------------------------------------------------------- offline sync
            """
            CREATE TABLE FLD_DEVICE (tenant_id {str64} NOT NULL, device_id {str64} NOT NULL, principal_id {str} NOT NULL, unit_id {str64},
              platform {str64},
              app_version {str64}, public_key {text}, key_alg {str64}, status {str64} NOT NULL, policy_json {text}, queue_key_id {str64},
              last_seq {long} NOT NULL, last_packet_hash {str64}, registered_at {ts} NOT NULL, last_seen_at {ts}, status_reason {str},
              status_changed_at {ts}, PRIMARY KEY (tenant_id, device_id))""",
            """
            CREATE TABLE FLD_SYNC_OP (tenant_id {str64} NOT NULL, operation_id {str64} NOT NULL, device_id {str64} NOT NULL,
              packet_type {str64} NOT NULL, request_hash {str64} NOT NULL, status {str64} NOT NULL, result_json {text},
              received_at {ts} NOT NULL, PRIMARY KEY (tenant_id, operation_id))""",
            """
            CREATE TABLE FLD_SEQ (tenant_id {str64} NOT NULL, seq_name {str64} NOT NULL, next_value {long} NOT NULL,
              PRIMARY KEY (tenant_id, seq_name))""",
            """
            CREATE TABLE FLD_CHANGE (tenant_id {str64} NOT NULL, change_seq {long} NOT NULL, entity_kind {str64} NOT NULL,
              entity_id {str64} NOT NULL, principal_id {str}, op {str64} NOT NULL, changed_at {ts} NOT NULL,
              PRIMARY KEY (tenant_id, change_seq))""",
            """
            CREATE TABLE FLD_CONFLICT (tenant_id {str64} NOT NULL, conflict_id {str64} NOT NULL, assignment_id {str64}, operation_id {str64},
              device_id {str64}, kind {str64} NOT NULL, client_json {text}, server_json {text}, status {str64} NOT NULL, resolution {str64},
              resolved_by {str}, resolved_at {ts}, note {text}, created_at {ts} NOT NULL, PRIMARY KEY (tenant_id, conflict_id))""",
            // ---------------------------------------------------------------- capture packets and evidence
            """
            CREATE TABLE FLD_PACKET (tenant_id {str64} NOT NULL, packet_id {str64} NOT NULL, assignment_id {str64}, device_id {str64} NOT NULL,
              operation_id {str64} NOT NULL, packet_type {str64} NOT NULL, device_seq {long} NOT NULL, packet_hash {str64} NOT NULL,
              prev_hash {str64}, signature {text}, signature_ok {small}, device_time {ts}, received_at {ts} NOT NULL, lat_e7 {long},
              lon_e7 {long}, accuracy_m {long}, mock_location {small} NOT NULL, integrity_status {str64} NOT NULL, integrity_json {text},
              doc_json {text} NOT NULL, PRIMARY KEY (tenant_id, packet_id))""",
            """
            CREATE TABLE FLD_EVIDENCE (tenant_id {str64} NOT NULL, evidence_id {str64} NOT NULL, packet_id {str64}, assignment_id {str64},
              item_id {str64}, media_type {str64}, sha256 {str64} NOT NULL, size_bytes {long} NOT NULL, storage_ref {text} NOT NULL,
              exif_json {text}, captured_at {ts}, lat_e7 {long}, lon_e7 {long}, accuracy_m {long}, integrity_status {str64} NOT NULL,
              integrity_json {text}, uploaded_by {str}, uploaded_at {ts} NOT NULL, PRIMARY KEY (tenant_id, evidence_id))""",
            """
            CREATE TABLE FLD_FINDING (tenant_id {str64} NOT NULL, finding_id {str64} NOT NULL, assignment_id {str64} NOT NULL,
              packet_id {str64}, item_id {str64}, severity {str64} NOT NULL, code {str64} NOT NULL, description {text},
              status {str64} NOT NULL, created_at {ts} NOT NULL, PRIMARY KEY (tenant_id, finding_id))""",
            // ---------------------------------------------------------------- dispatch
            """
            CREATE TABLE FLD_UNIT (tenant_id {str64} NOT NULL, unit_id {str64} NOT NULL, kind {str64} NOT NULL, label {str},
              skills_json {text}, home_lat_e7 {long}, home_lon_e7 {long}, status {str64} NOT NULL, last_lat_e7 {long}, last_lon_e7 {long},
              last_accuracy_m {long}, last_fix_at {ts}, current_job_id {str64}, org_unit_ref {str64}, attributes_json {text},
              version {int} NOT NULL, updated_at {ts} NOT NULL, PRIMARY KEY (tenant_id, unit_id))""",
            """
            CREATE TABLE FLD_JOB (tenant_id {str64} NOT NULL, job_id {str64} NOT NULL, kind {str64} NOT NULL, priority {str64} NOT NULL,
              lat_e7 {long} NOT NULL, lon_e7 {long} NOT NULL, address {text}, required_skills_json {text}, status {str64} NOT NULL,
              assigned_unit_id {str64}, declined_json {text}, sla_target_seconds {long} NOT NULL, business_key {str}, created_at {ts} NOT NULL,
              offered_at {ts}, assigned_at {ts}, en_route_at {ts}, on_scene_at {ts}, completed_at {ts}, closed_at {ts}, outcome {str64},
              outcome_note {text}, proof_packet_id {str64}, response_seconds {long}, sla_met {small}, attributes_json {text},
              version {int} NOT NULL, created_by {str}, PRIMARY KEY (tenant_id, job_id))""",
            """
            CREATE TABLE FLD_OFFER (tenant_id {str64} NOT NULL, offer_id {str64} NOT NULL, job_id {str64} NOT NULL, unit_id {str64} NOT NULL,
              status {str64} NOT NULL, offered_at {ts} NOT NULL, expires_at {ts} NOT NULL, responded_at {ts}, distance_m {long},
              eta_seconds {long}, reason {str}, PRIMARY KEY (tenant_id, offer_id))""",
            """
            CREATE TABLE FLD_TRIP (tenant_id {str64} NOT NULL, trip_id {str64} NOT NULL, unit_id {str64} NOT NULL, job_id {str64},
              purpose {str}, status {str64} NOT NULL, started_at {ts} NOT NULL, ended_at {ts}, start_odometer {long}, end_odometer {long},
              track_distance_m {long}, legs_json {text}, approved_by {str}, approved_at {ts}, rejection_reason {text},
              PRIMARY KEY (tenant_id, trip_id))""",
            """
            CREATE TABLE FLD_POSITION (tenant_id {str64} NOT NULL, unit_id {str64} NOT NULL, fix_at {ts} NOT NULL, lat_e7 {long} NOT NULL,
              lon_e7 {long} NOT NULL, accuracy_m {long}, speed_cmps {long}, heading_deg {int}, source {str64}, trip_id {str64},
              PRIMARY KEY (tenant_id, unit_id, fix_at))"""
    };

    private static final String[] INDEXES = {
            "CREATE INDEX FLD_ASSIGN_ASSIGNEE ON FLD_ASSIGNMENT (tenant_id, assignee, status)",
            "CREATE INDEX FLD_ASSIGN_TARGET ON FLD_ASSIGNMENT (tenant_id, target_ref, created_at)",
            "CREATE INDEX FLD_ASSIGN_DUE ON FLD_ASSIGNMENT (tenant_id, status, due_at)",
            "CREATE INDEX FLD_SCHEDULE_DUE ON FLD_SCHEDULE (tenant_id, active, next_run_at)",
            "CREATE INDEX FLD_DEVICE_PRINCIPAL ON FLD_DEVICE (tenant_id, principal_id)",
            "CREATE INDEX FLD_CHANGE_PRINCIPAL ON FLD_CHANGE (tenant_id, principal_id, change_seq)",
            "CREATE INDEX FLD_CONFLICT_STATUS ON FLD_CONFLICT (tenant_id, status, created_at)",
            "CREATE INDEX FLD_PACKET_ASSIGN ON FLD_PACKET (tenant_id, assignment_id)",
            "CREATE INDEX FLD_PACKET_DEVICE ON FLD_PACKET (tenant_id, device_id, device_seq)",
            "CREATE INDEX FLD_EVIDENCE_HASH ON FLD_EVIDENCE (tenant_id, sha256)",
            "CREATE INDEX FLD_EVIDENCE_ASSIGN ON FLD_EVIDENCE (tenant_id, assignment_id)",
            "CREATE INDEX FLD_FINDING_ASSIGN ON FLD_FINDING (tenant_id, assignment_id)",
            "CREATE INDEX FLD_UNIT_STATUS ON FLD_UNIT (tenant_id, status)",
            "CREATE INDEX FLD_JOB_STATUS ON FLD_JOB (tenant_id, status, created_at)",
            "CREATE INDEX FLD_OFFER_JOB ON FLD_OFFER (tenant_id, job_id, status)",
            "CREATE INDEX FLD_OFFER_DUE ON FLD_OFFER (tenant_id, status, expires_at)",
            "CREATE INDEX FLD_TRIP_UNIT ON FLD_TRIP (tenant_id, unit_id, started_at)"
    };

    public static List<String> ddl(Dialect dialect) {
        List<String> out = new ArrayList<>();
        for (String t : TABLES) {
            out.add(dialect.expand(t.strip()) + dialect.createTableSuffix());
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
