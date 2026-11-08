// SPDX-License-Identifier: Apache-2.0
package org.sangam.gateway;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import reactor.core.publisher.Mono;

/**
 * Sticky route cache. A full snapshot is loaded at start, then incremental pulls every {@code refreshInterval}
 * ({@code /api/v1/routes?sinceMs=}). A route is replaced only by one with an equal or higher route epoch. While the
 * control plane is unreachable, cached routes keep being served up to {@code maxStale} (the control plane is never on
 * the request path). A miss is resolved with a single-tenant lookup and cached.
 */
@Component
public class RouteCache {

    private static final Logger LOG = LoggerFactory.getLogger(RouteCache.class);

    public record Route(String tenantCode, String path, String cellId, String apiBaseUrl, String state, long routeEpoch,
                        long ttlSeconds, int retryAfterSeconds) {
    }

    record Entry(Route route, long fetchedAtMs) {
    }

    record Snapshot(long asOfMs, List<Route> routes) {
    }

    private final Map<String, Entry> routes = new ConcurrentHashMap<>();
    private final WebClient cp;
    private final GatewayProperties props;
    private volatile long lastSnapshotMs;
    private volatile long lastSuccessMs;

    public RouteCache(WebClient.Builder builder, GatewayProperties props) {
        this.props = props;
        WebClient.Builder b = builder.clone().baseUrl(props.controlPlaneUrl());
        if (props.controlPlaneToken() != null && !props.controlPlaneToken().isBlank()) {
            b.defaultHeader("Authorization", "Bearer " + props.controlPlaneToken());
        }
        this.cp = b.build();
    }

    public Mono<Route> resolve(String tenant) {
        Entry e = routes.get(tenant);
        long now = System.currentTimeMillis();
        if (e != null && now - e.fetchedAtMs() < Math.max(1, e.route().ttlSeconds()) * 1000L) {
            return Mono.just(e.route());
        }
        return cp.get().uri("/api/v1/routes/tenants/{t}", tenant).retrieve().bodyToMono(Route.class)
                .timeout(Duration.ofSeconds(2))
                .doOnNext(r -> {
                    put(r);
                    lastSuccessMs = System.currentTimeMillis();
                })
                .onErrorResume(WebClientResponseException.class, ex -> ex.getStatusCode() == HttpStatus.NOT_FOUND
                        ? Mono.empty() : stale(tenant, e, ex))
                .onErrorResume(ex -> stale(tenant, e, ex));
    }

    private Mono<Route> stale(String tenant, Entry e, Throwable ex) {
        if (e != null && System.currentTimeMillis() - e.fetchedAtMs() < props.maxStale().toMillis()) {
            return Mono.just(e.route());
        }
        LOG.warn("No route for tenant {} and control plane unavailable: {}", tenant, ex.toString());
        return Mono.error(new ControlPlaneUnavailable());
    }

    /** Called when a cell answers 421 Misdirected (tenant moved) so the next request re-resolves. */
    public void invalidate(String tenant) {
        routes.remove(tenant);
    }

    void put(Route r) {
        routes.merge(r.tenantCode(), new Entry(r, System.currentTimeMillis()),
                (old, neu) -> neu.route().routeEpoch() >= old.route().routeEpoch() ? neu : old);
    }

    @Scheduled(fixedDelayString = "${sangam.gateway.refresh-interval:5s}", initialDelay = 0)
    public void refresh() {
        try {
            Snapshot s = cp.get().uri(u -> u.path("/api/v1/routes").queryParam("sinceMs", Math.max(0, lastSnapshotMs - 1000)).build())
                    .retrieve().bodyToMono(new ParameterizedTypeReference<Snapshot>() { })
                    .block(Duration.ofSeconds(10));
            if (s != null) {
                long now = System.currentTimeMillis();
                for (Route r : s.routes()) {
                    routes.merge(r.tenantCode(), new Entry(r, now),
                            (old, neu) -> neu.route().routeEpoch() >= old.route().routeEpoch() ? neu : old);
                }
                lastSnapshotMs = s.asOfMs();
                lastSuccessMs = now;
            }
        } catch (RuntimeException e) {
            LOG.debug("Route snapshot refresh failed: {}", e.toString());
        }
    }

    public int size() {
        return routes.size();
    }

    public long lastSuccessMs() {
        return lastSuccessMs;
    }

    /** No route and no usable stale entry. */
    public static final class ControlPlaneUnavailable extends RuntimeException {
        public ControlPlaneUnavailable() {
            super("control plane unavailable and no cached route", null, false, false);
        }
    }
}
