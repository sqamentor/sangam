// SPDX-License-Identifier: Apache-2.0
package org.sangam.gateway;

import java.net.ConnectException;
import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import org.sangam.core.layer.Hashes;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Reactive reverse proxy to the tenant's cell.
 * <ul>
 *   <li>Request id: {@code X-Request-Id} is kept when well-formed, otherwise generated (UUIDv7); echoed on the
 *       response; {@code traceparent}/{@code tracestate} pass through untouched.</li>
 *   <li>Route state: ROUTABLE proxies; DRAINING proxies reads and answers writes 503 + Retry-After; MOVING and
 *       CELL_DOWN answer 503 + Retry-After; UNPLACED or unknown tenant answers 404.</li>
 *   <li>Cell unreachable (connect error, timeout): 503 + Retry-After. An upstream 421 (tenant moved away) invalidates
 *       the cached route and answers 503 with Retry-After 1 so the client retries against the new cell.</li>
 *   <li>Noisy neighbour: per-tenant in-flight cap per gateway node, 429 + Retry-After 1 above it.</li>
 * </ul>
 * Bodies are streamed (no buffering); hop-by-hop headers are removed.
 */
@Component
public class CellProxy {

    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9._:-]{8,128}");
    private static final Set<String> HOP_BY_HOP = Set.of("connection", "keep-alive", "proxy-authenticate",
            "proxy-authorization", "te", "trailer", "transfer-encoding", "upgrade", "host", "content-length");
    private static final Set<HttpMethod> READS = Set.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS);

    private final TenantResolver tenants;
    private final RouteCache routes;
    private final GatewayProperties props;
    private final WebClient upstream;
    private final Map<String, AtomicInteger> inFlight = new ConcurrentHashMap<>();

    public CellProxy(TenantResolver tenants, RouteCache routes, GatewayProperties props, WebClient.Builder builder) {
        this.tenants = tenants;
        this.routes = routes;
        this.props = props;
        this.upstream = builder.clone().build();
    }

    public Mono<ServerResponse> handle(ServerRequest request) {
        ServerHttpRequest req = request.exchange().getRequest();
        String rid = req.getHeaders().getFirst("X-Request-Id");
        String requestId = rid != null && REQUEST_ID.matcher(rid).matches() ? rid : Hashes.uuidV7().toString();
        String tenant = tenants.resolve(req).orElse(null);
        if (tenant == null) {
            return problem(HttpStatus.BAD_REQUEST, "tenant-unresolved",
                    "No tenant in token, host or " + props.tenantHeader() + " header", requestId, 0);
        }
        return routes.resolve(tenant)
                .flatMap(route -> forward(request, req, tenant, route, requestId))
                .switchIfEmpty(Mono.defer(() -> problem(HttpStatus.NOT_FOUND, "tenant-unknown",
                        "Tenant " + tenant + " is not registered", requestId, 0)))
                .onErrorResume(RouteCache.ControlPlaneUnavailable.class, e -> problem(HttpStatus.SERVICE_UNAVAILABLE,
                        "routing-unavailable", "Route for " + tenant + " unknown and control plane unreachable",
                        requestId, props.retryAfterSeconds()));
    }

    private Mono<ServerResponse> forward(ServerRequest request, ServerHttpRequest req, String tenant,
                                         RouteCache.Route route, String requestId) {
        HttpMethod method = req.getMethod();
        switch (route.state()) {
            case "ROUTABLE" -> {
            }
            case "DRAINING" -> {
                if (!READS.contains(method)) {
                    return problem(HttpStatus.SERVICE_UNAVAILABLE, "tenant-draining",
                            "Tenant " + tenant + " is being moved; retry later", requestId, retry(route));
                }
            }
            case "MOVING", "CELL_DOWN" -> {
                return problem(HttpStatus.SERVICE_UNAVAILABLE, route.state().equals("MOVING") ? "tenant-moving" : "cell-down",
                        "Cell for tenant " + tenant + " is not available; retry later", requestId, retry(route));
            }
            default -> {
                return problem(HttpStatus.NOT_FOUND, "tenant-unplaced", "Tenant " + tenant + " has no cell", requestId, 0);
            }
        }
        AtomicInteger counter = inFlight.computeIfAbsent(tenant, k -> new AtomicInteger());
        if (counter.incrementAndGet() > props.maxInFlightPerTenant()) {
            counter.decrementAndGet();
            return problem(HttpStatus.TOO_MANY_REQUESTS, "tenant-overloaded",
                    "Too many concurrent requests for tenant " + tenant, requestId, 1);
        }
        URI target = URI.create(route.apiBaseUrl().replaceAll("/+$", "") + req.getURI().getRawPath()
                + (req.getURI().getRawQuery() == null ? "" : "?" + req.getURI().getRawQuery()));
        Flux<DataBuffer> body = request.bodyToFlux(DataBuffer.class);
        return upstream.method(method).uri(target)
                .headers(h -> copyRequestHeaders(req.getHeaders(), h, requestId, route, req))
                .body(BodyInserters.fromDataBuffers(body))
                .retrieve()
                .onStatus(s -> true, r -> Mono.empty())
                .toEntityFlux(DataBuffer.class)
                .timeout(props.responseTimeout())
                .flatMap(entity -> respond(entity, tenant, requestId))
                .onErrorResume(e -> isUnreachable(e), e -> problem(HttpStatus.SERVICE_UNAVAILABLE, "cell-unreachable",
                        "Cell " + route.cellId() + " did not answer", requestId, props.retryAfterSeconds()))
                .doFinally(s -> counter.decrementAndGet());
    }

    private Mono<ServerResponse> respond(ResponseEntity<Flux<DataBuffer>> entity, String tenant, String requestId) {
        if (entity.getStatusCode().value() == 421) {
            routes.invalidate(tenant);
            Flux<DataBuffer> b = entity.getBody();
            Mono<Void> drain = b == null ? Mono.empty() : b.map(org.springframework.core.io.buffer.DataBufferUtils::release).then();
            return drain.then(problem(HttpStatus.SERVICE_UNAVAILABLE, "tenant-moved",
                    "Tenant " + tenant + " moved to another cell; retry", requestId, 1));
        }
        ServerResponse.BodyBuilder out = ServerResponse.status(entity.getStatusCode());
        out.headers(h -> {
            entity.getHeaders().forEach((k, v) -> {
                if (!HOP_BY_HOP.contains(k.toLowerCase())) {
                    h.addAll(k, v);
                }
            });
            h.set("X-Request-Id", requestId);
        });
        Flux<DataBuffer> b = entity.getBody();
        return b == null ? out.build() : out.body(BodyInserters.fromDataBuffers(b));
    }

    private static void copyRequestHeaders(HttpHeaders in, HttpHeaders out, String requestId, RouteCache.Route route,
                                           ServerHttpRequest req) {
        in.forEach((k, v) -> {
            if (!HOP_BY_HOP.contains(k.toLowerCase()) && !k.equalsIgnoreCase("X-Request-Id")) {
                out.addAll(k, v);
            }
        });
        out.set("X-Request-Id", requestId);
        out.set("X-Sangam-Cell", route.cellId());
        out.set("X-Sangam-Route-Epoch", Long.toString(route.routeEpoch()));
        String host = in.getFirst("Host");
        if (host != null) {
            out.set("X-Forwarded-Host", host);
        }
        out.set("X-Forwarded-Proto", req.getURI().getScheme() == null ? "http" : req.getURI().getScheme());
        if (req.getRemoteAddress() != null && req.getRemoteAddress().getAddress() != null) {
            String prior = in.getFirst("X-Forwarded-For");
            String addr = req.getRemoteAddress().getAddress().getHostAddress();
            out.set("X-Forwarded-For", prior == null ? addr : prior + ", " + addr);
        }
    }

    private int retry(RouteCache.Route r) {
        return r.retryAfterSeconds() > 0 ? r.retryAfterSeconds() : props.retryAfterSeconds();
    }

    private static boolean isUnreachable(Throwable e) {
        return e instanceof WebClientRequestException || e instanceof TimeoutException
                || e.getCause() instanceof ConnectException;
    }

    static Mono<ServerResponse> problem(HttpStatus status, String type, String detail, String requestId, int retryAfter) {
        ServerResponse.BodyBuilder b = ServerResponse.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .header("X-Request-Id", requestId);
        if (retryAfter > 0) {
            b.header(HttpHeaders.RETRY_AFTER, Integer.toString(retryAfter));
        }
        String json = "{\"type\":\"https://sangam.org/problems/" + type + "\",\"title\":\"" + status.getReasonPhrase()
                + "\",\"status\":" + status.value() + ",\"detail\":" + org.sangam.cell.rollup.MiniJson.write(detail)
                + ",\"requestId\":\"" + requestId + "\"}";
        return b.bodyValue(json);
    }
}
