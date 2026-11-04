// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.sim;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.sangam.packs.port.BatchLedger;

/** In-memory {@link BatchLedger}. */
public class InMemoryBatchLedger implements BatchLedger {

    private record Outcome(String outcome, String reason, Map<String, Object> data) {
    }

    private final Map<String, Map<String, Outcome>> rounds = new ConcurrentHashMap<>();

    private static String key(String tenantId, String batchId, int round) {
        return tenantId + "|" + batchId + "|" + round;
    }

    @Override
    public void open(String tenantId, String batchId, int round, List<String> itemKeys) {
        Map<String, Outcome> m = rounds.computeIfAbsent(key(tenantId, batchId, round),
                k -> new ConcurrentHashMap<>());
        for (String item : itemKeys) {
            m.putIfAbsent(item, new Outcome(null, null, Map.of()));
        }
    }

    @Override
    public void record(String tenantId, String batchId, int round, String itemKey, String outcome, String reasonCode,
                       Map<String, Object> data) {
        Map<String, Outcome> m = rounds.computeIfAbsent(key(tenantId, batchId, round),
                k -> new ConcurrentHashMap<>());
        m.compute(itemKey, (k, old) -> old != null && old.outcome() != null ? old
                : new Outcome(outcome, reasonCode, data == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(data))));
    }

    @Override
    public BatchSummary summary(String tenantId, String batchId, int round) {
        Map<String, Outcome> m = rounds.getOrDefault(key(tenantId, batchId, round), Map.of());
        int done = 0;
        int ok = 0;
        Map<String, Integer> by = new HashMap<>();
        for (Outcome o : m.values()) {
            if (o.outcome() != null) {
                done++;
                by.merge(o.outcome(), 1, Integer::sum);
                if ("SUCCESS".equals(o.outcome())) {
                    ok++;
                }
            }
        }
        return new BatchSummary(m.size(), done, ok, by);
    }

    @Override
    public Map<String, String> failures(String tenantId, String batchId, int round) {
        Map<String, String> out = new LinkedHashMap<>();
        rounds.getOrDefault(key(tenantId, batchId, round), Map.of()).forEach((item, o) -> {
            if (o.outcome() != null && !"SUCCESS".equals(o.outcome())) {
                out.put(item, o.reason() == null ? o.outcome() : o.reason());
            }
        });
        return out;
    }

    @Override
    public Map<String, Map<String, Object>> itemData(String tenantId, String batchId, int round) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        rounds.getOrDefault(key(tenantId, batchId, round), Map.of()).forEach((item, o) -> out.put(item, o.data()));
        return out;
    }
}
