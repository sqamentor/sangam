// SPDX-License-Identifier: Apache-2.0
package org.sangam.ops.utc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
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
import org.sangam.integration.connector.Json;
import org.sangam.ops.utc.UtcConversionTool.Run;
import org.sangam.persistence.SangamSchema;

/**
 * Decisions 150 and 203 on H2 with the product changelog and the generated timestamp inventory: dry run first, then
 * apply in resumable batches with a per-cell recovery snapshot, reconcile, rollback. Round trips, precision, ordering,
 * already-UTC rows, mixed data (two tenants, two zones), DST gap and overlap, the cutover boundary and a restart after an
 * interrupted apply. The same flows on Oracle and SQL Server are sangam-server {@code UtcConversionVendorIT} (NOT_RUN in
 * this coding phase).
 */
class UtcConversionToolH2Test {

    static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata");
    static final Instant CUTOVER = Instant.parse("2026-11-01T00:00:00Z");
    static final String OPS = "ops";

    Transactions tx;
    final AuditChain audit = new AuditChain(Dialect.POSTGRESQL);
    UtcConversionTool tool;

    @BeforeEach
    void db() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=TRUE");
        SangamSchema.apply(ds);
        tx = Transactions.of(ds);
        tool = new UtcConversionTool(tx, Dialect.POSTGRESQL, audit, UtcInventory.product());
    }

    static Instant at(String s) {
        return Instant.parse(s);
    }

    void task(String id, String tenant, String create, String end) {
        tx.inTransaction(c -> Jdbc.update(c, "INSERT INTO HRM_TASK(id, tenant_id, state, create_time, end_time)"
                + " VALUES (?, ?, 'completed', ?, ?)", id, tenant, create == null ? null : at(create),
                end == null ? null : at(end)));
    }

    Instant value(String id, String column) {
        return tx.inTransaction(c -> Jdbc.query(c, "SELECT " + column + " FROM HRM_TASK WHERE id = ?",
                rs -> Jdbc.instant(rs, column), id).get(0));
    }

    static UtcDataset tasks(ZoneId zone, String tenant) {
        return new UtcDataset("HRM_TASK", List.of("create_time", "end_time"), "create_time", zone, CUTOVER, tenant);
    }

    /** Tenant t1 written in Berlin until the cutover; t2 written in Kolkata (mixed data, separate datasets). */
    void corpus() {
        task("k1", "t1", "2026-01-15T10:00:00.123456Z", "2026-01-15T11:00:00Z"); // winter, +01:00, microseconds
        task("k2", "t1", "2026-05-10T10:00:00Z", null);                            // summer, +02:00
        task("k3", "t1", "2026-11-15T10:00:00Z", "2026-11-15T12:00:00Z");          // after the cutover: already UTC
        task("k4", "t1", null, "2026-02-01T08:00:00Z");                            // no provenance
        task("k5", "t1", "2026-03-29T02:30:00Z", "2026-03-29T03:30:00Z");          // DST gap
        task("k6", "t1", "2026-10-25T02:30:00Z", null);                            // DST overlap
        task("k8", "t1", "2026-11-01T00:30:00Z", null);                            // either side of the cutover
        task("k7", "t2", "2026-01-15T10:00:00Z", null);                            // other tenant, other zone
    }

    @Test
    void dryRunCountsAndChangesNothing() {
        corpus();
        Run dry = tool.dryRun("r1", tasks(BERLIN, "t1"), "operator", OPS);
        assertEquals("DRY_RUN", dry.state());
        assertEquals(7, dry.rowsRead());
        assertEquals(3, dry.converted(), "k1 two cells, k2 one");
        assertEquals(1, dry.alreadyUtc());
        assertEquals(5, dry.unresolved(), "k4 end, k5 two, k6 one, k8 one");
        Map<String, Object> report = Json.readMap(dry.report());
        assertEquals(Map.of("CUTOVER_AMBIGUOUS", 1, "DST_GAP", 2, "DST_OVERLAP", 1, "NO_PROVENANCE", 1),
                report.get("unresolvedByReason"));
        assertEquals(0, ((Number) report.get("precisionChanges")).intValue());
        assertEquals(1, ((Number) report.get("subSecondValues")).intValue());
        assertTrue(report.get("samples").toString().contains("2026-01-15T09:00:00.123456Z"), report.toString());
        assertEquals(at("2026-01-15T10:00:00.123456Z"), value("k1", "create_time"), "a dry run changes no row");
        assertEquals(0L, (long) tx.inTransaction(c -> Jdbc.one(c, "SELECT COUNT(*) FROM OPS_UTC_CELL",
                rs -> rs.getLong(1)).orElseThrow()));
        assertThrows(IllegalArgumentException.class, () -> tool.dryRun("r1", tasks(BERLIN, "t1"), "operator", OPS),
                "a run id is used once");
    }

    @Test
    void applyConvertsOnlyPreCutoverInstantsOnceThenReconcilesAndRollsBack() {
        corpus();
        tool.dryRun("r1", tasks(BERLIN, "t1"), "operator", OPS);
        Run applied = tool.apply("r1", 2, "MW-2026-11-20", "operator", OPS);
        assertEquals("APPLIED", applied.state());
        assertEquals(3, applied.converted());
        assertEquals(1, applied.alreadyUtc());
        assertEquals(5, applied.unresolved());
        assertEquals(at("2026-01-15T09:00:00.123456Z"), value("k1", "create_time"), "Berlin winter, microseconds kept");
        assertEquals(at("2026-01-15T10:00:00Z"), value("k1", "end_time"));
        assertEquals(at("2026-05-10T08:00:00Z"), value("k2", "create_time"), "Berlin summer");
        assertEquals(at("2026-11-15T10:00:00Z"), value("k3", "create_time"), "already UTC");
        assertEquals(at("2026-03-29T02:30:00Z"), value("k5", "create_time"), "a DST gap is not chosen");
        assertEquals(at("2026-10-25T02:30:00Z"), value("k6", "create_time"), "a DST overlap is not chosen");
        assertEquals(at("2026-11-01T00:30:00Z"), value("k8", "create_time"), "an ambiguous cutover row is not chosen");
        assertEquals(at("2026-01-15T10:00:00Z"), value("k7", "create_time"), "another tenant's dataset is untouched");
        assertTrue(value("k1", "create_time").isBefore(value("k2", "create_time")), "ordering kept");

        assertThrows(IllegalStateException.class, () -> tool.apply("r1", 2, "MW", "operator", OPS), "applied once");
        tool.dryRun("r2", tasks(BERLIN, "t1"), "operator", OPS);
        Run again = tool.apply("r2", 10, "MW-2026-11-20", "operator", OPS);
        assertEquals(0, again.converted(), "no cell is converted twice");
        assertEquals(3, again.alreadyConverted());
        assertEquals(at("2026-01-15T09:00:00.123456Z"), value("k1", "create_time"));

        Run t2 = tool.dryRun("r3", tasks(KOLKATA, "t2"), "operator", OPS);
        assertEquals(1, t2.converted());
        tool.apply("r3", 10, "MW-2026-11-20", "operator", OPS);
        assertEquals(at("2026-01-15T04:30:00Z"), value("k7", "create_time"), "mixed data: each dataset its own zone");

        Run reconciled = tool.reconcile("r1", "auditor", OPS);
        assertEquals("RECONCILED", reconciled.state());
        assertEquals(3, reconciled.matched());
        assertEquals(0, reconciled.mismatched());

        tx.inTransaction(c -> Jdbc.update(c, "UPDATE HRM_TASK SET create_time = ? WHERE id = 'k2'",
                at("2026-05-10T08:05:00Z")));
        Run tampered = tool.reconcile("r1", "auditor", OPS);
        assertEquals("APPLIED", tampered.state(), "complete only after a clean reconciliation");
        assertEquals(1, tampered.mismatched());

        assertThrows(IllegalStateException.class, () -> tool.rollback("r1", "MW-2026-11-21", "operator", OPS),
                "a value changed after the conversion is never overwritten");
        tx.inTransaction(c -> Jdbc.update(c, "UPDATE HRM_TASK SET create_time = ? WHERE id = 'k2'",
                at("2026-05-10T08:00:00Z")));
        Run rolled = tool.rollback("r1", "MW-2026-11-21", "operator", OPS);
        assertEquals("ROLLED_BACK", rolled.state());
        assertEquals(3, rolled.restored());
        assertEquals(at("2026-01-15T10:00:00.123456Z"), value("k1", "create_time"), "original value restored");
        assertEquals(at("2026-01-15T11:00:00Z"), value("k1", "end_time"));
        assertEquals(at("2026-05-10T10:00:00Z"), value("k2", "create_time"));
        assertEquals(at("2026-01-15T04:30:00Z"), value("k7", "create_time"), "another run's cells are not rolled back");

        Run redo = tool.dryRun("r4", tasks(BERLIN, "t1"), "operator", OPS);
        assertEquals(3, redo.converted(), "restored cells may be converted by a later run");
        assertTrue(tx.inTransaction(c -> audit.verify(c, OPS, UtcConversionTool.CHAIN_PREFIX + "r1")).ok());
    }

    void item(String run, String source, String updated) {
        tx.inTransaction(c -> Jdbc.update(c, "INSERT INTO OPS_FMIG_ITEM(run_id, source_instance_id, tenant_id, status,"
                + " plan_json, fingerprint, updated_at) VALUES (?, ?, 't1', 'PLANNED', '{}', 'f', ?)", run, source,
                at(updated)));
    }

    Instant itemValue(String run, String source) {
        return tx.inTransaction(c -> Jdbc.query(c, "SELECT updated_at FROM OPS_FMIG_ITEM WHERE run_id = ? AND"
                + " source_instance_id = ?", rs -> Jdbc.instant(rs, "updated_at"), run, source).get(0));
    }

    @Test
    void anInterruptedApplyResumesAfterItsLastCommittedBatchOverACompositeKey() {
        for (String run : List.of("ra", "rb")) {
            for (String s : List.of("a", "b", "c")) {
                item(run, s, "2026-02-01T10:00:00Z");
            }
        }
        UtcDataset items = new UtcDataset("OPS_FMIG_ITEM", List.of("updated_at"), "updated_at", KOLKATA, CUTOVER, null);
        tool.dryRun("i1", items, "operator", OPS);
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
        };
        UtcConversionTool crashing = new UtcConversionTool(failing, Dialect.POSTGRESQL, audit, UtcInventory.product());
        assertThrows(IllegalStateException.class, () -> crashing.apply("i1", 2, "MW-1", "operator", OPS));
        Run half = tool.run("i1").orElseThrow();
        assertEquals("APPLYING", half.state());
        assertEquals(2, half.converted(), "the first batch committed with its resume key");

        Run done = tool.apply("i1", 2, "MW-1", "operator", OPS);
        assertEquals("APPLIED", done.state());
        assertEquals(6, done.converted(), "resumed, every cell once");
        for (String run : List.of("ra", "rb")) {
            for (String s : List.of("a", "b", "c")) {
                assertEquals(at("2026-02-01T04:30:00Z"), itemValue(run, s), run + s);
            }
        }
        assertEquals("RECONCILED", tool.reconcile("i1", "auditor", OPS).state());
    }

    @Test
    void refusals() {
        corpus();
        assertThrows(IllegalArgumentException.class, () -> tasks(ZoneOffset.ofHours(1), "t1"), "fixed offset");
        assertThrows(IllegalArgumentException.class, () -> tasks(ZoneId.of("UTC+05:30"), "t1"), "offset-style id");
        assertThrows(IllegalArgumentException.class, () -> tool.dryRun("d1", new UtcDataset("PG_PERIOD",
                List.of("due_on"), "changed_at", BERLIN, CUTOVER, null), "operator", OPS), "date-only never converted");
        assertThrows(IllegalArgumentException.class, () -> tool.dryRun("d2", new UtcDataset("AUDIT_EVENT",
                List.of("occurred_at"), "occurred_at", BERLIN, CUTOVER, null), "operator", OPS), "hash-chained rows");
        assertThrows(IllegalArgumentException.class, () -> tool.dryRun("d3", new UtcDataset("OPS_UTC_RUN",
                List.of("cutover_at"), "created_at", BERLIN, CUTOVER, null), "operator", OPS), "the tool's own tables");
        assertThrows(IllegalArgumentException.class, () -> tool.dryRun("d4", new UtcDataset("HRM_TASK",
                List.of("name"), "create_time", BERLIN, CUTOVER, null), "operator", OPS), "not inventoried");
        assertThrows(IllegalArgumentException.class, () -> tool.dryRun("d 5", tasks(BERLIN, "t1"), "operator", OPS));
        assertThrows(IllegalArgumentException.class, () -> tool.dryRun("d6", tasks(BERLIN, "t1"), " ", OPS));
        assertThrows(IllegalArgumentException.class, () -> tool.dryRun("d7", tasks(BERLIN, "t1"), "operator", ""));
        assertThrows(IllegalArgumentException.class, () -> tool.apply("nope", 10, "MW", "operator", OPS));

        tool.dryRun("r1", tasks(BERLIN, "t1"), "operator", OPS);
        assertThrows(IllegalArgumentException.class, () -> tool.apply("r1", 10, " ", "operator", OPS),
                "the maintenance window is named");
        assertThrows(IllegalStateException.class, () -> tool.reconcile("r1", "auditor", OPS), "not applied yet");
        assertThrows(IllegalStateException.class, () -> tool.rollback("r1", "MW", "operator", OPS), "nothing applied");

        tx.inTransaction(c -> Jdbc.update(c, "UPDATE OPS_UTC_RUN SET fingerprint = 'x' WHERE run_id = 'r1'"));
        assertThrows(IllegalStateException.class, () -> tool.apply("r1", 10, "MW", "operator", OPS),
                "a run whose dataset or zone rules changed after its dry run is refused");

        tool.dryRun("r2", tasks(BERLIN, "t1"), "operator", OPS);
        tool.dryRun("r3", tasks(BERLIN, "t1"), "operator", OPS);
        tx.inTransaction(c -> Jdbc.update(c, "UPDATE OPS_UTC_RUN SET state = 'APPLYING' WHERE run_id = 'r2'"));
        assertThrows(IllegalStateException.class, () -> tool.apply("r3", 10, "MW", "operator", OPS),
                "one changing run per table");
        assertEquals(at("2026-01-15T10:00:00.123456Z"), value("k1", "create_time"));
        assertNull(tool.run("r3").orElseThrow().maintenanceRef());
    }

    @Test
    void wallClockRules() {
        assertEquals(at("2026-01-15T09:00:00Z"), WallClock.resolve(at("2026-01-15T10:00:00Z"), BERLIN).instant());
        assertEquals(WallClock.Unresolved.DST_GAP, WallClock.resolve(at("2026-03-29T02:00:00Z"), BERLIN).unresolved());
        assertEquals(WallClock.Unresolved.DST_OVERLAP,
                WallClock.resolve(at("2026-10-25T02:59:59.999999Z"), BERLIN).unresolved());
        assertEquals(at("2026-10-25T02:00:00Z"), WallClock.resolve(at("2026-10-25T03:00:00Z"), BERLIN).instant(),
                "the first instant after the overlap is unique");
        assertEquals(at("2026-11-15T10:00:00Z"), WallClock.resolve(at("2026-11-15T10:00:00Z"), ZoneId.of("UTC"))
                .instant(), "UTC is a named zone; already-UTC data maps to itself");
        assertNull(WallClock.side(at("2026-11-01T01:00:00Z"), BERLIN, CUTOVER).unresolved(), "after the cutover");
        assertNull(WallClock.side(at("2026-11-01T01:00:00Z"), BERLIN, CUTOVER).instant());
        assertEquals(WallClock.Unresolved.CUTOVER_INCONSISTENT,
                WallClock.side(at("2026-10-31T23:30:00Z"), ZoneId.of("America/New_York"), CUTOVER).unresolved(),
                "a wall clock behind UTC that fits neither side");
    }
}
