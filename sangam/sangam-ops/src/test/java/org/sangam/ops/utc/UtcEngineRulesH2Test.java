// SPDX-License-Identifier: Apache-2.0
package org.sangam.ops.utc;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;
import org.sangam.ops.utc.UtcInventory.ColumnClass;
import org.sangam.persistence.SangamSchema;

/**
 * Decision 150, engine side of the UTC tool (W4-E REQUEST 2026-11-13T01:36:38Z, round 4 item 3): the engine's
 * {@code ACT_*} inventory rows join the product inventory, the five MySQL/MariaDB {@code timestamp} engine columns are
 * refused on {@link Dialect#MYSQL} without a declared session zone (decision 240: {@code UtcSessionZoneH2Test}), and an
 * apply or rollback is refused while an engine job or external task holds an unexpired lock.
 */
class UtcEngineRulesH2Test {

    static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    static final Instant CUTOVER = Instant.parse("2026-11-01T00:00:00Z");

    /** Engine rows in the form sangam-engine writes them (db/engine-timestamp-inventory.csv). */
    static final String ENGINE_CSV = """
            table,column,token,class,changeset
            ACT_RU_JOB,LOCK_EXP_TIME_,timestamp,EVENT_INSTANT,operaton-2.2.0:engine
            ACT_HI_OP_LOG,TIMESTAMP_,timestamp,EVENT_INSTANT,operaton-2.2.0:history
            ACT_HI_OP_LOG,REMOVAL_TIME_,timestamp,EVENT_INSTANT,operaton-2.2.0:history
            """;

    Transactions tx;
    final AuditChain audit = new AuditChain(Dialect.POSTGRESQL);

