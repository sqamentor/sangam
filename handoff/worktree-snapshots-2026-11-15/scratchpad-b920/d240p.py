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
    ('     * BILL_UNVERIFIED_CONSUMER (0045-03, decisions 195 and 196).',
     '     * BILL_UNVERIFIED_CONSUMER (0045-03, decisions 195 and 196); plus OPS_UTC_RUN.session_zone (0045-09, decision 240).'),
    ('public static final int ADDITIONAL_CHANGESETS = 85;', 'public static final int ADDITIONAL_CHANGESETS = 86;'),
])
edit('sangam-persistence/README.md', [
    ('+ 85 further changesets (alter,', '+ 86 further changesets (alter,'),
])
t = 'sangam-persistence/src/test/java/org/sangam/persistence/Wave4bTablesH2Test.java'
edit(t, [
    ('''    /** Decision 227 (W4-H REQUEST 02:15:49Z): keyed exactly as sangam-dsc-enrolment-reference.sql. */''',
     '''    /** Owner decision 240: the declared historical session zone of a UTC run is a nullable alter (0045-09). */
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

    /** Decision 227 (W4-H REQUEST 02:15:49Z): keyed exactly as sangam-dsc-enrolment-reference.sql. */'''),
])
print('ok')
