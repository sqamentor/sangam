// SPDX-License-Identifier: Apache-2.0
package org.sangam.resources.catalogue;

import org.sangam.core.layer.Jdbc;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.resources.ResourcesContext;
import org.sangam.resources.support.ResourcesException;
import org.sangam.resources.support.Sql;
import org.sangam.resources.support.VisibleQuery;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resource types and the resource catalogue (N01): types from configuration, resources with capacity, attributes,
 * hierarchy, owning org unit and location; search by type, subtree, unit, location, capacity, indexed attributes and text.
 */
public final class ResourceCatalogue {

    private static final String COLS = "tenant_id, resource_id, type_id, code, name, parent_id, tree_path, org_unit_id,"
            + " location_id, geo_lat, geo_lon, capacity, unit_label, calendar_id, status, attributes_json, row_version,"
            + " created_at, updated_at";

    private final ResourcesContext ctx;
    /** Parsed type definitions by tenant|type|version (definitions are immutable per version). */
    private final Map<String, ResourceType> typeCache = new ConcurrentHashMap<>();

    public ResourceCatalogue(ResourcesContext ctx) {
        this.ctx = ctx;
    }

    // ------------------------------------------------------------------ types

    /** Creates or replaces a type definition (version incremented; existing resources keep their type id). */
    public ResourceType defineType(Connection c, SecurityIdentity actor, ResourceType type) {
        ctx.guard().require(c, actor, ResourcesContext.RT_RESOURCE, ResourcesContext.P_ADMIN, "type:" + type.typeId());
        String t = actor.tenantId();
        String json = Sql.toJson(type.toMap());
        Instant now = ctx.now(c);
        Optional<Long> existing = Jdbc.one(c, "SELECT def_version FROM RES_TYPE WHERE tenant_id=? AND type_id=?",
                rs -> rs.getLong(1), t, type.typeId());
        if (existing.isPresent()) {
            Jdbc.update(c, "UPDATE RES_TYPE SET kind=?, definition_json=?, def_version=?, status='ACTIVE', updated_at=?"
                    + " WHERE tenant_id=? AND type_id=?", type.kind(), json, existing.get() + 1, now, t, type.typeId());
        } else {
            Jdbc.update(c, "INSERT INTO RES_TYPE(tenant_id, type_id, kind, definition_json, def_version, status, updated_at)"
                    + " VALUES (?,?,?,?,1,'ACTIVE',?)", t, type.typeId(), type.kind(), json, now);
        }
        ctx.event(c, actor, "TYPE", type.typeId(), existing.isPresent() ? "TYPE_UPDATED" : "TYPE_DEFINED", null, null,
                Map.of("definition", type.toMap()));
        return type;
    }

    /** Seeds types from configuration ({@code resources.types} list of a resolved profile). */
    public List<ResourceType> loadTypes(Connection c, SecurityIdentity actor, List<Map<String, Object>> definitions) {
        List<ResourceType> out = new ArrayList<>();
        for (Map<String, Object> d : definitions) {
            out.add(defineType(c, actor, ResourceType.fromMap(d)));
        }
        return out;
    }

    public void retireType(Connection c, SecurityIdentity actor, String typeId) {
        ctx.guard().require(c, actor, ResourcesContext.RT_RESOURCE, ResourcesContext.P_ADMIN, "type:" + typeId);
        int n = Jdbc.update(c, "UPDATE RES_TYPE SET status='RETIRED', updated_at=? WHERE tenant_id=? AND type_id=?",
                ctx.now(c), actor.tenantId(), typeId);
        if (n == 0) {
            throw ResourcesException.notFound("resource type", typeId);
        }
        ctx.event(c, actor, "TYPE", typeId, "TYPE_RETIRED", null, null, Map.of());
    }

