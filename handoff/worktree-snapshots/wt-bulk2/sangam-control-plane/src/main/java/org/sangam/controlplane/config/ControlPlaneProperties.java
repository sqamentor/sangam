// SPDX-License-Identifier: Apache-2.0
package org.sangam.controlplane.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code sangam.controlplane.*}.
 *
 * @param tokens           static bearer tokens: {@code <token>:<ROLE>[,<ROLE>]} (roles ADMIN, CELL, GATEWAY, ROLLUP);
 *                         in production use per-cell tokens from the secret store, rotated (deploy README 5)
 * @param heartbeatTimeout a cell with no heartbeat for this long is DOWN for placement and routing health
 * @param targetUtilisation placement fills a cell to at most this share of its declared starts/s capacity (0.7 keeps
 *                         30 % headroom for peaks and for absorbing a moved tenant)
 * @param maxOutboxFillPct no new placement on a cell whose outbox is at or above this fill (R40: 50 % = alert)
 * @param maxDbPoolUsagePct no new placement above this pool usage
 * @param routeTtl         TTL the routing API tells gateways to cache a route
 * @param moveRetention    how long the source copy of a moved tenant is kept before purge (rollback window)
 * @param cellRequestTimeout timeout of control plane to cell admin calls
 */
@ConfigurationProperties(prefix = "sangam.controlplane")
public record ControlPlaneProperties(List<String> tokens, Duration heartbeatTimeout, Double targetUtilisation,
                                     Double maxOutboxFillPct, Double maxDbPoolUsagePct, Duration routeTtl,
                                     Duration moveRetention, Duration cellRequestTimeout, String cellAdminToken) {

    public ControlPlaneProperties {
        tokens = tokens == null ? List.of() : List.copyOf(tokens);
        heartbeatTimeout = heartbeatTimeout == null ? Duration.ofSeconds(30) : heartbeatTimeout;
        targetUtilisation = targetUtilisation == null ? 0.7 : targetUtilisation;
        maxOutboxFillPct = maxOutboxFillPct == null ? 50.0 : maxOutboxFillPct;
        maxDbPoolUsagePct = maxDbPoolUsagePct == null ? 80.0 : maxDbPoolUsagePct;
        routeTtl = routeTtl == null ? Duration.ofSeconds(5) : routeTtl;
        moveRetention = moveRetention == null ? Duration.ofDays(7) : moveRetention;
        cellRequestTimeout = cellRequestTimeout == null ? Duration.ofSeconds(30) : cellRequestTimeout;
    }
}
