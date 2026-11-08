// SPDX-License-Identifier: Apache-2.0
package org.sangam.geo;

import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;

/**
 * Reference DDL of the geo tables in the core {@link Dialect} type tokens (A-15). The integrator turns this into
 * sangam-persistence Liquibase changesets for the four Tier-1 dialects; {@link #create} is for H2/dev and tests.
 *
 * <p>Portable storage (N13 "Database neutrality"): geometry is GeoJSON text; bounding boxes and centroids are stored as
 * scaled integers (degrees × 10⁷, "E7", ~1 cm) so every dialect can index them with plain B-trees; each feature also
 * has a centroid geohash and a set of covering geohash cells ({@code GEO_FEATURE_CELL}) that make point-in-polygon
 * candidate lookup an indexed {@code IN} query on any database. The optional PostGIS column and GiST index are added
 * by {@code index.PostgisSpatialIndex} only when the extension is installed. Booleans are SMALLINT 0/1. Every table is
 * tenant-scoped with {@code tenant_id} first in its key.
 */
public final class GeoSchema {

    private GeoSchema() {
    }

    private static final String[] TABLES = {
            """
            CREATE TABLE GEO_LAYER (tenant_id {str64} NOT NULL, layer_code {str64} NOT NULL, kind {str64} NOT NULL, names_json {text},
              levels_json {text} NOT NULL, org_unit_linked {small} NOT NULL, index_precision {int} NOT NULL, attributes_json {text},
              version {int} NOT NULL, updated_by {str}, updated_at {ts} NOT NULL, PRIMARY KEY (tenant_id, layer_code))""",
            """
            CREATE TABLE GEO_FEATURE (tenant_id {str64} NOT NULL, feature_id {str} NOT NULL, layer_code {str64} NOT NULL,
              level_code {str64} NOT NULL, code {str} NOT NULL, parent_id {str}, names_json {text}, props_json {text},
              geometry_json {text} NOT NULL, geom_type {str64} NOT NULL, min_lat_e7 {long} NOT NULL, min_lon_e7 {long} NOT NULL,
              max_lat_e7 {long} NOT NULL, max_lon_e7 {long} NOT NULL, centroid_lat_e7 {long} NOT NULL, centroid_lon_e7 {long} NOT NULL,
              geohash {str64} NOT NULL, area_m2 {long} NOT NULL, org_unit_ref {str}, version {int} NOT NULL, active {small} NOT NULL,
              valid_from {ts} NOT NULL, valid_to {ts}, updated_by {str}, updated_at {ts} NOT NULL, PRIMARY KEY (tenant_id, feature_id))""",
            """
            CREATE TABLE GEO_FEATURE_CELL (tenant_id {str64} NOT NULL, layer_code {str64} NOT NULL, cell {str64} NOT NULL,
              feature_id {str} NOT NULL, PRIMARY KEY (tenant_id, layer_code, cell, feature_id))""",
            """
            CREATE TABLE GEO_FENCE (tenant_id {str64} NOT NULL, fence_id {str} NOT NULL, kind {str64} NOT NULL, geometry_json {text},
              center_lat_e7 {long}, center_lon_e7 {long}, radius_m {long}, feature_ref {str}, tolerance_m {long} NOT NULL,
              max_accuracy_m {long} NOT NULL, attributes_json {text}, active {small} NOT NULL, updated_by {str}, updated_at {ts} NOT NULL,
              PRIMARY KEY (tenant_id, fence_id))""",
            """
            CREATE TABLE GEO_OBJECT_GEOMETRY (tenant_id {str64} NOT NULL, object_type {str64} NOT NULL, object_id {str} NOT NULL,
              attribute {str64} NOT NULL, geometry_json {text} NOT NULL, geom_type {str64} NOT NULL, min_lat_e7 {long} NOT NULL,
              min_lon_e7 {long} NOT NULL, max_lat_e7 {long} NOT NULL, max_lon_e7 {long} NOT NULL, centroid_lat_e7 {long} NOT NULL,
              centroid_lon_e7 {long} NOT NULL, geohash {str64} NOT NULL, tags_json {text}, updated_by {str}, updated_at {ts} NOT NULL,
              PRIMARY KEY (tenant_id, object_type, object_id, attribute))""",
            """
            CREATE TABLE GEO_EXTERNAL_LAYER (tenant_id {str64} NOT NULL, layer_ref {str64} NOT NULL, protocol {str64} NOT NULL,
              connector_id {str64} NOT NULL, base_url {text} NOT NULL, remote_name {str} NOT NULL, service_version {str64},
              srs {str64}, output_format {str}, import_layer {str64}, attributes_json {text}, active {small} NOT NULL,
              updated_by {str}, updated_at {ts} NOT NULL, PRIMARY KEY (tenant_id, layer_ref))"""
    };

    private static final String[] INDEXES = {
            "CREATE INDEX GEO_FEATURE_CODE ON GEO_FEATURE (tenant_id, layer_code, code, active)",
            "CREATE INDEX GEO_FEATURE_PARENT ON GEO_FEATURE (tenant_id, layer_code, parent_id)",
            "CREATE INDEX GEO_FEATURE_LEVEL ON GEO_FEATURE (tenant_id, layer_code, level_code, active)",
            "CREATE INDEX GEO_FEATURE_BBOX ON GEO_FEATURE (tenant_id, layer_code, min_lat_e7, max_lat_e7, min_lon_e7, max_lon_e7)",
            "CREATE INDEX GEO_FEATURE_GH ON GEO_FEATURE (tenant_id, layer_code, geohash)",
            "CREATE INDEX GEO_FEATURE_ORG ON GEO_FEATURE (tenant_id, org_unit_ref)",
            "CREATE INDEX GEO_CELL_FEATURE ON GEO_FEATURE_CELL (tenant_id, feature_id)",
            "CREATE INDEX GEO_OBJ_CENTROID ON GEO_OBJECT_GEOMETRY (tenant_id, object_type, centroid_lat_e7, centroid_lon_e7)",
            "CREATE INDEX GEO_OBJ_GH ON GEO_OBJECT_GEOMETRY (tenant_id, object_type, geohash)",
            "CREATE INDEX GEO_FENCE_FEATURE ON GEO_FENCE (tenant_id, feature_ref)"
    };

    public static List<String> ddl(Dialect dialect) {
        List<String> out = new ArrayList<>();
        for (String t : TABLES) {
            out.add(dialect.expand(t.strip()) + dialect.createTableSuffix());
        }
        out.addAll(List.of(INDEXES));
        return out;
    }

    public static List<String> tables() {
        List<String> names = new ArrayList<>();
        for (String t : TABLES) {
            String s = t.strip().substring("CREATE TABLE ".length());
            names.add(s.substring(0, s.indexOf(' ')));
        }
        return names;
    }

    /** Creates the tables (development / tests; production uses Liquibase). */
    public static void create(Connection c, Dialect dialect) {
        for (String s : ddl(dialect)) {
            Jdbc.update(c, s);
        }
    }

    /** Degrees → E7 integer. */
    public static long e7(double deg) {
        return Math.round(deg * 1e7);
    }

    /** E7 integer → degrees. */
    public static double deg(long e7) {
        return e7 / 1e7;
    }
}
