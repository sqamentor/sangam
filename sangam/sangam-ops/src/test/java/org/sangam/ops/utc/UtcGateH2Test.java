// SPDX-License-Identifier: Apache-2.0
package org.sangam.ops.utc;

import org.sangam.core.api.ReadWork;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;
import org.sangam.ops.utc.UtcGate.Gate;
import org.sangam.persistence.SangamSchema;

/**
 * Integrator ruling 2026-11-13T04:07:40Z items 3 and 4 (option C): the UTC tool closes the engine-work gate
 * ({@code OPS_UTC_GATE} row ENGINE_WORK) in its own committed transaction before a run moves to APPLYING or ROLLING_BACK,
 * keeps {@link EngineLocks} as the second check, and reopens the gate in the transaction that reaches APPLIED or
 * ROLLED_BACK. A refusal before any row changed reopens it; a run left APPLYING or ROLLING_BACK keeps it closed until it
 * is resumed or rolled back. The engine side (shared lock on the row in the acquisition transaction) is W4-E's
 * {@code UtcConversionHardStopH2Test}; the lock interplay on real databases runs in the db-matrix (NOT_RUN).
 */
class UtcGateH2Test {

    static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    static final Instant CUTOVER = Instant.parse("2026-11-01T00:00:00Z");
    static final Instant FAR = Instant.parse("2999-01-01T00:00:00Z");

    Transactions tx;
    final AuditChain audit = new AuditChain(Dialect.POSTGRESQL);
    UtcConversionTool tool;

