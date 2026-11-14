// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.sangam.persistence.SangamSchema;
import org.springframework.mock.env.MockEnvironment;

/**
 * Owner decision 247: the oracle-vector changelog is reached only with sangam.ai.kb.vector-index=oracle, and then
 * start-up is refused unless the database is Oracle AI Database 23ai or later (JDBC major version), in every Liquibase
 * mode and before any changeset runs.
 */
class OracleVectorSchemaTest {

    /** A data source whose metadata reports {@code product} and {@code major}; counts the connections opened. */
    static DataSource database(String product, int major, AtomicInteger connections) {
        DatabaseMetaData md = (DatabaseMetaData) Proxy.newProxyInstance(DatabaseMetaData.class.getClassLoader(),
                new Class<?>[]{DatabaseMetaData.class}, (p, m, a) -> switch (m.getName()) {
                    case "getDatabaseProductName" -> product;
                    case "getDatabaseMajorVersion" -> major;
                    case "getDatabaseProductVersion" -> product + " " + major;
                    default -> throw new UnsupportedOperationException(m.getName());
                });
        Connection c = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (p, m, a) -> switch (m.getName()) {
                    case "getMetaData" -> md;
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(m.getName());
                });
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class},
                (p, m, a) -> {
                    if (m.getName().equals("getConnection")) {
                        connections.incrementAndGet();
                        return c;
                    }
                    throw new UnsupportedOperationException(m.getName());
                });
    }

    @Test
    void onlyTheOracleIndexAsksForTheChangelog() {
        assertFalse(OracleVectorSchema.wanted(null));
        assertFalse(OracleVectorSchema.wanted("portable"));
        assertFalse(OracleVectorSchema.wanted("pgvector"));
        assertFalse(OracleVectorSchema.wanted("ORACLE"), "the index names are case-sensitive, as in VectorIndex.named");
        assertTrue(OracleVectorSchema.wanted("oracle"));
        assertTrue(OracleVectorSchema.wanted(" oracle "), "trimmed, as in VectorIndex.named");
        assertEquals("oracle-vector", SangamSchema.ORACLE_VECTOR_CONTEXT);
    }

    @Test
    void oracleBelow23aiIsRefusedAndTheMessageNames23ai() {
        for (int major : new int[]{19, 21, 22}) {
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> OracleVectorSchema.requireOracle23ai(database("Oracle", major, new AtomicInteger())));
            assertTrue(e.getMessage().contains("23ai"), e.getMessage());
            assertTrue(e.getMessage().contains("sangam.ai.kb.vector-index=oracle"), e.getMessage());
            assertTrue(e.getMessage().contains("major version " + major), e.getMessage());
        }
    }

    @Test
    void oracle23aiAndLaterPass() {
        assertDoesNotThrow(() -> OracleVectorSchema.requireOracle23ai(database("Oracle", 23, new AtomicInteger())));
        assertDoesNotThrow(() -> OracleVectorSchema.requireOracle23ai(database("Oracle", 26, new AtomicInteger())));
    }

    @Test
    void anotherDatabaseIsRefusedToo() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> OracleVectorSchema.requireOracle23ai(database("PostgreSQL", 17, new AtomicInteger())));
        assertTrue(e.getMessage().contains("23ai") && e.getMessage().contains("PostgreSQL"), e.getMessage());
    }

    @Test
    void withoutTheSettingTheBeanNeverReachesTheOracleVectorChangelog() {
        MockEnvironment env = new MockEnvironment();
        SchemaBootstrap.SangamSpringLiquibase lb = (SchemaBootstrap.SangamSpringLiquibase) new SchemaBootstrap()
                .sangamLiquibase(database("PostgreSQL", 17, new AtomicInteger()), env);
        assertFalse(lb.oracleVector());
        env.setProperty("sangam.ai.kb.vector-index", "pgvector");
        lb = (SchemaBootstrap.SangamSpringLiquibase) new SchemaBootstrap()
                .sangamLiquibase(database("PostgreSQL", 17, new AtomicInteger()), env);
        assertFalse(lb.oracleVector());
    }

    @Test
    void theBeanRefusesOldOracleBeforeAnyChangesetInEveryMode() {
        for (String mode : new String[]{null, "migrate-only", "validate"}) {
            MockEnvironment env = new MockEnvironment().withProperty("sangam.ai.kb.vector-index", "oracle");
            if (mode != null) {
                env.setProperty(LiquibaseMode.PROPERTY, mode);
            }
            AtomicInteger connections = new AtomicInteger();
            SchemaBootstrap.SangamSpringLiquibase lb = (SchemaBootstrap.SangamSpringLiquibase) new SchemaBootstrap()
                    .sangamLiquibase(database("Oracle", 19, connections), env);
            assertTrue(lb.oracleVector());
            int before = connections.get();
            IllegalStateException e = assertThrows(IllegalStateException.class, lb::afterPropertiesSet,
                    "mode " + mode);
            assertTrue(e.getMessage().contains("23ai"), e.getMessage());
            assertEquals(before + 1, connections.get(), "only the version check touched the database");
        }
    }
}
