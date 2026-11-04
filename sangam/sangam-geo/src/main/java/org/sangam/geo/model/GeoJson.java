// SPDX-License-Identifier: Apache-2.0
package org.sangam.geo.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.sangam.geo.GeoException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** GeoJSON (RFC 7946) reader/writer for {@link Geometry}; also parses Feature / FeatureCollection. */
public final class GeoJson {

    public static final ObjectMapper MAPPER = new ObjectMapper();

    private GeoJson() {
    }

    /** A GeoJSON Feature: optional id, geometry, properties. */
    public record Feature(String id, Geometry geometry, Map<String, Object> properties) {
        public Feature {
            properties = properties == null ? Map.of() : properties;
        }

        public String prop(String key) {
            Object v = properties.get(key);
            return v == null ? null : v.toString();
        }
    }

    public static Geometry parse(String json) {
        try {
            return geometry(MAPPER.readTree(json));
        } catch (GeoException e) {
            throw e;
        } catch (Exception e) {
            throw new GeoException("INVALID_GEOJSON", "invalid GeoJSON: " + e.getMessage(), e);
        }
    }

    public static Geometry fromObject(Object value) {
        if (value instanceof String s) {
            return Geometries.parse(s);
        }
        return geometry(MAPPER.valueToTree(value));
    }

    public static Geometry geometry(JsonNode n) {
        if (n == null || n.isNull()) {
            throw new GeoException("INVALID_GEOJSON", "geometry is missing");
        }
        String type = n.path("type").asText();
        if ("Feature".equals(type)) {
            return geometry(n.get("geometry"));
        }
        JsonNode c = n.get("coordinates");
        if (c == null && !"GeometryCollection".equals(type)) {
            throw new GeoException("INVALID_GEOJSON", "coordinates are missing");
        }
        return switch (type) {
            case "Point" -> new Geometry.Point(pos(c));
            case "MultiPoint" -> new Geometry.MultiPoint(positions(c));
            case "LineString" -> new Geometry.LineString(positions(c));
            case "Polygon" -> new Geometry.Polygon(rings(c));
            case "MultiPolygon" -> {
                List<Geometry.Polygon> ps = new ArrayList<>();
                c.forEach(r -> ps.add(new Geometry.Polygon(rings(r))));
                yield new Geometry.MultiPolygon(ps);
            }
            default -> throw new GeoException("UNSUPPORTED_GEOMETRY", "unsupported GeoJSON type: " + type);
        };
    }

    public static List<Feature> features(String json) {
        try {
            return features(MAPPER.readTree(json));
        } catch (GeoException e) {
            throw e;
        } catch (Exception e) {
            throw new GeoException("INVALID_GEOJSON", "invalid GeoJSON: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    public static List<Feature> features(JsonNode n) {
        List<Feature> out = new ArrayList<>();
        String type = n.path("type").asText();
        if ("FeatureCollection".equals(type)) {
            n.path("features").forEach(f -> out.addAll(features(f)));
        } else if ("Feature".equals(type)) {
            String id = n.hasNonNull("id") ? n.get("id").asText() : null;
            Map<String, Object> props = n.hasNonNull("properties") ? MAPPER.convertValue(n.get("properties"), Map.class) : Map.of();
            out.add(new Feature(id, geometry(n.get("geometry")), props));
        } else {
            out.add(new Feature(null, geometry(n), Map.of()));
        }
        return out;
    }

    private static GeoPoint pos(JsonNode c) {
        if (!c.isArray() || c.size() < 2) {
            throw new GeoException("INVALID_GEOJSON", "position needs [lon, lat]");
        }
        return new GeoPoint(c.get(1).asDouble(), c.get(0).asDouble());
    }

    private static List<GeoPoint> positions(JsonNode c) {
        List<GeoPoint> out = new ArrayList<>(c.size());
        c.forEach(p -> out.add(pos(p)));
        return out;
    }

    private static List<List<GeoPoint>> rings(JsonNode c) {
        List<List<GeoPoint>> out = new ArrayList<>(c.size());
        c.forEach(r -> out.add(positions(r)));
        return out;
    }

    // ------------------------------------------------------------------ writing

    public static Map<String, Object> toMap(Geometry g) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", g.type());
        m.put("coordinates", switch (g) {
            case Geometry.Point p -> pos(p.point());
            case Geometry.MultiPoint mp -> positions(mp.points());
            case Geometry.LineString ls -> positions(ls.points());
            case Geometry.Polygon poly -> rings(poly.rings());
            case Geometry.MultiPolygon mp -> mp.polygons().stream().map(q -> rings(q.rings())).toList();
        });
        return m;
    }

    public static String write(Geometry g) {
        try {
            return MAPPER.writeValueAsString(toMap(g));
        } catch (Exception e) {
            throw new GeoException("INVALID_GEOMETRY", "cannot write GeoJSON", e);
        }
    }

    public static Map<String, Object> feature(String id, Geometry g, Map<String, Object> props) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "Feature");
        if (id != null) {
            m.put("id", id);
        }
        m.put("geometry", g == null ? null : toMap(g));
        m.put("properties", props == null ? Map.of() : props);
        return m;
    }

    private static List<Double> pos(GeoPoint p) {
        return List.of(round(p.lon()), round(p.lat()));
    }

    private static List<List<Double>> positions(List<GeoPoint> ps) {
        return ps.stream().map(GeoJson::pos).toList();
    }

    private static List<List<List<Double>>> rings(List<List<GeoPoint>> rs) {
        return rs.stream().map(GeoJson::positions).toList();
    }

    private static double round(double d) {
        return Math.round(d * 1e8) / 1e8;
    }
}
