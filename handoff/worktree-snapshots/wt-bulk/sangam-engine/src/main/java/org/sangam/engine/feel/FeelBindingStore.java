// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.feel;

import java.sql.Connection;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.sangam.core.layer.Jdbc;
import org.sangam.engine.db.EngineDialect;

/**
 * {@code SANGAM_FEEL_BINDING}: the FEEL engine each model version (decision / process / case definition id) was
 * deployed with (R10). Rows are immutable once written; a small cache avoids a query per decision evaluation.
 */
public final class FeelBindingStore {

    private static final String[] COLS = {"definition_id", "definition_key", "definition_kind", "deployment_id",
            "feel_engine", "recorded_at"};

    private final EngineDialect dialect;
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public FeelBindingStore(EngineDialect dialect) {
        this.dialect = dialect;
    }

    /** Records the binding unless the definition already has one; returns the binding in force. */
    public String record(Connection c, String definitionId, String key, String kind, String deploymentId,
                         String feelEngine, Instant now) {
        Optional<String> existing = find(c, definitionId);
        if (existing.isPresent()) {
            return existing.get();
        }
        dialect.insertIfAbsent(c, "SANGAM_FEEL_BINDING", COLS, "feel_engine", definitionId, key, kind, deploymentId,
                feelEngine, now);
        String bound = find(c, definitionId).orElse(feelEngine);
        cache.put(definitionId, bound);
        return bound;
    }

    public Optional<String> find(Connection c, String definitionId) {
        String cached = cache.get(definitionId);
        if (cached != null) {
            return Optional.of(cached);
        }
        Optional<String> v = Jdbc.one(c, "SELECT feel_engine FROM SANGAM_FEEL_BINDING WHERE definition_id = ?",
                rs -> rs.getString(1), definitionId);
        v.ifPresent(e -> cache.put(definitionId, e));
        return v;
    }
}
