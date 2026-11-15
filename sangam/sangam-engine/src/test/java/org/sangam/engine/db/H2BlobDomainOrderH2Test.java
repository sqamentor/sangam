// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.db;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.sangam.engine.OperatonEngineAdapter;
import org.sangam.engine.SangamEngineFactory;
import org.sangam.engine.SangamEngineSettings;
import org.sangam.persistence.SangamSchema;

/**
 * Why the product changelog and the engine tables must be created in one order on H2 (the development database), and
 * that the product's own start does it in that order.
 *
 * <p>Two facts of H2 2.3 meet here, neither is a Sangam setting:
 * <ol>
 *   <li>in {@code MODE=PostgreSQL} (which the product layer needs) H2 does not know the type name {@code BLOB}, and
 *       Operaton's H2 create script uses it ({@code ACT_GE_BYTEARRAY}); {@link DatabaseGuard#prepareH2} therefore
 *       creates a domain named {@code BLOB};</li>
 *   <li>H2 creates a domain named like one of its data types only while the database has no table at all; afterwards
 *       it answers {@code Domain "BLOB (PUBLIC.<a table>)" already exists}, in every mode.</li>
 * </ol>
 * So the alias has to be created on the empty database. {@code SangamEngineConfigurer.prepare} (and the server's
 * {@code SchemaBootstrap}) call {@code prepareH2} before the product changelog. A caller that applies
 * {@link SangamSchema} itself first and then builds an engine that creates its tables gets
 * {@code Unknown data type: "BLOB"} from the engine start: an artefact of H2's mode, not a product ordering fault
 * (PostgreSQL, Oracle, SQL Server and MySQL have no such alias). A test that wants both on one H2 database lets the
 * engine apply the changelog ({@code setApplyProductSchema(true)}) or calls {@code DatabaseGuard.prepareH2} first.
 */
class H2BlobDomainOrderH2Test {

    private static JdbcDataSource h2(boolean postgresMode) {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:blob" + UUID.randomUUID().toString().replace("-", "")
                + (postgresMode ? ";MODE=PostgreSQL" : "") + ";DB_CLOSE_DELAY=-1");
        return ds;
    }

    private static SangamEngineSettings settings() {
        SangamEngineSettings s = new SangamEngineSettings();
        s.setSystemAuditScope("system:platform"); // decision 144: required at engine start
        s.setAllowMultipleEnginesPerJvm(true);
        s.setEngineSchemaUpdate("true");
        s.setApplyEnvironment(false);
        s.setJobExecutorActivate(false);
        return s;
    }

    private static void sql(JdbcDataSource ds, String statement) throws SQLException {
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute(statement);
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        return String.valueOf(c.getMessage());
    }

    private static final String DOMAIN = "CREATE DOMAIN IF NOT EXISTS BLOB AS BINARY LARGE OBJECT";

    @Test
    void postgresModeDoesNotKnowBlobUntilTheAliasExists() throws SQLException {
        JdbcDataSource ds = h2(true);
        SQLException unknown = assertThrows(SQLException.class, () -> sql(ds, "CREATE TABLE A (ID INT, B BLOB)"));
        assertTrue(unknown.getMessage().contains("Unknown data type: \"BLOB\""), unknown.getMessage());
        sql(ds, DOMAIN); // no table yet: accepted
        sql(ds, "CREATE TABLE A (ID INT, B BLOB)");
        sql(ds, DOMAIN); // repeated on a database that has it (an engine restart): still accepted
    }

    @Test
    void h2RefusesTheAliasOnceAnyTableExists() throws SQLException {
        for (boolean postgresMode : new boolean[] {true, false}) {
            JdbcDataSource ds = h2(postgresMode);
            sql(ds, "CREATE TABLE PLAIN_T (ID INT)");
            SQLException refused = assertThrows(SQLException.class, () -> sql(ds, DOMAIN), "mode " + postgresMode);
            assertTrue(refused.getMessage().contains("Domain \"BLOB (PUBLIC.PLAIN_T)\" already exists"),
                    refused.getMessage());
        }
    }

    @Test
    void theChangelogBeforeTheAliasLeavesTheEngineWithoutBlob() {
        JdbcDataSource ds = h2(true);
        SangamSchema.apply(ds); // product tables first: the alias can no longer be created
        assertDoesNotThrow(() -> DatabaseGuard.prepareH2(ds), "the refusal is logged, not thrown");
        RuntimeException e = assertThrows(RuntimeException.class, () -> SangamEngineFactory.build(ds, settings(), "h2"));
        assertTrue(rootMessage(e).contains("Unknown data type: \"BLOB\""), rootMessage(e));
    }

    @Test
    void theProductOrderWorksOnOneDatabase() throws SQLException {
        // the engine start applies the changelog itself: alias, product tables, engine tables
        JdbcDataSource ds = h2(true);
        SangamEngineSettings s = settings();
        s.setApplyProductSchema(true);
        try (OperatonEngineAdapter a = SangamEngineFactory.build(ds, s, "h2")) {
            assertEquals(0, a.processEngine().getRepositoryService().createDeploymentQuery().count());
        }
        sql(ds, "SELECT COUNT(*) FROM OUTBOX");

        // a caller that applies the changelog itself: the alias first
        JdbcDataSource own = h2(true);
        DatabaseGuard.prepareH2(own);
        SangamSchema.apply(own);
        try (OperatonEngineAdapter a = SangamEngineFactory.build(own, settings(), "h2")) {
            assertEquals(0, a.processEngine().getRepositoryService().createDeploymentQuery().count());
        }
    }
}
