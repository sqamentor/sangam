// SPDX-License-Identifier: Apache-2.0
package org.sangam.casefile;

import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;

/**
 * Reference DDL of the case-file, meeting and reference tables, in the core {@link Dialect} type tokens (A-15). The
 * integrator turns this into sangam-persistence Liquibase changesets for the four Tier-1 dialects; {@link #create} is
 * for H2/dev and tests. Booleans are SMALLINT 0/1. Every table is tenant-scoped with {@code tenant_id} first in its key
 * ({@code ""} means "no tenant", as in the other product tables).
 */
public final class CaseFileSchema {

    private CaseFileSchema() {
    }

    private static final String[] TABLES = {
            // ------------------------------------------------------------ configuration (versioned, hash-pinned)
            """
            CREATE TABLE CF_CONFIG (tenant_id {str64} NOT NULL, config_kind {str64} NOT NULL, config_key {str64} NOT NULL, version {int} NOT NULL,
              definition_json {text} NOT NULL, content_hash {str64} NOT NULL, active {small} NOT NULL, defined_by {str}, defined_at {ts} NOT NULL,
              PRIMARY KEY (tenant_id, config_kind, config_key, version))""",
            """
            CREATE TABLE CF_NUMBER_SEQ (tenant_id {str64} NOT NULL, series_key {str} NOT NULL, period_key {str64} NOT NULL, next_value {long} NOT NULL,
              PRIMARY KEY (tenant_id, series_key, period_key))""",
            // ------------------------------------------------------------ case file (N04)
            """
            CREATE TABLE CF_CASE (tenant_id {str64} NOT NULL, case_id {str64} NOT NULL, case_type {str64} NOT NULL, type_version {int} NOT NULL,
              case_number {str} NOT NULL, title {str} NOT NULL, status {str64} NOT NULL, stage {str64}, jurisdiction {str}, org_unit {str64},
              owner_seat {str64}, priority {str64}, confidentiality {str64} NOT NULL, business_key {str}, cmmn_case_instance_id {str64},
              process_instance_id {str64}, merged_into {str64}, split_from {str64}, attributes_json {text}, opened_by {str} NOT NULL,
              opened_at {ts} NOT NULL, closed_at {ts}, closed_by {str}, close_reason {text}, row_version {long} NOT NULL,
              PRIMARY KEY (tenant_id, case_id))""",
            """
            CREATE TABLE CF_PARTY (tenant_id {str64} NOT NULL, party_id {str64} NOT NULL, party_kind {str64} NOT NULL, display_name {str} NOT NULL,
              name_key {str} NOT NULL, identifiers_json {text}, identifier_keys {text}, contact_json {text}, external_ref {str}, created_by {str},
              created_at {ts} NOT NULL, PRIMARY KEY (tenant_id, party_id))""",
            """
            CREATE TABLE CF_PARTY_KEY (tenant_id {str64} NOT NULL, key_hash {str64} NOT NULL, party_id {str64} NOT NULL,
              PRIMARY KEY (tenant_id, key_hash, party_id))""",
            """
            CREATE TABLE CF_CASE_PARTY (tenant_id {str64} NOT NULL, case_party_id {str64} NOT NULL, case_id {str64} NOT NULL, party_id {str64} NOT NULL,
              party_role {str64} NOT NULL, capacity {str}, represented_by {str64}, masked {small} NOT NULL, valid_from {ts} NOT NULL, valid_to {ts},
              added_by {str}, added_at {ts} NOT NULL, removed_by {str}, remove_reason {text}, PRIMARY KEY (tenant_id, case_party_id))""",
            """
            CREATE TABLE CF_CASE_RECORD (tenant_id {str64} NOT NULL, record_id {str64} NOT NULL, case_id {str64} NOT NULL, record_type {str64} NOT NULL,
              record_ref {str} NOT NULL, relation {str64} NOT NULL, title {str}, added_by {str}, added_at {ts} NOT NULL, removed_at {ts},
              PRIMARY KEY (tenant_id, record_id))""",
            """
            CREATE TABLE CF_FOLDER (tenant_id {str64} NOT NULL, folder_id {str64} NOT NULL, case_id {str64} NOT NULL, parent_folder_id {str64},
              name {str} NOT NULL, created_by {str}, created_at {ts} NOT NULL, PRIMARY KEY (tenant_id, folder_id))""",
            """
            CREATE TABLE CF_DOCUMENT (tenant_id {str64} NOT NULL, document_id {str64} NOT NULL, case_id {str64} NOT NULL, folder_id {str64},
              content_item_id {str64} NOT NULL, title {str} NOT NULL, doc_type {str64}, confidentiality {str64} NOT NULL, exhibit {small} NOT NULL,
              exhibit_ref {str}, added_by {str}, added_at {ts} NOT NULL, removed_at {ts}, PRIMARY KEY (tenant_id, document_id))""",
            """
            CREATE TABLE CF_NOTE (tenant_id {str64} NOT NULL, note_id {str64} NOT NULL, case_id {str64} NOT NULL, body {text} NOT NULL,
              visibility {str64} NOT NULL, author {str} NOT NULL, created_at {ts} NOT NULL, supersedes {str64}, superseded {small} NOT NULL,
              PRIMARY KEY (tenant_id, note_id))""",
            """
            CREATE TABLE CF_EVENT (tenant_id {str64} NOT NULL, event_id {str64} NOT NULL, scope_type {str64} NOT NULL, scope_id {str64} NOT NULL,
              event_type {str64} NOT NULL, occurred_at {ts} NOT NULL, actor {str}, summary {str}, payload_json {text}, source {str64} NOT NULL,
              PRIMARY KEY (tenant_id, event_id))""",
            """
            CREATE TABLE CF_HEARING (tenant_id {str64} NOT NULL, hearing_id {str64} NOT NULL, case_id {str64} NOT NULL, kind {str64} NOT NULL,
              forum {str}, scheduled_at {ts} NOT NULL, status {str64} NOT NULL, outcome {str64}, outcome_text {text}, next_date {ts},
              external_ref {str}, recorded_by {str}, recorded_at {ts} NOT NULL, PRIMARY KEY (tenant_id, hearing_id))""",
            """
            CREATE TABLE CF_CASE_LINK (tenant_id {str64} NOT NULL, link_id {str64} NOT NULL, from_case {str64} NOT NULL, to_case {str64} NOT NULL,
              link_type {str64} NOT NULL, reason {text}, created_by {str}, created_at {ts} NOT NULL, removed_at {ts},
              PRIMARY KEY (tenant_id, link_id))""",
            // ------------------------------------------------------------ deadlines (C12) shared by cases, references, actions
            """
            CREATE TABLE CF_DEADLINE (tenant_id {str64} NOT NULL, deadline_id {str64} NOT NULL, scope_type {str64} NOT NULL, scope_id {str64} NOT NULL,
              kind {str64} NOT NULL, owner_seat {str64}, owner_person {str}, started_at {ts} NOT NULL, due_at {ts} NOT NULL, warn_at {ts},
              state {str64} NOT NULL, escalation_level {int} NOT NULL, ladder_json {text}, last_escalated_at {ts}, met_at {ts},
              extension_count {int} NOT NULL, calendar {str64}, zone_id {str64}, next_check_at {ts}, row_version {long} NOT NULL, PRIMARY KEY (tenant_id, deadline_id))""",
            // ------------------------------------------------------------ committees and meetings (N16)
            """
            CREATE TABLE CF_COMMITTEE (tenant_id {str64} NOT NULL, committee_id {str64} NOT NULL, committee_kind {str64} NOT NULL, name {str} NOT NULL,
              org_unit {str64}, constitution_ref {str}, constituted_by {str}, constituted_at {ts} NOT NULL, valid_from {ts} NOT NULL, valid_to {ts},
              quorum_rule {str64} NOT NULL, chair_required {small} NOT NULL, min_notice_hours {int} NOT NULL, terms_json {text}, status {str64} NOT NULL,
              reconstituted_from {str64}, row_version {long} NOT NULL, PRIMARY KEY (tenant_id, committee_id))""",
            """
            CREATE TABLE CF_COMMITTEE_MEMBER (tenant_id {str64} NOT NULL, member_id {str64} NOT NULL, committee_id {str64} NOT NULL,
              person_ref {str}, seat_ref {str64}, display_name {str}, member_role {str64} NOT NULL, voting {small} NOT NULL, valid_from {ts} NOT NULL,
              valid_to {ts}, added_by {str}, PRIMARY KEY (tenant_id, member_id))""",
            """
            CREATE TABLE CF_MEETING (tenant_id {str64} NOT NULL, meeting_id {str64} NOT NULL, committee_id {str64} NOT NULL, meeting_number {str},
              title {str} NOT NULL, scheduled_at {ts} NOT NULL, ends_at {ts}, venue {str}, venue_booking_ref {str}, meeting_mode {str64} NOT NULL,
              status {str64} NOT NULL, quorum_required {int} NOT NULL, quorum_present {int}, chair_member_id {str64}, urgent_reason {text},
              case_id {str64}, adjourned_to {str64}, started_at {ts}, ended_at {ts}, created_by {str}, created_at {ts} NOT NULL,
              row_version {long} NOT NULL, PRIMARY KEY (tenant_id, meeting_id))""",
            """
            CREATE TABLE CF_AGENDA_ITEM (tenant_id {str64} NOT NULL, item_id {str64} NOT NULL, meeting_id {str64} NOT NULL, seq {int} NOT NULL,
              title {str} NOT NULL, description {text}, linked_type {str64}, linked_ref {str}, presenter {str}, status {str64} NOT NULL,
              carried_from {str64}, added_by {str}, added_at {ts} NOT NULL, PRIMARY KEY (tenant_id, item_id))""",
            """
            CREATE TABLE CF_ATTENDANCE (tenant_id {str64} NOT NULL, meeting_id {str64} NOT NULL, attendee_ref {str} NOT NULL, member_id {str64},
              attendance {str64} NOT NULL, voting {small} NOT NULL, proxy_for {str64}, marked_by {str}, marked_at {ts} NOT NULL,
              PRIMARY KEY (tenant_id, meeting_id, attendee_ref))""",
            """
            CREATE TABLE CF_NOTICE (tenant_id {str64} NOT NULL, notice_id {str64} NOT NULL, meeting_id {str64} NOT NULL, notice_kind {str64} NOT NULL,
              recipient_ref {str} NOT NULL, channel {str64}, content_hash {str64} NOT NULL, delivery_ref {str}, sent_by {str}, sent_at {ts} NOT NULL,
              acknowledged_at {ts}, PRIMARY KEY (tenant_id, notice_id))""",
            """
            CREATE TABLE CF_MINUTES (tenant_id {str64} NOT NULL, meeting_id {str64} NOT NULL, version {int} NOT NULL, body {text} NOT NULL,
              status {str64} NOT NULL, content_hash {str64} NOT NULL, drafted_by {str} NOT NULL, drafted_at {ts} NOT NULL, comments_json {text},
              approved_by {str}, approved_at {ts}, esign_ref {str}, circulated_at {ts}, PRIMARY KEY (tenant_id, meeting_id, version))""",
            """
            CREATE TABLE CF_DECISION (tenant_id {str64} NOT NULL, decision_id {str64} NOT NULL, meeting_id {str64} NOT NULL, item_id {str64},
              decision_text {text} NOT NULL, outcome {str64} NOT NULL, votes_for {int} NOT NULL, votes_against {int} NOT NULL, abstentions {int} NOT NULL,
              dissent_json {text}, recorded_by {str}, recorded_at {ts} NOT NULL, PRIMARY KEY (tenant_id, decision_id))""",
            """
            CREATE TABLE CF_ACTION_ITEM (tenant_id {str64} NOT NULL, action_id {str64} NOT NULL, meeting_id {str64}, decision_id {str64},
              committee_id {str64}, title {str} NOT NULL, owner_seat {str64}, owner_person {str}, due_at {ts}, status {str64} NOT NULL,
              atr_text {text}, atr_by {str}, atr_at {ts}, verified_by {str}, verified_at {ts}, return_reason {text}, deadline_id {str64},
              created_by {str}, created_at {ts} NOT NULL, row_version {long} NOT NULL, PRIMARY KEY (tenant_id, action_id))""",
            // ------------------------------------------------------------ references with a deadline (N16)
            """
            CREATE TABLE CF_REFERENCE (tenant_id {str64} NOT NULL, reference_id {str64} NOT NULL, ref_kind {str64} NOT NULL, kind_version {int} NOT NULL,
              ref_number {str} NOT NULL, source_name {str}, source_category {str64}, source_ref {str}, subject {str} NOT NULL, subject_key {str64} NOT NULL,
              body {text}, received_at {ts} NOT NULL, received_via {str64}, priority {str64}, status {str64} NOT NULL, assigned_seat {str64},
              assigned_person {str}, org_unit {str64}, due_at {ts}, deadline_id {str64}, case_id {str64}, parent_reference_id {str64},
              attributes_json {text}, reply_draft {text}, reply_drafted_by {str}, reply_text {text}, reply_ref {str}, replied_by {str},
              replied_at {ts}, reply_approved_by {str}, late {small} NOT NULL, closed_at {ts}, close_reason {text}, created_by {str},
              row_version {long} NOT NULL, PRIMARY KEY (tenant_id, reference_id))""",
            """
            CREATE TABLE CF_REFERENCE_MOVE (tenant_id {str64} NOT NULL, move_id {str64} NOT NULL, reference_id {str64} NOT NULL, from_seat {str64},
              to_seat {str64}, move_kind {str64} NOT NULL, remarks {text}, moved_by {str}, moved_at {ts} NOT NULL, PRIMARY KEY (tenant_id, move_id))"""
    };

