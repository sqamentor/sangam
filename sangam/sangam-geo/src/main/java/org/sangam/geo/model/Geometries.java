// SPDX-License-Identifier: Apache-2.0
package org.sangam.geo.model;

import org.sangam.geo.GeoException;

import java.util.Map;

/** Format-sniffing entry point: GeoJSON (starts with '{'), WKT/EWKT, or "lat,lon". */
public final class Geometries {

    private Geometries() {
    }

    public static Geometry parse(String text) {
        if (text == null || text.isBlank()) {
            throw new GeoException("INVALID_GEOMETRY", "empty geometry");
        }
        String s = text.strip();
        if (s.startsWith("{")) {
            return GeoJson.parse(s);
        }
        if (s.matches("^-?\\d+(\\.\\d+)?\\s*,\\s*-?\\d+(\\.\\d+)?$")) {
            String[] parts = s.split(",");
            return new Geometry.Point(new GeoPoint(Double.parseDouble(parts[0].strip()), Double.parseDouble(parts[1].strip())));
        }
        return Wkt.parse(s);
    }

    /** Accepts a GeoJSON map, {"wkt": ...}, {"geojson": ...}, {"lat":..,"lon":..} or a string. */
    @SuppressWarnings("unchecked")
    public static Geometry from(Object value) {
        if (value == null) {
            throw new GeoException("INVALID_GEOMETRY", "geometry is missing");
        }
        if (value instanceof Geometry g) {
            return g;
        }
        if (value instanceof String s) {
            return parse(s);
        }
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> map = (Map<String, Object>) m;
            if (map.get("wkt") instanceof String w) {
                return Wkt.parse(w);
            }
            if (map.get("geojson") != null) {
                return from(map.get("geojson"));
            }
            if (map.containsKey("lat") && map.containsKey("lon")) {
                return new Geometry.Point(new GeoPoint(num(map.get("lat")), num(map.get("lon"))));
            }
            return GeoJson.fromObject(map);
        }
        throw new GeoException("INVALID_GEOMETRY", "unsupported geometry value: " + value.getClass().getSimpleName());
    }

    public static double num(Object o) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        if (o instanceof String s && !s.isBlank()) {
            return Double.parseDouble(s.strip());
        }
        throw new GeoException("INVALID_COORDINATE", "number expected: " + o);
    }
}
