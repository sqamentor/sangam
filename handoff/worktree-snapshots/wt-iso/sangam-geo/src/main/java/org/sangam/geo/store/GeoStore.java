// SPDX-License-Identifier: Apache-2.0
package org.sangam.geo.store;

import com.fasterxml.jackson.core.type.TypeReference;
import org.sangam.core.layer.Jdbc;
import org.sangam.geo.GeoException;
import org.sangam.geo.GeoSchema;
import org.sangam.geo.boundary.BoundaryLevel;
import org.sangam.geo.boundary.GeoFeature;
import org.sangam.geo.boundary.JurisdictionLayer;
import org.sangam.geo.connector.ExternalLayer;
import org.sangam.geo.geofence.Geofence;
import org.sangam.geo.model.BBox;
import org.sangam.geo.model.GeoJson;
import org.sangam.geo.model.GeoMath;
import org.sangam.geo.model.GeoPoint;
import org.sangam.geo.model.Geohash;
import org.sangam.geo.model.Geometry;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** JDBC access to the GEO_* tables (portable SQL only; dialect-specific SQL lives in the spatial index). */
public final class GeoStore {

    private static final String FEATURE_COLS = "feature_id, layer_code, level_code, code, parent_id, names_json, props_json, "
            + "geometry_json, min_lat_e7, min_lon_e7, max_lat_e7, max_lon_e7, centroid_lat_e7, centroid_lon_e7, geohash, "
            + "org_unit_ref, version, valid_from, valid_to";

    // ------------------------------------------------------------------ layers

    public void upsertLayer(Connection c, String tenantId, JurisdictionLayer l, String actor, Instant now) {
        Optional<Integer> v = Jdbc.one(c, "SELECT version FROM GEO_LAYER WHERE tenant_id = ? AND layer_code = ?",
                rs -> rs.getInt(1), tenantId, l.code());
        if (v.isPresent()) {
            Jdbc.update(c, "UPDATE GEO_LAYER SET kind = ?, names_json = ?, levels_json = ?, org_unit_linked = ?, index_precision = ?, "
                            + "attributes_json = ?, version = ?, updated_by = ?, updated_at = ? WHERE tenant_id = ? AND layer_code = ?",
                    l.kind(), json(l.names()), json(l.levels()), l.orgUnitLinked() ? 1 : 0, l.indexPrecision(), json(l.attributes()),
                    v.get() + 1, actor, now, tenantId, l.code());
        } else {
            Jdbc.update(c, "INSERT INTO GEO_LAYER (tenant_id, layer_code, kind, names_json, levels_json, org_unit_linked, index_precision, "
                            + "attributes_json, version, updated_by, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                    tenantId, l.code(), l.kind(), json(l.names()), json(l.levels()), l.orgUnitLinked() ? 1 : 0, l.indexPrecision(),
                    json(l.attributes()), 1, actor, now);
        }
    }

    public Optional<JurisdictionLayer> layer(Connection c, String tenantId, String code) {
        return Jdbc.one(c, "SELECT layer_code, kind, names_json, levels_json, org_unit_linked, index_precision, attributes_json "
                + "FROM GEO_LAYER WHERE tenant_id = ? AND layer_code = ?", GeoStore::mapLayer, tenantId, code);
    }

    public List<JurisdictionLayer> layers(Connection c, String tenantId) {
        return Jdbc.query(c, "SELECT layer_code, kind, names_json, levels_json, org_unit_linked, index_precision, attributes_json "
                + "FROM GEO_LAYER WHERE tenant_id = ? ORDER BY layer_code", GeoStore::mapLayer, tenantId);
    }

    private static JurisdictionLayer mapLayer(ResultSet rs) throws SQLException {
        List<BoundaryLevel> levels = read(rs.getString("levels_json"), new TypeReference<List<BoundaryLevel>>() { });
        return new JurisdictionLayer(rs.getString("layer_code"), rs.getString("kind"),
                read(rs.getString("names_json"), new TypeReference<Map<String, String>>() { }), levels,
                rs.getInt("org_unit_linked") == 1, rs.getInt("index_precision"),
                read(rs.getString("attributes_json"), new TypeReference<Map<String, Object>>() { }));
    }

