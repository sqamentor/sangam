// SPDX-License-Identifier: Apache-2.0
package org.sangam.geo.index;

import org.sangam.core.layer.Jdbc;
import org.sangam.geo.GeoSchema;
import org.sangam.geo.model.BBox;
import org.sangam.geo.model.GeoPoint;
import org.sangam.geo.model.Geohash;
import org.sangam.geo.model.Geometry;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Database-neutral spatial index (PostgreSQL, Oracle, SQL Server, MySQL, H2): each feature is covered by at most
 * {@link #MAX_CELLS} geohash cells at an adaptive precision (or the layer's fixed precision) stored in
 * {@code GEO_FEATURE_CELL}. A point query looks up the twelve prefixes of the point's geohash with one indexed
 * {@code IN} predicate: a covering cell of any precision is a prefix of the geohash of every point inside it. Box
 * queries use the E7 bounding-box columns of {@code GEO_FEATURE}.
 */
public final class PortableGridIndex implements SpatialIndex {

    public static final int MAX_CELLS = 32;

    @Override
    public String name() {
        return "portable-geohash";
    }

    @Override
    public void index(Connection c, String tenantId, String layer, String featureId, Geometry geometry, int fixedPrecision) {
        remove(c, tenantId, layer, featureId);
        Set<String> cells = fixedPrecision > 0 && cellCount(geometry.bbox(), fixedPrecision) <= 4096
                ? Geohash.coverAt(geometry.bbox(), fixedPrecision)
                : Geohash.cover(geometry.bbox(), MAX_CELLS);
        for (String cell : cells) {
            Jdbc.update(c, "INSERT INTO GEO_FEATURE_CELL (tenant_id, layer_code, cell, feature_id) VALUES (?,?,?,?)",
                    tenantId, layer, cell, featureId);
        }
    }

    private static long cellCount(BBox box, int precision) {
        double[] s = Geohash.cellSizeDeg(precision);
        return ((long) Math.ceil(box.heightDeg() / s[0]) + 1) * ((long) Math.ceil(box.widthDeg() / s[1]) + 1);
    }

    @Override
    public void remove(Connection c, String tenantId, String layer, String featureId) {
        Jdbc.update(c, "DELETE FROM GEO_FEATURE_CELL WHERE tenant_id = ? AND feature_id = ?", tenantId, featureId);
    }

    @Override
    public List<String> containingCandidates(Connection c, String tenantId, String layer, GeoPoint point) {
        Set<String> prefixes = Geohash.prefixes(point);
        StringBuilder in = new StringBuilder();
        List<Object> params = new ArrayList<>();
        params.add(tenantId);
        params.add(layer);
        for (String p : prefixes) {
            in.append(in.isEmpty() ? "?" : ",?");
            params.add(p);
        }
        String sql = "SELECT DISTINCT c.feature_id FROM GEO_FEATURE_CELL c JOIN GEO_FEATURE f ON f.tenant_id = c.tenant_id "
                + "AND f.feature_id = c.feature_id WHERE c.tenant_id = ? AND c.layer_code = ? AND c.cell IN (" + in
                + ") AND f.active = 1";
        return Jdbc.query(c, sql, rs -> rs.getString(1), params.toArray());
    }

    @Override
    public List<String> intersectingCandidates(Connection c, String tenantId, String layer, BBox box) {
        return Jdbc.query(c, "SELECT feature_id FROM GEO_FEATURE WHERE tenant_id = ? AND layer_code = ? AND active = 1 "
                        + "AND min_lat_e7 <= ? AND max_lat_e7 >= ? AND min_lon_e7 <= ? AND max_lon_e7 >= ?",
                rs -> rs.getString(1), tenantId, layer, GeoSchema.e7(box.maxLat()), GeoSchema.e7(box.minLat()),
                GeoSchema.e7(box.maxLon()), GeoSchema.e7(box.minLon()));
    }
}
