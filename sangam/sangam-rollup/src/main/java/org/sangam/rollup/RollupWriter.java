// SPDX-License-Identifier: Apache-2.0
package org.sangam.rollup;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The roll-up's row writes, shared by the per-event and the micro-batch path (one SQL text per write, portable
 * across the five dialects). Every method runs inside the caller's transaction. A first insert that races another
 * writer's first insert of the same key raises a {@code DataIntegrityViolationException}; the caller rolls the
 * whole event or batch back and retries, so a write is never half applied.
 */
final class RollupWriter {

    /** Event ids per {@code IN (...)} lookup; stays far below every dialect's bind-parameter limit. */
    static final int ID_CHUNK = 100;

    private final JdbcTemplate jdbc;

    RollupWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Dedup row; a duplicate id raises {@code DuplicateKeyException}. */
    void insertSeen(String id, String cell, String tenant, long now) {
        jdbc.update("INSERT INTO RU_EVENT_SEEN(event_id, cell_id, tenant_code, received_at_ms) VALUES (?,?,?,?)",
                id, cell, clipTenant(tenant), now);
    }

    void insertSeen(List<Object[]> rows) {
        if (!rows.isEmpty()) {
            jdbc.batchUpdate("INSERT INTO RU_EVENT_SEEN(event_id, cell_id, tenant_code, received_at_ms) VALUES (?,?,?,?)",
                    rows);
        }
    }

    /** The ids among {@code ids} that are already projected. */
    Set<String> alreadySeen(Collection<String> ids) {
        Set<String> seen = new HashSet<>();
        List<String> all = new ArrayList<>(ids);
        for (int from = 0; from < all.size(); from += ID_CHUNK) {
            List<String> chunk = all.subList(from, Math.min(all.size(), from + ID_CHUNK));
            String marks = String.join(",", java.util.Collections.nCopies(chunk.size(), "?"));
            seen.addAll(jdbc.queryForList("SELECT event_id FROM RU_EVENT_SEEN WHERE event_id IN (" + marks + ")",
                    String.class, chunk.toArray()));
        }
        return seen;
    }

    void addCounter(String node, String metric, int day, int shard, long delta, long now) {
        int n = jdbc.update("UPDATE RU_COUNTER SET counter_value = counter_value + ?, updated_at_ms = ?"
                + " WHERE node_path = ? AND metric = ? AND day_bucket = ? AND shard = ?", delta, now, node, metric, day, shard);
        if (n == 0) {
            jdbc.update("INSERT INTO RU_COUNTER(node_path, metric, day_bucket, shard, counter_value, updated_at_ms)"
                    + " VALUES (?,?,?,?,?,?)", node, metric, day, shard, delta, now);
        }
    }

    /** M-063: a delta this instance still owes the upper tier (shipped by {@link RollupUpstreamEmitter}). */
    void addPending(String node, String metric, int day, int shard, long delta, long now) {
        int n = jdbc.update("UPDATE RU_UPSTREAM_PENDING SET pending_delta = pending_delta + ?, updated_at_ms = ?"
                + " WHERE node_path = ? AND metric = ? AND day_bucket = ? AND shard = ?", delta, now, node, metric, day, shard);
        if (n == 0) {
            jdbc.update("INSERT INTO RU_UPSTREAM_PENDING(node_path, metric, day_bucket, shard, pending_delta, updated_at_ms)"
                    + " VALUES (?,?,?,?,?,?)", node, metric, day, shard, delta, now);
        }
    }

    /** Registers a hierarchy node; true when this call inserted it. */
    boolean ensureNode(String node, String parent, int depth, long now) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM RU_NODE WHERE node_path = ?", Long.class, node);
        if (n == null || n == 0) {
            jdbc.update("INSERT INTO RU_NODE(node_path, parent_path, depth, first_seen_ms) VALUES (?,?,?,?)",
                    node, parent, depth, now);
            return true;
        }
        return false;
    }

    void bumpFeed(String cell, int shard, long events, long now) {
        if (jdbc.update("UPDATE RU_CELL_FEED SET events = events + ?, last_event_at_ms = ? WHERE cell_id = ? AND shard = ?",
                events, now, cell, shard) == 0) {
            jdbc.update("INSERT INTO RU_CELL_FEED(cell_id, shard, events, last_event_at_ms) VALUES (?,?,?,?)",
                    cell, shard, events, now);
        }
    }

    static String clipTenant(String tenant) {
        return tenant.length() > 64 ? tenant.substring(0, 64) : tenant;
    }
}