    // ------------------------------------------------------------------ features

    public void insertFeature(Connection c, String tenantId, GeoFeature f, String actor, Instant now) {
        Geometry g = f.geometry();
        BBox b = g.bbox();
        GeoPoint ct = f.centroid();
        Jdbc.update(c, "INSERT INTO GEO_FEATURE (tenant_id, feature_id, layer_code, level_code, code, parent_id, names_json, props_json, "
                        + "geometry_json, geom_type, min_lat_e7, min_lon_e7, max_lat_e7, max_lon_e7, centroid_lat_e7, centroid_lon_e7, "
                        + "geohash, area_m2, org_unit_ref, version, active, valid_from, valid_to, updated_by, updated_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                tenantId, f.id(), f.layer(), f.level(), f.code(), f.parentId(), json(f.names()), json(f.properties()),
                GeoJson.write(g), g.type(), GeoSchema.e7(b.minLat()), GeoSchema.e7(b.minLon()), GeoSchema.e7(b.maxLat()),
                GeoSchema.e7(b.maxLon()), GeoSchema.e7(ct.lat()), GeoSchema.e7(ct.lon()), f.geohash(),
                Math.round(GeoMath.areaSqMeters(g)), f.orgUnitRef(), f.version(), f.validTo() == null ? 1 : 0, f.validFrom(),
                f.validTo(), actor, now);
    }

    /** Closes the current version of a code (valid_to = at, active = 0). Returns the closed version, if any. */
    public Optional<GeoFeature> closeCurrent(Connection c, String tenantId, String layer, String level, String code, Instant at) {
        Optional<GeoFeature> cur = current(c, tenantId, layer, level, code);
        cur.ifPresent(f -> Jdbc.update(c, "UPDATE GEO_FEATURE SET active = 0, valid_to = ?, updated_at = ? WHERE tenant_id = ? AND feature_id = ?",
                at, at, tenantId, f.id()));
        return cur;
    }

    public Optional<GeoFeature> current(Connection c, String tenantId, String layer, String level, String code) {
        return Jdbc.one(c, "SELECT " + FEATURE_COLS + " FROM GEO_FEATURE WHERE tenant_id = ? AND layer_code = ? AND level_code = ? "
                + "AND code = ? AND active = 1", GeoStore::mapFeature, tenantId, layer, level, code);
    }

    /** Current version of a code at any level (codes are expected unique per layer unless levels repeat them). */
    public List<GeoFeature> currentByCode(Connection c, String tenantId, String layer, String code) {
        return Jdbc.query(c, "SELECT " + FEATURE_COLS + " FROM GEO_FEATURE WHERE tenant_id = ? AND layer_code = ? AND code = ? "
                + "AND active = 1", GeoStore::mapFeature, tenantId, layer, code);
    }

    public Optional<GeoFeature> feature(Connection c, String tenantId, String featureId) {
        return Jdbc.one(c, "SELECT " + FEATURE_COLS + " FROM GEO_FEATURE WHERE tenant_id = ? AND feature_id = ?",
                GeoStore::mapFeature, tenantId, featureId);
    }

    public List<GeoFeature> features(Connection c, String tenantId, List<String> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        List<GeoFeature> out = new ArrayList<>(ids.size());
        for (int i = 0; i < ids.size(); i += 500) {
            List<String> chunk = ids.subList(i, Math.min(ids.size(), i + 500));
            String in = "?" + ",?".repeat(chunk.size() - 1);
            List<Object> params = new ArrayList<>();
            params.add(tenantId);
            params.addAll(chunk);
            out.addAll(Jdbc.query(c, "SELECT " + FEATURE_COLS + " FROM GEO_FEATURE WHERE tenant_id = ? AND feature_id IN (" + in + ")",
                    GeoStore::mapFeature, params.toArray()));
        }
        return out;
    }

