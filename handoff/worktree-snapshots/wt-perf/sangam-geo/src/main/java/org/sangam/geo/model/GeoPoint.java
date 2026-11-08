// SPDX-License-Identifier: Apache-2.0
package org.sangam.geo.model;

import org.sangam.geo.GeoException;

/**
 * A WGS-84 position (EPSG:4326). Latitude first in the Java API; GeoJSON and WKT order is longitude, latitude and the
 * parsers handle the swap.
 */
public record GeoPoint(double lat, double lon) {

    public GeoPoint {
        if (Double.isNaN(lat) || Double.isNaN(lon) || lat < -90 || lat > 90 || lon < -180 || lon > 180) {
            throw new GeoException("INVALID_COORDINATE", "coordinate out of range: lat=" + lat + " lon=" + lon);
        }
    }

    public static GeoPoint of(double lat, double lon) {
        return new GeoPoint(lat, lon);
    }

    public double distanceMeters(GeoPoint other) {
        return GeoMath.haversineMeters(this, other);
    }

    @Override
    public String toString() {
        return lat + "," + lon;
    }
}