    public Optional<ResourceType> findType(Connection c, String tenantId, String typeId) {
        return Jdbc.one(c, "SELECT def_version, definition_json FROM RES_TYPE WHERE tenant_id=? AND type_id=?",
                rs -> new Object[]{rs.getLong(1), Sql.text(rs, "definition_json")}, tenantId, typeId)
                .map(r -> typeCache.computeIfAbsent(tenantId + "|" + typeId + "|" + r[0],
                        k -> ResourceType.fromMap(Sql.mapJson((String) r[1]))));
    }

    public ResourceType type(Connection c, String tenantId, String typeId) {
        return findType(c, tenantId, typeId).orElseThrow(() -> ResourcesException.notFound("resource type", typeId));
    }

    public List<ResourceType> types(Connection c, String tenantId) {
        List<String> ids = Jdbc.query(c, "SELECT type_id FROM RES_TYPE WHERE tenant_id=? AND status='ACTIVE' ORDER BY type_id",
                rs -> rs.getString(1), tenantId);
        return ids.stream().map(id -> type(c, tenantId, id)).toList();
    }

    // ------------------------------------------------------------------ resources

    public Resource create(Connection c, SecurityIdentity actor, Resource.Draft d) {
        String t = actor.tenantId();
        ResourceType type = type(c, t, d.typeId());
        ctx.guard().require(c, actor, ResourcesContext.RT_RESOURCE, ResourcesContext.P_ADMIN,
                d.parentId() == null ? "*" : d.parentId());
        String id = d.resourceId() == null || d.resourceId().isBlank() ? Sql.newId() : d.resourceId();
        if (id.contains("/")) {
            throw ResourcesException.invalid("RESOURCE_ID", "resource id must not contain '/'");
        }
        String path = "/" + id;
        if (d.parentId() != null) {
            Resource parent = get(c, t, d.parentId());
            if (!type.allowedParentTypes().isEmpty() && !type.allowedParentTypes().contains(parent.typeId())) {
                throw ResourcesException.invalid("PARENT_TYPE", type.typeId() + " cannot be placed under " + parent.typeId());
            }
            path = parent.treePath() + "/" + id;
        }
        Map<String, Object> attrs = type.validate(d.attributes());
        int capacity = d.capacity() == null ? type.defaultCapacity() : d.capacity();
        if (capacity < 0) {
            throw ResourcesException.invalid("CAPACITY", "capacity must not be negative");
        }
        Instant now = ctx.now(c);
        String code = d.code() == null || d.code().isBlank() ? id : d.code();
        boolean inserted = ctx.dialect().insertIfAbsent(c, "INSERT INTO RES_RESOURCE(" + COLS + ")"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                t, id, type.typeId(), code, d.name() == null ? code : d.name(), d.parentId(), path, d.orgUnitId(),
                d.locationId(), d.geoLat(), d.geoLon(), capacity, d.unitLabel() == null ? type.unitLabel() : d.unitLabel(),
                d.calendarId(), Resource.Status.ACTIVE.name(), Sql.toJson(attrs), 1L, now, now);
        if (!inserted) {
            throw ResourcesException.conflict("RESOURCE_EXISTS", "a resource with this id or code already exists");
        }
        indexAttributes(c, t, id, type, attrs);
        ctx.event(c, actor, "RESOURCE", id, "RESOURCE_CREATED", null, null, Map.of("type", type.typeId(), "code", code));
        return get(c, t, id);
    }