    public List<GeoFeature> list(Connection c, String tenantId, String layer, String level, String parentId, boolean activeOnly,
                                 int offset, int limit) {
        StringBuilder sql = new StringBuilder("SELECT " + FEATURE_COLS + " FROM GEO_FEATURE WHERE tenant_id = ? AND layer_code = ?");
        List<Object> p = new ArrayList<>(List.of(tenantId, layer));
        if (level != null) {
            sql.append(" AND level_code = ?");
            p.add(level);
        }
        if (parentId != null) {
            sql.append(" AND parent_id = ?");
            p.add(parentId);
        }
        if (activeOnly) {
            sql.append(" AND active = 1");
        }
        sql.append(" ORDER BY level_code, code, version");
        List<GeoFeature> all = Jdbc.query(c, sql.toString(), GeoStore::mapFeature, p.toArray());
        int from = Math.min(all.size(), Math.max(0, offset));
        return all.subList(from, Math.min(all.size(), from + Math.max(1, limit)));
    }

    public int countActive(Connection c, String tenantId, String layer) {
        return Jdbc.one(c, "SELECT COUNT(*) FROM GEO_FEATURE WHERE tenant_id = ? AND layer_code = ? AND active = 1",
                rs -> rs.getInt(1), tenantId, layer).orElse(0);
    }

    public void retire(Connection c, String tenantId, String featureId, Instant at) {
        int n = Jdbc.update(c, "UPDATE GEO_FEATURE SET active = 0, valid_to = ?, updated_at = ? WHERE tenant_id = ? AND feature_id = ? AND active = 1",
                at, at, tenantId, featureId);
        if (n == 0) {
            throw new GeoException("NOT_FOUND", "no active feature " + featureId);
        }
    }

    /** Active features whose centroid lies in the box (nearest-N expansion). */
    public List<String> idsByCentroidBox(Connection c, String tenantId, String layer, BBox box) {
        return Jdbc.query(c, "SELECT feature_id FROM GEO_FEATURE WHERE tenant_id = ? AND layer_code = ? AND active = 1 "
                        + "AND min_lat_e7 <= ? AND max_lat_e7 >= ? AND min_lon_e7 <= ? AND max_lon_e7 >= ?",
                rs -> rs.getString(1), tenantId, layer, GeoSchema.e7(box.maxLat()), GeoSchema.e7(box.minLat()),
                GeoSchema.e7(box.maxLon()), GeoSchema.e7(box.minLon()));
    }

    private static GeoFeature mapFeature(ResultSet rs) throws SQLException {
        Geometry g = GeoJson.parse(rs.getString("geometry_json"));
        BBox b = new BBox(GeoSchema.deg(rs.getLong("min_lat_e7")), GeoSchema.deg(rs.getLong("min_lon_e7")),
                GeoSchema.deg(rs.getLong("max_lat_e7")), GeoSchema.deg(rs.getLong("max_lon_e7")));
        GeoPoint ct = new GeoPoint(GeoSchema.deg(rs.getLong("centroid_lat_e7")), GeoSchema.deg(rs.getLong("centroid_lon_e7")));
        return new GeoFeature(rs.getString("feature_id"), rs.getString("layer_code"), rs.getString("level_code"),
                rs.getString("code"), rs.getString("parent_id"),
                read(rs.getString("names_json"), new TypeReference<Map<String, String>>() { }),
                read(rs.getString("props_json"), new TypeReference<Map<String, Object>>() { }), g, b, ct,
                rs.getString("geohash"), rs.getString("org_unit_ref"), rs.getInt("version"),
                Jdbc.instant(rs, "valid_from"), Jdbc.instant(rs, "valid_to"));
    }

    // ------------------------------------------------------------------ fences

    public void upsertFence(Connection c, String tenantId, Geofence f, String actor, Instant now) {
        Jdbc.update(c, "DELETE FROM GEO_FENCE WHERE tenant_id = ? AND fence_id = ?", tenantId, f.id());
        Jdbc.update(c, "INSERT INTO GEO_FENCE (tenant_id, fence_id, kind, geometry_json, center_lat_e7, center_lon_e7, radius_m, feature_ref, "
                        + "tolerance_m, max_accuracy_m, attributes_json, active, updated_by, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                tenantId, f.id(), f.kind().name(), f.geometry() == null ? null : GeoJson.write(f.geometry()),
                f.center() == null ? null : GeoSchema.e7(f.center().lat()), f.center() == null ? null : GeoSchema.e7(f.center().lon()),
                f.kind() == Geofence.Kind.CIRCLE ? Math.round(f.radiusMeters()) : null, f.featureRef(),
                Math.round(f.toleranceMeters()), Math.round(f.maxAccuracyMeters()), json(f.attributes()), 1, actor, now);
    }

