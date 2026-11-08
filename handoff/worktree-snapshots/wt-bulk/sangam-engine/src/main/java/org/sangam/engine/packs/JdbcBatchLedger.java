// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.packs;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.sangam.core.engine.SangamClock;
import org.sangam.core.layer.Jdbc;
import org.sangam.packs.port.BatchLedger;

/**
 * JDBC {@link BatchLedger} (GP-09, GP-49) over {@code PACK_BATCH_ITEM}, with the semantics of the WS-PACKS simulator
 * ({@code InMemoryBatchLedger}): {@code open} is idempotent; {@code record} is idempotent per (batch, round, item) and
 * <b>the first terminal outcome wins</b> ({@code UPDATE .. WHERE outcome IS NULL}, so concurrent children cannot
 * overwrite each other); an item recorded without having been opened is added. Each child records in its own
 * (engine) transaction; the parent reads {@link #summary} on a timer, so no parent row is ever hot (E6).
 */
public final class JdbcBatchLedger implements BatchLedger {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String[] ITEM_COLS = {"tenant_id", "batch_id", "batch_round", "item_key", "opened_at"};
    private static final String[] RECORD_COLS = {"tenant_id", "batch_id", "batch_round", "item_key", "outcome",
            "reason_code", "data_json", "opened_at", "recorded_at"};

    private final ProductJdbc jdbc;
    private final SangamClock clock;

    public JdbcBatchLedger(DataSource dataSource, SangamClock clock) {
        this.jdbc = new ProductJdbc(dataSource);
        this.clock = clock;
    }

    @Override
    public void open(String tenantId, String batchId, int round, List<String> itemKeys) {
        jdbc.run(c -> {
            for (String item : itemKeys) {
                jdbc.dialect(c).insertIfAbsent(c, "PACK_BATCH_ITEM", ITEM_COLS, "batch_round", tenantId, batchId, round,
                        item, clock.now());
            }
            return null;
        });
    }

    @Override
    public void record(String tenantId, String batchId, int round, String itemKey, String outcome, String reasonCode,
                       Map<String, Object> data) {
        String json = toJson(data);
        jdbc.run(c -> {
            int n = Jdbc.update(c, "UPDATE PACK_BATCH_ITEM SET outcome = ?, reason_code = ?, data_json = ?, recorded_at = ? "
                            + "WHERE tenant_id = ? AND batch_id = ? AND batch_round = ? AND item_key = ? AND outcome IS NULL",
                    outcome, reasonCode, json, clock.now(), tenantId, batchId, round, itemKey);
            if (n == 0) {
                // not opened yet: add it with this outcome; an existing row with an outcome keeps the first one
                jdbc.dialect(c).insertIfAbsent(c, "PACK_BATCH_ITEM", RECORD_COLS, "batch_round", tenantId, batchId, round,
                        itemKey, outcome, reasonCode, json, clock.now(), clock.now());
            }
            return null;
        });
    }

    private record Item(String key, String outcome, String reason, String data) {
    }

    private List<Item> items(String tenantId, String batchId, int round) {
        return jdbc.run(c -> Jdbc.query(c, "SELECT item_key, outcome, reason_code, data_json FROM PACK_BATCH_ITEM "
                        + "WHERE tenant_id = ? AND batch_id = ? AND batch_round = ? ORDER BY item_key",
                rs -> new Item(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)), tenantId, batchId, round));
    }

    @Override
    public BatchSummary summary(String tenantId, String batchId, int round) {
        int total = 0;
        int done = 0;
        int ok = 0;
        Map<String, Integer> by = new HashMap<>();
        for (Item i : items(tenantId, batchId, round)) {
            total++;
            if (i.outcome() != null) {
                done++;
                by.merge(i.outcome(), 1, Integer::sum);
                if ("SUCCESS".equals(i.outcome())) {
                    ok++;
                }
            }
        }
        return new BatchSummary(total, done, ok, by);
    }

    @Override
    public Map<String, String> failures(String tenantId, String batchId, int round) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Item i : items(tenantId, batchId, round)) {
            if (i.outcome() != null && !"SUCCESS".equals(i.outcome())) {
                out.put(i.key(), i.reason() == null ? i.outcome() : i.reason());
            }
        }
        return out;
    }

    @Override
    public Map<String, Map<String, Object>> itemData(String tenantId, String batchId, int round) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Item i : items(tenantId, batchId, round)) {
            out.put(i.key(), fromJson(i.data()));
        }
        return out;
    }

    private static String toJson(Map<String, Object> data) {
        try {
            return data == null || data.isEmpty() ? null : JSON.writeValueAsString(data);
        } catch (Exception e) {
            throw new IllegalArgumentException("BatchLedger: item data is not JSON-serialisable", e);
        }
    }

    private static Map<String, Object> fromJson(String json) {
        if (json == null) {
            return Map.of();
        }
        try {
            return java.util.Collections.unmodifiableMap(JSON.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {
            }));
        } catch (Exception e) {
            throw new IllegalStateException("BatchLedger: stored item data is not valid JSON", e);
        }
    }
}