    public Resource update(Connection c, SecurityIdentity actor, String resourceId, long expectedVersion, Resource.Patch p) {
        String t = actor.tenantId();
        ctx.guard().require(c, actor, ResourcesContext.RT_RESOURCE, ResourcesContext.P_MODIFY, resourceId);
        Resource r = get(c, t, resourceId);
        ResourceType type = type(c, t, r.typeId());
        Map<String, Object> attrs = r.attributes();
        if (p.attributes() != null) {
            Map<String, Object> merged = new LinkedHashMap<>(r.attributes());
            merged.putAll(p.attributes());
            merged.values().removeIf(v -> v == null);
            attrs = type.validate(merged);
        }
        int n = Jdbc.update(c, "UPDATE RES_RESOURCE SET name=?, org_unit_id=?, location_id=?, geo_lat=?, geo_lon=?, capacity=?,"
                        + " unit_label=?, calendar_id=?, attributes_json=?, row_version=row_version+1, updated_at=?"
                        + " WHERE tenant_id=? AND resource_id=? AND row_version=?",
                coalesce(p.name(), r.name()), coalesce(p.orgUnitId(), r.orgUnitId()), coalesce(p.locationId(), r.locationId()),
                coalesce(p.geoLat(), r.geoLat()), coalesce(p.geoLon(), r.geoLon()),
                p.capacity() == null ? r.capacity() : p.capacity(), coalesce(p.unitLabel(), r.unitLabel()),
                coalesce(p.calendarId(), r.calendarId()), Sql.toJson(attrs), ctx.now(c), t, resourceId, expectedVersion);
        if (n == 0) {
            throw ResourcesException.stale("resource " + resourceId + " was changed by someone else");
        }
        Jdbc.update(c, "DELETE FROM RES_RESOURCE_ATTR WHERE tenant_id=? AND resource_id=?", t, resourceId);
        indexAttributes(c, t, resourceId, type, attrs);
        ctx.event(c, actor, "RESOURCE", resourceId, "RESOURCE_UPDATED", null, null, Map.of("version", expectedVersion + 1));
        return get(c, t, resourceId);
    }

    /** Status change (maintenance, inactive, retired); a retired resource keeps its history and existing bookings. */
    public Resource setStatus(Connection c, SecurityIdentity actor, String resourceId, Resource.Status status, String reason) {
        String t = actor.tenantId();
        ctx.guard().require(c, actor, ResourcesContext.RT_RESOURCE, ResourcesContext.P_MODIFY, resourceId);
        int n = Jdbc.update(c, "UPDATE RES_RESOURCE SET status=?, row_version=row_version+1, updated_at=?"
                + " WHERE tenant_id=? AND resource_id=?", status.name(), ctx.now(c), t, resourceId);
        if (n == 0) {
            throw ResourcesException.notFound("resource", resourceId);
        }
        ctx.event(c, actor, "RESOURCE", resourceId, "RESOURCE_STATUS", reason, null, Map.of("status", status.name()));
        return get(c, t, resourceId);
    }

    /** Moves a resource (and its subtree) under a new parent; cycles are rejected. */
    public Resource move(Connection c, SecurityIdentity actor, String resourceId, String newParentId) {
        String t = actor.tenantId();
        ctx.guard().require(c, actor, ResourcesContext.RT_RESOURCE, ResourcesContext.P_ADMIN, resourceId);
        Resource r = Jdbc.one(c, ctx.dialect().lockingSelect(COLS, "RES_RESOURCE", "tenant_id=? AND resource_id=?"),
                ResourceCatalogue::map, t, resourceId).orElseThrow(() -> ResourcesException.notFound("resource", resourceId));
        String newPath = "/" + resourceId;
        if (newParentId != null) {
            Resource parent = get(c, t, newParentId);
            if (parent.lineage().contains(resourceId)) {
                throw ResourcesException.invalid("HIERARCHY_CYCLE", "a resource cannot be moved under its own subtree");
            }
            ResourceType type = type(c, t, r.typeId());
            if (!type.allowedParentTypes().isEmpty() && !type.allowedParentTypes().contains(parent.typeId())) {
                throw ResourcesException.invalid("PARENT_TYPE", type.typeId() + " cannot be placed under " + parent.typeId());
            }
            newPath = parent.treePath() + "/" + resourceId;
        }
        String oldPath = r.treePath();
        List<String[]> subtree = Jdbc.query(c, "SELECT resource_id, tree_path FROM RES_RESOURCE WHERE tenant_id=?"
                + " AND (tree_path=? OR tree_path LIKE ? ESCAPE '!')", rs -> new String[]{rs.getString(1), rs.getString(2)},
                t, oldPath, likePrefix(oldPath + "/"));
        Instant now = ctx.now(c);
        for (String[] row : subtree) {
            String p = newPath + row[1].substring(oldPath.length());
            Jdbc.update(c, "UPDATE RES_RESOURCE SET tree_path=?, updated_at=?, row_version=row_version+1"
                    + " WHERE tenant_id=? AND resource_id=?", p, now, t, row[0]);
        }
        Jdbc.update(c, "UPDATE RES_RESOURCE SET parent_id=? WHERE tenant_id=? AND resource_id=?", newParentId, t, resourceId);
        ctx.event(c, actor, "RESOURCE", resourceId, "RESOURCE_MOVED", null, null,
                mapOf("from", r.parentId(), "to", newParentId));
        return get(c, t, resourceId);
    }

