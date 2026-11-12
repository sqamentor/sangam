// SPDX-License-Identifier: Apache-2.0
package org.sangam.rollup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.sangam.cell.rollup.MiniJson;
import org.sangam.cell.rollup.RollupEnvelope;

/**
 * M-063 (SCALE-ARCHITECTURE 8.4 a) on H2: a state roll-up (scope IN/RJ, forwarding districts) feeds a national
 * roll-up through explicit metric events, and the national totals at and above the forwarded level equal the state's
 * own totals exactly, also under redelivery (T-SCALE-10 shape, small).
 */
class RollupTierH2Test {

    private final RollupTestDb stateDb = new RollupTestDb();
    private final RollupTestDb nationDb = new RollupTestDb();
    private final RollupProperties stateProps = RollupTestDb.props(true, "IN/RJ", RollupTestDb.upstream("rj-1", null));
    private final RollupProjector state = new RollupProjector(stateDb.jdbc, stateDb.tm, stateProps);
    private final RollupProjector nation = new RollupProjector(nationDb.jdbc, nationDb.tm, RollupTestDb.props(true, "", null));
    private final RollupMetrics metrics = RollupMetrics.inMemory();
    private final RollupUpstreamEmitter emitter = new RollupUpstreamEmitter(stateDb.jdbc, stateDb.tm, stateProps, metrics);
    private final List<String> posted = new ArrayList<>();

    /** The national ingest as the state's transport sees it (the TIER role, the controller's 202/422 rule). */
    private RollupUpstreamSender.UpstreamTransport toNation() {
        return json -> {
            posted.add(json);
            List<RollupEnvelope> events = new ArrayList<>();
            for (Object o : (List<?>) MiniJson.read(json)) {
                events.add(RollupEnvelope.parse(MiniJson.write(o)));
            }
            RollupProjector.BatchResult r = nation.projectAll(events, true);
            int status = events.size() == 1 && r.rejected() == 1 ? 422 : 202;
            return new RollupUpstreamSender.UpstreamTransport.Response(status, "{\"projected\":" + r.projected()
                    + ",\"duplicates\":" + r.duplicates() + ",\"rejected\":" + r.rejected() + "}");
        };
    }

