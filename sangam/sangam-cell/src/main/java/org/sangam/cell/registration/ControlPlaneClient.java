// SPDX-License-Identifier: Apache-2.0
package org.sangam.cell.registration;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.sangam.cell.rollup.MiniJson;

/**
 * Cell-side client of the control plane: register, heartbeat, tenant-path lookup (cached; stale value served while the
 * control plane is down). JDK HTTP client only. Auth: per-cell bearer token issued by the control-plane operator.
 */
public final class ControlPlaneClient {

    private final URI base;
    private final String token;
    private final HttpClient http;
    private final Map<String, CachedPath> pathCache = new ConcurrentHashMap<>();
    private final Duration pathTtl;

    private record CachedPath(String path, long expiresAt) {
    }

    public ControlPlaneClient(URI base, String token, Duration pathTtl) {
        this.base = base;
        this.token = token;
        this.pathTtl = pathTtl == null ? Duration.ofMinutes(5) : pathTtl;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    public void register(CellDescriptor d) throws IOException, InterruptedException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("region", d.region());
        m.put("jurisdiction", d.jurisdiction());
        m.put("residency", d.residency());
        m.put("apiBaseUrl", d.apiBaseUrl());
        m.put("adminBaseUrl", d.adminBaseUrl());
        m.put("dbVendor", d.dbVendor());
        m.put("capacityStartsPerSec", d.capacityStartsPerSec());
        m.put("capacityTenants", d.capacityTenants());
        m.put("version", d.version());
        send("PUT", "/api/v1/cells/" + enc(d.cellId()), MiniJson.write(m));
    }

    public void heartbeat(CellHeartbeat h) throws IOException, InterruptedException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", h.status());
        m.put("startsPerSec", h.startsPerSec());
        m.put("p99CommandMs", h.p99CommandMs());
        m.put("activeNodes", h.activeNodes());
        m.put("dbPoolUsagePct", h.dbPoolUsagePct());
        m.put("outboxFillPct", h.outboxFillPct());
        m.put("tenants", h.tenants());
        m.put("version", h.version());
        send("POST", "/api/v1/cells/" + enc(h.cellId()) + "/heartbeat", MiniJson.write(m));
    }

    public Optional<String> tenantPath(String tenantCode) {
        long now = System.currentTimeMillis();
        CachedPath c = pathCache.get(tenantCode);
        if (c != null && c.expiresAt > now) {
            return Optional.of(c.path);
        }
        try {
            String body = send("GET", "/api/v1/routes/tenants/" + enc(tenantCode), null);
            Object p = MiniJson.readObject(body).get("path");
            if (p == null) {
                return Optional.empty();
            }
            pathCache.put(tenantCode, new CachedPath(p.toString(), now + pathTtl.toMillis()));
            return Optional.of(p.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return c == null ? Optional.empty() : Optional.of(c.path);
        } catch (IOException | RuntimeException e) {
            return c == null ? Optional.empty() : Optional.of(c.path);
        }
    }

    private String send(String method, String path, String json) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(5));
        if (token != null && !token.isBlank()) {
            b.header("Authorization", "Bearer " + token);
        }
        if (json != null) {
            b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(json));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() / 100 != 2) {
            throw new IOException(method + " " + path + " -> HTTP " + r.statusCode());
        }
        return r.body();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
