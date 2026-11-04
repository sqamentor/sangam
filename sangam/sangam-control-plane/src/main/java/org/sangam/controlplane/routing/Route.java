// SPDX-License-Identifier: Apache-2.0
package org.sangam.controlplane.routing;

/**
 * Routing answer for a tenant (contract consumed by sangam-gateway, cells and the roll-up).
 *
 * @param state      ROUTABLE, DRAINING (gateway answers 503 Retry-After for writes), MOVING (503 for all but reads
 *                   that the source still serves), CELL_DOWN (503), UNPLACED (404)
 * @param routeEpoch bumps on every change; a gateway drops cached routes with a lower epoch
 * @param ttlSeconds cache TTL hint
 */
public record Route(String tenantCode, String path, String cellId, String apiBaseUrl, String state, long routeEpoch,
                    long ttlSeconds, int retryAfterSeconds) {
}