    public Optional<Resource> find(Connection c, String tenantId, String resourceId) {
        return Jdbc.one(c, "SELECT " + COLS + " FROM RES_RESOURCE WHERE tenant_id=? AND resource_id=?",
                ResourceCatalogue::map, tenantId, resourceId);
    }

    public Resource get(Connection c, String tenantId, String resourceId) {
        return find(c, tenantId, resourceId).orElseThrow(() -> ResourcesException.notFound("resource", resourceId));
    }

    /** Reads and locks the resource row (the allotment serialisation point). */
    public Resource lock(Connection c, String tenantId, String resourceId) {
        return Jdbc.one(c, ctx.dialect().lockingSelect(COLS, "RES_RESOURCE", "tenant_id=? AND resource_id=?"),
                ResourceCatalogue::map, tenantId, resourceId).orElseThrow(() -> ResourcesException.notFound("resource", resourceId));
    }

    /** Visible children of a resource (VI.4 predicate on every row). */
    public List<Resource> children(Connection c, SecurityIdentity actor, String parentId) {
        return VisibleQuery.on(ctx, actor, ResourcesContext.RT_RESOURCE, ResourcesContext.P_READ, "RES_RESOURCE", "r")
                .select(COLS).where("r.parent_id=?", parentId).orderBy("r.code").all(c, ResourceCatalogue::map, 1000);
    }

    /** Get-by-id through the same visibility predicate as the lists; a hidden resource is NOT_FOUND. */
    public Resource visible(Connection c, SecurityIdentity actor, String resourceId) {
        return VisibleQuery.on(ctx, actor, ResourcesContext.RT_RESOURCE, ResourcesContext.P_READ, "RES_RESOURCE", "r")
                .select(COLS).require(c, "resource_id", resourceId, ResourceCatalogue::map, "resource");
    }

    /** Ancestors root first, excluding the resource itself. */
    public List<Resource> ancestors(Connection c, String tenantId, String resourceId) {
        Resource r = get(c, tenantId, resourceId);
        List<Resource> out = new ArrayList<>();
        for (String id : r.lineage()) {
            if (!id.equals(resourceId)) {
                find(c, tenantId, id).ifPresent(out::add);
            }
        }
        return out;
    }

    public List<Resource> search(Connection c, SecurityIdentity actor, Resource.Query q) {
        return searchQuery(c, actor, q).orderBy("r.type_id, r.code")
                .list(c, ResourceCatalogue::map, Math.max(0, q.offset()), q.limit() <= 0 ? 50 : Math.min(q.limit(), 1000));
    }

    /** Capped count of the same search ("more than N", A-16). */
    public VisibleQuery.CappedCount count(Connection c, SecurityIdentity actor, Resource.Query q, int cap) {
        return searchQuery(c, actor, q).count(c, cap);
    }