    private List<RollupEnvelope> stateStream() {
        List<RollupEnvelope> out = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            String path = "IN/RJ/RJ-D" + (i % 3) + "/H" + (i % 4);
            out.add(i % 5 == 4 ? RollupTestDb.end("s" + i, path) : RollupTestDb.start("s" + i, path));
        }
        return out;
    }

    @Test
    void stateRejectsEventsOutsideItsScope() {
        RollupProjector.BatchResult r = state.projectBatch(List.of(RollupTestDb.start("mh", "IN/MH/D1"),
                RollupTestDb.start("rj", "IN/RJ/D1")), false);
        assertEquals(new RollupProjector.BatchResult(1, 0, 1), r);
        assertEquals(RollupProjector.Outcome.REJECTED, state.project(RollupTestDb.start("mh2", "IN/MH/D1")));
    }

    @Test
    void pendingDeltasAreKeptPerDistrictInTheCounterTransaction() {
        state.projectAll(stateStream(), false);
        long districtStarts = stateDb.count("SELECT COALESCE(SUM(pending_delta), 0) FROM RU_UPSTREAM_PENDING"
                + " WHERE node_path = 'IN/RJ/RJ-D0' AND metric = 'process.start'");
        assertEquals(stateDb.total("IN/RJ/RJ-D0", "process.start", 20261112), districtStarts);
        assertEquals(0, stateDb.count("SELECT COUNT(*) FROM RU_UPSTREAM_PENDING WHERE node_path LIKE 'IN/RJ/RJ-D0/%'"),
                "hospitals are not forwarded at depth 2");
    }

    @Test
    void nationalTotalsEqualTheStateTotalsAtAndAboveTheForwardedLevel() {
        List<RollupEnvelope> events = stateStream();
        state.projectAll(events.subList(0, 17), false);
        emitter.drain();
        state.projectAll(events.subList(17, 30), false);   // more arrives after the first interval
        emitter.drain();
        RollupUpstreamSender sender = new RollupUpstreamSender(stateDb.jdbc, stateProps, metrics, toNation());
        while (sender.sendDue() > 0) {
            // drain the outbox
        }

        assertEquals(0, stateDb.count("SELECT COUNT(*) FROM RU_UPSTREAM_OUTBOX"), "every event delivered");
        assertEquals(0, stateDb.count("SELECT COUNT(*) FROM RU_UPSTREAM_PENDING"), "nothing owed");
        for (String node : List.of("IN", "IN/RJ", "IN/RJ/RJ-D0", "IN/RJ/RJ-D1", "IN/RJ/RJ-D2")) {
            for (String metric : List.of("process.start", "process.end", "process.open", "process.start#p1")) {
                assertEquals(stateDb.total(node, metric, 20261112), nationDb.total(node, metric, 20261112),
                        node + " " + metric);
            }
        }
        assertEquals(24, nationDb.total("IN", "process.start", 20261112));
        assertEquals(0, nationDb.count("SELECT COUNT(*) FROM RU_NODE WHERE depth > 2"), "hospitals stay in the state");
        assertEquals(1, nationDb.count("SELECT COUNT(*) FROM RU_NODE WHERE node_path = 'IN/RJ' AND parent_path = 'IN'"));
        assertTrue(nationDb.count("SELECT COALESCE(SUM(events), 0) FROM RU_CELL_FEED WHERE cell_id = 'rj-1'") > 0,
                "the national feed view shows the state instance");
    }

    @Test
    void redeliveryToTheNationIsDeduplicated() {
        state.projectAll(stateStream(), false);
        emitter.drain();
        List<String> payloads = stateDb.jdbc.queryForList("SELECT payload FROM RU_UPSTREAM_OUTBOX", String.class);
        String array = "[" + String.join(",", payloads) + "]";
        RollupUpstreamSender.UpstreamTransport t = toNation();
        try {
            t.post(array);
            Map<String, Long> once = nationDb.totals();
            t.post(array);   // the state's sender lost the first answer and sends again
            assertEquals(once, nationDb.totals());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void guardedDecrementMissesARowThatChangedAfterItWasRead() {
        state.project(RollupTestDb.start("a", "IN/RJ/D1/H1"));
        // the emitter read pending_delta = 1; a projection then adds 1 before the decrement: the guarded UPDATE
        // (pending_delta = value read) must change nothing, so the addition is neither lost nor shipped twice
        stateDb.jdbc.update("UPDATE RU_UPSTREAM_PENDING SET pending_delta = pending_delta + 1 WHERE metric = 'process.start'");
        Map<String, Object> row = stateDb.jdbc.queryForMap("SELECT node_path, metric, day_bucket, shard FROM"
                + " RU_UPSTREAM_PENDING WHERE metric = 'process.start'");
        assertEquals(0, stateDb.jdbc.update(RollupUpstreamEmitter.DECREMENT, 1L, 0L, row.get("NODE_PATH"),
                row.get("METRIC"), row.get("DAY_BUCKET"), row.get("SHARD"), 1L), "stale value read: no decrement");

        int queued = emitter.drain();
        assertEquals(3, queued, "process.start (2), process.start#p1 (1) and process.open (1)");
        assertEquals(0, stateDb.count("SELECT COUNT(*) FROM RU_UPSTREAM_PENDING"));
        long shipped = 0;
        for (String p : stateDb.jdbc.queryForList("SELECT payload FROM RU_UPSTREAM_OUTBOX", String.class)) {
            RollupEnvelope e = RollupEnvelope.parse(p);
            if ("process.start".equals(e.data().get("metric"))) {
                shipped += (Long) e.data().get("delta");
            }
        }
        assertEquals(2, shipped, "what was in the row when decremented, nothing more, nothing less");
    }

    @Test
    void senderRetriesOutagesAndParksOnlyPoisonEvents() {
        state.projectAll(stateStream(), false);
        emitter.drain();
        long queued = stateDb.count("SELECT COUNT(*) FROM RU_UPSTREAM_OUTBOX");
        AtomicInteger calls = new AtomicInteger();

        RollupUpstreamSender down = new RollupUpstreamSender(stateDb.jdbc, stateProps, metrics,
                json -> { calls.incrementAndGet(); return new RollupUpstreamSender.UpstreamTransport.Response(503, ""); });
        long before = System.currentTimeMillis();
        down.sendDue();
        assertEquals(queued, stateDb.count("SELECT COUNT(*) FROM RU_UPSTREAM_OUTBOX WHERE attempts = 1 AND parked = 0"));
        assertEquals(queued, stateDb.count("SELECT COUNT(*) FROM RU_UPSTREAM_OUTBOX WHERE next_attempt_ms >= ?",
                before + 1000), "due again only after the back-off");
        assertTrue(calls.get() >= 1);

        stateDb.jdbc.update("UPDATE RU_UPSTREAM_OUTBOX SET next_attempt_ms = 0");
        RollupUpstreamSender unauthorised = new RollupUpstreamSender(stateDb.jdbc, stateProps, metrics,
                json -> new RollupUpstreamSender.UpstreamTransport.Response(401, ""));
        unauthorised.sendDue();
        assertEquals(0, stateDb.count("SELECT COUNT(*) FROM RU_UPSTREAM_OUTBOX WHERE parked = 1"),
                "a wrong token is configuration, not poison");

        stateDb.jdbc.update("UPDATE RU_UPSTREAM_OUTBOX SET next_attempt_ms = 0");
        RollupUpstreamSender poison = new RollupUpstreamSender(stateDb.jdbc, stateProps, metrics,
                json -> new RollupUpstreamSender.UpstreamTransport.Response(422,
                        "{\"projected\":0,\"duplicates\":0,\"rejected\":1}"));
        poison.sendDue();
        assertTrue(stateDb.count("SELECT COUNT(*) FROM RU_UPSTREAM_OUTBOX WHERE parked = 1") > 0);
        assertEquals(stateDb.count("SELECT COUNT(*) FROM RU_UPSTREAM_OUTBOX"),
                stateDb.count("SELECT COUNT(*) FROM RU_UPSTREAM_OUTBOX WHERE parked = 1"), "parked, never deleted");
        assertEquals(Map.of("queued", 0L, "parked", queued), poison.queue());
    }

    @Test
    void senderRulesAreTheRelayRules() {
        assertTrue(RollupUpstreamSender.permanent(422));
        assertTrue(RollupUpstreamSender.permanent(400));
        assertTrue(!RollupUpstreamSender.permanent(401) && !RollupUpstreamSender.permanent(404));
        assertTrue(!RollupUpstreamSender.permanent(429) && !RollupUpstreamSender.permanent(503));
        assertEquals(1000L, RollupUpstreamSender.backoffMs(0));
        assertEquals(8000L, RollupUpstreamSender.backoffMs(3));
        assertEquals(RollupUpstreamSender.MAX_BACKOFF_MS, RollupUpstreamSender.backoffMs(40));
        assertEquals(0, RollupUpstreamSender.rejected("{\"projected\":3,\"duplicates\":0,\"rejected\":0}"));
        assertEquals(1, RollupUpstreamSender.rejected("not json"), "an unreadable answer is looked at event by event");
    }
}
