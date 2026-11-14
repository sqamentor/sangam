import os
import sys

os.chdir(sys.argv[1])


def edit(p, pairs):
    s = open(p, encoding='utf-8').read()
    for o, n in pairs:
        assert s.count(o) == 1, (p, o[:80], s.count(o))
        s = s.replace(o, n)
    open(p, 'w', encoding='utf-8', newline='').write(s)


edit('sangam-persistence/src/test/java/org/sangam/persistence/Wave4bTablesH2Test.java', [
    ('''                + " signed_attributes, signing_time, prepared_pdf, created_at, expires_at) VALUES (?, ?, ?, 'd1', 3, 'aa',"
                + " 'p1', 'bb', 'A Signer', 'SHA256withRSA', 'cc', ?, CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP,"
                + " CURRENT_TIMESTAMP)", tenant, prepareId, task, new byte[]{0x31, 0x00}, new byte[]{0x25, 0x50});''',
     '''                + " signed_attributes, signing_time, prepared_pdf, created_at, expires_at, enrolment_id,"
                + " enrolment_approval_seq) VALUES (?, ?, ?, 'd1', 3, 'aa', 'p1', 'bb', 'A Signer', 'SHA256withRSA', 'cc', ?,"
                + " CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'e1', 1)", tenant, prepareId, task,
                new byte[]{0x31, 0x00}, new byte[]{0x25, 0x50});'''),
    ('''    /** Owner decision 247: the Oracle VECTOR column only in context oracle-vector, outside the master changelog. */''',
     '''    /** Decision 241 (W4-H REQUEST 03:49:34Z): the identity binding columns, alters 0045-11 and 0045-12. */
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
        String rollback = mssql.substring(mssql.indexOf("--rollback", at), mssql.indexOf("\\n", mssql.indexOf("--rollback", at)));
        assertTrue(rollback.contains("DROP CONSTRAINT DF_INTEGRATION_DSC_CERTIFICATE;")
                && rollback.indexOf("DROP CONSTRAINT") < rollback.indexOf("DROP COLUMN approval_seq"), rollback);
    }

    /** Owner decision 247: the Oracle VECTOR column only in context oracle-vector, outside the master changelog. */'''),
])
print('ok')