    private static final String[] INDEXES = {
            "CREATE UNIQUE INDEX CF_CASE_NUMBER ON CF_CASE (tenant_id, case_number)",
            "CREATE INDEX CF_CASE_TYPE_STATUS ON CF_CASE (tenant_id, case_type, status, opened_at)",
            "CREATE INDEX CF_CASE_CMMN ON CF_CASE (tenant_id, cmmn_case_instance_id)",
            "CREATE INDEX CF_CASE_BK ON CF_CASE (tenant_id, business_key)",
            "CREATE INDEX CF_PARTY_NAME ON CF_PARTY (tenant_id, name_key)",
            "CREATE INDEX CF_PARTY_KEY_P ON CF_PARTY_KEY (tenant_id, party_id)",
            "CREATE INDEX CF_CASE_PARTY_CASE ON CF_CASE_PARTY (tenant_id, case_id)",
            "CREATE INDEX CF_CASE_PARTY_PARTY ON CF_CASE_PARTY (tenant_id, party_id)",
            "CREATE INDEX CF_CASE_RECORD_CASE ON CF_CASE_RECORD (tenant_id, case_id)",
            "CREATE INDEX CF_CASE_RECORD_REF ON CF_CASE_RECORD (tenant_id, record_type, record_ref)",
            "CREATE INDEX CF_FOLDER_CASE ON CF_FOLDER (tenant_id, case_id)",
            "CREATE INDEX CF_DOCUMENT_CASE ON CF_DOCUMENT (tenant_id, case_id)",
            "CREATE INDEX CF_NOTE_CASE ON CF_NOTE (tenant_id, case_id, created_at)",
            "CREATE INDEX CF_EVENT_SCOPE ON CF_EVENT (tenant_id, scope_type, scope_id, occurred_at)",
            "CREATE INDEX CF_HEARING_CASE ON CF_HEARING (tenant_id, case_id)",
            "CREATE INDEX CF_HEARING_LIST ON CF_HEARING (tenant_id, forum, scheduled_at)",
            "CREATE INDEX CF_CASE_LINK_FROM ON CF_CASE_LINK (tenant_id, from_case)",
            "CREATE INDEX CF_CASE_LINK_TO ON CF_CASE_LINK (tenant_id, to_case)",
            "CREATE INDEX CF_DEADLINE_SCOPE ON CF_DEADLINE (tenant_id, scope_type, scope_id)",
            "CREATE INDEX CF_DEADLINE_SWEEP ON CF_DEADLINE (next_check_at)",
            "CREATE INDEX CF_MEMBER_COMMITTEE ON CF_COMMITTEE_MEMBER (tenant_id, committee_id)",
            "CREATE INDEX CF_MEETING_COMMITTEE ON CF_MEETING (tenant_id, committee_id, scheduled_at)",
            "CREATE INDEX CF_AGENDA_MEETING ON CF_AGENDA_ITEM (tenant_id, meeting_id, seq)",
            "CREATE INDEX CF_NOTICE_MEETING ON CF_NOTICE (tenant_id, meeting_id)",
            "CREATE INDEX CF_DECISION_MEETING ON CF_DECISION (tenant_id, meeting_id)",
            "CREATE INDEX CF_ACTION_OWNER ON CF_ACTION_ITEM (tenant_id, owner_seat, status)",
            "CREATE INDEX CF_ACTION_MEETING ON CF_ACTION_ITEM (tenant_id, meeting_id)",
            "CREATE UNIQUE INDEX CF_REFERENCE_NUMBER ON CF_REFERENCE (tenant_id, ref_number)",
            "CREATE INDEX CF_REFERENCE_QUEUE ON CF_REFERENCE (tenant_id, ref_kind, status, due_at)",
            "CREATE INDEX CF_REFERENCE_SEAT ON CF_REFERENCE (tenant_id, assigned_seat, status)",
            "CREATE INDEX CF_REFERENCE_DUP ON CF_REFERENCE (tenant_id, subject_key)",
            "CREATE INDEX CF_REFERENCE_MOVE_REF ON CF_REFERENCE_MOVE (tenant_id, reference_id)"
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