    @BeforeEach
    void db() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=TRUE");
        SangamSchema.apply(ds);
        tx = Transactions.of(ds);
        sql("CREATE TABLE ACT_RU_JOB (ID_ VARCHAR(64) PRIMARY KEY, LOCK_EXP_TIME_ TIMESTAMP WITH TIME ZONE)");
        sql("CREATE TABLE ACT_RU_EXT_TASK (ID_ VARCHAR(64) PRIMARY KEY, LOCK_EXP_TIME_ TIMESTAMP WITH TIME ZONE)");
        for (String id : List.of("k1", "k2", "k3")) {
            sql("INSERT INTO HRM_TASK(id, tenant_id, state, create_time) VALUES (?, 't1', 'completed', ?)", id,
                    Instant.parse("2026-01-15T10:00:00Z"));
        }
        tool = tool(tx);
    }

    UtcConversionTool tool(Transactions t) {
        return new UtcConversionTool(t, Dialect.POSTGRESQL, audit, UtcInventory.product());
    }

    void sql(String sql, Object... params) {
        tx.inTransaction(c -> Jdbc.update(c, sql, params));
    }

    static UtcDataset tasks() {
        return new UtcDataset("HRM_TASK", List.of("create_time"), "create_time", BERLIN, CUTOVER, "t1");
    }

    Gate gate() {
        return tx.inTransaction(UtcGate::read).orElseThrow();
    }

    Instant value(String id) {
        return tx.inTransaction(c -> Jdbc.one(c, "SELECT create_time FROM HRM_TASK WHERE id = ?",
                rs -> Jdbc.instant(rs, "create_time"), id).orElseThrow());
    }

    List<String> gateEvents() {
        return tx.inTransaction(c -> Jdbc.query(c, "SELECT e.event_type, p.payload FROM AUDIT_PAYLOAD p JOIN AUDIT_EVENT e"
                + " ON e.event_id = p.event_id WHERE e.chain_id = ? ORDER BY e.seq",
                rs -> rs.getString(1) + " " + rs.getString(2), UtcGate.CHAIN));
    }

    /** Records the committed gate, read in a separate transaction, before each transaction of the tool. */
    final class Observing implements Transactions {
        final List<Gate> seen = new ArrayList<>();

        @Override
        public <T> T inTransaction(Function<Connection, T> work) {
            seen.add(gate());
            return tx.inTransaction(work);
        }

        @Override
        public <T> T read(ReadWork<T> work) {
            seen.add(gate());
            return tx.read(work);
        }
    }

    @Test
    void theSeededGateIsOpen() {
        Gate g = gate();
        assertEquals(UtcGate.OPEN, g.state());
        assertNull(g.runId());
        assertEquals(0, g.version());
    }

    @Test
    void applyClosesTheGateInItsOwnCommitBeforeApplyingAndReopensAtApplied() {
        tool.dryRun("r1", tasks(), "operator", "ops");
        Observing observing = new Observing();
        assertEquals("APPLIED", tool(observing).apply("r1", 100, "MW-1", "operator", "ops").state());
        assertEquals(new Gate(UtcGate.OPEN, null, 0), observing.seen.get(0), "open before the close");
        for (Gate g : observing.seen.subList(1, observing.seen.size())) {
            assertEquals(new Gate(UtcGate.CLOSED, "r1", 1), g,
                    "committed CLOSED before the APPLYING transaction and every batch: " + observing.seen);
        }
        assertEquals(new Gate(UtcGate.OPEN, null, 2), gate(), "reopened with APPLIED");
        assertEquals(Instant.parse("2026-01-15T09:00:00Z"), value("k1"));
        List<String> events = gateEvents();
        assertEquals(2, events.size(), events.toString());
        assertTrue(events.get(0).startsWith(UtcGate.EVENT_CLOSED) && events.get(0).contains("\"runId\":\"r1\"")
                && events.get(0).contains("\"purpose\":\"apply\""), events.toString());
        assertTrue(events.get(1).startsWith(UtcGate.EVENT_OPENED) && events.get(1).contains("\"purpose\":\"applied\""),
                events.toString());
    }

    @Test
    void rollbackClosesTheGateBeforeRollingBackAndReopensAtRolledBack() {
        tool.dryRun("r1", tasks(), "operator", "ops");
        tool.apply("r1", 100, "MW-1", "operator", "ops");
        Observing observing = new Observing();
        assertEquals("ROLLED_BACK", tool(observing).rollback("r1", "MW-2", "operator", "ops").state());
        assertEquals(new Gate(UtcGate.OPEN, null, 2), observing.seen.get(0));
        for (Gate g : observing.seen.subList(1, observing.seen.size())) {
            assertEquals(new Gate(UtcGate.CLOSED, "r1", 3), g, observing.seen.toString());
        }
        assertEquals(new Gate(UtcGate.OPEN, null, 4), gate());
        assertEquals(Instant.parse("2026-01-15T10:00:00Z"), value("k1"));
        assertEquals(4, gateEvents().size());
    }

    @Test
    void aGateHeldByAnotherRunRefusesApplyAndRollbackAndChangesNothing() {
        tool.dryRun("r1", tasks(), "operator", "ops");
        sql("UPDATE OPS_UTC_GATE SET state = 'CLOSED', run_id = 'other', version = 7 WHERE gate_id = 'ENGINE_WORK'");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> tool.apply("r1", 100, "MW-1", "operator", "ops"));
        assertTrue(e.getMessage().contains("CLOSED by run other"), e.getMessage());
        assertEquals("DRY_RUN", tool.run("r1").orElseThrow().state());
        assertEquals(new Gate(UtcGate.CLOSED, "other", 7), gate(), "another run's gate is never reopened");
        assertEquals(Instant.parse("2026-01-15T10:00:00Z"), value("k1"));

        sql("UPDATE OPS_UTC_GATE SET state = 'OPEN', run_id = NULL WHERE gate_id = 'ENGINE_WORK'");
        tool.apply("r1", 100, "MW-1", "operator", "ops");
        sql("UPDATE OPS_UTC_GATE SET state = 'CLOSED', run_id = 'other' WHERE gate_id = 'ENGINE_WORK'");
        assertThrows(IllegalStateException.class, () -> tool.rollback("r1", "MW-2", "operator", "ops"));
        assertEquals("APPLIED", tool.run("r1").orElseThrow().state());
        assertEquals("other", gate().runId());
        assertEquals(Instant.parse("2026-01-15T09:00:00Z"), value("k1"));
    }

    @Test
    void aMissingGateRowRefusesBeforeAnyChange() {
        tool.dryRun("r1", tasks(), "operator", "ops");
        sql("DELETE FROM OPS_UTC_GATE");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> tool.apply("r1", 100, "MW-1", "operator", "ops"));
        assertTrue(e.getMessage().contains("missing"), e.getMessage());
        assertEquals("DRY_RUN", tool.run("r1").orElseThrow().state());
        assertEquals(Instant.parse("2026-01-15T10:00:00Z"), value("k1"));
    }

    /** The engine locks stay the second check, behind the closed gate; that refusal changed no row, so the gate reopens. */
    @Test
    void aLiveEngineLockBehindTheGateRefusesAndTheGateReopens() {
        tool.dryRun("r1", tasks(), "operator", "ops");
        sql("INSERT INTO ACT_RU_JOB(ID_, LOCK_EXP_TIME_) VALUES ('j1', ?)", FAR);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> tool.apply("r1", 100, "MW-1", "operator", "ops"));
        assertTrue(e.getMessage().contains("ACT_RU_JOB 1"), e.getMessage());
        assertEquals("DRY_RUN", tool.run("r1").orElseThrow().state());
        assertEquals(new Gate(UtcGate.OPEN, null, 2), gate(), "closed, then reopened: nothing changed");
        List<String> events = gateEvents();
        assertTrue(events.get(1).startsWith(UtcGate.EVENT_OPENED) && events.get(1).contains("apply refused"),
                events.toString());
        assertRows("2026-01-15T10:00:00Z", "the refused apply changed no row");
        assertEquals(0L, cells("r1"), "and wrote no snapshot cell");

        sql("UPDATE ACT_RU_JOB SET LOCK_EXP_TIME_ = ?", Instant.parse("2000-01-01T00:00:00Z")); // expired
        assertEquals("APPLIED", tool.apply("r1", 100, "MW-1", "operator", "ops").state());
        assertRows("2026-01-15T09:00:00Z", "applied");
        sql("UPDATE ACT_RU_JOB SET LOCK_EXP_TIME_ = ?", FAR);
        assertThrows(IllegalStateException.class, () -> tool.rollback("r1", "MW-2", "operator", "ops"));
        assertEquals("APPLIED", tool.run("r1").orElseThrow().state());
        assertEquals(UtcGate.OPEN, gate().state(), "a refused rollback of a complete run reopens too");
        assertRows("2026-01-15T09:00:00Z", "the refused rollback changed no row");
        long notConverted = tx.inTransaction(c -> Jdbc.one(c, "SELECT COUNT(*) FROM OPS_UTC_CELL WHERE run_id = 'r1'"
                + " AND status <> 'CONVERTED'", rs -> rs.getLong(1)).orElseThrow());
        assertEquals(0L, notConverted, "and restored no snapshot cell");
    }

    void assertRows(String expected, String message) {
        for (String id : List.of("k1", "k2", "k3")) {
            assertEquals(Instant.parse(expected), value(id), message + ": " + id);
        }
    }

    long cells(String runId) {
        return tx.inTransaction(c -> Jdbc.one(c, "SELECT COUNT(*) FROM OPS_UTC_CELL WHERE run_id = ?",
                rs -> rs.getLong(1), runId).orElseThrow());
    }

    /** Owner decision 262: the gate stays CLOSED after a failed APPLYING; only the same run reaching APPLIED reopens it. */
    @Test
    void anInterruptedApplyKeepsTheGateClosedUntilItIsResumedOrRolledBack() {
        tool.dryRun("r1", tasks(), "operator", "ops");
        AtomicInteger calls = new AtomicInteger();
        Transactions failing = new Transactions() {
            @Override
            public <T> T inTransaction(Function<Connection, T> work) {
                // 1 gate close, 2 start (APPLYING), 3 row access, 4 first batch (committed), 5 second batch: stop
                if (calls.incrementAndGet() == 5) {
                    throw new IllegalStateException("simulated stop after the first batch");
                }
                return tx.inTransaction(work);
            }

            @Override
            public <T> T read(ReadWork<T> work) {
                // 1 gate close, 2 start (APPLYING), 3 row access, 4 first batch (committed), 5 second batch: stop
                if (calls.incrementAndGet() == 5) {
                    throw new IllegalStateException("simulated stop after the first batch");
                }
                return tx.read(work);
            }
        };
        assertThrows(IllegalStateException.class, () -> tool(failing).apply("r1", 1, "MW-1", "operator", "ops"));
        assertEquals("APPLYING", tool.run("r1").orElseThrow().state());
        assertEquals(new Gate(UtcGate.CLOSED, "r1", 1), gate(), "part of the table is converted: engine work waits");

        tool.dryRun("r2", tasks(), "operator", "ops");
        assertThrows(IllegalStateException.class, () -> tool.apply("r2", 100, "MW-1", "operator", "ops"),
                "no other run while r1 holds the gate");
        assertEquals(new Gate(UtcGate.CLOSED, "r1", 1), gate());

        assertEquals("APPLIED", tool.apply("r1", 1, "MW-1", "operator", "ops").state(), "resumed under its own gate");
        assertEquals(new Gate(UtcGate.OPEN, null, 3), gate());
    }

    @Test
    void anInterruptedApplyIsRolledBackUnderItsOwnGate() {
        tool.dryRun("r1", tasks(), "operator", "ops");
        AtomicInteger calls = new AtomicInteger();
        Transactions failing = new Transactions() {
            @Override
            public <T> T inTransaction(Function<Connection, T> work) {
                if (calls.incrementAndGet() == 5) {
                    throw new IllegalStateException("simulated stop after the first batch");
                }
                return tx.inTransaction(work);
            }

            @Override
            public <T> T read(ReadWork<T> work) {
                if (calls.incrementAndGet() == 5) {
                    throw new IllegalStateException("simulated stop after the first batch");
                }
                return tx.read(work);
            }
        };
        assertThrows(IllegalStateException.class, () -> tool(failing).apply("r1", 1, "MW-1", "operator", "ops"));
        assertEquals(UtcGate.CLOSED, gate().state());
        assertEquals("ROLLED_BACK", tool.rollback("r1", "MW-2", "operator", "ops").state());
        assertEquals(new Gate(UtcGate.OPEN, null, 3), gate());
        for (String id : List.of("k1", "k2", "k3")) {
            assertEquals(Instant.parse("2026-01-15T10:00:00Z"), value(id), id);
        }
    }

    /** Owner decision 262: a failed ROLLING_BACK keeps the gate CLOSED; the same run's resumed rollback reopens it. */
    @Test
    void anInterruptedRollbackKeepsTheGateClosedUntilItsRollbackIsResumed() {
        tool.dryRun("r1", tasks(), "operator", "ops");
        assertEquals("APPLIED", tool.apply("r1", 100, "MW-1", "operator", "ops").state());
        AtomicInteger calls = new AtomicInteger();
        Transactions failing = new Transactions() {
            @Override
            public <T> T inTransaction(Function<Connection, T> work) {
                // 1 gate close, 2 start (ROLLING_BACK, committed), 3 row access: stop
                if (calls.incrementAndGet() == 3) {
                    throw new IllegalStateException("simulated stop after the rollback started");
                }
                return tx.inTransaction(work);
            }

            @Override
            public <T> T read(ReadWork<T> work) {
                // 1 gate close, 2 start (ROLLING_BACK, committed), 3 row access: stop
                if (calls.incrementAndGet() == 3) {
                    throw new IllegalStateException("simulated stop after the rollback started");
                }
                return tx.read(work);
            }
        };
        assertThrows(IllegalStateException.class, () -> tool(failing).rollback("r1", "MW-2", "operator", "ops"));
        assertEquals("ROLLING_BACK", tool.run("r1").orElseThrow().state());
        assertEquals(new Gate(UtcGate.CLOSED, "r1", 3), gate(), "no reopen after a failed rollback");
        assertEquals(Instant.parse("2026-01-15T09:00:00Z"), value("k1"), "nothing restored yet");

        tool.dryRun("r2", new UtcDataset("OPS_FMIG_ITEM", List.of("updated_at"), "updated_at", BERLIN, CUTOVER, null),
                "operator", "ops");
        IllegalStateException other = assertThrows(IllegalStateException.class,
                () -> tool.apply("r2", 100, "MW-3", "operator", "ops"), "another table waits too: one gate");
        assertTrue(other.getMessage().contains("CLOSED by run r1"), other.getMessage());
        assertEquals("DRY_RUN", tool.run("r2").orElseThrow().state());
        assertEquals(new Gate(UtcGate.CLOSED, "r1", 3), gate(), "the refused run never reopens r1's gate");

        assertEquals("ROLLED_BACK", tool.rollback("r1", "MW-2", "operator", "ops").state(), "resumed under its own gate");
        assertEquals(new Gate(UtcGate.OPEN, null, 5), gate());
        for (String id : List.of("k1", "k2", "k3")) {
            assertEquals(Instant.parse("2026-01-15T10:00:00Z"), value(id), id);
        }
    }
}