    @BeforeEach
    void db() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=TRUE");
        SangamSchema.apply(ds);
        tx = Transactions.of(ds);
    }

    void sql(String sql, Object... params) {
        tx.inTransaction(c -> Jdbc.update(c, sql, params));
    }

    void engineLockTables() {
        sql("CREATE TABLE ACT_RU_JOB (ID_ VARCHAR(64) PRIMARY KEY, LOCK_EXP_TIME_ TIMESTAMP WITH TIME ZONE)");
        sql("CREATE TABLE ACT_RU_EXT_TASK (ID_ VARCHAR(64) PRIMARY KEY, LOCK_EXP_TIME_ TIMESTAMP WITH TIME ZONE)");
    }

    @Test
    void engineRowsJoinTheProductInventory() {
        UtcInventory product = UtcInventory.product();
        UtcInventory all = product.withEngine(ENGINE_CSV);
        assertEquals(product.size() + 3, all.size());
        assertEquals(ColumnClass.EVENT_INSTANT, all.find("ACT_HI_OP_LOG", "TIMESTAMP_").orElseThrow().cls());
        assertEquals(product.columns(), all.columns().subList(0, product.size()), "product rows first, unchanged");
        String header = "table,column,token,class,changeset\n";
        assertThrows(IllegalArgumentException.class,
                () -> product.withEngine(header + "HRM_TASK,X_,timestamp,EVENT_INSTANT,engine\n"), "only ACT_* tables");
        assertThrows(IllegalArgumentException.class,
                () -> product.withEngine(header + "ACT_RU_JOB,DUEDATE_,{ts},EVENT_INSTANT,engine\n"), "engine type token");
        assertThrows(IllegalArgumentException.class,
                () -> product.withEngine(header + "ACT_RU_JOB,DUEDATE_,timestamp,LOCAL_BUSINESS,engine\n"), "instants only");
        assertThrows(IllegalArgumentException.class, () -> product.withEngine(header), "an empty engine inventory");
        assertThrows(IllegalArgumentException.class, () -> all.withEngine(ENGINE_CSV), "a column listed twice");
    }

    @Test
    void theEngineInventoryIsNeverSilentlyLeftOut() {
        assertNull(UtcInventory.class.getClassLoader().getResource(UtcInventory.ENGINE_RESOURCE),
                "sangam-engine is not on the sangam-ops test classpath (provided scope)");
        IllegalStateException e = assertThrows(IllegalStateException.class, UtcInventory::productAndEngine);
        assertTrue(e.getMessage().contains(UtcInventory.ENGINE_RESOURCE), e.getMessage());
    }

    @Test
    void mysqlRefusesTheFiveSessionZonedEngineColumns() {
        assertEquals(5, UtcInventory.MYSQL_SESSION_ZONED.size());
        UtcInventory inv = UtcInventory.product().withEngine(ENGINE_CSV);
        UtcDataset zoned = new UtcDataset("ACT_HI_OP_LOG", List.of("TIMESTAMP_"), "TIMESTAMP_", BERLIN, CUTOVER, null);
        UtcDataset plain = new UtcDataset("ACT_HI_OP_LOG", List.of("REMOVAL_TIME_"), "REMOVAL_TIME_", BERLIN, CUTOVER, null);
        UtcConversionTool mysql = new UtcConversionTool(tx, Dialect.MYSQL, new AuditChain(Dialect.MYSQL), inv);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> mysql.check(zoned));
        assertTrue(e.getMessage().contains("ACT_HI_OP_LOG.TIMESTAMP_ is a MySQL/MariaDB timestamp column"), e.getMessage());
        UtcDataset provenanceOnly = new UtcDataset("ACT_HI_OP_LOG", List.of("REMOVAL_TIME_"), "TIMESTAMP_", BERLIN,
                CUTOVER, null);
        assertThrows(IllegalArgumentException.class, () -> mysql.check(provenanceOnly), "nor read as provenance");
        assertDoesNotThrow(() -> mysql.check(plain), "the datetime engine columns stay convertible on MySQL");
        UtcConversionTool pg = new UtcConversionTool(tx, Dialect.POSTGRESQL, audit, inv);
        assertDoesNotThrow(() -> pg.check(zoned), "zone-aware on the other dialects");
    }

    @Test
    void applyRefusesWhileAnEngineLockIsLive() {
        engineLockTables();
        sql("INSERT INTO HRM_TASK(id, tenant_id, state, create_time) VALUES ('k1', 't1', 'completed', ?)",
                Instant.parse("2026-01-15T10:00:00Z"));
        UtcConversionTool tool = new UtcConversionTool(tx, Dialect.POSTGRESQL, audit, UtcInventory.product());
        UtcDataset d = new UtcDataset("HRM_TASK", List.of("create_time"), "create_time", BERLIN, CUTOVER, "t1");
        tool.dryRun("r1", d, "operator", "ops");
        sql("INSERT INTO ACT_RU_JOB(ID_, LOCK_EXP_TIME_) VALUES ('j1', ?)", Instant.parse("2999-01-01T00:00:00Z"));
        sql("INSERT INTO ACT_RU_EXT_TASK(ID_, LOCK_EXP_TIME_) VALUES ('x1', ?)", Instant.parse("2999-01-01T00:00:00Z"));
        sql("INSERT INTO ACT_RU_EXT_TASK(ID_, LOCK_EXP_TIME_) VALUES ('x2', NULL)");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> tool.apply("r1", 100, "MW-1", "operator", "ops"));
        assertTrue(e.getMessage().contains("ACT_RU_JOB 1") && e.getMessage().contains("ACT_RU_EXT_TASK 1"), e.getMessage());
        assertEquals("DRY_RUN", tool.run("r1").orElseThrow().state(), "nothing started");
        assertEquals(Instant.parse("2026-01-15T10:00:00Z"), tx.inTransaction(c -> Jdbc.one(c,
                "SELECT create_time FROM HRM_TASK WHERE id = 'k1'", rs -> Jdbc.instant(rs, "create_time")).orElseThrow()));

        sql("UPDATE ACT_RU_JOB SET LOCK_EXP_TIME_ = ?", Instant.parse("2000-01-01T00:00:00Z")); // expired
        assertThrows(IllegalStateException.class, () -> tool.apply("r1", 100, "MW-1", "operator", "ops"),
                "the external task lock is still live");
        sql("UPDATE ACT_RU_EXT_TASK SET LOCK_EXP_TIME_ = NULL");
        assertEquals("APPLIED", tool.apply("r1", 100, "MW-1", "operator", "ops").state());
        assertEquals(Instant.parse("2026-01-15T09:00:00Z"), tx.inTransaction(c -> Jdbc.one(c,
                "SELECT create_time FROM HRM_TASK WHERE id = 'k1'", rs -> Jdbc.instant(rs, "create_time")).orElseThrow()));
    }

    /** Owner decision 240: the rollback refuses a live engine lock as the apply does, at its start and in every batch. */
    @Test
    void rollbackRefusesWhileAnEngineLockIsLive() {
        engineLockTables();
        sql("INSERT INTO HRM_TASK(id, tenant_id, state, create_time) VALUES ('k1', 't1', 'completed', ?)",
                Instant.parse("2026-01-15T10:00:00Z"));
        UtcConversionTool tool = new UtcConversionTool(tx, Dialect.POSTGRESQL, audit, UtcInventory.product());
        UtcDataset d = new UtcDataset("HRM_TASK", List.of("create_time"), "create_time", BERLIN, CUTOVER, "t1");
        tool.dryRun("r1", d, "operator", "ops");
        assertEquals("APPLIED", tool.apply("r1", 100, "MW-1", "operator", "ops").state());
        sql("INSERT INTO ACT_RU_EXT_TASK(ID_, LOCK_EXP_TIME_) VALUES ('x1', ?)", Instant.parse("2999-01-01T00:00:00Z"));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> tool.rollback("r1", "MW-2", "operator", "ops"));
        assertTrue(e.getMessage().contains("ACT_RU_EXT_TASK 1"), e.getMessage());
        assertEquals("APPLIED", tool.run("r1").orElseThrow().state(), "the rollback did not start");
        assertEquals(Instant.parse("2026-01-15T09:00:00Z"), tx.inTransaction(c -> Jdbc.one(c,
                "SELECT create_time FROM HRM_TASK WHERE id = 'k1'", rs -> Jdbc.instant(rs, "create_time")).orElseThrow()));
        sql("UPDATE ACT_RU_EXT_TASK SET LOCK_EXP_TIME_ = ?", Instant.parse("2000-01-01T00:00:00Z"));
        assertEquals("ROLLED_BACK", tool.rollback("r1", "MW-2", "operator", "ops").state());
        assertEquals(Instant.parse("2026-01-15T10:00:00Z"), tx.inTransaction(c -> Jdbc.one(c,
                "SELECT create_time FROM HRM_TASK WHERE id = 'k1'", rs -> Jdbc.instant(rs, "create_time")).orElseThrow()));
    }

    @Test
    void anEngineTableNeedsItsLockTablesOnTheSameConnection() {
        tx.inTransaction(c -> {
            assertDoesNotThrow(() -> EngineLocks.requireNone(c, Dialect.POSTGRESQL, "HRM_TASK"),
                    "a product table: maintenance reference alone when the engine schema is elsewhere");
            assertThrows(IllegalStateException.class, () -> EngineLocks.requireNone(c, Dialect.POSTGRESQL, "ACT_HI_OP_LOG"));
            return null;
        });
        engineLockTables();
        tx.inTransaction(c -> {
            assertDoesNotThrow(() -> EngineLocks.requireNone(c, Dialect.POSTGRESQL, "ACT_HI_OP_LOG"));
            return null;
        });
    }
}
