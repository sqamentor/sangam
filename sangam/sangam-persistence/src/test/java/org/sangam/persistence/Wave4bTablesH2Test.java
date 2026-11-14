// SPDX-License-Identifier: Apache-2.0
package org.sangam.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Wave 4b generator changesets: SEC_SCIM_RESOURCE (0044-01, decision 154), PROCESS_CONTINUATION (0044-02, decision
 * 149), OPS_FMIG_RUN and OPS_FMIG_ITEM (0044-03/04, decision 169), the HRM record_source and legacy_ref alters
 * (0044-05..07, decision 160), the PostgreSQL-only pgvector column (0044-08, decision 170) and the imported
 * LedgerSchema tables BILL_UNVERIFIED_PAYMENT (0019-06, decision 141) and BILL_UNVERIFIED_ACCRUAL_PAUSE (0019-07,
 * decision 195) with the alter 0045-03 (resolution_evidence_ref, BILL_UNVERIFIED_CONSUMER; decision 196), and
 * INTEGRATION_DSC_PREPARED (0045-04, decision 165), INTEGRATION_DSC_CERTIFICATE (0045-08, decision 227), and the UTC
 * conversion gate OPS_UTC_GATE with its seeded row (0045-13/14, integrator ruling 2026-11-13 option C). Keys and
 * CHECKs are
 * exercised on H2; the dialect texts are read from the generated changelogs. Oracle and SQL Server application of the
 * same changesets runs in the db-matrix (NOT_RUN in this coding phase).
 */
class Wave4bTablesH2Test {

    static Connection schema() throws Exception {
        Connection c = DriverManager.getConnection("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        SangamSchema.apply(c, SangamSchema.MASTER_CHANGELOG, null);
        return c;
    }

    static int exec(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            return ps.executeUpdate();
        }
    }

