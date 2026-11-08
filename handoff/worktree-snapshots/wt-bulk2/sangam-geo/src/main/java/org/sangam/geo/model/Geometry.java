// SPDX-License-Identifier: Apache-2.0
package org.sangam.geo.model;

import org.sangam.geo.GeoException;

import java.util.ArrayList;
import java.util.List;

/**
 * Geometry value (OGC simple features subset, WGS-84): Point, MultiPoint, LineString, Polygon (outer ring + holes)
 * and MultiPolygon. Stored portably as GeoJSON text plus a bounding box and a geohash (N13 "Database neutrality").
 */
public sealed interface Geometry permits Geometry.Point, Geometry.MultiPoint, Geometry.LineString, Geometry.Polygon,
        Geometry.MultiPolygon {

    String type();

    BBox bbox();

    /** All vertices (for bounding boxes and centroids of non-areal geometry). */
    List<GeoPoint> vertices();

    default GeoPoint centroid() {
        return GeoMath.centroid(this);
    }

    default boolean contains(GeoPoint p) {
        return GeoMath.contains(this, p);
    }

    /** 0 when the point is inside an areal geometry or on a line/point; otherwise metres to the nearest edge/vertex. */
    default double distanceMeters(GeoPoint p) {
        return GeoMath.distanceMeters(this, p);
    }

    default boolean isAreal() {
        return this instanceof Polygon || this instanceof MultiPolygon;
    }

    record Point(GeoPoint point) implements Geometry {
        public String type() {
            return "Point";
        }

        public BBox bbox() {
            return new BBox(point.lat(), point.lon(), point.lat(), point.lon());
        }

        public List<GeoPoint> vertices() {
            return List.of(point);
        }
    }

    record MultiPoint(List<GeoPoint> points) implements Geometry {
        public MultiPoint {
            if (points == null || points.isEmpty()) {
                throw new GeoException("INVALID_GEOMETRY", "MultiPoint needs at least one point");
            }
            points = List.copyOf(points);
        }

        public String type() {
            return "MultiPoint";
        }

        public BBox bbox() {
            return BBox.of(points);
        }

        public List<GeoPoint> vertices() {
            return points;
        }
    }

    record LineString(List<GeoPoint> points) implements Geometry {
        public LineString {
            if (points == null || points.size() < 2) {
                throw new GeoException("INVALID_GEOMETRY", "LineString needs at least two points");
            }
            points = List.copyOf(points);
        }

        public String type() {
            return "LineString";
        }

        public BBox bbox() {
            return BBox.of(points);
        }

        public List<GeoPoint> vertices() {
            return points;
        }
    }

    /** rings.get(0) is the outer ring; the rest are holes. Rings are closed on construction if open. */
    record Polygon(List<List<GeoPoint>> rings) implements Geometry {
        public Polygon {
            if (rings == null || rings.isEmpty()) {
                throw new GeoException("INVALID_GEOMETRY", "Polygon needs an outer ring");
            }
            List<List<GeoPoint>> closed = new ArrayList<>(rings.size());
            for (List<GeoPoint> r : rings) {
                List<GeoPoint> ring = new ArrayList<>(r);
                if (ring.size() >= 1 && !ring.get(0).equals(ring.get(ring.size() - 1))) {
                    ring.add(ring.get(0));
                }
                if (ring.size() < 4) {
                    throw new GeoException("INVALID_GEOMETRY", "Polygon ring needs at least three distinct points");
                }
                closed.add(List.copyOf(ring));
            }
            rings = List.copyOf(closed);
        }

        public static Polygon of(List<GeoPoint> outer) {
            return new Polygon(List.of(outer));
        }

        public List<GeoPoint> outer() {
            return rings.get(0);
        }

        public List<List<GeoPoint>> holes() {
            return rings.subList(1, rings.size());
        }

        public String type() {
            return "Polygon";
        }

        public BBox bbox() {
            return BBox.of(outer());
        }

        public List<GeoPoint> vertices() {
            List<GeoPoint> all = new ArrayList<>();
            rings.forEach(all::addAll);
            return all;
        }
    }

    record MultiPolygon(List<Polygon> polygons) implements Geometry {
        public MultiPolygon {
            if (polygons == null || polygons.isEmpty()) {
                throw new GeoException("INVALID_GEOMETRY", "MultiPolygon needs at least one polygon");
            }
            polygons = List.copyOf(polygons);
        }

        public String type() {
            return "MultiPolygon";
        }

        public BBox bbox() {
            BBox b = polygons.get(0).bbox();
            for (int i = 1; i < polygons.size(); i++) {
                b = b.union(polygons.get(i).bbox());
            }
            return b;
        }

        public List<GeoPoint> vertices() {
            List<GeoPoint> all = new ArrayList<>();
            polygons.forEach(p -> all.addAll(p.vertices()));
            return all;
        }
    }

    /** Circle approximated as a polygon (for circular geofences that must be stored as GeoJSON). */
    static Polygon circle(GeoPoint center, double radiusMeters, int segments) {
        int n = Math.max(8, segments);
        List<GeoPoint> ring = new ArrayList<>(n + 1);
        for (int i = 0; i < n; i++) {
            ring.add(GeoMath.destination(center, 360.0 * i / n, radiusMeters));
        }
        return Polygon.of(ring);
    }
}