    private VisibleQuery searchQuery(Connection c, SecurityIdentity actor, Resource.Query q) {
        VisibleQuery vq = VisibleQuery.on(ctx, actor, ResourcesContext.RT_RESOURCE, ResourcesContext.P_READ, "RES_RESOURCE", "r")
                .select(COLS);
        if (q.typeId() != null) {
            vq.where("r.type_id=?", q.typeId());
        }
        if (q.parentId() != null) {
            vq.where("r.parent_id=?", q.parentId());
        }
        if (q.ancestorId() != null) {
            Resource a = get(c, actor.tenantId(), q.ancestorId());
            vq.where("r.tree_path LIKE ? ESCAPE '!'", likePrefix(a.treePath() + "/"));
        }
        if (q.orgUnitId() != null) {
            vq.where("r.org_unit_id=?", q.orgUnitId());
        }
        if (q.locationId() != null) {
            vq.where("r.location_id=?", q.locationId());
        }
        if (q.minCapacity() != null) {
            vq.where("r.capacity >= ?", q.minCapacity());
        }
        vq.where("r.status=?", (q.status() == null ? Resource.Status.ACTIVE : q.status()).name());
        if (q.text() != null && !q.text().isBlank()) {
            String like = "%" + escapeLike(q.text().toLowerCase()) + "%";
            vq.where("LOWER(r.name) LIKE ? ESCAPE '!' OR LOWER(r.code) LIKE ? ESCAPE '!'", like, like);
        }
        if (q.attributes() != null) {
            for (Map.Entry<String, String> e : q.attributes().entrySet()) {
                vq.where("EXISTS (SELECT 1 FROM RES_RESOURCE_ATTR a WHERE a.tenant_id=r.tenant_id"
                        + " AND a.resource_id=r.resource_id AND a.attr_name=? AND a.attr_value=?)", e.getKey(), e.getValue());
            }
        }
        return vq;
    }

    // ------------------------------------------------------------------ helpers

    private void indexAttributes(Connection c, String t, String resourceId, ResourceType type, Map<String, Object> attrs) {
        for (String name : type.indexedAttributes()) {
            Object v = attrs.get(name);
            if (v == null) {
                continue;
            }
            List<Object> values = v instanceof List<?> l ? new ArrayList<>(l) : List.of(v);
            for (Object one : values) {
                String s = String.valueOf(one);
                if (s.length() > 255) {
                    s = s.substring(0, 255);
                }
                ctx.dialect().insertIfAbsent(c, "INSERT INTO RES_RESOURCE_ATTR(tenant_id, resource_id, attr_name, attr_value)"
                        + " VALUES (?,?,?,?)", t, resourceId, name, s);
            }
        }
    }

    static Resource map(ResultSet rs) throws SQLException {
        return new Resource(rs.getString("tenant_id"), rs.getString("resource_id"), rs.getString("type_id"),
                rs.getString("code"), rs.getString("name"), rs.getString("parent_id"), rs.getString("tree_path"),
                rs.getString("org_unit_id"), rs.getString("location_id"), rs.getString("geo_lat"), rs.getString("geo_lon"),
                rs.getInt("capacity"), rs.getString("unit_label"), rs.getString("calendar_id"),
                Resource.Status.valueOf(rs.getString("status")), Sql.mapJson(Sql.text(rs, "attributes_json")),
                rs.getLong("row_version"), Jdbc.instant(rs, "created_at"), Jdbc.instant(rs, "updated_at"));
    }

    /** LIKE prefix pattern; used with {@code ESCAPE '!'}, which all five dialects support. */
    static String likePrefix(String prefix) {
        return escapeLike(prefix) + "%";
    }

    static String escapeLike(String s) {
        return s.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    private static String coalesce(String a, String b) {
        return a != null ? a : b;
    }

    private static Map<String, Object> mapOf(String k1, Object v1, String k2, Object v2) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(k1, v1);
        m.put(k2, v2);
        return m;
    }
}
