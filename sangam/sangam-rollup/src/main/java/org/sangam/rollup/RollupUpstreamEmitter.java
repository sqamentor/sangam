// SPDX-License-Identifier: Apache-2.0
package org.sangam.rollup;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import org.sangam.cell.rollup.RollupEnvelope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * State-level roll-up tier, shipping side (M-063, SCALE-ARCHITECTURE.md 8.4 a): turns the deltas this instance owes
 * the upper tier ({@code RU_UPSTREAM_PENDING}, written by {@link RollupProjector} in the same transaction as the
 * counters) into explicit metric events in {@code RU_UPSTREAM_OUTBOX}, one per (node, metric, day) and interval.
 * {@link RollupUpstreamSender} delivers them.
 *
 * <p>Exactness: in one transaction, each pending row is decremented by exactly the value read
 * ({@code UPDATE ... SET pending_delta = pending_delta - v WHERE ... AND pending_delta = v}) and the event carrying
 * that value is inserted. A projection that adds to the row in between makes the guarded decrement miss; that row
 * simply ships at the next interval. Nothing is shipped twice (a second emitter's decrement misses too) and nothing is
 * lost (the decrement and the event commit or roll back together). The upper tier deduplicates on the event id, so the
 * sender may redeliver.
 */
@Component
public class RollupUpstreamEmitter {

    /** The guarded decrement: subtracts exactly the value read, and only while the row still holds it. */
    static final String DECREMENT = "UPDATE RU_UPSTREAM_PENDING SET pending_delta = pending_delta - ?, updated_at_ms = ?"
            + " WHERE node_path = ? AND metric = ? AND day_bucket = ? AND shard = ? AND pending_delta = ?";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final RollupProperties props;
    private final RollupMetrics metrics;

    public RollupUpstreamEmitter(JdbcTemplate jdbc, PlatformTransactionManager tm, RollupProperties props,
                                 RollupMetrics metrics) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(tm);
        this.props = props;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${sangam.rollup.upstream.interval:PT1M}",
            initialDelayString = "${sangam.rollup.upstream.interval:PT1M}")
    public void scheduled() {
        if (props.upstream().enabled()) {
            drain();
        }
    }

    /** Moves every pending delta into the outbox; returns the events written. */
    public int drain() {
        int limit = Math.max(1, props.maxBatch());
        int total = 0;
        while (true) {
            int[] readAndQueued = tx.execute(s -> drainOnce(limit));
            if (readAndQueued == null) {
                return total;
            }
            total += readAndQueued[1];
            // stop when the table is drained, or when every guarded decrement missed (rows busy: next interval)
            if (readAndQueued[0] < limit || readAndQueued[2] == 0) {
                return total;
            }
        }
    }

    /** {rows read, events queued, rows decremented}. */
    int[] drainOnce(int limit) {
        long now = System.currentTimeMillis();
        JdbcTemplate limited = new JdbcTemplate(jdbc.getDataSource());   // same transaction-bound connection
        limited.setMaxRows(limit);
        List<Object[]> rows = limited.query("SELECT node_path, metric, day_bucket, shard, pending_delta FROM RU_UPSTREAM_PENDING"
                        + " WHERE pending_delta <> 0 ORDER BY node_path, metric, day_bucket, shard",
                (rs, i) -> new Object[]{rs.getString(1), rs.getString(2), rs.getInt(3), rs.getInt(4), rs.getLong(5)});
        Map<RollupBatch.Key, Long> sums = new TreeMap<>(RollupBatch.ORDER);
        int decremented = 0;
        for (Object[] r : rows) {
            long v = (Long) r[4];
            int n = jdbc.update(DECREMENT, v, now, r[0], r[1], r[2], r[3], v);
            if (n == 1) {
                decremented++;
                sums.merge(new RollupBatch.Key((String) r[0], (String) r[1], (Integer) r[2]), v, Long::sum);
                jdbc.update("DELETE FROM RU_UPSTREAM_PENDING WHERE node_path = ? AND metric = ? AND day_bucket = ?"
                        + " AND shard = ? AND pending_delta = 0", r[0], r[1], r[2], r[3]);
            }
        }
        List<Object[]> outbox = new ArrayList<>();
        sums.forEach((k, v) -> {
            if (v != 0L) {
                String id = UUID.randomUUID().toString();   // the outbox key is the event id the upper tier dedups on
                outbox.add(new Object[]{id, eventJson(id, props.upstream().instanceId(), k, v, now), now, 0, now, 0});
            }
        });
        if (!outbox.isEmpty()) {
            jdbc.batchUpdate("INSERT INTO RU_UPSTREAM_OUTBOX(event_id, payload, created_at_ms, attempts, next_attempt_ms,"
                    + " parked) VALUES (?,?,?,?,?,?)", outbox);
        }
        metrics.upstream("queued", outbox.size());
        return new int[]{rows.size(), outbox.size(), decremented};
    }

    /** The explicit metric event of one (node, metric, day) delta, as the upper tier's ingest reads it. */
    static String eventJson(String id, String instanceId, RollupBatch.Key k, long delta, long now) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("metric", k.metric());
        data.put("day", (long) k.day());
        data.put("delta", delta);
        return new RollupEnvelope(id, "sangam://rollup/" + instanceId, RollupEvents.METRIC_TYPE,
                Instant.ofEpochMilli(now).toString(), instanceId, RollupEvents.lastSegment(k.node()), k.node(), null, 0L,
                null, data).toJson();
    }
}
