// SPDX-License-Identifier: Apache-2.0
package org.sangam.ops.vars;

import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;
import org.sangam.integration.connector.Json;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cluster and tenant variables with merged scopes: a tenant value overrides the cluster value. Exposed to expressions as a bean, for
 * example {@code ${sangamVars.get(execution.tenantId, 'limit')}}, once the integrator registers it in the engine's bean map. Values
 * are plain configuration; secrets belong in {@code SecretAdmin}, and names that look like secrets are refused. Reads are served
 * from a short-lived cache per scope, so an edit is visible after {@code cacheMillis}.
 */
public final class ScopedVariables {

    public static final String CLUSTER = "cluster";

    private final Dialect dialect;
    private final Transactions tx;
    private final AuditChain audit;
    private final long cacheMillis;
    private final Map<String, Cached> cache = new java.util.concurrent.ConcurrentHashMap<>();

    private record Cached(Map<String, Object> values, long at) {
    }

    public ScopedVariables(Dialect dialect, Transactions tx, AuditChain audit, long cacheMillis) {
        this.dialect = dialect;
        this.tx = tx;
        this.audit = audit;
        this.cacheMillis = cacheMillis;
    }

    public static String tenantScope(String tenantId) {
        return "tenant:" + tenantId;
    }

    public void set(String scope, String name, Object value, String actor) {
        set(scope, name, value, actor, null);
    }

    /** Writes a value; {@code version} increases on every change, and {@code reason} is stored with it. */
    public void set(String scope, String name, Object value, String actor, String reason) {
        tx.inTransaction(c -> {
            set(c, scope, name, value, actor, reason);
            return null;
        });
        cache.remove(scope);
    }

    /**
     * {@link #set(String, String, Object, String, String)} inside the caller's transaction (bulk ingest commits the value
     * with its own row bookkeeping). The caller calls {@link #evict} after its commit.
     */
    public void set(java.sql.Connection c, String scope, String name, Object value, String actor, String reason) {
        if (name == null || !name.matches("[A-Za-z][A-Za-z0-9_.-]{0,127}")) {
            throw new IllegalArgumentException("invalid variable name");
        }
        if (name.toLowerCase(java.util.Locale.ROOT).matches(".*(password|secret|token|apikey|api-key|credential).*")) {
            throw new IllegalArgumentException("secret-like names belong in the secret store");
        }
        String json = Json.write(Map.of("v", value));
        Instant now = Jdbc.dbNow(c, dialect);
        if (Jdbc.update(c, "UPDATE OPS_VARIABLE SET value_json=?, updated_by=?, updated_at=?, version=version+1, reason=? WHERE scope_id=? AND name=?",
                json, actor, now, reason, scope, name) == 0) {
            Jdbc.update(c, "INSERT INTO OPS_VARIABLE(scope_id,name,value_json,updated_by,updated_at,scope_type,tenant_id,version,reason) VALUES (?,?,?,?,?,?,?,1,?)",
                    scope, name, json, actor, now, scopeType(scope), tenantIdOf(scope), reason);
        }
        audit.append(c, tenantOf(scope), "variables:" + scope, "VARIABLE_SET", Map.of("scope", scope, "name", name, "actor", actor, "reason", String.valueOf(reason)));
    }

    /** Drops the cached values of a scope (after a write committed elsewhere). */
    public void evict(String scope) {
        cache.remove(scope);
    }

    public void delete(String scope, String name, String actor) {
        tx.inTransaction(c -> {
            if (Jdbc.update(c, "DELETE FROM OPS_VARIABLE WHERE scope_id=? AND name=?", scope, name) > 0) {
                audit.append(c, tenantOf(scope), "variables:" + scope, "VARIABLE_DELETED", Map.of("scope", scope, "name", name, "actor", actor));
            }
            return null;
        });
        cache.remove(scope);
    }

    /** Cluster values overlaid with the tenant's. */
    public Map<String, Object> merged(String tenantId) {
        Map<String, Object> out = new LinkedHashMap<>(scope(CLUSTER));
        out.putAll(scope(tenantScope(tenantId)));
        return out;
    }

    /** For expressions. Returns null when the name is not defined in either scope. */
    public Object get(String tenantId, String name) {
        return merged(tenantId).get(name);
    }

    public Map<String, Object> scope(String scope) {
        Cached c = cache.get(scope);
        long now = System.currentTimeMillis();
        if (c != null && now - c.at < cacheMillis) {
            return c.values;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        List<Object[]> rows = tx.inTransaction(x -> Jdbc.query(x, "SELECT name,value_json FROM OPS_VARIABLE WHERE scope_id=?", rs -> new Object[]{rs.getString(1), rs.getString(2)}, scope));
        rows.forEach(r -> m.put((String) r[0], Json.readMap((String) r[1]).get("v")));
        Map<String, Object> frozen = java.util.Collections.unmodifiableMap(m);
        cache.put(scope, new Cached(frozen, now));
        return frozen;
    }

    /** One stored variable with its history fields. */
    public record Entry(String scope, String scopeType, String tenantId, String name, Object value, long version, String updatedBy, Instant updatedAt, String reason) {
    }

    public java.util.List<Entry> entries(String scope) {
        return tx.inTransaction(c -> Jdbc.query(c, "SELECT scope_id,scope_type,tenant_id,name,value_json,version,updated_by,updated_at,reason FROM OPS_VARIABLE WHERE scope_id=? ORDER BY name",
                rs -> new Entry(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), Json.readMap(rs.getString(5)).get("v"), rs.getLong(6), rs.getString(7),
                        Jdbc.instant(rs, "updated_at"), rs.getString(9)), scope));
    }

    static String scopeType(String scope) {
        return CLUSTER.equals(scope) ? "CLUSTER" : "TENANT";
    }

    static String tenantIdOf(String scope) {
        return scope.startsWith("tenant:") ? scope.substring(7) : null;
    }

    private static String tenantOf(String scope) {
        return scope.startsWith("tenant:") ? scope.substring(7) : "platform";
    }
}
