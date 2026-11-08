// SPDX-License-Identifier: Apache-2.0
package org.sangam.rollup;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.sangam.cell.rollup.RollupEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Idempotent projection of the roll-up stream (SCALE-ARCHITECTURE.md section 8.2).
 *
 * <p>One transaction per event: (1) insert the event id into {@code RU_EVENT_SEEN} (duplicate key = already projected,
 * the transaction is rolled back and the event acknowledged); (2) add the event's deltas to {@code RU_COUNTER} for
 * <em>every prefix</em> of the tenant hierarchy path (hospital, district, state, nation), on shard
 * {@code hash(eventId) mod shards}; (3) register unseen hierarchy nodes; (4) bump the per-cell feed shard. Because
 * dedup and counters commit together, a redelivered event can never be counted twice and a crash never loses one
 * (the relay redelivers anything not acknowledged).
 *
 * <p>Metrics are generic (ADR-D-20): the event type with the {@code sangam.history.} prefix removed
 * ({@code process.start}, {@code task.complete}, {@code incident.create} ...), optionally per process definition.
 * One derived gauge: {@code process.open} = starts minus ends.
 */
@Service
public class RollupProjector {

    private static final Logger LOG = LoggerFactory.getLogger(RollupProjector.class);
    private static final String HISTORY_PREFIX = "sangam.history.";

    public enum Outcome { PROJECTED, DUPLICATE, REJECTED }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final RollupProperties props;
    private final Set<String> knownNodes = ConcurrentHashMap.newKeySet();

    public RollupProjector(JdbcTemplate jdbc, PlatformTransactionManager tm, RollupProperties props) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(tm);
        this.props = props;
    }

    public Outcome project(RollupEnvelope e) {
        String reject = validate(e);
        if (reject != null) {
            LOG.warn("Rejected roll-up event {}: {}", e.id(), reject);
            return Outcome.REJECTED;
        }
        for (int attempt = 1; ; attempt++) {
            try {
                Boolean projected = tx.execute(s -> apply(e));
                return Boolean.TRUE.equals(projected) ? Outcome.PROJECTED : Outcome.DUPLICATE;
            } catch (DuplicateKeyException dup) {
                if (isSeenDuplicate(e.id())) {
                    return Outcome.DUPLICATE;
                }
                if (attempt >= 5) {
                    throw dup;
                }
                // a concurrent first insert of a counter/node row: retry the whole event (all-or-nothing)
            } catch (DataIntegrityViolationException d) {
                if (attempt >= 5) {
                    throw d;
                }
            }
        }
    }

    static String validate(RollupEnvelope e) {
        if (e.id() == null || e.id().isBlank() || e.id().length() > 64) {
            return "missing or oversized id";
        }
        if (e.type() == null || e.type().isBlank()) {
            return "missing type";
        }
        if (e.cell() == null || e.cell().isBlank() || e.cell().length() > 16) {
            return "missing sangamcell";
        }
        if (e.tenantPath() == null || e.tenantPath().isBlank() || e.tenantPath().length() > 256) {
            return "missing or oversized sangamtenantpath";
        }
        return null;
    }

    private boolean isSeenDuplicate(String id) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM RU_EVENT_SEEN WHERE event_id = ?", Long.class, id);
        return n != null && n > 0;
    }

    private boolean apply(RollupEnvelope e) {
        long now = System.currentTimeMillis();
        String tenant = e.tenant() == null ? lastSegment(e.tenantPath()) : e.tenant();
        // A duplicate id throws DuplicateKeyException; the whole transaction rolls back (PostgreSQL aborts it anyway)
        // and project() classifies it outside the transaction.
        jdbc.update("INSERT INTO RU_EVENT_SEEN(event_id, cell_id, tenant_code, received_at_ms) VALUES (?,?,?,?)",
                e.id(), e.cell(), tenant.length() > 64 ? tenant.substring(0, 64) : tenant, now);
        int day = dayBucket(e.time());
        int shard = Math.floorMod(e.id().hashCode(), props.shards());
        List<Delta> deltas = deltas(e);
        List<String> prefixes = prefixes(e.tenantPath());
        for (int i = 0; i < prefixes.size(); i++) {
            String node = prefixes.get(i);
            ensureNode(node, i == 0 ? null : prefixes.get(i - 1), i, now);
            for (Delta d : deltas) {
                add(node, d.metric(), day, shard, d.value(), now);
            }
        }
        int feedShard = Math.floorMod(e.id().hashCode() >>> 8, props.shards());
        if (jdbc.update("UPDATE RU_CELL_FEED SET events = events + 1, last_event_at_ms = ? WHERE cell_id = ? AND shard = ?",
                now, e.cell(), feedShard) == 0) {
            jdbc.update("INSERT INTO RU_CELL_FEED(cell_id, shard, events, last_event_at_ms) VALUES (?,?,?,?)",
                    e.cell(), feedShard, 1L, now);
        }
        return true;
    }

    record Delta(String metric, long value) {
    }

    List<Delta> deltas(RollupEnvelope e) {
        String metric = e.type().startsWith(HISTORY_PREFIX) ? e.type().substring(HISTORY_PREFIX.length()) : e.type();
        metric = clip(metric);
        List<Delta> out = new ArrayList<>();
        out.add(new Delta(metric, 1));
        Object def = e.data() == null ? null : e.data().get("processDefinitionKey");
        if (props.perDefinitionMetrics() && def != null) {
            out.add(new Delta(clip(metric + "#" + def), 1));
        }
        if ("process.start".equals(metric)) {
            out.add(new Delta("process.open", 1));
        } else if ("process.end".equals(metric)) {
            out.add(new Delta("process.open", -1));
        }
        return out;
    }

    private void add(String node, String metric, int day, int shard, long delta, long now) {
        int n = jdbc.update("UPDATE RU_COUNTER SET counter_value = counter_value + ?, updated_at_ms = ?"
                + " WHERE node_path = ? AND metric = ? AND day_bucket = ? AND shard = ?", delta, now, node, metric, day, shard);
        if (n == 0) {
            jdbc.update("INSERT INTO RU_COUNTER(node_path, metric, day_bucket, shard, counter_value, updated_at_ms)"
                    + " VALUES (?,?,?,?,?,?)", node, metric, day, shard, delta, now);
        }
    }

    private void ensureNode(String node, String parent, int depth, long now) {
        if (knownNodes.contains(node)) {
            return;
        }
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM RU_NODE WHERE node_path = ?", Long.class, node);
        if (n == null || n == 0) {
            jdbc.update("INSERT INTO RU_NODE(node_path, parent_path, depth, first_seen_ms) VALUES (?,?,?,?)",
                    node, parent, depth, now);
        }
        knownNodes.add(node);
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

    private static String clip(String m) {
        return m.length() > 96 ? m.substring(0, 96) : m;
    }

    private static String lastSegment(String path) {
        int i = path.lastIndexOf('/');
        return i < 0 ? path : path.substring(i + 1);
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
