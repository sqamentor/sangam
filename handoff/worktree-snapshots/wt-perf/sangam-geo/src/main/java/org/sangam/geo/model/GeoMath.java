// SPDX-License-Identifier: Apache-2.0
package org.sangam.geo.model;

import java.util.List;

/**
 * Spherical / locally-planar geometry in plain Java (no JTS). Accuracy is what jurisdiction routing, geofencing and
 * nearest-unit dispatch need (metre level at district/ward scale): great-circle distance by haversine, edge distances
 * in a local equirectangular projection centred on the query point, point-in-polygon by ray casting with an explicit
 * on-edge test, and polygon overlap by edge intersection plus vertex containment. Heavy topology (buffers, unions,
 * cadastral editing) is out of scope (N13 boundary) and goes to an external GIS worker.
 */
public final class GeoMath {

    /** IUGG mean Earth radius. */
    public static final double EARTH_RADIUS_M = 6_371_008.8;

    private static final double ON_EDGE_M = 0.05;

    private GeoMath() {
    }

    public static double haversineMeters(GeoPoint a, GeoPoint b) {
        double p1 = Math.toRadians(a.lat()), p2 = Math.toRadians(b.lat());
        double dp = p2 - p1, dl = Math.toRadians(b.lon() - a.lon());
        double h = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1, Math.sqrt(h)));
    }

    /** Initial bearing in degrees (0 = north, clockwise). */
    public static double bearingDeg(GeoPoint a, GeoPoint b) {
        double p1 = Math.toRadians(a.lat()), p2 = Math.toRadians(b.lat()), dl = Math.toRadians(b.lon() - a.lon());
        double y = Math.sin(dl) * Math.cos(p2);
        double x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl);
        return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360;
    }

    /** Destination point from a start, a bearing and a distance on the sphere. */
    public static GeoPoint destination(GeoPoint start, double bearingDeg, double meters) {
        double d = meters / EARTH_RADIUS_M, br = Math.toRadians(bearingDeg);
        double p1 = Math.toRadians(start.lat()), l1 = Math.toRadians(start.lon());
        double p2 = Math.asin(Math.sin(p1) * Math.cos(d) + Math.cos(p1) * Math.sin(d) * Math.cos(br));
        double l2 = l1 + Math.atan2(Math.sin(br) * Math.sin(d) * Math.cos(p1), Math.cos(d) - Math.sin(p1) * Math.sin(p2));
        double lon = ((Math.toDegrees(l2) + 540) % 360) - 180;
        return new GeoPoint(Math.max(-90, Math.min(90, Math.toDegrees(p2))), lon);
    }

    // ------------------------------------------------------------------ containment

    public static boolean contains(Geometry g, GeoPoint p) {
        return switch (g) {
            case Geometry.Point pt -> haversineMeters(pt.point(), p) <= ON_EDGE_M;
            case Geometry.MultiPoint mp -> mp.points().stream().anyMatch(q -> haversineMeters(q, p) <= ON_EDGE_M);
            case Geometry.LineString ls -> distanceToPath(ls.points(), p) <= ON_EDGE_M;
            case Geometry.Polygon poly -> polygonContains(poly, p);
            case Geometry.MultiPolygon mp -> mp.polygons().stream().anyMatch(q -> polygonContains(q, p));
        };
    }

    /** Boundary counts as inside (a point on a ward boundary resolves to a ward, deterministic by layer order). */
    public static boolean polygonContains(Geometry.Polygon poly, GeoPoint p) {
        if (!poly.bbox().contains(p)) {
            return false;
        }
        if (!ringContains(poly.outer(), p)) {
            return false;
        }
        for (List<GeoPoint> hole : poly.holes()) {
            if (ringContains(hole, p) && distanceToPath(hole, p) > ON_EDGE_M) {
                return false;
            }
        }
        return true;
    }

    /** Ray casting in lon/lat space, with an explicit on-edge test so boundary points are included. */
    public static boolean ringContains(List<GeoPoint> ring, GeoPoint p) {
        boolean inside = false;
        double x = p.lon(), y = p.lat();
        for (int i = 0, j = ring.size() - 1; i < ring.size(); j = i++) {
            double xi = ring.get(i).lon(), yi = ring.get(i).lat(), xj = ring.get(j).lon(), yj = ring.get(j).lat();
            if (onSegmentDeg(x, y, xi, yi, xj, yj)) {
                return true;
            }
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) {
                inside = !inside;
            }
        }
        return inside;
    }

    private static boolean onSegmentDeg(double x, double y, double x1, double y1, double x2, double y2) {
        double cross = (x - x1) * (y2 - y1) - (y - y1) * (x2 - x1);
        if (Math.abs(cross) > 1e-12) {
            return false;
        }
        return x >= Math.min(x1, x2) - 1e-12 && x <= Math.max(x1, x2) + 1e-12 && y >= Math.min(y1, y2) - 1e-12
                && y <= Math.max(y1, y2) + 1e-12;
    }

    // ------------------------------------------------------------------ distances

    public static double distanceMeters(Geometry g, GeoPoint p) {
        return switch (g) {
            case Geometry.Point pt -> haversineMeters(pt.point(), p);
            case Geometry.MultiPoint mp -> mp.points().stream().mapToDouble(q -> haversineMeters(q, p)).min().orElse(Double.MAX_VALUE);
            case Geometry.LineString ls -> distanceToPath(ls.points(), p);
            case Geometry.Polygon poly -> polygonContains(poly, p) ? 0 : distanceToBoundaryMeters(poly, p);
            case Geometry.MultiPolygon mp -> mp.polygons().stream()
                    .mapToDouble(q -> polygonContains(q, p) ? 0 : distanceToBoundaryMeters(q, p)).min().orElse(Double.MAX_VALUE);
        };
    }

    /**
     * Signed distance to the edge of an areal geometry: negative inside (metres to the nearest edge), positive outside.
     * For non-areal geometry it is the plain distance.
     */
    public static double signedDistanceMeters(Geometry g, GeoPoint p) {
        if (!g.isAreal()) {
            return distanceMeters(g, p);
        }
        double edge = switch (g) {
            case Geometry.Polygon poly -> distanceToBoundaryMeters(poly, p);
            case Geometry.MultiPolygon mp -> mp.polygons().stream().mapToDouble(q -> distanceToBoundaryMeters(q, p)).min()
                    .orElse(Double.MAX_VALUE);
            default -> 0;
        };
        return contains(g, p) ? -edge : edge;
    }

    public static double distanceToBoundaryMeters(Geometry.Polygon poly, GeoPoint p) {
        double best = Double.MAX_VALUE;
        for (List<GeoPoint> ring : poly.rings()) {
            best = Math.min(best, distanceToPath(ring, p));
        }
        return best;
    }

    /** Minimum distance from p to a polyline, in a local equirectangular projection centred on p. */
    public static double distanceToPath(List<GeoPoint> path, GeoPoint p) {
        if (path.size() == 1) {
            return haversineMeters(path.get(0), p);
        }
        double best = Double.MAX_VALUE;
        double k = Math.cos(Math.toRadians(p.lat()));
        for (int i = 1; i < path.size(); i++) {
            double[] a = project(path.get(i - 1), p, k), b = project(path.get(i), p, k);
            best = Math.min(best, segmentDistanceFromOrigin(a[0], a[1], b[0], b[1]));
        }
        return best;
    }

    private static double[] project(GeoPoint q, GeoPoint origin, double cosLat) {
        double dLon = q.lon() - origin.lon();
        if (dLon > 180) {
            dLon -= 360;
        } else if (dLon < -180) {
            dLon += 360;
        }
        double x = Math.toRadians(dLon) * cosLat * EARTH_RADIUS_M;
        double y = Math.toRadians(q.lat() - origin.lat()) * EARTH_RADIUS_M;
        return new double[]{x, y};
    }

    private static double segmentDistanceFromOrigin(double ax, double ay, double bx, double by) {
        double dx = bx - ax, dy = by - ay;
        double len2 = dx * dx + dy * dy;
        double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, -(ax * dx + ay * dy) / len2));
        double cx = ax + t * dx, cy = ay + t * dy;
        return Math.sqrt(cx * cx + cy * cy);
    }

    // ------------------------------------------------------------------ area, centroid

    /** Approximate area in square metres (shoelace in a local projection centred on the bbox centre). */
    public static double areaSqMeters(Geometry g) {
        return switch (g) {
            case Geometry.Polygon poly -> polygonArea(poly);
            case Geometry.MultiPolygon mp -> mp.polygons().stream().mapToDouble(GeoMath::polygonArea).sum();
            default -> 0;
        };
    }

    private static double polygonArea(Geometry.Polygon poly) {
        GeoPoint o = poly.bbox().center();
        double k = Math.cos(Math.toRadians(o.lat()));
        double area = Math.abs(ringArea(poly.outer(), o, k));
        for (List<GeoPoint> h : poly.holes()) {
            area -= Math.abs(ringArea(h, o, k));
        }
        return Math.max(0, area);
    }

    private static double ringArea(List<GeoPoint> ring, GeoPoint o, double k) {
        double s = 0;
        for (int i = 1; i < ring.size(); i++) {
            double[] a = project(ring.get(i - 1), o, k), b = project(ring.get(i), o, k);
            s += a[0] * b[1] - b[0] * a[1];
        }
        return s / 2;
    }

    public static GeoPoint centroid(Geometry g) {
        Geometry.Polygon poly = switch (g) {
            case Geometry.Polygon p -> p;
            case Geometry.MultiPolygon mp -> mp.polygons().stream().max((a, b) -> Double.compare(polygonArea(a), polygonArea(b)))
                    .orElseThrow();
            default -> null;
        };
        if (poly == null) {
            List<GeoPoint> v = g.vertices();
            double lat = 0, lon = 0;
            for (GeoPoint q : v) {
                lat += q.lat();
                lon += q.lon();
            }
            return new GeoPoint(lat / v.size(), lon / v.size());
        }
        GeoPoint o = poly.bbox().center();
        double k = Math.cos(Math.toRadians(o.lat()));
        List<GeoPoint> ring = poly.outer();
        double a2 = 0, cx = 0, cy = 0;
        for (int i = 1; i < ring.size(); i++) {
            double[] a = project(ring.get(i - 1), o, k), b = project(ring.get(i), o, k);
            double cross = a[0] * b[1] - b[0] * a[1];
            a2 += cross;
            cx += (a[0] + b[0]) * cross;
            cy += (a[1] + b[1]) * cross;
        }
        if (Math.abs(a2) < 1e-9) {
            return o;
        }
        cx /= (3 * a2);
        cy /= (3 * a2);
        double lat = o.lat() + Math.toDegrees(cy / EARTH_RADIUS_M);
        double lon = o.lon() + Math.toDegrees(cx / (EARTH_RADIUS_M * k));
        return new GeoPoint(Math.max(-90, Math.min(90, lat)), Math.max(-180, Math.min(180, lon)));
    }

    // ------------------------------------------------------------------ overlap

    /** True when two geometries share any point (boundary contact counts). */
    public static boolean intersects(Geometry a, Geometry b) {
        if (!a.bbox().intersects(b.bbox())) {
            return false;
        }
        for (GeoPoint v : a.vertices()) {
            if (contains(b, v)) {
                return true;
            }
        }
        for (GeoPoint v : b.vertices()) {
            if (contains(a, v)) {
                return true;
            }
        }
        List<List<GeoPoint>> ea = edges(a), eb = edges(b);
        for (List<GeoPoint> pa : ea) {
            for (List<GeoPoint> pb : eb) {
                for (int i = 1; i < pa.size(); i++) {
                    for (int j = 1; j < pb.size(); j++) {
                        if (segmentsIntersect(pa.get(i - 1), pa.get(i), pb.get(j - 1), pb.get(j))) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private static List<List<GeoPoint>> edges(Geometry g) {
        return switch (g) {
            case Geometry.Point p -> List.of(List.of(p.point()));
            case Geometry.MultiPoint mp -> mp.points().stream().map(List::of).toList();
            case Geometry.LineString ls -> List.of(ls.points());
            case Geometry.Polygon poly -> poly.rings();
            case Geometry.MultiPolygon mp -> mp.polygons().stream().flatMap(q -> q.rings().stream()).toList();
        };
    }

    static boolean segmentsIntersect(GeoPoint p1, GeoPoint p2, GeoPoint p3, GeoPoint p4) {
        double d1 = orient(p3, p4, p1), d2 = orient(p3, p4, p2), d3 = orient(p1, p2, p3), d4 = orient(p1, p2, p4);
        if (((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))) {
            return true;
        }
        return (d1 == 0 && onSegmentDeg(p1.lon(), p1.lat(), p3.lon(), p3.lat(), p4.lon(), p4.lat()))
                || (d2 == 0 && onSegmentDeg(p2.lon(), p2.lat(), p3.lon(), p3.lat(), p4.lon(), p4.lat()))
                || (d3 == 0 && onSegmentDeg(p3.lon(), p3.lat(), p1.lon(), p1.lat(), p2.lon(), p2.lat()))
                || (d4 == 0 && onSegmentDeg(p4.lon(), p4.lat(), p1.lon(), p1.lat(), p2.lon(), p2.lat()));
    }

    private static double orient(GeoPoint a, GeoPoint b, GeoPoint c) {
        return (b.lon() - a.lon()) * (c.lat() - a.lat()) - (b.lat() - a.lat()) * (c.lon() - a.lon());
    }
}
