// SPDX-License-Identifier: Apache-2.0
package org.sangam.registry.kernel;

import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.registry.RegistryContext;
import org.sangam.registry.RegistryException;
import org.sangam.registry.support.Db;
import org.sangam.registry.support.Json;
import org.sangam.registry.support.Maps;
import org.sangam.registry.support.RegistryAudit;

import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Configured object types, versioned: publishing a changed definition adds version n+1 and leaves existing objects
 * on the version they were created with ({@code REG_OBJECT.type_version}); new objects use the latest ACTIVE
 * version whose {@code effective_from} has passed. Definitions are cached per (tenant, type, version) because a
 * version never changes once written.
 */
public final class ObjectTypeService {

    private final Dialect dialect;
    private final RegistryAudit audit;
    private final Map<String, ObjectTypeDefinition> cache = new ConcurrentHashMap<>();

    public ObjectTypeService(Dialect dialect, RegistryAudit audit) {
        this.dialect = dialect;
        this.audit = audit;
    }

    /** One row of the type catalogue. */
    public record TypeVersion(String typeKey, int version, String kind, String status, Instant effectiveFrom,
                              Instant createdAt, String createdBy) {
    }

    /**
     * Publishes a definition as a new version (validated by parsing).
     *
     * @param effectiveFrom when the version applies to new objects (null = now)
     */
    public ObjectTypeDefinition publish(Connection c, RegistryContext ctx, Map<String, Object> definition, Instant effectiveFrom) {
        String key = Maps.required(definition, "typeKey");
        int next = Jdbc.one(c, "SELECT MAX(version) FROM REG_OBJECT_TYPE WHERE tenant_id=? AND type_key=?",
                rs -> rs.getInt(1), ctx.tenant(), key).orElse(0) + 1;
        ObjectTypeDefinition def = ObjectTypeDefinition.from(definition, next);
        Instant now = Db.now(c, dialect);
        Instant eff = effectiveFrom == null ? now : effectiveFrom;
        try {
            Jdbc.update(c, "INSERT INTO REG_OBJECT_TYPE(tenant_id, type_key, version, kind, status, definition_json,"
                            + " effective_from, created_at, created_by) VALUES (?,?,?,?,?,?,?,?,?)",
                    ctx.tenant(), key, next, def.kind().name(), "ACTIVE", Json.write(def.toMap()), eff, now, ctx.actor());
        } catch (Jdbc.LayerSqlException e) {
            if (dialect.isUniqueViolation(e.sql())) {
                throw RegistryException.conflict("type " + key + " version " + next + " was published concurrently");
            }
            throw e;
        }
        audit.record(c, ctx, typeRoot(key), "REGISTRY_TYPE_PUBLISHED",
                Maps.of("typeKey", key, "version", next, "kind", def.kind().name(), "effectiveFrom", eff));
        cache.put(cacheKey(ctx.tenant(), key, next), def);
        return def;
    }

    /** Retires a version: no new objects use it; existing objects keep it. */
    public void retire(Connection c, RegistryContext ctx, String typeKey, int version) {
        int n = Jdbc.update(c, "UPDATE REG_OBJECT_TYPE SET status='RETIRED' WHERE tenant_id=? AND type_key=? AND version=?",
                ctx.tenant(), typeKey, version);
        if (n == 0) {
            throw RegistryException.notFound("object type", typeKey + " v" + version);
        }
        audit.record(c, ctx, typeRoot(typeKey), "REGISTRY_TYPE_RETIRED", Maps.of("typeKey", typeKey, "version", version));
    }

    /** The version that applies to new objects now. */
    public ObjectTypeDefinition current(Connection c, String tenant, String typeKey) {
        Instant now = Db.now(c, dialect);
        Optional<Integer> v = Jdbc.one(c, "SELECT MAX(version) FROM REG_OBJECT_TYPE WHERE tenant_id=? AND type_key=?"
                        + " AND status='ACTIVE' AND effective_from <= ?",
                rs -> {
                    int x = rs.getInt(1);
                    return rs.wasNull() ? null : x;
                }, tenant, typeKey, now);
        if (v.isEmpty() || v.get() == null) {
            throw RegistryException.notFound("active object type", typeKey);
        }
        return version(c, tenant, typeKey, v.get());
    }

    /** A specific version (the one an existing object was created with). */
    public ObjectTypeDefinition version(Connection c, String tenant, String typeKey, int version) {
        String k = cacheKey(tenant, typeKey, version);
        ObjectTypeDefinition hit = cache.get(k);
        if (hit != null) {
            return hit;
        }
        String json = Jdbc.one(c, "SELECT definition_json FROM REG_OBJECT_TYPE WHERE tenant_id=? AND type_key=? AND version=?",
                rs -> rs.getString(1), tenant, typeKey, version)
                .orElseThrow(() -> RegistryException.notFound("object type", typeKey + " v" + version));
        ObjectTypeDefinition def = ObjectTypeDefinition.from(Json.map(json), version);
        cache.put(k, def);
        return def;
    }

    public List<TypeVersion> list(Connection c, String tenant, String kind) {
        String sql = "SELECT type_key, version, kind, status, effective_from, created_at, created_by FROM REG_OBJECT_TYPE"
                + " WHERE tenant_id=?" + (kind == null ? "" : " AND kind=?") + " ORDER BY type_key, version";
        Object[] params = kind == null ? new Object[]{tenant} : new Object[]{tenant, kind.toUpperCase()};
        return Jdbc.query(c, sql, rs -> new TypeVersion(rs.getString(1), rs.getInt(2), rs.getString(3), rs.getString(4),
                Jdbc.instant(rs, "effective_from"), Jdbc.instant(rs, "created_at"), rs.getString(7)), params);
    }

    /** Audit chain root of a type's catalogue history (a UUIDv5 so it fits the 64-character root id). */
    static String typeRoot(String typeKey) {
        return "type:" + org.sangam.core.layer.Hashes.uuidV5(java.util.UUID.fromString("6f1c3f0e-6a51-4c55-9a4d-2f3f7a0c9b11"),
                typeKey);
    }

    private static String cacheKey(String tenant, String typeKey, int version) {
        return tenant + "|" + typeKey + "|" + version;
    }
}
