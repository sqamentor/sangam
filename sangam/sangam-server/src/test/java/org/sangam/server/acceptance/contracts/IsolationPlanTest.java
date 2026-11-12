// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.acceptance.contracts;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.sangam.core.layer.Dialect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Unit tests (no database) of the per-run namespace plan behind {@link IsolatedProductTables} (SL-96). */
class IsolationPlanTest {

    @Test
    void everyAcceptanceDatabaseMapsToItsDialect() {
        assertEquals(Dialect.POSTGRESQL, IsolationPlan.dialectOf("postgres"));
        assertEquals(Dialect.SQLSERVER, IsolationPlan.dialectOf("sqlserver"));
        assertEquals(Dialect.MYSQL, IsolationPlan.dialectOf("mysql"));
        assertEquals(Dialect.ORACLE, IsolationPlan.dialectOf("oracle"));
        assertThrows(IllegalArgumentException.class, () -> IsolationPlan.dialectOf("sqlserver2025"));
        assertThrows(IllegalArgumentException.class, () -> IsolationPlan.dialectOf("h2"));
    }

    @Test
    void namespaceIsPerRunAndFoldedForOracle() {
        assertEquals("acc_c0a1b2c3d", IsolationPlan.namespace(Dialect.POSTGRESQL, "c0A1b2c3d"));
        assertEquals("acc_c0a1b2c3d", IsolationPlan.namespace(Dialect.SQLSERVER, "c0a1b2c3d"));
        assertEquals("acc_c0a1b2c3d", IsolationPlan.namespace(Dialect.MYSQL, "c0a1b2c3d"));
        assertEquals("ACC_C0A1B2C3D", IsolationPlan.namespace(Dialect.ORACLE, "c0a1b2c3d"));
        assertThrows(IllegalArgumentException.class, () -> IsolationPlan.namespace(Dialect.POSTGRESQL, "x;DROP"));
        assertThrows(IllegalArgumentException.class, () -> IsolationPlan.namespace(Dialect.POSTGRESQL, "x".repeat(40)));
    }

    @Test
    void namespaceStatementsPerDatabase() {
        assertEquals(List.of("CREATE SCHEMA acc_c1"), IsolationPlan.createNamespace(Dialect.POSTGRESQL, "acc_c1", "sangam"));
        assertEquals(List.of("CREATE DATABASE acc_c1",
                        "ALTER DATABASE acc_c1 SET READ_COMMITTED_SNAPSHOT ON WITH ROLLBACK IMMEDIATE",
                        "ALTER DATABASE acc_c1 SET ALLOW_SNAPSHOT_ISOLATION ON"),
                IsolationPlan.createNamespace(Dialect.SQLSERVER, "acc_c1", "sa"));
        assertEquals(List.of("CREATE DATABASE `acc_c1`", "GRANT ALL PRIVILEGES ON `acc_c1`.* TO 'sangam'@'%'"),
                IsolationPlan.createNamespace(Dialect.MYSQL, "acc_c1", "sangam"));
        assertThrows(IllegalArgumentException.class, () -> IsolationPlan.createNamespace(Dialect.ORACLE, "ACC_C1", "sangam"));
        assertThrows(IllegalArgumentException.class, () -> IsolationPlan.createNamespace(Dialect.MYSQL, "acc_c1", "x'@'%"));
    }

    @Test
    void oracleUserCopiesTheServerUsersTablespacePrivilegesAndRoles() {
        assertEquals(List.of(
                        "CREATE USER ACC_C1 IDENTIFIED BY \"sangam\" DEFAULT TABLESPACE USERS QUOTA UNLIMITED ON USERS",
                        "GRANT CREATE SESSION TO ACC_C1",
                        "GRANT CREATE TABLE TO ACC_C1",
                        "GRANT CONNECT TO ACC_C1",
                        "GRANT RESOURCE TO ACC_C1"),
                IsolationPlan.createOracleUser("ACC_C1", "sangam", "USERS", List.of("CREATE SESSION", "CREATE TABLE"),
                        List.of("CONNECT", "RESOURCE")));
        assertThrows(IllegalArgumentException.class,
                () -> IsolationPlan.createOracleUser("ACC_C1", "a\"b", "USERS", List.of(), List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> IsolationPlan.createOracleUser("ACC_C1", "pw", "USERS", List.of("CREATE TABLE; DROP"), List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> IsolationPlan.createOracleUser("ACC_C1", "pw", "USERS", List.of(), List.of("DBA TO X")));
    }

    @Test
    void postgresSearchPathIsTheNamespaceAlone() {
        assertEquals("jdbc:postgresql://h:5432/sangam?currentSchema=acc_c1",
                IsolationPlan.withCurrentSchema("jdbc:postgresql://h:5432/sangam", "acc_c1"));
        assertEquals("jdbc:postgresql://h:5432/sangam?loggerLevel=OFF&currentSchema=acc_c1",
                IsolationPlan.withCurrentSchema("jdbc:postgresql://h:5432/sangam?loggerLevel=OFF", "acc_c1"));
    }

    @Test
    void truncatePerDatabase() {
        List<String> tables = List.of("OUTBOX", "INBOX");
        assertEquals(List.of("TRUNCATE OUTBOX, INBOX RESTART IDENTITY"), IsolationPlan.truncate(Dialect.POSTGRESQL, tables));
        for (Dialect d : List.of(Dialect.SQLSERVER, Dialect.MYSQL, Dialect.ORACLE)) {
            assertEquals(List.of("TRUNCATE TABLE OUTBOX", "TRUNCATE TABLE INBOX"), IsolationPlan.truncate(d, tables), d.name());
        }
        assertThrows(IllegalArgumentException.class, () -> IsolationPlan.truncate(Dialect.MYSQL, List.of()));
        assertThrows(IllegalArgumentException.class, () -> IsolationPlan.truncate(Dialect.MYSQL, List.of("A B")));
    }

    @Test
    void pagingPerDatabase() {
        assertEquals(" LIMIT 7 OFFSET 14", IsolationPlan.page(Dialect.POSTGRESQL, 7, 14));
        assertEquals(" LIMIT 7 OFFSET 0", IsolationPlan.page(Dialect.MYSQL, 7, 0));
        assertEquals(" OFFSET 14 ROWS FETCH NEXT 7 ROWS ONLY", IsolationPlan.page(Dialect.SQLSERVER, 7, 14));
        assertEquals(" OFFSET 14 ROWS FETCH NEXT 7 ROWS ONLY", IsolationPlan.page(Dialect.ORACLE, 7, 14));
        assertThrows(IllegalArgumentException.class, () -> IsolationPlan.page(Dialect.ORACLE, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> IsolationPlan.page(Dialect.ORACLE, 1, -1));
    }
}
