// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.sim.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.sangam.integration.sim.EffectsLedger;

/**
 * The HTTP side of one simulator container (owner decision 184): it listens on {@code SANGAM_SIM_BIND:SANGAM_SIM_PORT},
 * answers the control endpoints itself and passes every other request unchanged to the simulator running on the
 * loopback interface. A simulator that drops its answer (fault {@code DROP_RESPONSE}) is mirrored: the container closes
 * the connection without a status line too, so callers see the same "lost response" case as in process.
 * <ul>
 *   <li>{@code GET /__sim/health}: {@code 200 {"status":"UP","kind":...}}</li>
 *   <li>{@code GET /__sim/ledger}: {@code 200 {"kind":...,"effects":[{"seq","at","type","key","attributes"}]}}</li>
 *   <li>{@code POST /__sim/faults} with a {@link FaultScript}: {@code 200 {"pendingOnce":n}}, {@code 400} when the
 *       script is invalid (nothing applied)</li>
 * </ul>
 * Demo and test only: the control endpoints have no authentication.
 */
final class SimFront implements AutoCloseable {

    static final String HEALTH = "/__sim/health";
    static final String LEDGER = "/__sim/ledger";
    static final String FAULTS = "/__sim/faults";
    static final int MAX_BODY = 16 * 1024 * 1024;

    private static final ObjectMapper JSON = new ObjectMapper();
    /** Headers the JDK client sets itself, and hop-by-hop headers that are not passed on. */
    private static final Set<String> NOT_FORWARDED = Set.of("host", "connection", "content-length", "expect", "upgrade",
            "transfer-encoding", "keep-alive", "te", "trailer", "proxy-connection", "http2-settings");

    private final HostedSimulator sim;
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final HttpClient http;

    SimFront(HostedSimulator sim, InetSocketAddress address) throws IOException {
        this.sim = sim;
        this.server = HttpServer.create(address, 0);
        this.http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(10)).build();
        server.setExecutor(executor);
        server.createContext(HEALTH, ex -> control(ex, "GET", this::health));
        server.createContext(LEDGER, ex -> control(ex, "GET", this::ledger));
        server.createContext(FAULTS, ex -> control(ex, "POST", this::faults));
        server.createContext("/", this::proxy);
    }

    SimFront start() {
        server.start();
        return this;
    }

    int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    private interface Control {
        void handle(HttpExchange ex) throws IOException;
    }

    private void control(HttpExchange ex, String method, Control handler) throws IOException {
        try (ex) {
            String path = ex.getRequestURI().getPath();
            if (!path.equals(ex.getHttpContext().getPath())) {
                send(ex, 404, Map.of("error", "not found"));
            } else if (!method.equals(ex.getRequestMethod())) {
                ex.getResponseHeaders().set("Allow", method);
                send(ex, 405, Map.of("error", "method not allowed"));
            } else {
                handler.handle(ex);
            }
        }
    }

    private void health(HttpExchange ex) throws IOException {
        send(ex, 200, Map.of("status", "UP", "kind", sim.kind()));
    }

    private void ledger(HttpExchange ex) throws IOException {
        List<Map<String, Object>> effects = new ArrayList<>();
        for (EffectsLedger.Effect e : sim.ledger().all()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("seq", e.seq());
            m.put("at", e.at().toString());
            m.put("type", e.type());
            m.put("key", e.key());
            m.put("attributes", e.attributes());
            effects.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kind", sim.kind());
        out.put("effects", effects);
        send(ex, 200, out);
    }

    private void faults(HttpExchange ex) throws IOException {
        byte[] body;
        try (InputStream in = ex.getRequestBody()) {
            body = in.readNBytes(MAX_BODY + 1);
        }
        if (body.length > MAX_BODY) {
            send(ex, 413, Map.of("error", "the fault script is too large"));
            return;
        }
        FaultScript script;
        try {
            script = FaultScript.parse(body);
        } catch (IllegalArgumentException e) {
            send(ex, 400, Map.of("error", e.getMessage()));
            return;
        }
        script.applyTo(sim.faults());
        send(ex, 200, Map.of("pendingOnce", sim.faults().pendingOnce()));
    }

    private void proxy(HttpExchange ex) throws IOException {
        try (ex) {
            if (ex.getRequestURI().getPath().startsWith("/__sim/")) {
                send(ex, 404, Map.of("error", "not found"));
                return;
            }
            URI target = sim.httpTarget();
            if (target == null) {
                send(ex, 404, Map.of("error", "the " + sim.kind() + " simulator has no HTTP interface; use the control"
                        + " endpoints"));
                return;
            }
            byte[] body;
            try (InputStream in = ex.getRequestBody()) {
                body = in.readNBytes(MAX_BODY + 1);
            }
            if (body.length > MAX_BODY) {
                send(ex, 413, Map.of("error", "the request is too large for the simulator"));
                return;
            }
            String raw = ex.getRequestURI().getRawPath()
                    + (ex.getRequestURI().getRawQuery() == null ? "" : "?" + ex.getRequestURI().getRawQuery());
            HttpRequest.Builder b = HttpRequest.newBuilder(target.resolve(raw)).timeout(Duration.ofMinutes(15))
                    .method(ex.getRequestMethod(), body.length == 0 ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofByteArray(body));
            ex.getRequestHeaders().forEach((name, values) -> {
                if (!NOT_FORWARDED.contains(name.toLowerCase(Locale.ROOT))) {
                    values.forEach(v -> b.header(name, v));
                }
            });
            HttpResponse<byte[]> r;
            try {
                r = http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
            } catch (IOException e) {
                return; // the simulator closed without an answer (DROP_RESPONSE): close this connection the same way
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            r.headers().map().forEach((name, values) -> {
                if (!NOT_FORWARDED.contains(name.toLowerCase(Locale.ROOT)) && !name.startsWith(":")) {
                    ex.getResponseHeaders().put(name, new ArrayList<>(values));
                }
            });
            byte[] answer = r.body();
            ex.sendResponseHeaders(r.statusCode(), answer.length == 0 ? -1 : answer.length);
            if (answer.length > 0) {
                try (OutputStream out = ex.getResponseBody()) {
                    out.write(answer);
                }
            }
        }
    }

    private static void send(HttpExchange ex, int status, Map<String, ?> body) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(body);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }
}
