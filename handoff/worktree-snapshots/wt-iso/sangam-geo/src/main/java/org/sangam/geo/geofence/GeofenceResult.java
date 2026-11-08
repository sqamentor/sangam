// SPDX-License-Identifier: Apache-2.0
package org.sangam.geo.geofence;

/**
 * Result of a geofence check.
 *
 * @param status             INSIDE, OUTSIDE or INDETERMINATE (the accuracy circle straddles the edge, or the fix is
 *                           too inaccurate)
 * @param signedDistanceM    negative inside (metres to the edge), positive outside
 * @param accuracyM          reported fix accuracy (68 % radius)
 * @param reason             machine-readable reason: OK, OUTSIDE_FENCE, NEAR_EDGE, ACCURACY_TOO_LOW, NO_FIX
 */
public record GeofenceResult(String fenceId, Status status, double signedDistanceM, double accuracyM, String reason) {

    public enum Status { INSIDE, OUTSIDE, INDETERMINATE }

    public boolean inside() {
        return status == Status.INSIDE;
    }
}
