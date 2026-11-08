// SPDX-License-Identifier: Apache-2.0
package org.sangam.gateway;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code sangam.gateway.*}.
 *
 * @param controlPlaneUrl      control-plane base URL
 * @param controlPlaneToken    bearer token with role GATEWAY
 * @param refreshInterval      incremental route snapshot pull interval
 * @param maxStale             how long a cached route is still used while the control plane is unreachable (sticky)
 * @param tenantHeader         header naming the tenant (lowest precedence)
 * @param tenantClaim          JWT claim naming the tenant (highest precedence; same claim the cell enforces)
 * @param hostSuffix           {@code <tenant><hostSuffix>} host names map to a tenant, e.g. {@code .sangam.gov.in}
 * @param retryAfterSeconds    default Retry-After for 503 answers
 * @param connectTimeout       upstream connect timeout
 * @param responseTimeout      upstream response timeout
 * @param maxInFlightPerTenant per-tenant concurrent request cap on this gateway node (noisy-neighbour guard, 429)
 */
@ConfigurationProperties(prefix = "sangam.gateway")
public record GatewayProperties(String controlPlaneUrl, String controlPlaneToken, Duration refreshInterval,
                                Duration maxStale, String tenantHeader, String tenantClaim, String hostSuffix,
                                Integer retryAfterSeconds, Duration connectTimeout, Duration responseTimeout,
                                Integer maxInFlightPerTenant) {

    public GatewayProperties {
        controlPlaneUrl = controlPlaneUrl == null ? "http://localhost:8090" : controlPlaneUrl;
        refreshInterval = refreshInterval == null ? Duration.ofSeconds(5) : refreshInterval;
        maxStale = maxStale == null ? Duration.ofHours(24) : maxStale;
        tenantHeader = tenantHeader == null ? "X-Sangam-Tenant" : tenantHeader;
        tenantClaim = tenantClaim == null ? "tenant" : tenantClaim;
        retryAfterSeconds = retryAfterSeconds == null ? 30 : retryAfterSeconds;
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
        responseTimeout = responseTimeout == null ? Duration.ofSeconds(60) : responseTimeout;
        maxInFlightPerTenant = maxInFlightPerTenant == null ? 400 : maxInFlightPerTenant;
    }
}
