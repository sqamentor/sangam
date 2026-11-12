// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

/**
 * SG-19 end-to-end regression: Sangam used to force Operaton {@code databaseSchemaUpdate=true}, so every start ran
 * engine DDL. These tests start a real engine through {@link SangamEngineFactory} with the default settings (no
 * explicit {@code engineSchemaUpdate}) against databases where auto-DDL would visibly act, and assert that no engine
 * DDL ran. Each was red with the forced {@code true}: the empty database got the whole engine schema, and the planted
 * gap (a dropped schema component) was silently re-created.
 *
 * <p>Operaton 2.2.0 decides per component by one marker table ({@code DbSqlSession.is*TablePresent}, checked with
 * javap): engine {@code ACT_RU_EXECUTION}, history {@code ACT_HI_PROCINST}, identity {@code ACT_ID_USER}, ... With
 * {@code true} it creates a component whose marker is missing; with {@code false} it only checks.
 */
class EngineNoDdlStartH2Test {

    private static final String[] IDENTITY_TABLES = {"ACT_ID_TENANT_MEMBER", "ACT_ID_MEMBERSHIP", "ACT_ID_INFO",
            "ACT_ID_TENANT", "ACT_ID_GROUP", "ACT_ID_USER"};

    private static JdbcDataSource h2() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:sg19" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        return ds;
    }

    private static SangamEngineSettings defaults() {
        SangamEngineSettings s = new SangamEngineSettings();
        s.setAllowMultipleEnginesPerJvm(true);
        return s; // engineSchemaUpdate stays null: the production default
    }

    private static int engineTables(JdbcDataSource ds) {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES"
                     + " WHERE UPPER(TABLE_NAME) LIKE 'ACT\\_%' ESCAPE '\\'")) {
            rs.next();
            return rs.getInt(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean tableExists(JdbcDataSource ds, String table) {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE UPPER(TABLE_NAME) = '"
                     + table + "'")) {
            rs.next();
            return rs.getInt(1) > 0;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Creates the full engine schema explicitly (the migrate-only path), then plants the identity-component gap. */
    private static void migrateThenPlantGap(JdbcDataSource ds) {
        SangamEngineSettings migrate = defaults();
        migrate.setEngineSchemaUpdate("true");
        SangamEngineFactory.build(ds, migrate, "h2").close();
        assertTrue(tableExists(ds, "ACT_ID_USER"), "migrated schema has the identity component");
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            for (String t : IDENTITY_TABLES) {
                st.execute("DROP TABLE IF EXISTS " + t + " CASCADE");
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        assertFalse(tableExists(ds, "ACT_ID_USER"), "gap planted");
    }

    @Test
    void emptyDatabaseIsRefusedAndNoEngineDdlRuns() {
        JdbcDataSource ds = h2();
        assertEquals(0, engineTables(ds));
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> SangamEngineFactory.build(ds, defaults(), "h2").close(),
                "a default start on a database without the engine schema must fail closed");
        assertEquals(0, engineTables(ds), "no engine table was created by the start: " + e.getMessage());
    }

    @Test
    void plantedMissingComponentIsNotRecreatedByADefaultStart() {
        JdbcDataSource ds = h2();
        migrateThenPlantGap(ds);
        int before = engineTables(ds);
        try {
            SangamEngineFactory.build(ds, defaults(), "h2").close();
        } catch (RuntimeException expected) {
            // Operaton's schema check may refuse the missing component; either way no DDL may run
        }
        assertFalse(tableExists(ds, "ACT_ID_USER"), "auto-DDL re-created the planted gap");
        assertEquals(before, engineTables(ds), "no engine table was created or dropped by the start");
    }

    /** Control: the plant is exactly what auto-DDL acts on, so the previous test can only pass if no DDL ran. */
    @Test
    void anExplicitOptInStillRunsTheEngineDdl() {
        JdbcDataSource ds = h2();
        migrateThenPlantGap(ds);
        SangamEngineSettings optIn = defaults();
        optIn.setEngineSchemaUpdate("true");
        SangamEngineFactory.build(ds, optIn, "h2").close();
        assertTrue(tableExists(ds, "ACT_ID_USER"), "explicit opt-in re-creates the missing component");
    }
}
