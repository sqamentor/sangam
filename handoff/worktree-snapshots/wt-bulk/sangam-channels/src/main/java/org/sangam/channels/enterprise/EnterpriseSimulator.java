// SPDX-License-Identifier: Apache-2.0
package org.sangam.channels.enterprise;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.sangam.integration.connector.Json;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Loopback simulator of the public ServiceNow Table API shape ({@code /api/now/table/}) and of an OData service with a CSRF token
 * ({@code /odata/svc}), for the simulator profile. It models request shape and token handling, not vendor business rules. It uses
 * the JDK server directly because these exchanges need several methods on one path and custom response headers.
 */
public final class EnterpriseSimulator implements AutoCloseable {

    public static final String CSRF = "sim-csrf-token";

    private final List<Map<String, Object>> records = new CopyOnWriteArrayList<>();
    private final List<Map<String, Object>> entities = new CopyOnWriteArrayList<>();
    private final java.util.concurrent.atomic.AtomicBoolean expireNextToken = new java.util.concurrent.atomic.AtomicBoolean();
    private final HttpServer server;

    public EnterpriseSimulator() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/api/now/table/", this::table);
        server.createContext("/odata/", this::odata);
        server.start();
    }

    public URI baseUri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    public URI odataRoot() {
        return URI.create(baseUri() + "/odata/svc");
    }

    private void table(HttpExchange x) throws IOException {
        String method = x.getRequestMethod();
        if (method.equals("POST")) {
            Map<String, Object> rec = new LinkedHashMap<>(Json.readMap(new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            rec.put("sys_id", UUID.randomUUID().toString());
            records.add(rec);
            reply(x, 201, Json.write(Map.of("result", rec)));
            return;
        }
        if (method.equals("PATCH")) {
            String p = x.getRequestURI().getPath();
            String id = p.substring(p.lastIndexOf('/') + 1);
            Map<String, Object> changes = Json.readMap(new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            var target = records.stream().filter(r -> id.equals(r.get("sys_id"))).findFirst();
            if (target.isEmpty()) {
                reply(x, 404, "{\"error\":{\"message\":\"No Record found\"}}");
                return;
            }
            target.get().putAll(changes);
            reply(x, 200, Json.write(Map.of("result", target.get())));
            return;
        }
        String rawQuery = x.getRequestURI().getRawQuery();
        if (rawQuery != null && rawQuery.contains("sysparm_query=")) {
            String q = URLDecoder.decode(rawQuery.substring(rawQuery.indexOf("sysparm_query=") + 14), StandardCharsets.UTF_8);
            String[] kv = q.split("=", 2);
            reply(x, 200, Json.write(Map.of("result", records.stream().filter(r -> kv[1].equals(r.get(kv[0]))).limit(1).toList())));
            return;
        }
        String path = x.getRequestURI().getPath();
        String sys = path.substring(path.lastIndexOf('/') + 1);
        var found = records.stream().filter(r -> sys.equals(r.get("sys_id"))).findFirst();
        if (found.isPresent()) {
            reply(x, 200, Json.write(Map.of("result", found.get())));
        } else {
            reply(x, 404, "{\"error\":{\"message\":\"No Record found\"}}");
        }
    }

    private void odata(HttpExchange x) throws IOException {
        if (x.getRequestMethod().equals("GET")) {
            if ("Fetch".equalsIgnoreCase(x.getRequestHeaders().getFirst("X-CSRF-Token"))) {
                x.getResponseHeaders().add("X-CSRF-Token", CSRF);
                x.getResponseHeaders().add("Set-Cookie", "sim-session=1; Path=/");
            }
            reply(x, 200, "{\"d\":{\"results\":[]}}");
            return;
        }
        String in = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        boolean expired = expireNextToken.getAndSet(false);
        if (!expired && CSRF.equals(x.getRequestHeaders().getFirst("X-CSRF-Token")) && "sim-session=1".equals(x.getRequestHeaders().getFirst("Cookie"))) {
            entities.add(Json.readMap(in));
            x.getResponseHeaders().add("Location", baseUri() + x.getRequestURI().getPath() + "('" + entities.size() + "')");
            reply(x, 201, in);
        } else {
            x.getResponseHeaders().add("X-CSRF-Token", "Required");
            reply(x, 403, "{\"error\":\"CSRF token validation failed\"}");
        }
    }

    private static void reply(HttpExchange x, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().add("Content-Type", "application/json");
        x.sendResponseHeaders(status, body.length);
        try (var out = x.getResponseBody()) {
            out.write(body);
        }
    }

    /** The next modifying OData request is refused as if its session had timed out. */
    public void expireNextToken() {
        expireNextToken.set(true);
    }

    public List<Map<String, Object>> records() {
        return List.copyOf(records);
    }

    public List<Map<String, Object>> entities() {
        return List.copyOf(entities);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