    public Optional<Geofence> fence(Connection c, String tenantId, String fenceId) {
        return Jdbc.one(c, "SELECT fence_id, kind, geometry_json, center_lat_e7, center_lon_e7, radius_m, feature_ref, tolerance_m, "
                + "max_accuracy_m, attributes_json FROM GEO_FENCE WHERE tenant_id = ? AND fence_id = ? AND active = 1", rs -> {
            String gj = rs.getString("geometry_json");
            long clat = rs.getLong("center_lat_e7");
            boolean hasCenter = !rs.wasNull();
            long clon = rs.getLong("center_lon_e7");
            return new Geofence(rs.getString("fence_id"), Geofence.Kind.valueOf(rs.getString("kind")),
                    gj == null ? null : GeoJson.parse(gj),
                    hasCenter ? new GeoPoint(GeoSchema.deg(clat), GeoSchema.deg(clon)) : null, rs.getLong("radius_m"),
                    rs.getString("feature_ref"), rs.getLong("tolerance_m"), rs.getLong("max_accuracy_m"),
                    read(rs.getString("attributes_json"), new TypeReference<Map<String, Object>>() { }));
        }, tenantId, fenceId);
    }

    public void deactivateFence(Connection c, String tenantId, String fenceId, Instant now) {
        Jdbc.update(c, "UPDATE GEO_FENCE SET active = 0, updated_at = ? WHERE tenant_id = ? AND fence_id = ?", now, tenantId, fenceId);
    }

    // ------------------------------------------------------------------ object geometry (typed attribute of any registry object)

    public void upsertObjectGeometry(Connection c, String tenantId, String objectType, String objectId, String attribute,
                                     Geometry g, Set<String> tags, String actor, Instant now) {
        BBox b = g.bbox();
        GeoPoint ct = g.centroid();
        Jdbc.update(c, "DELETE FROM GEO_OBJECT_GEOMETRY WHERE tenant_id = ? AND object_type = ? AND object_id = ? AND attribute = ?",
                tenantId, objectType, objectId, attribute);
        Jdbc.update(c, "INSERT INTO GEO_OBJECT_GEOMETRY (tenant_id, object_type, object_id, attribute, geometry_json, geom_type, "
                        + "min_lat_e7, min_lon_e7, max_lat_e7, max_lon_e7, centroid_lat_e7, centroid_lon_e7, geohash, tags_json, updated_by, "
                        + "updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                tenantId, objectType, objectId, attribute, GeoJson.write(g), g.type(), GeoSchema.e7(b.minLat()), GeoSchema.e7(b.minLon()),
                GeoSchema.e7(b.maxLat()), GeoSchema.e7(b.maxLon()), GeoSchema.e7(ct.lat()), GeoSchema.e7(ct.lon()),
                Geohash.encode(ct, 9), json(tags == null ? Set.of() : tags), actor, now);
    }

    public record ObjectGeometry(String objectType, String objectId, String attribute, Geometry geometry, GeoPoint centroid,
                                 Set<String> tags) {
    }

    public Optional<ObjectGeometry> objectGeometry(Connection c, String tenantId, String objectType, String objectId, String attribute) {
        return Jdbc.one(c, "SELECT object_type, object_id, attribute, geometry_json, centroid_lat_e7, centroid_lon_e7, tags_json "
                        + "FROM GEO_OBJECT_GEOMETRY WHERE tenant_id = ? AND object_type = ? AND object_id = ? AND attribute = ?",
                GeoStore::mapObject, tenantId, objectType, objectId, attribute);
    }

