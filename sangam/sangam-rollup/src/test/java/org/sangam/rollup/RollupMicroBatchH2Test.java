// SPDX-License-Identifier: Apache-2.0
package org.sangam.rollup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.sangam.cell.rollup.RollupEnvelope;

/**
 * M-063 (SCALE-ARCHITECTURE 8.4 b) on H2 with the generated changelog: the micro-batch path counts exactly what the
 * per-event path counts, stays idempotent under redelivery, and records the SG-15 measurement hooks.
 */
class RollupMicroBatchH2Test {

    private static List<RollupEnvelope> stream(int n) {
        List<RollupEnvelope> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String path = "IN/" + (i % 2 == 0 ? "RJ" : "MH") + "/D" + (i % 3) + "/H" + (i % 5);
            out.add(i % 4 == 3 ? RollupTestDb.end("e" + i, path) : RollupTestDb.start("e" + i, path));
        }
        return out;
    }

    @Test
    void batchTotalsEqualPerEventTotals() {
        RollupTestDb perEvent = new RollupTestDb();
        RollupTestDb batched = new RollupTestDb();
        RollupProjector one = new RollupProjector(perEvent.jdbc, perEvent.tm, RollupTestDb.props(false, "", null));
        RollupProjector many = new RollupProjector(batched.jdbc, batched.tm, RollupTestDb.props(true, "", null));
        List<RollupEnvelope> events = stream(40);

        RollupProjector.BatchResult a = one.projectAll(events, false);
        RollupProjector.BatchResult b = many.projectAll(events, false);

        assertEquals(new RollupProjector.BatchResult(40, 0, 0), a);
        assertEquals(new RollupProjector.BatchResult(40, 0, 0), b);
        assertEquals(perEvent.totals(), batched.totals(), "same (node, metric, day) totals, zero rows included");
        assertEquals(perEvent.count("SELECT COUNT(*) FROM RU_NODE"), batched.count("SELECT COUNT(*) FROM RU_NODE"));
        assertEquals(40, batched.count("SELECT COUNT(*) FROM RU_EVENT_SEEN"));
        assertEquals(40, batched.count("SELECT COALESCE(SUM(events), 0) FROM RU_CELL_FEED WHERE cell_id = 'c1'"));
        assertEquals(30, batched.total("IN", "process.start", 20261112));
        assertEquals(10, batched.total("IN", "process.end", 20261112));
        assertEquals(20, batched.total("IN", "process.open", 20261112));
    }

    @Test
    void redeliveredBatchCountsNothingTwice() {
        RollupTestDb db = new RollupTestDb();
        RollupProjector p = new RollupProjector(db.jdbc, db.tm, RollupTestDb.props(true, "", null));
        List<RollupEnvelope> events = stream(12);
        p.projectBatch(events, false);
        Map<String, Long> before = db.totals();

        assertEquals(new RollupProjector.BatchResult(0, 12, 0), p.projectBatch(events, false));
        assertEquals(before, db.totals());

        // a batch that overlaps a projected one: only the new ids count
        List<RollupEnvelope> overlap = new ArrayList<>(events.subList(6, 12));
        overlap.add(RollupTestDb.start("new-1", "IN/RJ/D0/H0"));
        assertEquals(new RollupProjector.BatchResult(1, 6, 0), p.projectBatch(overlap, false));
        assertEquals(before.get("IN|process.start|20261112") + 1, db.total("IN", "process.start", 20261112));
    }

    @Test
    void sameIdTwiceInOneBatchCountsOnce() {
        RollupTestDb db = new RollupTestDb();
        RollupProjector p = new RollupProjector(db.jdbc, db.tm, RollupTestDb.props(true, "", null));
        RollupEnvelope e = RollupTestDb.start("dup", "IN/RJ");
        assertEquals(new RollupProjector.BatchResult(1, 1, 0), p.projectBatch(List.of(e, e), false));
        assertEquals(1, db.total("IN/RJ", "process.start", 20261112));
    }

    @Test
    void rejectedEventsAreCountedAndTheRestIsProjected() {
        RollupTestDb db = new RollupTestDb();
        RollupProjector p = new RollupProjector(db.jdbc, db.tm, RollupTestDb.props(true, "", null));
        RollupEnvelope bad = RollupTestDb.start("", "IN/RJ");
        RollupEnvelope explicitFromCell = RollupTestDb.metric("m1", "IN/RJ", "task.complete", 20261112L, 50L);
        RollupProjector.BatchResult r = p.projectBatch(List.of(bad, explicitFromCell, RollupTestDb.start("ok", "IN/RJ")),
                false);
        assertEquals(new RollupProjector.BatchResult(1, 0, 2), r);
        assertEquals(0, db.total("IN/RJ", "task.complete", 20261112), "a cell token cannot move totals by 50");
    }

    @Test
    void nodeTreeIsRegisteredWithParentsAndDepths() {
        RollupTestDb db = new RollupTestDb();
        RollupProjector p = new RollupProjector(db.jdbc, db.tm, RollupTestDb.props(true, "", null));
        p.projectBatch(List.of(RollupTestDb.start("1", "IN/RJ/D1"), RollupTestDb.start("2", "IN/RJ/D2")), false);
        assertEquals(1, db.count("SELECT COUNT(*) FROM RU_NODE WHERE node_path = 'IN' AND parent_path IS NULL AND depth = 0"));
        assertEquals(2, db.count("SELECT COUNT(*) FROM RU_NODE WHERE parent_path = 'IN/RJ' AND depth = 2"));
    }

    @Test
    void aSingleEventGoesThroughThePerEventPath() {
        RollupTestDb db = new RollupTestDb();
        RollupMetrics metrics = RollupMetrics.inMemory();
        RollupProjector p = new RollupProjector(db.jdbc, db.tm, RollupTestDb.props(true, "", null), metrics);
        p.projectAll(List.of(RollupTestDb.start("solo", "IN")), false);
        assertEquals(1L, metrics.registry().get("sangam.rollup.ingest").tag("mode", "event").timer().count());
        assertEquals(0L, metrics.registry().get("sangam.rollup.ingest").tag("mode", "batch").timer().count());
    }

    @Test
    void measurementHooksRecordTheRowSaving() {
        RollupTestDb db = new RollupTestDb();
        RollupMetrics metrics = RollupMetrics.inMemory();
        RollupProjector p = new RollupProjector(db.jdbc, db.tm, RollupTestDb.props(true, "", null), metrics);
        p.projectBatch(List.of(RollupTestDb.start("1", "IN/RJ/D1/H1"), RollupTestDb.start("2", "IN/RJ/D1/H1"),
                RollupTestDb.start("3", "IN/RJ/D1/H1")), false);
        var registry = metrics.registry();
        assertEquals(1L, registry.get("sangam.rollup.ingest").tag("mode", "batch").timer().count());
        assertEquals(3.0, registry.get("sangam.rollup.batch.events").summary().totalAmount());
        // 3 events x 4 levels x 3 metrics (start, start#p1, open) = 36 naive writes; 4 levels x 3 metrics = 12 rows
        assertEquals(36.0, registry.get("sangam.rollup.batch.rows.naive").summary().totalAmount());
        assertEquals(12.0, registry.get("sangam.rollup.batch.rows").summary().totalAmount());
        assertEquals(3.0, registry.get("sangam.rollup.events").tag("outcome", "projected").counter().count());
        assertTrue(registry.get("sangam.rollup.batch.fallback").counter().count() == 0.0);
    }
}