    static String one(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), sql);
                return rs.getString(1);
            }
        }
    }

    static void scim(Connection c, String type, String id, String nameKey, String externalKey) throws SQLException {
        exec(c, "INSERT INTO SEC_SCIM_RESOURCE(tenant_id, resource_type, resource_id, user_name, display_name, name_key,"
                + " external_id, external_key, attributes, version, created_at, updated_at)"
                + " VALUES ('t1', ?, ?, NULL, NULL, ?, NULL, ?, NULL, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                type, id, nameKey, externalKey);
    }

    @Test
    void scimNamesAndExternalIdsAreUniquePerTenantAndType() throws Exception {
        try (Connection c = schema()) {
            scim(c, "USER", "p1", "alice", "x:ext-1");
            scim(c, "GROUP", "g1", "alice", "x:ext-1"); // another resource type: allowed
            assertThrows(SQLException.class, () -> scim(c, "USER", "p2", "alice", "id:p2"), "userName 409 uniqueness");
            assertThrows(SQLException.class, () -> scim(c, "USER", "p3", "bob", "x:ext-1"), "externalId 409 uniqueness");
            assertThrows(SQLException.class, () -> scim(c, "USER", "p1", "carol", "id:p1"), "primary key");
            assertThrows(SQLException.class, () -> scim(c, "POSTING", "s1", "s1", "id:s1"), "only USER and GROUP");
            assertThrows(SQLException.class, () -> scim(c, "USER", "p4", null, "id:p4"), "name_key is NOT NULL");
        }
    }

    static void continuation(Connection c, String request, String pred, String succ, int generation) throws SQLException {
        exec(c, "INSERT INTO PROCESS_CONTINUATION(tenant_id, request_id, predecessor_id, successor_id, chain_root_id,"
                + " generation, definition_id, business_key, actor_id, reason, created_at)"
                + " VALUES ('t1', ?, ?, ?, 'root', ?, 'loan:3:abc', NULL, 'alice', 'yearly renewal', CURRENT_TIMESTAMP)",
                request, pred, succ, generation);
    }

    @Test
    void oneSuccessorPerPredecessorAndOneRowPerRequest() throws Exception {
        try (Connection c = schema()) {
            continuation(c, "r1", "root", "i2", 1);
            continuation(c, "r2", "i2", "i3", 2);
            assertThrows(SQLException.class, () -> continuation(c, "r1", "x", "y", 9), "a retried request id");
            assertThrows(SQLException.class, () -> continuation(c, "r3", "root", "i9", 1), "second successor");
            assertThrows(SQLException.class, () -> continuation(c, "r4", "i8", "i3", 3), "successor reused");
            assertEquals("i3", one(c, "SELECT successor_id FROM PROCESS_CONTINUATION WHERE tenant_id = 't1'"
                    + " AND chain_root_id = 'root' AND generation = 2"));
        }
    }

    @Test
    void flowableMigrationRunCountersDefaultToZeroAndItemsAreKeyedBySourceInstance() throws Exception {
        try (Connection c = schema()) {
            exec(c, "INSERT INTO OPS_FMIG_RUN(run_id, tenant_id, source_system, source_zone, state, mapping_json, version,"
                    + " created_by, created_at, updated_at) VALUES ('run1', 't1', 'flowable', 'Asia/Kolkata', 'DRY_RUN', '{}', 1,"
                    + " 'ops', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            assertEquals("0", one(c, "SELECT planned + rejected + applied + failed + matched + mismatched FROM OPS_FMIG_RUN"));
            String item = "INSERT INTO OPS_FMIG_ITEM(run_id, source_instance_id, tenant_id, status, plan_json, fingerprint,"
                    + " updated_at) VALUES ('run1', 'pi-1', 't1', 'PLANNED', '{}', 'f1', CURRENT_TIMESTAMP)";
            exec(c, item);
            assertEquals("0", one(c, "SELECT attempts FROM OPS_FMIG_ITEM WHERE source_instance_id = 'pi-1'"));
            assertThrows(SQLException.class, () -> exec(c, item), "one item per run and source instance");
        }
    }

    @Test
    void readModelRowsAreEngineByDefaultAndOnlyEngineOrLegacy() throws Exception {
        try (Connection c = schema()) {
            exec(c, "INSERT INTO HRM_PROCESS_INSTANCE(id, tenant_id, scope_type, state, last_seq) VALUES ('live', 't1', 'process', 'active', 1)");
            assertEquals("ENGINE", one(c, "SELECT record_source FROM HRM_PROCESS_INSTANCE WHERE id = 'live'"),
                    "rows of the live projector (no column named) are ENGINE");
            exec(c, "INSERT INTO HRM_PROCESS_INSTANCE(id, tenant_id, scope_type, state, last_seq, record_source, legacy_ref)"
                    + " VALUES ('lg-1', 't1', 'process', 'completed', 1, 'LEGACY', 'job1:ACT_HI_PROCINST:42')");
            assertEquals("job1:ACT_HI_PROCINST:42", one(c, "SELECT legacy_ref FROM HRM_PROCESS_INSTANCE WHERE id = 'lg-1'"));
            for (String table : new String[]{"HRM_PROCESS_INSTANCE", "HRM_ACTIVITY_INSTANCE", "HRM_TASK"}) {
                try (ResultSet rs = c.getMetaData().getColumns(null, null, table, "RECORD_SOURCE")) {
                    assertTrue(rs.next(), table);
                    assertEquals("NO", rs.getString("IS_NULLABLE"), table);
                }
            }
            assertThrows(SQLException.class, () -> exec(c, "INSERT INTO HRM_TASK(id, tenant_id, record_source)"
                    + " VALUES ('x', 't1', 'IMPORTED')"), "the CHECK refuses other markers");
            exec(c, "INSERT INTO HRM_ACTIVITY_INSTANCE(id, tenant_id, state) VALUES ('a1', 't1', 'open')");
            assertEquals("ENGINE", one(c, "SELECT record_source FROM HRM_ACTIVITY_INSTANCE WHERE id = 'a1'"));
        }
    }

    static void unverified(Connection c, String paymentId, String channel, String ref) throws SQLException {
        exec(c, "INSERT INTO BILL_UNVERIFIED_PAYMENT(tenant_id, payment_id, ledger_id, consumer, office_id, channel,"
                + " payment_ref, amount, currency, paid_at, details_json, reason, evidence_ref, policy_version,"
                + " downstream_json, accepted_by, accepted_at, state, version) VALUES ('t1', ?, 'l1', 'c1', 'o1', ?, ?,"
                + " 150050, 'INR', CURRENT_TIMESTAMP, '{}', 'bank slip', 'ev-1', 'p1', '{}', 'clerk',"
                + " CURRENT_TIMESTAMP, 'PENDING', 1)", paymentId, channel, ref);
    }

    @Test
    void unverifiedPaymentIsOnePerTenantChannelAndReference() throws Exception {
        try (Connection c = schema()) {
            unverified(c, "u1", "UPI", "ref-1");
            unverified(c, "u2", "NEFT", "ref-1"); // another channel: allowed
            assertThrows(SQLException.class, () -> unverified(c, "u3", "UPI", "ref-1"), "BILL_UNVERIFIED_REF");
            assertThrows(SQLException.class, () -> unverified(c, "u1", "CASH", "ref-9"), "primary key");
            assertEquals("150050", one(c, "SELECT amount FROM BILL_UNVERIFIED_PAYMENT WHERE payment_id = 'u1'"),
                    "money is whole minor units");
        }
        assertTrue(read("db/changelog/oracle/sangam-0001-baseline.sql")
                .contains("--changeset sangam:0019-06-bill_unverified_payment dbms:oracle"));
    }

    static void pause(Connection c, String paymentId, String account, String asOf) throws SQLException {
        exec(c, "INSERT INTO BILL_UNVERIFIED_ACCRUAL_PAUSE(tenant_id, payment_id, base_account_id, as_of, paused_from,"
                + " ledger_id, period, line_code, consumer, stage, amount, currency, recorded_at) VALUES ('t1', ?, ?, ?,"
                + " '2026-10-01', 'l1', '2026-27', 'WATER', 'c1', 'BILLED', 1250, 'INR', CURRENT_TIMESTAMP)",
                paymentId, account, asOf);
    }

    /** W4-A REQUEST 01:12:21Z: 0019-07 (decision 195) and 0045-03 (decision 196), shapes from LedgerSchema. */
    @Test
    void accrualPausesAreOnePerPaymentAccountAndDateAndEvidenceIsOptional() throws Exception {
        try (Connection c = schema()) {
            unverified(c, "u1", "UPI", "ref-1");
            assertEquals(null, one(c, "SELECT resolution_evidence_ref FROM BILL_UNVERIFIED_PAYMENT WHERE payment_id = 'u1'"),
                    "existing and non-manual resolutions keep NULL");
            exec(c, "UPDATE BILL_UNVERIFIED_PAYMENT SET resolution_evidence_ref = 'ev-manual-1' WHERE payment_id = 'u1'");
            pause(c, "u1", "acc-1", "2026-11-01");
            pause(c, "u1", "acc-1", "2026-11-02"); // next levy date: a row of its own
            pause(c, "u1", "acc-2", "2026-11-01"); // another base account
            assertThrows(SQLException.class, () -> pause(c, "u1", "acc-1", "2026-11-01"), "primary key");
            assertEquals(null, one(c, "SELECT resumed_at FROM BILL_UNVERIFIED_ACCRUAL_PAUSE WHERE as_of = '2026-11-02'"));
            assertEquals("1250", one(c, "SELECT amount FROM BILL_UNVERIFIED_ACCRUAL_PAUSE WHERE as_of = '2026-11-02'"));
            try (ResultSet rs = c.getMetaData().getIndexInfo(null, null, "BILL_UNVERIFIED_PAYMENT", false, false)) {
                boolean found = false;
                while (rs.next()) {
                    found |= "BILL_UNVERIFIED_CONSUMER".equalsIgnoreCase(rs.getString("INDEX_NAME"));
                }
                assertTrue(found, "BILL_UNVERIFIED_CONSUMER");
            }
        }
        for (String dbms : new String[]{"postgresql", "oracle", "mssql", "mysql", "h2"}) {
            String sql = read("db/changelog/" + dbms + "/sangam-0001-baseline.sql");
            int table = sql.indexOf("--changeset sangam:0019-07-bill_unverified_accrual_pause dbms:" + dbms);
            int alter = sql.indexOf("--changeset sangam:0045-03-bill_unverified_payment-alter dbms:" + dbms);
            assertTrue(table > sql.indexOf("--changeset sangam:0019-06-bill_unverified_payment dbms:" + dbms), dbms + " 0019-07");
            assertTrue(alter > table, dbms + " 0045-03");
            assertTrue(sql.contains("CREATE INDEX BILL_UNVERIFIED_CONSUMER ON BILL_UNVERIFIED_PAYMENT (tenant_id, ledger_id,"
                    + " consumer, state);"), dbms);
            int create = sql.indexOf("CREATE TABLE BILL_UNVERIFIED_PAYMENT");
            assertFalse(sql.substring(create, sql.indexOf("--rollback", create)).contains("resolution_evidence_ref"),
                    dbms + ": 0019-06 is unchanged; the column comes from 0045-03");
        }
    }

    static void prepared(Connection c, String tenant, String prepareId, String task) throws SQLException {
        exec(c, "INSERT INTO INTEGRATION_DSC_PREPARED(tenant_id, prepare_id, task_id, document_id, document_version,"
                + " document_sha256, principal_id, signer_cert_sha256, intended_signer_name, algorithm, prepared_digest_sha256,"
                + " signed_attributes, signing_time, prepared_pdf, created_at, expires_at, enrolment_id,"
                + " enrolment_approval_seq) VALUES (?, ?, ?, 'd1', 3, 'aa', 'p1', 'bb', 'A Signer', 'SHA256withRSA', 'cc', ?,"
                + " CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'e1', 1)", tenant, prepareId, task,
                new byte[]{0x31, 0x00}, new byte[]{0x25, 0x50});
    }

    /** W4-H REQUEST 01:24:24Z: 0045-04 (decision 165) exactly as sangam-integration db/sangam-dsc-reference.sql. */
    @Test
    void dscPreparedIsOnePerTenantAndPrepareIdWithTheReferenceColumns() throws Exception {
        try (Connection c = schema()) {
            prepared(c, "t1", "p-1", "task-1");
            prepared(c, "t2", "p-1", "task-1"); // another tenant
            assertThrows(SQLException.class, () -> prepared(c, "t1", "p-1", "task-2"), "a second prepare_id is refused");
            assertEquals(1, exec(c, "UPDATE INTEGRATION_DSC_PREPARED SET used_at = CURRENT_TIMESTAMP, prepared_pdf = NULL,"
                    + " outcome = 'VERIFIED_VALID', reason_code = NULL, signed_document_version = 4, completed_at ="
                    + " CURRENT_TIMESTAMP WHERE tenant_id = 't1' AND prepare_id = 'p-1' AND used_at IS NULL"));
            assertEquals(0, exec(c, "UPDATE INTEGRATION_DSC_PREPARED SET used_at = CURRENT_TIMESTAMP WHERE tenant_id = 't1'"
                    + " AND prepare_id = 'p-1' AND used_at IS NULL"), "used at most once");
            assertThrows(SQLException.class, () -> exec(c, "UPDATE INTEGRATION_DSC_PREPARED SET signed_attributes = NULL"),
                    "signed_attributes NOT NULL");
            java.util.List<String> cols = new java.util.ArrayList<>();
            try (ResultSet rs = c.getMetaData().getColumns(null, null, "INTEGRATION_DSC_PREPARED", null)) {
                while (rs.next()) {
                    cols.add(rs.getString("COLUMN_NAME").toLowerCase(java.util.Locale.ROOT));
                }
            }
            assertEquals(java.util.List.of("tenant_id", "prepare_id", "task_id", "document_id", "document_version",
                    "document_sha256", "principal_id", "signer_cert_sha256", "intended_signer_name", "algorithm",
                    "prepared_digest_sha256", "signed_attributes", "signing_time", "prepared_pdf", "created_at", "expires_at",
                    "used_at", "outcome", "reason_code", "signed_document_version", "completed_at"), cols);
        }
        for (String dbms : new String[]{"postgresql", "oracle", "mssql", "mysql", "h2"}) {
            String sql = read("db/changelog/" + dbms + "/sangam-0001-baseline.sql");
            assertTrue(sql.contains("--changeset sangam:0045-04-integration_dsc_prepared dbms:" + dbms), dbms);
            assertTrue(sql.contains("CREATE INDEX INTEGRATION_DSC_PREPARED_TASK ON INTEGRATION_DSC_PREPARED (tenant_id,"
                    + " task_id);"), dbms);
            assertTrue(sql.contains("CREATE INDEX INTEGRATION_DSC_PREPARED_EXPIRY ON INTEGRATION_DSC_PREPARED (expires_at);"),
                    dbms);
        }
    }

    /**
     * W4-E REQUESTs 01:18:42Z and 01:02:45Z and decision 231: EXEC_RESIZE (0045-05), SHARED_DEPLOYMENT (0045-06) and
     * OPS_FMIG_ACK (0045-07).
     */
    @Test
    void resizeRegistryAndListenerAcknowledgementAreKeyedAsRequested() throws Exception {
        try (Connection c = schema()) {
            String resize = "INSERT INTO EXEC_RESIZE(resize_id, cluster_id, state, from_count, to_count, from_map_epoch,"
                    + " to_map_epoch, algorithm_version, actor_id, reason, requested_at) VALUES (?, 'c1', 'REQUESTED', 8, 12, 4,"
                    + " 5, 'MM-1', 'platform:ops', 'peak load', CURRENT_TIMESTAMP)";
            exec(c, resize, "r1");
            assertThrows(SQLException.class, () -> exec(c, resize, "r1"), "one row per resize id");
            assertEquals(null, one(c, "SELECT handover_pause_ms FROM EXEC_RESIZE WHERE resize_id = 'r1'"));

            String shared = "INSERT INTO SHARED_DEPLOYMENT(deployment_id, registered_by, reason, registered_at)"
                    + " VALUES (?, 'platform:deployer', 'shared approval model', CURRENT_TIMESTAMP)";
            exec(c, shared, "dep-1");
            assertThrows(SQLException.class, () -> exec(c, shared, "dep-1"), "one row per deployment id");
            try (ResultSet rs = c.getMetaData().getColumns(null, null, "SHARED_DEPLOYMENT", "TENANT_ID")) {
                assertFalse(rs.next(), "platform scope: no tenant column");
            }

            String ack = "INSERT INTO OPS_FMIG_ACK(tenant_id, source_system, definition_id, definition_key, definition_version,"
                    + " listeners_sha256, listeners_json, reason, acknowledged_by, acknowledged_at) VALUES ('t1', ?, 'order:1:100',"
                    + " 'order', 1, 'ab', '{}', 'end on Flowable with listeners disabled', 'ops', CURRENT_TIMESTAMP)";
            exec(c, ack, "flowable-a");
            exec(c, ack, "flowable-b"); // the same definition id in another source system
            assertThrows(SQLException.class, () -> exec(c, ack, "flowable-a"), "one acknowledgement per definition");
        }
        for (String dbms : new String[]{"postgresql", "oracle", "mssql", "mysql", "h2"}) {
            String sql = read("db/changelog/" + dbms + "/sangam-0001-baseline.sql");
            for (String id : new String[]{"0045-05-exec_resize", "0045-06-shared_deployment", "0045-07-ops_fmig_ack"}) {
                assertTrue(sql.contains("--changeset sangam:" + id + " dbms:" + dbms), dbms + " " + id);
            }
            assertTrue(sql.contains("CREATE INDEX EXEC_RESIZE_STATE ON EXEC_RESIZE (cluster_id, state);"), dbms);
        }
    }

    /** Owner decision 240: the declared historical session zone of a UTC run is a nullable alter (0045-09). */
    @Test
    void utcRunCarriesTheDeclaredSessionZoneAsAnAlter() throws Exception {
        try (Connection c = schema(); ResultSet rs = c.getMetaData().getColumns(null, null, "OPS_UTC_RUN", "SESSION_ZONE")) {
            assertTrue(rs.next(), "OPS_UTC_RUN.session_zone");
            assertEquals(64, rs.getInt("COLUMN_SIZE"));
            assertEquals(java.sql.DatabaseMetaData.columnNullable, rs.getInt("NULLABLE"), "NULL for runs without one");
        }
        for (String dbms : new String[]{"postgresql", "oracle", "mssql", "mysql", "h2"}) {
            String sql = read("db/changelog/" + dbms + "/sangam-0001-baseline.sql");
            int table = sql.indexOf("--changeset sangam:0045-01-ops_utc_run dbms:" + dbms);
            int alter = sql.indexOf("--changeset sangam:0045-09-ops_utc_run-alter dbms:" + dbms);
            assertTrue(table > 0 && alter > table, dbms + " 0045-09 after 0045-01");
            assertFalse(sql.substring(table, sql.indexOf("--rollback", table)).contains("session_zone"),
                    dbms + ": 0045-01 is unchanged; the column comes from 0045-09");
        }
    }

    /** Decision 227 (W4-H REQUEST 02:15:49Z): keyed exactly as sangam-dsc-enrolment-reference.sql. */
    @Test
    void dscCertificateEnrolmentIsKeyedAsTheReferenceDdl() throws Exception {
        try (Connection c = schema()) {
            String enrol = "INSERT INTO INTEGRATION_DSC_CERTIFICATE(tenant_id, certificate_sha256, enrolment_id, principal_id,"
                    + " issuer_dn, serial_hex, subject_dn, not_before, not_after, certificate, status, enrolled_at)"
                    + " VALUES (?, ?, ?, 'signer-1', 'CN=CA', '0a', 'CN=Signer', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,"
                    + " X'3082', 'PENDING', CURRENT_TIMESTAMP)";
            exec(c, enrol, "t1", "aa", "e1");
            assertThrows(SQLException.class, () -> exec(c, enrol, "t1", "aa", "e2"), "one row per tenant and certificate");
            assertThrows(SQLException.class, () -> exec(c, enrol, "t1", "bb", "e1"), "enrolment id unique per tenant");
            exec(c, enrol, "t2", "aa", "e1"); // the same certificate and enrolment id in another tenant
            assertEquals(1, exec(c, "UPDATE INTEGRATION_DSC_CERTIFICATE SET status = 'APPROVED', decided_by = 'admin-1',"
                    + " decided_at = CURRENT_TIMESTAMP WHERE tenant_id = 't1' AND enrolment_id = 'e1' AND status = 'PENDING'"));
            assertEquals(0, exec(c, "UPDATE INTEGRATION_DSC_CERTIFICATE SET status = 'APPROVED' WHERE tenant_id = 't1'"
                    + " AND enrolment_id = 'e1' AND status = 'PENDING'"), "the conditional update decides once");
            assertThrows(SQLException.class, () -> exec(c, "UPDATE INTEGRATION_DSC_CERTIFICATE SET certificate = NULL"),
                    "the DER is required");
            try (ResultSet rs = c.getMetaData().getColumns(null, null, "INTEGRATION_DSC_CERTIFICATE", "ISSUER_DN")) {
                assertTrue(rs.next());
                assertEquals(1024, rs.getInt("COLUMN_SIZE"));
            }
        }
        for (String dbms : new String[]{"postgresql", "oracle", "mssql", "mysql", "h2"}) {
            String sql = read("db/changelog/" + dbms + "/sangam-0001-baseline.sql");
            assertTrue(sql.contains("--changeset sangam:0045-08-integration_dsc_certificate dbms:" + dbms), dbms);
            assertTrue(sql.contains("CREATE UNIQUE INDEX INTEGRATION_DSC_CERTIFICATE_ID ON INTEGRATION_DSC_CERTIFICATE"
                    + " (tenant_id, enrolment_id);"), dbms);
            assertTrue(sql.contains("CREATE INDEX INTEGRATION_DSC_CERTIFICATE_PRINCIPAL ON INTEGRATION_DSC_CERTIFICATE"
                    + " (tenant_id, principal_id);"), dbms);
            assertTrue(sql.contains("CREATE INDEX INTEGRATION_DSC_CERTIFICATE_STATUS ON INTEGRATION_DSC_CERTIFICATE"
                    + " (tenant_id, status);"), dbms);
        }
    }

    /** Decision 241 (W4-H REQUEST 03:49:34Z): the identity binding columns, alters 0045-11 and 0045-12. */
    @Test
    void dscIdentityBindingColumnsAreNotNullAlters() throws Exception {
        try (Connection c = schema()) {
            exec(c, "INSERT INTO INTEGRATION_DSC_CERTIFICATE(tenant_id, certificate_sha256, enrolment_id, principal_id,"
                    + " issuer_dn, serial_hex, subject_dn, not_before, not_after, certificate, status, enrolled_at)"
                    + " VALUES ('t1', 'aa', 'e1', 'signer-1', 'CN=CA', '0a', 'CN=Signer', CURRENT_TIMESTAMP,"
                    + " CURRENT_TIMESTAMP, X'3082', 'PENDING', CURRENT_TIMESTAMP)");
            assertEquals("0", one(c, "SELECT approval_seq FROM INTEGRATION_DSC_CERTIFICATE WHERE enrolment_id = 'e1'"));
            assertEquals(1, exec(c, "UPDATE INTEGRATION_DSC_CERTIFICATE SET status = 'APPROVED', approval_seq = approval_seq + 1"
                    + " WHERE tenant_id = 't1' AND enrolment_id = 'e1' AND status = 'PENDING'"));
            assertThrows(SQLException.class, () -> exec(c, "UPDATE INTEGRATION_DSC_CERTIFICATE SET approval_seq = NULL"));
            prepared(c, "t1", "p1", "task-1");
            assertEquals("e1 1", one(c, "SELECT enrolment_id || ' ' || enrolment_approval_seq FROM INTEGRATION_DSC_PREPARED"
                    + " WHERE prepare_id = 'p1'"));
            assertThrows(SQLException.class, () -> exec(c, "UPDATE INTEGRATION_DSC_PREPARED SET enrolment_id = NULL"));
            assertThrows(SQLException.class, () -> exec(c, "UPDATE INTEGRATION_DSC_PREPARED SET enrolment_approval_seq = NULL"));
        }
        for (String dbms : new String[]{"postgresql", "oracle", "mssql", "mysql", "h2"}) {
            String sql = read("db/changelog/" + dbms + "/sangam-0001-baseline.sql");
            int cert = sql.indexOf("--changeset sangam:0045-08-integration_dsc_certificate dbms:" + dbms);
            int prep = sql.indexOf("--changeset sangam:0045-04-integration_dsc_prepared dbms:" + dbms);
            int a11 = sql.indexOf("--changeset sangam:0045-11-integration_dsc_certificate-alter dbms:" + dbms);
            int a12 = sql.indexOf("--changeset sangam:0045-12-integration_dsc_prepared-alter dbms:" + dbms);
            assertTrue(cert > 0 && prep > 0 && a11 > cert && a11 > prep && a12 > a11, dbms + " order");
            String alter11 = sql.substring(a11, sql.indexOf("--rollback", a11));
            assertTrue(alter11.contains("approval_seq") && alter11.contains("DEFAULT 0 NOT NULL"), alter11);
            String alter12 = sql.substring(a12, sql.indexOf("--rollback", a12));
            assertTrue(alter12.contains("enrolment_id") && alter12.contains("enrolment_approval_seq"), alter12);
            assertFalse(alter12.contains("DEFAULT"), "no backfill: " + alter12);
            assertTrue(alter12.split("NOT NULL", -1).length - 1 == 2, alter12);
            assertFalse(sql.substring(cert, sql.indexOf("--rollback", cert)).contains("approval_seq"), dbms + " 0045-08 unchanged");
        }
        String mssql = read("db/changelog/mssql/sangam-0001-baseline.sql");
        int at = mssql.indexOf("--changeset sangam:0045-11-integration_dsc_certificate-alter dbms:mssql");
        String rollback = mssql.substring(mssql.indexOf("--rollback", at), mssql.indexOf("\n", mssql.indexOf("--rollback", at)));
        assertTrue(rollback.contains("DROP CONSTRAINT DF_INTEGRATION_DSC_CERTIFICATE;")
                && rollback.indexOf("DROP CONSTRAINT") < rollback.indexOf("DROP COLUMN approval_seq"), rollback);
    }

    /** Owner decision 247: the Oracle VECTOR column only in context oracle-vector, outside the master changelog. */
    @Test
    void oracleVectorColumnIsOnlyInItsOwnContextChangelog() throws Exception {
        String cs = read("db/changelog/oracle-vector/oracle.sql");
        assertTrue(cs.startsWith("--liquibase formatted sql"), cs);
        assertTrue(cs.contains("--changeset sangam:0045-10-ai_kb_chunk-oracle-vector dbms:oracle context:oracle-vector\n"), cs);
        assertTrue(cs.contains("\nALTER TABLE AI_KB_CHUNK ADD (embedding_vec VECTOR);\n"), "no dimension, no format: " + cs);
        assertTrue(cs.contains("--rollback ALTER TABLE AI_KB_CHUNK DROP COLUMN embedding_vec;"), cs);
        assertFalse(cs.toUpperCase(java.util.Locale.ROOT).contains("CREATE"), "no vector index: " + cs);
        assertEquals(1, cs.split("--changeset ", -1).length - 1, "one changeset");

        String wrapper = read(SangamSchema.ORACLE_VECTOR_CHANGELOG);
        assertTrue(wrapper.contains("<include file=\"oracle-vector/oracle.sql\" relativeToChangelogFile=\"true\""
                + " context=\"" + SangamSchema.ORACLE_VECTOR_CONTEXT + "\"/>"), wrapper);
        assertTrue(wrapper.contains("<dbms type=\"oracle\"/>") && wrapper.contains("onFail=\"HALT\""), wrapper);
        assertFalse(read(SangamSchema.MASTER_CHANGELOG).contains("oracle-vector"),
                "never reached by a plain update: Liquibase runs context-tagged changesets when no context is given");
        for (String dbms : new String[]{"postgresql", "oracle", "mssql", "mysql", "h2"}) {
            assertFalse(read("db/changelog/" + dbms + "/sangam-0001-baseline.sql").contains("embedding_vec VECTOR"), dbms);
        }

        try (Connection c = schema()) {
            assertThrows(RuntimeException.class, () -> SangamSchema.apply(c, SangamSchema.ORACLE_VECTOR_CHANGELOG,
                    SangamSchema.ORACLE_VECTOR_CONTEXT), "Oracle only");
            try (ResultSet rs = c.getMetaData().getColumns(null, null, "AI_KB_CHUNK", "EMBEDDING_VEC")) {
                assertFalse(rs.next(), "no column outside Oracle");
            }
        }
    }

    @Test
    void pgvectorColumnIsPostgresqlOnlyAndOptIn() throws Exception {
        String pg = read("db/changelog/postgresql/sangam-0001-baseline.sql");
        int at = pg.indexOf("--changeset sangam:0044-08-vector-optional dbms:postgresql");
        assertTrue(at > 0, "PostgreSQL changeset present");
        String cs = pg.substring(at, pg.indexOf("--rollback", at));
        assertTrue(cs.contains("--preconditions onFail:CONTINUE onError:HALT"), "skipped, not recorded, while absent");
        assertTrue(cs.contains("SELECT COUNT(*) FROM pg_extension WHERE extname = 'vector'"), cs);
        assertTrue(cs.contains("ALTER TABLE AI_KB_CHUNK ADD COLUMN embedding_vec vector;"), cs);
        for (String dbms : new String[]{"oracle", "mssql", "mysql", "h2"}) {
            assertFalse(read("db/changelog/" + dbms + "/sangam-0001-baseline.sql").contains("embedding_vec"), dbms);
        }
        try (Connection c = schema(); ResultSet rs = c.getMetaData().getColumns(null, null, "AI_KB_CHUNK", "EMBEDDING_VEC")) {
            assertFalse(rs.next(), "H2 never gets the column");
        }
    }

    @Test
    void everyDialectCarriesTheWave4bChangesets() throws IOException {
        for (String dbms : new String[]{"postgresql", "oracle", "mssql", "mysql", "h2"}) {
            String sql = read("db/changelog/" + dbms + "/sangam-0001-baseline.sql");
            for (String id : new String[]{"0044-01-sec_scim_resource", "0044-02-process_continuation", "0044-03-ops_fmig_run",
                    "0044-04-ops_fmig_item", "0044-05-hrm_process_instance-alter", "0044-06-hrm_activity_instance-alter",
                    "0044-07-hrm_task-alter"}) {
                assertTrue(sql.contains("--changeset sangam:" + id + " dbms:" + dbms), dbms + " " + id);
            }
            assertTrue(sql.contains("CONSTRAINT CK_SEC_SCIM_RESOURCE_TYPE CHECK (resource_type IN ('USER', 'GROUP'))"), dbms);
            assertTrue(sql.contains("ALTER TABLE HRM_TASK DROP COLUMN record_source;"), dbms + " rollback");
        }
        assertTrue(read("db/changelog/mssql/sangam-0001-baseline.sql").contains("CONSTRAINT DF_HRM_TASK_RECORD_SOURCE DEFAULT"
                + " 'ENGINE'"), "SQL Server names the default so the rollback can drop it");
    }

    /**
     * Integrator ruling 2026-11-13 option C: OPS_UTC_GATE (0045-13) holds exactly the seeded ENGINE_WORK row, OPEN
     * (0045-14), its state is OPEN or CLOSED only, and the seed takes the database's UTC instant on every dialect.
     */
    @Test
    void utcGateTableIsSeededOpenAndItsStateIsChecked() throws Exception {
        try (Connection c = schema()) {
            assertEquals("1", one(c, "SELECT COUNT(*) FROM OPS_UTC_GATE"));
            assertEquals("OPEN", one(c, "SELECT state FROM OPS_UTC_GATE WHERE gate_id = 'ENGINE_WORK'"));
            assertEquals("0", one(c, "SELECT version FROM OPS_UTC_GATE WHERE gate_id = 'ENGINE_WORK'"));
            assertEquals("changelog", one(c, "SELECT changed_by FROM OPS_UTC_GATE WHERE gate_id = 'ENGINE_WORK'"));
            assertEquals("1", one(c, "SELECT COUNT(*) FROM OPS_UTC_GATE WHERE run_id IS NULL AND changed_at IS NOT NULL"));
            assertThrows(SQLException.class, () -> exec(c, "UPDATE OPS_UTC_GATE SET state = 'HALF'"), "OPEN or CLOSED");
            assertThrows(SQLException.class, () -> exec(c, "INSERT INTO OPS_UTC_GATE(gate_id, state, run_id, changed_by,"
                    + " changed_at, version) VALUES ('ENGINE_WORK', 'OPEN', NULL, 'x', CURRENT_TIMESTAMP, 0)"), "one row per id");
        }
        for (String dbms : new String[]{"postgresql", "oracle", "mssql", "mysql", "h2"}) {
            String sql = read("db/changelog/" + dbms + "/sangam-0001-baseline.sql");
            int table = sql.indexOf("--changeset sangam:0045-13-ops_utc_gate dbms:" + dbms);
            int seed = sql.indexOf("--changeset sangam:0045-14-ops_utc_gate-data dbms:" + dbms);
            assertTrue(table > 0 && seed > table, dbms + ": the table, then its seed row");
            assertTrue(sql.contains("CONSTRAINT CK_OPS_UTC_GATE_STATE CHECK (state IN ('OPEN', 'CLOSED'))"), dbms);
            String seedSql = sql.substring(seed, sql.indexOf("--rollback", seed));
            String clock = switch (dbms) {
                case "mssql" -> "SYSDATETIMEOFFSET()";
                case "mysql" -> "UTC_TIMESTAMP(6)";
                default -> "CURRENT_TIMESTAMP";
            };
            assertTrue(seedSql.contains("VALUES ('ENGINE_WORK', 'OPEN', NULL, 'changelog', " + clock + ", 0);"), dbms + seedSql);
            assertTrue(sql.contains("--rollback DELETE FROM OPS_UTC_GATE WHERE gate_id = 'ENGINE_WORK';"), dbms);
        }
    }

    /**
     * Round 11 (coordinator 2026-11-13T08:17:45Z): the CMP_INCIDENT closure alter 0045-15 (nullable, existing rows keep
     * NULL), the decision 304/305 tables 0045-16..19 with their keys, and the CONNECTOR_CALL_DELIVERY index 0045-20.
     */
    @Test
    void incidentClosureColumnsAndDltTablesAreGenerated() throws Exception {
        try (Connection c = schema()) {
            for (String col : new String[]{"ROOT_CAUSE", "LESSONS_LEARNED", "CLOSED_AT", "CLOSED_BY"}) {
                try (ResultSet rs = c.getMetaData().getColumns(null, null, "CMP_INCIDENT", col)) {
                    assertTrue(rs.next(), "CMP_INCIDENT." + col);
                    assertEquals("YES", rs.getString("IS_NULLABLE"), col + " nullable: closed incidents keep NULL");
                }
            }
            exec(c, "INSERT INTO NOTIFICATION_DELIVERY(tenant_id, operation_id, connector_id, root_id, message_id, state,"
                    + " state_rank, reason, reported_at, source, reports, lookups, next_lookup_at, created_at, updated_at)"
                    + " VALUES ('t1', 'op1', 'sms', 'r1', NULL, 'SUBMITTED', 1, NULL, '2026-11-13 10:00:00', 'LOOKUP', 0,"
                    + " 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            assertThrows(SQLException.class, () -> exec(c, "INSERT INTO NOTIFICATION_DELIVERY(tenant_id, operation_id,"
                    + " connector_id, root_id, state, state_rank, source, reports, lookups, created_at, updated_at)"
                    + " VALUES ('t1', 'op1', 'sms', 'r1', 'SUBMITTED', 1, 'LOOKUP', 0, 0, CURRENT_TIMESTAMP,"
                    + " CURRENT_TIMESTAMP)"), "one row per (tenant, operation)");
            exec(c, "INSERT INTO NOTIFICATION_DELIVERY_REPORT VALUES ('t1', 'rep1', 'op1', CURRENT_TIMESTAMP)");
            assertThrows(SQLException.class, () -> exec(c, "INSERT INTO NOTIFICATION_DELIVERY_REPORT VALUES ('t1',"
                    + " 'rep1', 'op2', CURRENT_TIMESTAMP)"), "a report is seen once per tenant");
            exec(c, "INSERT INTO DLT_REGISTRATION VALUES ('t1', NULL, 1, NULL, CURRENT_TIMESTAMP)");
            exec(c, "INSERT INTO DLT_REGISTRATION_ITEM VALUES ('t1', 'TEMPLATE', 'tpl1', 'SANGAM', 'Your code {#var#}', 1,"
                    + " 'admin', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            assertThrows(SQLException.class, () -> exec(c, "INSERT INTO DLT_REGISTRATION_ITEM VALUES ('t1', 'TEMPLATE',"
                    + " 'tpl1', NULL, NULL, NULL, 'admin', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)"), "(tenant, kind, key)");
            exec(c, "INSERT INTO DLT_REGISTRATION_ITEM VALUES ('t1', 'HEADER', 'tpl1', NULL, NULL, NULL, 'admin',"
                    + " CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            assertEquals("2", one(c, "SELECT COUNT(*) FROM DLT_REGISTRATION_ITEM WHERE tenant_id = 't1'"));
        }
        for (String dbms : new String[]{"postgresql", "oracle", "mssql", "mysql", "h2"}) {
            String sql = read("db/changelog/" + dbms + "/sangam-0001-baseline.sql");
            for (String id : new String[]{"0045-15-cmp_incident-alter", "0045-16-notification_delivery",
                    "0045-17-notification_delivery_report", "0045-18-dlt_registration", "0045-19-dlt_registration_item",
                    "0045-20-connector_call-alter"}) {
                assertTrue(sql.contains("--changeset sangam:" + id + " dbms:" + dbms), dbms + " " + id);
            }
            for (String ix : new String[]{"CREATE INDEX NOTIFICATION_DELIVERY_MESSAGE ON NOTIFICATION_DELIVERY (tenant_id,"
                    + " message_id);", "CREATE INDEX NOTIFICATION_DELIVERY_DUE ON NOTIFICATION_DELIVERY (state_rank,"
                    + " next_lookup_at);", "CREATE INDEX CONNECTOR_CALL_DELIVERY ON CONNECTOR_CALL (connector_id, last_result,"
                    + " updated_at);"}) {
                assertTrue(sql.contains(ix), dbms + ": " + ix);
            }
            assertTrue(sql.contains("ALTER TABLE CMP_INCIDENT DROP COLUMN closed_by;"), dbms + " rollback");
        }
        String inventory = read("db/timestamp-inventory.csv");
        assertTrue(inventory.contains("CMP_INCIDENT,closed_at,{ts},EVENT_INSTANT,0045-15"), "closed_at inventoried");
        assertFalse(inventory.contains("NOTIFICATION_DELIVERY,reported_at,"), "reported_at is the gateway's text");
    }

    static String read(String resource) throws IOException {
        try (InputStream in = Wave4bTablesH2Test.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("missing " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
