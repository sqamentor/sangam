// SPDX-License-Identifier: Apache-2.0
package org.sangam.rollup;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.sangam.cell.rollup.RollupEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Idempotent projection of the roll-up stream (SCALE-ARCHITECTURE.md sections 8.2 and 8.4).
 *
 * <p><b>Per event</b> ({@link #project}): one transaction: (1) insert the event id into {@code RU_EVENT_SEEN}
 * (duplicate key = already projected, the transaction is rolled back and the event acknowledged); (2) add the event's
 * deltas to {@code RU_COUNTER} for <em>every prefix</em> of the tenant hierarchy path (hospital, district, state,
 * nation), on shard {@code hash(eventId) mod shards}; (3) register unseen hierarchy nodes; (4) bump the per-cell feed
 * shard; (5) on a forwarding tier, add the deltas still owed to the upper tier ({@code RU_UPSTREAM_PENDING}). Because
 * dedup and counters commit together, a redelivered event can never be counted twice and a crash never loses one.
 *
 * <p><b>Micro-batch</b> ({@link #projectBatch}, M-063, 8.4 b): an ingest array in one transaction: the ids already
 * projected are read once, the new ones inserted together, and their deltas summed per (node, metric, day)
 * ({@link RollupBatch}) before each distinct row is written once. The same dedup row commits with the same counters,
 * so the batch is as exact as the per-event path. If a concurrent writer inserts one of the batch's ids (or the first
 * row of one of its keys) in between, the whole batch rolls back and is projected event by event.
 *
 * <p>What an event counts is defined once, in {@link RollupEvents}; the tier feed is {@link RollupUpstreamEmitter}.
 * Hierarchy nodes are remembered only after their transaction commits, so a rolled-back insert is retried.
 */
@Service
public class RollupProjector {

    private static final Logger LOG = LoggerFactory.getLogger(RollupProjector.class);

    public enum Outcome { PROJECTED, DUPLICATE, REJECTED }

    /** Counts of one ingest call. */
    public record BatchResult(int projected, int duplicates, int rejected) {
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final RollupProperties props;
    private final RollupWriter writer;
    private final RollupMetrics metrics;
    private final int forwardDepth;
    private final Set<String> knownNodes = ConcurrentHashMap.newKeySet();

    @Autowired
    public RollupProjector(JdbcTemplate jdbc, PlatformTransactionManager tm, RollupProperties props, RollupMetrics metrics) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(tm);
        this.props = props;
        this.writer = new RollupWriter(jdbc);
        this.metrics = metrics;
        this.forwardDepth = forwardDepth(props);
    }

    public RollupProjector(JdbcTemplate jdbc, PlatformTransactionManager tm, RollupProperties props) {
        this(jdbc, tm, props, RollupMetrics.inMemory());
    }

    /** {@code upstream.depth} when forwarding (default: one level below the scope, or 1), else -1. */
    static int forwardDepth(RollupProperties props) {
        if (!props.upstream().enabled()) {
            return -1;
        }
        Integer depth = props.upstream().depth();
        return depth != null ? depth : Math.max(1, prefixes(props.scope()).size());
    }

    /** A cell's event (no TIER role). */
    public Outcome project(RollupEnvelope e) {
        return project(e, false);
    }

    /** One event in its own transaction (8.2). */
    public Outcome project(RollupEnvelope e, boolean tierCaller) {
        long t0 = System.nanoTime();
        Outcome o = projectOne(e, tierCaller);
        metrics.event(System.nanoTime() - t0);
        metrics.outcome(o, 1);
        return o;
    }

    private Outcome projectOne(RollupEnvelope e, boolean tierCaller) {
        String reject = RollupEvents.validate(e, tierCaller, props.scope());
        if (reject != null) {
            LOG.warn("Rejected roll-up event {}: {}", e.id(), reject);
            return Outcome.REJECTED;
        }
        RollupEvents.Effect effect = RollupEvents.effect(e, props.perDefinitionMetrics());
        for (int attempt = 1; ; attempt++) {
            Set<String> inserted = new HashSet<>();
            try {
                tx.executeWithoutResult(s -> apply(e, effect, inserted));
                knownNodes.addAll(inserted);
                return Outcome.PROJECTED;
            } catch (DuplicateKeyException dup) {
                if (isSeenDuplicate(e.id())) {
                    return Outcome.DUPLICATE;
                }
                if (attempt >= 5) {
                    throw dup;
                }
                // a concurrent first insert of a counter/node/pending row: retry the whole event (all-or-nothing)
            } catch (DataIntegrityViolationException d) {
                if (attempt >= 5) {
                    throw d;
                }
            }
        }
    }

    /** Every event of one ingest call: one batch transaction when pre-aggregation is on and there are several. */
    public BatchResult projectAll(List<RollupEnvelope> events, boolean tierCaller) {
        if (events.size() > 1 && props.preAggregate()) {
            return projectBatch(events, tierCaller);
        }
        int projected = 0;
        int duplicates = 0;
        int rejected = 0;
        for (RollupEnvelope e : events) {
            switch (project(e, tierCaller)) {
                case PROJECTED -> projected++;
                case DUPLICATE -> duplicates++;
                case REJECTED -> rejected++;
            }
        }
        return new BatchResult(projected, duplicates, rejected);
    }

    /** M-063 micro-batch: see the class comment. */
    public BatchResult projectBatch(List<RollupEnvelope> events, boolean tierCaller) {
        long t0 = System.nanoTime();
        int rejected = 0;
        int duplicates = 0;
        Map<String, RollupEnvelope> fresh = new LinkedHashMap<>();
        Map<String, RollupEvents.Effect> effects = new LinkedHashMap<>();
        for (RollupEnvelope e : events) {
            String reject = RollupEvents.validate(e, tierCaller, props.scope());
            if (reject != null) {
                LOG.warn("Rejected roll-up event {}: {}", e.id(), reject);
                rejected++;
            } else if (fresh.putIfAbsent(e.id(), e) != null) {
                duplicates++;   // the same id twice in one batch: counted once
            } else {
                effects.put(e.id(), RollupEvents.effect(e, props.perDefinitionMetrics()));
            }
        }
        int projected = 0;
        if (!fresh.isEmpty()) {
            Set<String> inserted = new HashSet<>();
            RollupBatch batch = new RollupBatch(forwardDepth);
            try {
                Integer n = tx.execute(s -> applyBatch(fresh, effects, batch, inserted));
                projected = n == null ? 0 : n;
                duplicates += fresh.size() - projected;
                knownNodes.addAll(inserted);
                metrics.batch(System.nanoTime() - t0, batch.events(), batch.rows(), batch.naiveRows());
            } catch (DataIntegrityViolationException race) {
                // Rolled back as a whole: a concurrent writer inserted one of these ids or the first row of one of
                // the keys. Event by event is exact (each event's own dedup row decides).
                metrics.fallback();
                LOG.debug("Roll-up batch of {} events replayed event by event: {}", fresh.size(), race.getMessage());
                for (RollupEnvelope e : fresh.values()) {
                    switch (projectOne(e, tierCaller)) {
                        case PROJECTED -> projected++;
                        case DUPLICATE -> duplicates++;
                        case REJECTED -> rejected++;
                    }
                }
            }
        }
        metrics.outcome(Outcome.PROJECTED, projected);
        metrics.outcome(Outcome.DUPLICATE, duplicates);
        metrics.outcome(Outcome.REJECTED, rejected);
        return new BatchResult(projected, duplicates, rejected);
    }

    private int applyBatch(Map<String, RollupEnvelope> fresh, Map<String, RollupEvents.Effect> effects, RollupBatch batch,
                           Set<String> inserted) {
        long now = System.currentTimeMillis();
        Set<String> seen = writer.alreadySeen(fresh.keySet());
        List<Object[]> seenRows = new ArrayList<>();
        String first = null;
        for (RollupEnvelope e : fresh.values()) {
            if (seen.contains(e.id())) {
                continue;
            }
            first = first == null ? e.id() : first;
            seenRows.add(new Object[]{e.id(), e.cell(), RollupWriter.clipTenant(tenantOf(e)), now});
            batch.add(e.cell(), effects.get(e.id()));
        }
        if (seenRows.isEmpty()) {
            return 0;
        }
        writer.insertSeen(seenRows);
        // one shard per batch: concurrent batches spread over the shards like single events do
        int shard = Math.floorMod(first.hashCode(), props.shards());
        for (RollupBatch.Node n : batch.nodes().values()) {
            ensureNode(n.path(), n.parent(), n.depth(), now, inserted);
        }
        batch.counters().forEach((k, v) -> writer.addCounter(k.node(), k.metric(), k.day(), shard, v, now));
        batch.pending().forEach((k, v) -> writer.addPending(k.node(), k.metric(), k.day(), shard, v, now));
        int feedShard = Math.floorMod(first.hashCode() >>> 8, props.shards());
        batch.feedEvents().forEach((cell, n) -> writer.bumpFeed(cell, feedShard, n, now));
        return seenRows.size();
    }

    private boolean isSeenDuplicate(String id) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM RU_EVENT_SEEN WHERE event_id = ?", Long.class, id);
        return n != null && n > 0;
    }

    private void apply(RollupEnvelope e, RollupEvents.Effect effect, Set<String> inserted) {
        long now = System.currentTimeMillis();
        // A duplicate id throws DuplicateKeyException; the whole transaction rolls back (PostgreSQL aborts it anyway)
        // and project() classifies it outside the transaction.
        writer.insertSeen(e.id(), e.cell(), tenantOf(e), now);
        int shard = Math.floorMod(e.id().hashCode(), props.shards());
        List<String> prefixes = effect.prefixes();
        for (int i = 0; i < prefixes.size(); i++) {
            String node = prefixes.get(i);
            ensureNode(node, i == 0 ? null : prefixes.get(i - 1), i, now, inserted);
            for (RollupEvents.Delta d : effect.deltas()) {
                writer.addCounter(node, d.metric(), effect.day(), shard, d.value(), now);
            }
        }
        if (forwardDepth >= 0) {
            String cut = RollupEvents.cutNode(prefixes, forwardDepth);
            for (RollupEvents.Delta d : effect.deltas()) {
                writer.addPending(cut, d.metric(), effect.day(), shard, d.value(), now);
            }
        }
        writer.bumpFeed(e.cell(), Math.floorMod(e.id().hashCode() >>> 8, props.shards()), 1L, now);
    }

    private void ensureNode(String node, String parent, int depth, long now, Set<String> inserted) {
        if (knownNodes.contains(node) || inserted.contains(node)) {
            return;
        }
        writer.ensureNode(node, parent, depth, now);
        inserted.add(node);   // remembered in knownNodes only once the transaction has committed
    }

    private static String tenantOf(RollupEnvelope e) {
        return e.tenant() == null ? RollupEvents.lastSegment(e.tenantPath()) : e.tenant();
    }

    static List<String> prefixes(String path) {
        String[] parts = path.split("/");
        List<String> out = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isBlank()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append('/');
            }
            sb.append(p.trim());
            out.add(sb.toString());
        }
        return out;
    }

    static int dayBucket(String isoTime) {
        LocalDate d;
        try {
            d = isoTime == null ? LocalDate.now(ZoneOffset.UTC) : Instant.parse(isoTime).atZone(ZoneOffset.UTC).toLocalDate();
        } catch (DateTimeParseException ex) {
            d = LocalDate.now(ZoneOffset.UTC);
        }
        return d.getYear() * 10000 + d.getMonthValue() * 100 + d.getDayOfMonth();
    }

    /** Forget event ids older than the dedup retention (bounded table). */
    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT5M")
    public void pruneSeen() {
        long cutoff = System.currentTimeMillis() - props.dedupRetention().toMillis();
        int n = jdbc.update("DELETE FROM RU_EVENT_SEEN WHERE received_at_ms < ?", cutoff);
        if (n > 0) {
            LOG.info("Pruned {} roll-up dedup rows older than {}", n, props.dedupRetention());
        }
    }

    /** Exposed for tests and the admin view. */
    public Map<String, Integer> config() {
        return Map.of("shards", props.shards());
    }
}
