// SPDX-License-Identifier: Apache-2.0
package org.sangam.rollup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** M-063 (SCALE-ARCHITECTURE 8.4 b): in-memory pre-aggregation is exact and writes fewer rows. */
class RollupBatchTest {

    private static RollupEvents.Effect effect(org.sangam.cell.rollup.RollupEnvelope e) {
        return RollupEvents.effect(e, false);
    }

    @Test
    void sumsDeltasPerNodeMetricAndDay() {
        RollupBatch b = new RollupBatch(-1);
        b.add("c1", effect(RollupTestDb.start("1", "IN/RJ/D1/H1")));
        b.add("c1", effect(RollupTestDb.start("2", "IN/RJ/D1/H2")));
        b.add("c2", effect(RollupTestDb.start("3", "IN/RJ/D2/H3")));
        Map<RollupBatch.Key, Long> c = b.counters();
        assertEquals(3L, c.get(new RollupBatch.Key("IN", "process.start", 20261112)));
        assertEquals(3L, c.get(new RollupBatch.Key("IN/RJ", "process.open", 20261112)));
        assertEquals(2L, c.get(new RollupBatch.Key("IN/RJ/D1", "process.start", 20261112)));
        assertEquals(1L, c.get(new RollupBatch.Key("IN/RJ/D2/H3", "process.start", 20261112)));
        assertEquals(3, b.events());
        assertEquals(3L * 4 * 2, b.naiveRows(), "the per-event path: 4 levels x 2 metrics per event");
        // IN, IN/RJ, IN/RJ/D1, IN/RJ/D2, H1, H2, H3 = 7 nodes x 2 metrics
        assertEquals(14, b.rows());
        assertEquals(Map.of("c1", 2L, "c2", 1L), b.feedEvents());
        assertTrue(b.pending().isEmpty(), "not a forwarding tier");
    }

    @Test
    void cancellingDeltasStillWriteTheCounterButShipNothing() {
        RollupBatch b = new RollupBatch(1);
        b.add("c1", effect(RollupTestDb.start("1", "IN/RJ/D1")));
        b.add("c1", effect(RollupTestDb.end("2", "IN/RJ/D1")));
        assertEquals(0L, b.counters().get(new RollupBatch.Key("IN/RJ", "process.open", 20261112)),
                "written as +0 so the same rows exist as after the per-event path");
        assertFalse(b.pending().containsKey(new RollupBatch.Key("IN/RJ", "process.open", 20261112)));
        assertEquals(1L, b.pending().get(new RollupBatch.Key("IN/RJ", "process.start", 20261112)));
        assertEquals(1L, b.pending().get(new RollupBatch.Key("IN/RJ", "process.end", 20261112)));
    }

    @Test
    void pendingIsKeyedByTheCutNode() {
        RollupBatch b = new RollupBatch(2);
        b.add("c1", effect(RollupTestDb.start("1", "IN/RJ/D1/H1")));
        b.add("c1", effect(RollupTestDb.start("2", "IN/RJ/D1/H2")));
        b.add("c1", effect(RollupTestDb.start("3", "IN/RJ")));   // shorter than the forward depth: its own node
        assertEquals(2L, b.pending().get(new RollupBatch.Key("IN/RJ/D1", "process.start", 20261112)));
        assertEquals(1L, b.pending().get(new RollupBatch.Key("IN/RJ", "process.start", 20261112)));
        assertEquals(2 * 2, b.pending().size(), "two cut nodes x (process.start, process.open)");
    }

    @Test
    void nodesAreSortedParentFirstWithTheirParentAndDepth() {
        RollupBatch b = new RollupBatch(-1);
        b.add("c1", effect(RollupTestDb.start("1", "IN/RJ/D1")));
        List<RollupBatch.Node> nodes = List.copyOf(b.nodes().values());
        assertEquals(List.of(new RollupBatch.Node("IN", null, 0), new RollupBatch.Node("IN/RJ", "IN", 1),
                new RollupBatch.Node("IN/RJ/D1", "IN/RJ", 2)), nodes);
    }

    @Test
    void keysAreWrittenInOneOrderSoConcurrentBatchesDoNotDeadlock() {
        RollupBatch b = new RollupBatch(-1);
        b.add("c1", effect(RollupTestDb.start("1", "IN/MH")));
        b.add("c1", effect(RollupTestDb.start("2", "IN/AP")));
        List<RollupBatch.Key> keys = List.copyOf(b.counters().keySet());
        for (int i = 1; i < keys.size(); i++) {
            assertTrue(RollupBatch.ORDER.compare(keys.get(i - 1), keys.get(i)) < 0, keys.toString());
        }
    }
}
