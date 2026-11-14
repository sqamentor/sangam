// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.modules.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.TimeZone;

import org.junit.jupiter.api.Test;

/**
 * M-092: the schema status read. Liquibase's own table shapes (DATABASECHANGELOG, DATABASECHANGELOGLOCK) and
 * Operaton's ACT_GE_PROPERTY, created by hand here. Oracle and SQL Server run of the same SQL: NOT_RUN (test phase,
 * sangam-server acceptance lanes).
 */
class SchemaStatusReaderH2Test {

    static Connection db(String name) throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        Connection c = DriverManager.getConnection("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1");
        try (Statement s = c.createStatement()) {
            s.execute("CREATE TABLE DATABASECHANGELOG (ID VARCHAR(255) NOT NULL, AUTHOR VARCHAR(255) NOT NULL,"
                    + " FILENAME VARCHAR(255) NOT NULL, DATEEXECUTED TIMESTAMP NOT NULL, ORDEREXECUTED INT NOT NULL,"
                    + " EXECTYPE VARCHAR(10) NOT NULL)");
            s.execute("CREATE TABLE DATABASECHANGELOGLOCK (ID INT NOT NULL PRIMARY KEY, LOCKED BOOLEAN NOT NULL,"
                    + " LOCKGRANTED TIMESTAMP, LOCKEDBY VARCHAR(255))");
            s.execute("CREATE TABLE ACT_GE_PROPERTY (NAME_ VARCHAR(64) PRIMARY KEY, VALUE_ VARCHAR(300), REV_ INT)");
            s.execute("INSERT INTO DATABASECHANGELOG VALUES ('0001-01-sangam_lease', 'sangam',"
                    + " 'db/changelog/postgresql/sangam-0001-baseline.sql', TIMESTAMP '2026-11-01 10:00:00', 1, 'EXECUTED')");
            s.execute("INSERT INTO DATABASECHANGELOG VALUES ('P-1-mysql-datetime-precision', 'sangam',"
                    + " 'db/changelog/sangam-engine-patches.xml', TIMESTAMP '2026-11-01 10:00:05', 2, 'MARK_RAN')");
            s.execute("INSERT INTO DATABASECHANGELOG VALUES ('0001-02-sangam_fetch_lock', 'sangam',"
                    + " 'db/changelog/postgresql/sangam-0001-baseline.sql', TIMESTAMP '2026-11-01 10:00:01', 3, 'EXECUTED')");
            s.execute("INSERT INTO DATABASECHANGELOG VALUES ('hist-1', 'sangam',"
                    + " 'db/changelog/history-partitioning/postgresql.sql', TIMESTAMP '2026-11-01 10:00:09', 4, 'EXECUTED')");
            s.execute("INSERT INTO ACT_GE_PROPERTY VALUES ('schema.version', '1.0.0', 1)");
        }
        return c;
    }

    @Test
    void productChangesetsInOrderWithoutEnginePatchesOrPartitioning() throws Exception {
        try (Connection c = db("schema1")) {
            SchemaStatusReader.Status s = SchemaStatusReader.read(c);
            assertEquals(List.of("0001-01-sangam_lease", "0001-02-sangam_fetch_lock"), s.product().ids());
            assertEquals(2, s.product().applied());
            assertEquals("0001-02-sangam_fetch_lock", s.product().lastId());
            assertEquals("sangam", s.product().lastAuthor());
            assertEquals(Instant.parse("2026-11-01T10:00:01Z"), s.product().lastExecutedAt());
            assertEquals("1.0.0", s.engine().schemaVersion());
        }
    }

    @Test
    void theLockIsReportedOnlyWhileHeld() throws Exception {
        try (Connection c = db("schema2"); Statement s = c.createStatement()) {
            assertFalse(SchemaStatusReader.read(c).liquibaseLocked(), "no lock row means not locked");
            s.execute("INSERT INTO DATABASECHANGELOGLOCK VALUES (1, FALSE, NULL, NULL)");
            SchemaStatusReader.Status free = SchemaStatusReader.read(c);
            assertFalse(free.liquibaseLocked());
            assertNull(free.lockedBy());
            s.execute("UPDATE DATABASECHANGELOGLOCK SET LOCKED = TRUE, LOCKGRANTED = TIMESTAMP '2026-11-12 09:00:00',"
                    + " LOCKEDBY = 'migrate-job-7 (10.0.0.7)' WHERE ID = 1");
            SchemaStatusReader.Status held = SchemaStatusReader.read(c);
            assertTrue(held.liquibaseLocked());
            assertEquals("migrate-job-7 (10.0.0.7)", held.lockedBy());
            assertEquals(Instant.parse("2026-11-12T09:00:00Z"), held.lockGrantedAt());
        }
    }

    @Test
    void fileNamesAreClassifiedOnEitherSlash() {
        assertTrue(SchemaStatusReader.isProduct("db/changelog/sangam-0002-onboarding.sql"));
        assertFalse(SchemaStatusReader.isProduct("classpath:db\\changelog\\sangam-engine-patches.xml"));
        assertFalse(SchemaStatusReader.isProduct("db/changelog/sangam-history-partitioning.xml"));
    }
}
