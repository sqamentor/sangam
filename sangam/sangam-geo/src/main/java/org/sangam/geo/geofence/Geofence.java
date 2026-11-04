// SPDX-License-Identifier: Apache-2.0
package org.sangam.geo.geofence;

import org.sangam.geo.GeoException;
import org.sangam.geo.model.GeoPoint;
import org.sangam.geo.model.Geometry;

import java.util.Map;

/**
 * A geofence for field visits (N05) and dispatch arrival (N17).
 *
 * <ul>
 *   <li>{@code CIRCLE}: centre + radius (a visit must be within r metres of the target);</li>
 *   <li>{@code POLYGON}: any areal geometry (a parcel, a site boundary);</li>
 *   <li>{@code FEATURE}: a reference to a stored jurisdiction feature (a ward, a village), resolved by GeoService.</li>
 * </ul>
 * {@code toleranceMeters} widens the fence for known boundary imprecision. {@code maxAccuracyMeters} rejects fixes
 * whose reported accuracy is worse (the result is then INDETERMINATE, never INSIDE).
 */
public record Geofence(String id, Kind kind, Geometry geometry, GeoPoint center, double radiusMeters, String featureRef,
                       double toleranceMeters, double maxAccuracyMeters, Map<String, Object> attributes) {

    public enum Kind { CIRCLE, POLYGON, FEATURE }

    public Geofence {
        if (kind == null) {
            throw new GeoException("INVALID_FENCE", "fence kind is required");
        }
        switch (kind) {
            case CIRCLE -> {
                if (center == null || radiusMeters <= 0) {
                    throw new GeoException("INVALID_FENCE", "circle fence needs a centre and a positive radius");
                }
            }
            case POLYGON -> {
                if (geometry == null || !geometry.isAreal()) {
                    throw new GeoException("INVALID_FENCE", "polygon fence needs an areal geometry");
                }
            }
            case FEATURE -> {
                if (featureRef == null || featureRef.isBlank()) {
                    throw new GeoException("INVALID_FENCE", "feature fence needs a feature reference");
                }
            }
        }
        toleranceMeters = Math.max(0, toleranceMeters);
        maxAccuracyMeters = maxAccuracyMeters <= 0 ? 100 : maxAccuracyMeters;
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    public static Geofence circle(String id, GeoPoint center, double radiusMeters) {
        return new Geofence(id, Kind.CIRCLE, null, center, radiusMeters, null, 0, 0, Map.of());
    }

    public static Geofence polygon(String id, Geometry area, double toleranceMeters) {
        return new Geofence(id, Kind.POLYGON, area, null, 0, null, toleranceMeters, 0, Map.of());
    }

    public static Geofence feature(String id, String featureRef, double toleranceMeters) {
        return new Geofence(id, Kind.FEATURE, null, null, 0, featureRef, toleranceMeters, 0, Map.of());
    }

    /** Same fence with a resolved geometry (FEATURE → POLYGON). */
    public Geofence resolved(Geometry area) {
        return new Geofence(id, Kind.POLYGON, area, null, 0, featureRef, toleranceMeters, maxAccuracyMeters, attributes);
    }
}
