// SPDX-License-Identifier: Apache-2.0
package org.sangam.modeller.mock;

import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;
import org.sangam.integration.connector.Json;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persists {@link ServiceMocks.Rule}s in {@code MOD_SERVICE_MOCK}, keyed by (tenant, service, operation): one rule per operation, so
 * a later save of the same operation replaces it. {@code enabled} is the stored per-service toggle. {@link #load} builds the mocks for
 * a tenant; the production refusal stays in {@link ServiceMocks#wrap}.
 */
public final class ServiceMockStore {

    public record Stored(ServiceMocks.Rule rule, boolean enabled, String createdBy, Instant createdAt) {
    }

    private final Dialect dialect;
    private final Transactions tx;

    public ServiceMockStore(Dialect dialect, Transactions tx) {
        this.dialect = dialect;
        this.tx = tx;
    }

    public void save(String tenantId, ServiceMocks.Rule r, boolean enabled, String actor) {
        String when = Json.write(r.whenInput() == null ? Map.of() : r.whenInput());
        String out = r.output() == null ? null : Json.write(r.output());
        Long delay = r.delay() == null ? null : r.delay().toMillis();
        tx.inTransaction(c -> {
            Instant now = Jdbc.dbNow(c, dialect);
            if (Jdbc.update(c, "UPDATE MOD_SERVICE_MOCK SET when_input_json=?, output_json=?, error_code=?, delay_ms=?, enabled=?, created_by=?, created_at=? "
                    + "WHERE tenant_id=? AND service=? AND operation=?", when, out, r.errorCode(), delay, enabled ? 1 : 0, actor, now, tenantId, r.service(), r.operation()) == 0) {
                Jdbc.update(c, "INSERT INTO MOD_SERVICE_MOCK(tenant_id,service,operation,when_input_json,output_json,error_code,delay_ms,enabled,created_by,created_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?)", tenantId, r.service(), r.operation(), when, out, r.errorCode(), delay, enabled ? 1 : 0, actor, now);
            }
            return null;
        });
    }

    public boolean delete(String tenantId, String service, String operation) {
        return tx.inTransaction(c -> Jdbc.update(c, "DELETE FROM MOD_SERVICE_MOCK WHERE tenant_id=? AND service=? AND operation=?", tenantId, service, operation)) > 0;
    }

    /** The stored per-service toggle for all operations of {@code service}. */
    public int setServiceEnabled(String tenantId, String service, boolean enabled) {
        return tx.inTransaction(c -> Jdbc.update(c, "UPDATE MOD_SERVICE_MOCK SET enabled=? WHERE tenant_id=? AND service=?", enabled ? 1 : 0, tenantId, service));
    }

    public List<Stored> list(String tenantId) {
        return tx.inTransaction(c -> Jdbc.query(c, "SELECT service,operation,when_input_json,output_json,error_code,delay_ms,enabled,created_by,created_at FROM MOD_SERVICE_MOCK "
                + "WHERE tenant_id=? ORDER BY service, operation", rs -> {
            Map<String, String> when = new LinkedHashMap<>();
            if (rs.getString(3) != null) {
                Json.readMap(rs.getString(3)).forEach((k, v) -> when.put(k, String.valueOf(v)));
            }
            long delay = rs.getLong(6);
            Duration d = rs.wasNull() ? null : Duration.ofMillis(delay);
            ServiceMocks.Rule rule = new ServiceMocks.Rule(rs.getString(1), rs.getString(2), when, rs.getString(4) == null ? null : Json.readMap(rs.getString(4)), rs.getString(5), d);
            return new Stored(rule, rs.getInt(7) == 1, rs.getString(8), Jdbc.instant(rs, "created_at"));
        }, tenantId));
    }

    /** Mocks of a tenant; disabled services call the real connector. */
    public ServiceMocks load(String tenantId, boolean productionProfile) {
        List<Stored> all = list(tenantId);
        ServiceMocks m = new ServiceMocks(productionProfile, all.stream().map(Stored::rule).toList());
        all.stream().filter(s -> !s.enabled()).forEach(s -> m.setServiceEnabled(s.rule().service(), false));
        return m;
    }
}
