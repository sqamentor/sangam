// SPDX-License-Identifier: Apache-2.0
// Ported from spike/simulators org.sangam.spike.sim.SimHttpServer. Modified by Sangam.
package org.sangam.integration.sim;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Base for in-process HTTP simulators on the JDK {@link HttpServer}, bound to the loopback address
 * on a free port (SIMULATOR connector profile, ARCHITECTURE.md §1.9 air-gapped development). Every
 * route goes through the {@link FaultInjector}; the route name used for fault matching is the
 * request path.
 *
 * <p>Two kinds of routes:
 * <ul>
 *   <li>{@link #route(String, String, Handler)}: the path must match exactly (the spike behaviour).</li>
 *   <li>{@link #prefixRoute(String, String, Handler)}: the path must start with the prefix and be longer
 *       than it, for resource paths such as {@code GET /api/operations/{id}}. The handler receives the
 *       full request path; {@link Request#pathAfter(String)} gives the remainder.</li>
 * </ul>
 * The JDK server picks the longest matching context, so an exact route {@code /x} and a prefix
 * route {@code /x/} can coexist.
 */
public abstract class SimHttpServer implements AutoCloseable {

    /** A request as seen by a route handler. */
    public record Request(String method, String path, String query, Headers headers, byte[] body) {
        public String bodyString() {
            return new String(body, StandardCharsets.UTF_8);
        }

        public String header(String name) {
            return headers.getFirst(name);
        }

        public String queryParam(String name) {
            if (query == null) {
                return null;
            }
            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                String k = eq < 0 ? pair : pair.substring(0, eq);
                if (k.equals(name)) {
                    return eq < 0 ? "" : java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                }
            }
            return null;
        }

        /** The URL-decoded path remainder after {@code prefix}, or null when the path does not start with it. */
        public String pathAfter(String prefix) {
            if (path == null || !path.startsWith(prefix)) {
                return null;
            }
            return java.net.URLDecoder.decode(path.substring(prefix.length()), StandardCharsets.UTF_8);
        }
    }

    /** A response produced by a route handler. */
    public record Response(int status, String contentType, byte[] body) {
        public static Response json(int status, String json) {
            return new Response(status, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
        }

        public static Response xml(int status, String xml) {
            return new Response(status, "application/xml; charset=utf-8", xml.getBytes(StandardCharsets.UTF_8));
        }

        public static Response text(int status, String text) {
            return new Response(status, "text/plain; charset=utf-8", text.getBytes(StandardCharsets.UTF_8));
        }
    }

    @FunctionalInterface
    public interface Handler {
        Response handle(Request request) throws Exception;
    }

    private final String name;
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final FaultInjector faults = new FaultInjector();
    private final EffectsLedger ledger = new EffectsLedger();
    private final AtomicLong received = new AtomicLong();
    private volatile boolean started;

    protected SimHttpServer(String name) {
        this.name = name;
        try {
            this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.setExecutor(executor);
    }

    /** Register a route; {@code method} is matched exactly, the path exactly. */
    protected final void route(String method, String path, Handler handler) {
        server.createContext(path, exchange -> dispatch(exchange, method, path, false, handler));
    }

    /**
     * Register a prefix route; {@code method} is matched exactly, the request path must start with
     * {@code prefix} and have at least one more character. {@code prefix} should end with {@code /}.
     */
    protected final void prefixRoute(String method, String prefix, Handler handler) {
        server.createContext(prefix, exchange -> dispatch(exchange, method, prefix, true, handler));
    }

    @SuppressWarnings("unchecked")
    public <T extends SimHttpServer> T start() {
        if (!started) {
            server.start();
            started = true;
        }
        return (T) this;
    }

    public URI baseUri() {
        InetSocketAddress a = server.getAddress();
        return URI.create("http://" + a.getAddress().getHostAddress() + ":" + a.getPort());
    }

    public URI uri(String path) {
        return baseUri().resolve(path);
    }

    public FaultInjector faults() {
        return faults;
    }

    public EffectsLedger ledger() {
        return ledger;
    }

    /** Number of HTTP requests received (including faulted ones). */
    public long requestsReceived() {
        return received.get();
    }

    public String name() {
        return name;
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    private void dispatch(HttpExchange ex, String method, String path, boolean prefix, Handler handler)
            throws IOException {
        try (ex) {
            received.incrementAndGet();
            String actual = ex.getRequestURI().getPath();
            boolean pathOk = prefix ? actual.startsWith(path) && actual.length() > path.length() : actual.equals(path);
            if (!pathOk) {
                send(ex, Response.text(404, "not found"));
                return;
            }
            if (!ex.getRequestMethod().equalsIgnoreCase(method)) {
                send(ex, Response.text(405, "method not allowed"));
                return;
            }
            String routeName = prefix ? actual : path;
            Request req = new Request(ex.getRequestMethod(), routeName, ex.getRequestURI().getRawQuery(),
                    ex.getRequestHeaders(), ex.getRequestBody().readAllBytes());
            FaultInjector.Active active = faults.next(routeName);
            active.sleepDelay();
            Fault error = active.error();
            if (error != null) {
                send(ex, Response.json(error.status(),
                        "{\"status\":\"ERROR\",\"errorCode\":\"SIM_INJECTED_ERROR\",\"message\":\"injected fault\"}"));
                return;
            }
            Response resp = invoke(handler, req);
            if (active.duplicate()) {
                invoke(handler, req); // duplicate delivery: the second answer is discarded
            }
            if (active.dropResponse()) {
                // Effect done, response lost: close the connection without a status line.
                return;
            }
            send(ex, resp);
        }
    }

    private Response invoke(Handler handler, Request req) {
        try {
            return handler.handle(req);
        } catch (Exception e) {
            String msg = String.valueOf(e.getMessage()).replace("\"", "'");
            return Response.json(500, "{\"status\":\"ERROR\",\"errorCode\":\"SIM_INTERNAL\",\"message\":\"" + msg + "\"}");
        }
    }

    private static void send(HttpExchange ex, Response r) throws IOException {
        ex.getResponseHeaders().set("Content-Type", r.contentType());
        byte[] body = r.body();
        ex.sendResponseHeaders(r.status(), body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        }
    }
}