    public List<ObjectGeometry> objectsByCentroidBox(Connection c, String tenantId, String objectType, BBox box) {
        return Jdbc.query(c, "SELECT object_type, object_id, attribute, geometry_json, centroid_lat_e7, centroid_lon_e7, tags_json "
                        + "FROM GEO_OBJECT_GEOMETRY WHERE tenant_id = ? AND object_type = ? AND centroid_lat_e7 BETWEEN ? AND ? "
                        + "AND centroid_lon_e7 BETWEEN ? AND ?", GeoStore::mapObject, tenantId, objectType,
                GeoSchema.e7(box.minLat()), GeoSchema.e7(box.maxLat()), GeoSchema.e7(box.minLon()), GeoSchema.e7(box.maxLon()));
    }

    public void deleteObjectGeometry(Connection c, String tenantId, String objectType, String objectId) {
        Jdbc.update(c, "DELETE FROM GEO_OBJECT_GEOMETRY WHERE tenant_id = ? AND object_type = ? AND object_id = ?",
                tenantId, objectType, objectId);
    }

    private static ObjectGeometry mapObject(ResultSet rs) throws SQLException {
        return new ObjectGeometry(rs.getString("object_type"), rs.getString("object_id"), rs.getString("attribute"),
                GeoJson.parse(rs.getString("geometry_json")),
                new GeoPoint(GeoSchema.deg(rs.getLong("centroid_lat_e7")), GeoSchema.deg(rs.getLong("centroid_lon_e7"))),
                read(rs.getString("tags_json"), new TypeReference<Set<String>>() { }));
    }

    // ------------------------------------------------------------------ external layers

    public void upsertExternal(Connection c, String tenantId, ExternalLayer x, String actor, Instant now) {
        Jdbc.update(c, "DELETE FROM GEO_EXTERNAL_LAYER WHERE tenant_id = ? AND layer_ref = ?", tenantId, x.ref());
        Jdbc.update(c, "INSERT INTO GEO_EXTERNAL_LAYER (tenant_id, layer_ref, protocol, connector_id, base_url, remote_name, service_version, "
                        + "srs, output_format, import_layer, attributes_json, active, updated_by, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                tenantId, x.ref(), x.protocol().name(), x.connectorId(), x.baseUrl(), x.remoteName(), x.serviceVersion(), x.srs(),
                x.outputFormat(), x.importLayer(), json(x.attributes()), x.active() ? 1 : 0, actor, now);
    }

    public Optional<ExternalLayer> external(Connection c, String tenantId, String ref) {
        return Jdbc.one(c, "SELECT * FROM GEO_EXTERNAL_LAYER WHERE tenant_id = ? AND layer_ref = ?", GeoStore::mapExternal, tenantId, ref);
    }

    public List<ExternalLayer> externals(Connection c, String tenantId) {
        return Jdbc.query(c, "SELECT * FROM GEO_EXTERNAL_LAYER WHERE tenant_id = ? ORDER BY layer_ref", GeoStore::mapExternal, tenantId);
    }

    private static ExternalLayer mapExternal(ResultSet rs) throws SQLException {
        return new ExternalLayer(rs.getString("layer_ref"), ExternalLayer.Protocol.valueOf(rs.getString("protocol")),
                rs.getString("connector_id"), rs.getString("base_url"), rs.getString("remote_name"), rs.getString("service_version"),
                rs.getString("srs"), rs.getString("output_format"), rs.getString("import_layer"),
                read(rs.getString("attributes_json"), new TypeReference<Map<String, Object>>() { }), rs.getInt("active") == 1);
    }

    // ------------------------------------------------------------------ json

    static String json(Object o) {
        try {
            return o == null ? null : GeoJson.MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            throw new GeoException("INVALID_JSON", "cannot serialise: " + e.getMessage(), e);
        }
    }

    static <T> T read(String s, TypeReference<T> type) {
        if (s == null || s.isBlank()) {
            try {
                return GeoJson.MAPPER.readValue(emptyFor(type), type);
            } catch (Exception e) {
                return null;
            }
        }
        try {
            return GeoJson.MAPPER.readValue(s, type);
        } catch (Exception e) {
            throw new GeoException("INVALID_JSON", "cannot read stored JSON: " + e.getMessage(), e);
        }
    }

    private static String emptyFor(TypeReference<?> type) {
        String n = type.getType().getTypeName();
        return n.startsWith("java.util.List") || n.startsWith("java.util.Set") ? "[]" : "{}";
    }
}
