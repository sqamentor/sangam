// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/** Decision 334: the named refusal of a check-only engine start on a database without the engine tables. */
class EngineSchemaGuardTest {

    static DataSource h2() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:esg" + UUID.randomUUID().toString().replace("-", "") + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
        return ds;
    }

    static StandardEnvironment env(String... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("test", m));
        return env;
    }

    static int tables(DataSource ds) throws SQLException {
        try (Connection c = ds.getConnection();
             var rs = c.createStatement().executeQuery(
                     "select count(*) from INFORMATION_SCHEMA.TABLES where TABLE_SCHEMA = 'PUBLIC'")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    static void execute(DataSource ds, String sql) throws SQLException {
        try (Connection c = ds.getConnection()) {
            c.createStatement().execute(sql);
        }
    }

    @Test
    void aCheckOnlyEngineOnAnEmptyDatabaseIsRefusedByNameAndNothingIsCreated() throws SQLException {
        DataSource ds = h2();
        EngineSchemaGuard.EngineSchemaMissingException e = assertThrows(
                EngineSchemaGuard.EngineSchemaMissingException.class,
                () -> EngineSchemaGuard.require(env("operaton.bpm.database.schema-update", "false"), ds));
        assertTrue(e.getMessage().startsWith(EngineSchemaGuard.CODE + ": "), e.getMessage());
        assertTrue(e.getMessage().contains("SANGAM_LIQUIBASE_MODE=migrate-only"), e.getMessage());
        assertEquals(0, tables(ds), "nothing created");
    }

    @Test
    void anEngineThatCreatesItsSchemaIsNotChecked() {
        DataSource ds = h2();
        for (String value : new String[] {"true", "create", "create-drop", "drop-create"}) {
            assertDoesNotThrow(() -> EngineSchemaGuard.require(env("operaton.bpm.database.schema-update", value), ds),
                    value);
        }
        // the Operaton starter's own default when nothing is set is true
        assertFalse(EngineSchemaGuard.checkOnly(env()));
    }

    @Test
    void theEngineTablesPresentLetTheStartGoOn() throws SQLException {
        DataSource ds = h2();
        execute(ds, "create table ACT_RU_EXECUTION (ID_ varchar(64))");
        assertDoesNotThrow(() -> EngineSchemaGuard.require(env("operaton.bpm.database.schema-update", "false"), ds));
    }

    @Test
    void theTablePrefixAndSchemaAreHonoured() throws SQLException {
        DataSource ds = h2();
        execute(ds, "create schema ENG");
        execute(ds, "create table ENG.ACT_RU_EXECUTION (ID_ varchar(64))");
        // as Operaton's own probe (DbSqlSession.isTablePresent): without a configured schema every schema counts
        assertDoesNotThrow(() -> EngineSchemaGuard.require(env("operaton.bpm.database.schema-update", "false"), ds));
        assertThrows(EngineSchemaGuard.EngineSchemaMissingException.class,
                () -> EngineSchemaGuard.require(env("operaton.bpm.database.schema-update", "false",
                        "operaton.bpm.database.schema-name", "OTHER"), ds), "the configured schema has no engine tables");
        assertThrows(EngineSchemaGuard.EngineSchemaMissingException.class,
                () -> EngineSchemaGuard.require(env("operaton.bpm.database.schema-update", "false",
                        "operaton.bpm.database.table-prefix", "OTHER."), ds), "the prefix's schema has none");
        assertDoesNotThrow(() -> EngineSchemaGuard.require(env("operaton.bpm.database.schema-update", "false",
                "operaton.bpm.database.table-prefix", "ENG."), ds));
        assertDoesNotThrow(() -> EngineSchemaGuard.require(env("operaton.bpm.database.schema-update", "false",
                "operaton.bpm.database.schema-name", "eng"), ds));
    }

    @Test
    void theGenericEnginePropertyWinsAsInTheStarter() {
        DataSource ds = h2();
        assertDoesNotThrow(() -> EngineSchemaGuard.require(env("operaton.bpm.database.schema-update", "false",
                "operaton.bpm.generic-properties.properties.databaseSchemaUpdate", "true"), ds));
        assertTrue(EngineSchemaGuard.checkOnly(env("operaton.bpm.database.schema-update", "true",
                "operaton.bpm.generic-properties.properties.database-schema-update", "false")));
    }

    @Test
    void aDatabaseThatCannotBeAskedIsRefused() {
        DataSource broken = (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(),
                new Class<?>[] {DataSource.class}, (proxy, method, args) -> {
                    if ("getConnection".equals(method.getName())) {
                        throw new SQLException("no database in this test");
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        EngineSchemaGuard.EngineSchemaMissingException e = assertThrows(
                EngineSchemaGuard.EngineSchemaMissingException.class,
                () -> EngineSchemaGuard.require(env("operaton.bpm.database.schema-update", "false"), broken));
        assertTrue(e.getMessage().startsWith(EngineSchemaGuard.CODE), e.getMessage());
    }
}
