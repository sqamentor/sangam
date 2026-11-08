// SPDX-License-Identifier: Apache-2.0
package org.sangam.geo.index;

import org.sangam.core.layer.Jdbc;
import org.sangam.geo.model.BBox;
import org.sangam.geo.model.GeoJson;
import org.sangam.geo.model.GeoPoint;
import org.sangam.geo.model.Geometry;

import java.sql.Connection;
import java.util.List;

/**
 * Optional native index for PostgreSQL with PostGIS (GPL-2.0 server extension, used over SQL only — nothing is linked
 * or shipped). Adds a {@code geom geometry(Geometry,4326)} column and a GiST index to {@code GEO_FEATURE}; point
 * queries use {@code ST_Covers}, box queries {@code &&}, nearest-N the KNN operator {@code <->}. The portable cell
 * rows are still written, so the deployment can drop PostGIS at any time without a re-index.
 */
public final class PostgisSpatialIndex implements SpatialIndex {

    private final PortableGridIndex portable = new PortableGridIndex();

    /** True when the PostGIS extension is installed in the current database. */
    public static boolean available(Connection c) {
        try {
            return Jdbc.one(c, "SELECT 1 FROM pg_extension WHERE extname = 'postgis'", rs -> rs.getInt(1)).isPresent();
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public String name() {
        return "postgis";
    }

    @Override
    public void ensure(Connection c) {
        Jdbc.update(c, "ALTER TABLE GEO_FEATURE ADD COLUMN IF NOT EXISTS geom geometry(Geometry, 4326)");
        Jdbc.update(c, "CREATE INDEX IF NOT EXISTS GEO_FEATURE_GEOM ON GEO_FEATURE USING GIST (geom)");
    }

    @Override
    public void index(Connection c, String tenantId, String layer, String featureId, Geometry geometry, int fixedPrecision) {
        portable.index(c, tenantId, layer, featureId, geometry, fixedPrecision);
        Jdbc.update(c, "UPDATE GEO_FEATURE SET geom = ST_SetSRID(ST_GeomFromGeoJSON(?), 4326) WHERE tenant_id = ? AND feature_id = ?",
                GeoJson.write(geometry), tenantId, featureId);
    }

    @Override
    public void remove(Connection c, String tenantId, String layer, String featureId) {
        portable.remove(c, tenantId, layer, featureId);
    }

    @Override
    public List<String> containingCandidates(Connection c, String tenantId, String layer, GeoPoint point) {
        return Jdbc.query(c, "SELECT feature_id FROM GEO_FEATURE WHERE tenant_id = ? AND layer_code = ? AND active = 1 "
                        + "AND ST_Covers(geom, ST_SetSRID(ST_MakePoint(?, ?), 4326))",
                rs -> rs.getString(1), tenantId, layer, point.lon(), point.lat());
    }

    @Override
    public List<String> intersectingCandidates(Connection c, String tenantId, String layer, BBox box) {
        return Jdbc.query(c, "SELECT feature_id FROM GEO_FEATURE WHERE tenant_id = ? AND layer_code = ? AND active = 1 "
                        + "AND geom && ST_MakeEnvelope(?, ?, ?, ?, 4326)",
                rs -> rs.getString(1), tenantId, layer, box.minLon(), box.minLat(), box.maxLon(), box.maxLat());
    }

    @Override
    public List<String> nearestCandidates(Connection c, String tenantId, String layer, GeoPoint point, int n) {
        return Jdbc.query(c, "SELECT feature_id FROM GEO_FEATURE WHERE tenant_id = ? AND layer_code = ? AND active = 1 "
                        + "ORDER BY geom <-> ST_SetSRID(ST_MakePoint(?, ?), 4326) LIMIT ?",
                rs -> rs.getString(1), tenantId, layer, point.lon(), point.lat(), Math.max(1, n) * 4);
    }
}
