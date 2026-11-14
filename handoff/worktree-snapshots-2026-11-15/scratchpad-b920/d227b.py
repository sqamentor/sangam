import os
import sys

os.chdir(sys.argv[1])


def edit(p, pairs):
    s = open(p, encoding='utf-8').read()
    for o, n in pairs:
        assert s.count(o) == 1, (p, o, s.count(o))
        s = s.replace(o, n)
    open(p, 'w', encoding='utf-8', newline='').write(s)


edit('sangam-persistence/src/main/java/org/sangam/persistence/SangamSchema.java', [
    ('''            // 0045-06), Flowable source listener acknowledgements (decision 231; 0045-07)
            "EXEC_RESIZE", "SHARED_DEPLOYMENT", "OPS_FMIG_ACK");''',
     '''            // 0045-06), Flowable source listener acknowledgements (decision 231; 0045-07), DSC certificate
            // enrolment (decision 227; 0045-08)
            "EXEC_RESIZE", "SHARED_DEPLOYMENT", "OPS_FMIG_ACK", "INTEGRATION_DSC_CERTIFICATE");'''),
])

t = 'sangam-persistence/src/test/java/org/sangam/persistence/Wave4bTablesH2Test.java'
edit(t, [
    (' * INTEGRATION_DSC_PREPARED (0045-04, decision 165). Keys and CHECKs are\n',
     ' * INTEGRATION_DSC_PREPARED (0045-04, decision 165), and INTEGRATION_DSC_CERTIFICATE (0045-08, decision 227). Keys and\n * CHECKs are\n'),
    ('''    @Test
    void pgvectorColumnIsPostgresqlOnlyAndOptIn() throws Exception {''',
     '''    /** Decision 227 (W4-H REQUEST 02:15:49Z): keyed exactly as sangam-dsc-enrolment-reference.sql. */
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

    @Test
    void pgvectorColumnIsPostgresqlOnlyAndOptIn() throws Exception {'''),
])

r = 'sangam-persistence/README.md'
s = open(r, encoding='utf-8').read()
lines = s.split('\n')
idx = [i for i, l in enumerate(lines) if l.startswith('| `OPS_FMIG_ACK`')]
assert len(idx) == 1, idx
lines.insert(idx[0] + 1, '| `INTEGRATION_DSC_CERTIFICATE` | sangam-integration `dsc/JdbcDscCertificateEnrolments` (changeset `0045-08`, reference DDL `db/sangam-dsc-enrolment-reference.sql`) | decision 227: one row per (tenant, certificate SHA-256); the server-issued enrolment id is unique per tenant; PENDING until an admin other than the signer approves, then APPROVED, REJECTED or REVOKED by conditional UPDATEs; indexes by principal and by status |')
s = '\n'.join(lines)
assert s.count('326 tables') >= 1, 'count'
s = s.replace('326 tables', '327 tables')
open(r, 'w', encoding='utf-8', newline='').write(s)
print('ok')
