// SPDX-License-Identifier: Apache-2.0
package org.sangam.geo.model;

import java.util.Collection;

/** Axis-aligned bounding box in degrees. Antimeridian-crossing boxes are not supported (split the geometry). */
public record BBox(double minLat, double minLon, double maxLat, double maxLon) {

    public static BBox of(Collection<GeoPoint> points) {
        double a = 90, b = 180, c = -90, d = -180;
        for (GeoPoint p : points) {
            a = Math.min(a, p.lat());
            b = Math.min(b, p.lon());
            c = Math.max(c, p.lat());
            d = Math.max(d, p.lon());
        }
        return new BBox(a, b, c, d);
    }

    /** Box around a point with a radius in metres. */
    public static BBox around(GeoPoint p, double radiusMeters) {
        double dLat = Math.toDegrees(radiusMeters / GeoMath.EARTH_RADIUS_M);
        double cos = Math.max(0.01, Math.cos(Math.toRadians(p.lat())));
        double dLon = Math.min(180, dLat / cos);
        return new BBox(Math.max(-90, p.lat() - dLat), Math.max(-180, p.lon() - dLon),
                Math.min(90, p.lat() + dLat), Math.min(180, p.lon() + dLon));
    }

    public boolean contains(GeoPoint p) {
        return p.lat() >= minLat && p.lat() <= maxLat && p.lon() >= minLon && p.lon() <= maxLon;
    }

    public boolean intersects(BBox o) {
        return o.minLat <= maxLat && o.maxLat >= minLat && o.minLon <= maxLon && o.maxLon >= minLon;
    }

    public BBox union(BBox o) {
        return new BBox(Math.min(minLat, o.minLat), Math.min(minLon, o.minLon), Math.max(maxLat, o.maxLat),
                Math.max(maxLon, o.maxLon));
    }

    public BBox expand(double meters) {
        BBox lo = around(new GeoPoint(minLat, minLon), meters);
        BBox hi = around(new GeoPoint(maxLat, maxLon), meters);
        return lo.union(hi);
    }

    public GeoPoint center() {
        return new GeoPoint((minLat + maxLat) / 2, (minLon + maxLon) / 2);
    }

    public double heightDeg() {
        return maxLat - minLat;
    }

    public double widthDeg() {
        return maxLon - minLon;
    }
}
