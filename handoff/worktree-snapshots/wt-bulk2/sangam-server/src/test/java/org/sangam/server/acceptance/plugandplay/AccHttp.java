// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.acceptance.plugandplay;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Small REST client for the acceptance ITs: bearer token, JSON body, Idempotency-Key on writes, status check. */
public final class AccHttp {

    public static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private final String base;
    private final String token;

    public AccHttp(String base, String token) {
        this.base = base;
        this.token = token;
    }

    public static AccHttp local(int port, String token) {
        return new AccHttp("http://localhost:" + port, token);
    }

    public record Reply(int status, JsonNode body, String raw) {
    }

    public Reply send(String method, String path, Object body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(120))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json");
        if (!"GET".equals(method)) {
            b.header("Idempotency-Key", UUID.randomUUID().toString());
        }
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body instanceof String s ? s : JSON.writeValueAsString(body)));
        HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        JsonNode node;
        try {
            node = r.body() == null || r.body().isBlank() ? JSON.nullNode() : JSON.readTree(r.body());
        } catch (Exception e) {
            node = JSON.getNodeFactory().textNode(r.body());
        }
        return new Reply(r.statusCode(), node, r.body());
    }

    /** Sends and asserts the status: any 2xx when {@code expected} is empty. */
    public JsonNode call(String method, String path, Object body, int... expected) throws Exception {
        Reply r = send(method, path, body);
        boolean ok = expected.length == 0 ? r.status() / 100 == 2 : Arrays.stream(expected).anyMatch(x -> x == r.status());
        if (!ok) {
            throw new AssertionError(method + " " + path + " -> " + r.status() + " " + r.raw());
        }
        return r.body();
    }
}
