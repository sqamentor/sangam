// SPDX-License-Identifier: Apache-2.0
package org.sangam.geo.geofence;

import org.sangam.geo.GeoException;
import org.sangam.geo.model.GeoMath;
import org.sangam.geo.model.GeoPoint;

/**
 * Pure geofence evaluation (no I/O). The decision uses the fix accuracy explicitly, so a fix whose uncertainty circle
 * crosses the fence edge is INDETERMINATE (sent for review) instead of a false pass or a false fail:
 * <pre>
 *   d = signed distance to the fence edge (negative inside), t = tolerance, a = accuracy
 *   d + a &lt;= t      → INSIDE
 *   d - a &gt;  t      → OUTSIDE
 *   otherwise       → INDETERMINATE (NEAR_EDGE)
 *   a &gt; maxAccuracy → INDETERMINATE (ACCURACY_TOO_LOW), unless clearly OUTSIDE
 * </pre>
 */
public final class GeofenceChecker {

    private GeofenceChecker() {
    }

    public static GeofenceResult check(Geofence fence, GeoPoint point, Double accuracyMeters) {
        if (point == null) {
            return new GeofenceResult(fence.id(), GeofenceResult.Status.INDETERMINATE, Double.NaN, Double.NaN, "NO_FIX");
        }
        double d = switch (fence.kind()) {
            case CIRCLE -> GeoMath.haversineMeters(fence.center(), point) - fence.radiusMeters();
            case POLYGON -> GeoMath.signedDistanceMeters(fence.geometry(), point);
            case FEATURE -> throw new GeoException("UNRESOLVED_FENCE", "feature fence must be resolved by GeoService first");
        };
        double a = accuracyMeters == null || accuracyMeters.isNaN() || accuracyMeters < 0 ? 0 : accuracyMeters;
        double t = fence.toleranceMeters();
        if (d - a > t) {
            return new GeofenceResult(fence.id(), GeofenceResult.Status.OUTSIDE, d, a, "OUTSIDE_FENCE");
        }
        if (a > fence.maxAccuracyMeters()) {
            return new GeofenceResult(fence.id(), GeofenceResult.Status.INDETERMINATE, d, a, "ACCURACY_TOO_LOW");
        }
        if (d + a <= t) {
            return new GeofenceResult(fence.id(), GeofenceResult.Status.INSIDE, d, a, "OK");
        }
        return new GeofenceResult(fence.id(), GeofenceResult.Status.INDETERMINATE, d, a, "NEAR_EDGE");
    }
}
