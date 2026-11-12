// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import org.sangam.sdk.SangamClient;
import org.sangam.sdk.http.RetryPolicy;
import org.sangam.sdk.log.SangamLogger;

/**
 * In-process fake of the Sangam worker API on 127.0.0.1 (JDK HttpServer, no dependency): records every request,
 * answers fetch-and-lock from a queue and the result calls from scripted statuses (default 204).
 */
final class FakeWorkServer implements AutoCloseable {

    static final Set<String> REPORTS = Set.of("complete", "fail", "bpmn-error", "outcome-unknown", "unlock", "cancel-ack");
    private static final ObjectMapper JSON = new ObjectMapper();

    record Call(String method, String path, String action, String idempotencyKey, Map<String, Object> body) { }

    record Log(String event, Map<String, ?> fields) { }

    final List<Call> calls = new CopyOnWriteArrayList<>();
    final List<Log> logs = new CopyOnWriteArrayList<>();
    private final Deque<String> batches = new ArrayDeque<>();
    private final Map<String, Deque<int[]>> statuses = new HashMap<>();
    private final Map<String, Deque<String>> bodies = new HashMap<>();
    private final HttpServer server;
    private final ExecutorService pool = Executors.newCachedThreadPool();

    FakeWorkServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(pool);
        server.start();
    }

    final SangamLogger logger = (level, event, message, fields, error) -> logs.add(new Log(event, fields));

    synchronized FakeWorkServer enqueue(String... items) {
        batches.add("[" + String.join(",", items) + "]");
        return this;
    }

    /** Scripted answer for {@code POST /work/{id}/{action}}, used in order; afterwards 204. */
    synchronized FakeWorkServer reply(String action, int status, String body) {
        statuses.computeIfAbsent(action, k -> new ArrayDeque<>()).add(new int[] {status});
        bodies.computeIfAbsent(action, k -> new ArrayDeque<>()).add(body == null ? "" : body);
        return this;
    }

    List<Call> callsOf(String action) {
        return calls.stream().filter(c -> c.action().equals(action)).toList();
    }

    boolean logged(String event) {
        return logs.stream().anyMatch(l -> l.event().equals(event));
    }

    Call waitFor(Predicate<Call> p) throws InterruptedException {
        long end = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < end) {
            for (Call c : new ArrayList<>(calls)) if (p.test(c)) return c;
            Thread.sleep(5);
        }
        throw new AssertionError("no matching call; calls=" + calls);
    }

    static void until(BooleanSupplier cond) throws InterruptedException {
        long end = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!cond.getAsBoolean()) {
            if (System.nanoTime() > end) throw new AssertionError("condition not met in time");
            Thread.sleep(5);
        }
    }

    SangamClient client() {
        return SangamClient.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .retryPolicy(RetryPolicy.none()) // the worker's own report retry is under test
                .logger(logger)
                .build();
    }

    SangamWorker.Builder worker() {
        Backoff fast = new Backoff(Duration.ofMillis(1), Duration.ofMillis(2), 1.0, 0.0);
        return SangamWorker.builder(client()).workerId("worker-1").maxConcurrency(1).maxTasksPerFetch(1)
                .asyncResponseTimeout(Duration.ofMillis(50)).fetchBackoff(fast).reportBackoff(fast)
                .shutdownTimeout(Duration.ofSeconds(2)).logger(logger);
    }

    /** A leased item as the server sends it after LOCKED (A-1); the lease runs one minute. */
    static String work(String extra) {
        String base = "{\"id\":\"wk-1\",\"topic\":\"pay\",\"lockVersion\":3,\"attemptId\":\"att-1\",\"operationId\":\"op-1\","
                + "\"lockExpiresAt\":\"" + java.time.Instant.now().plusSeconds(60) + "\",\"retries\":3,\"variables\":{}";
        return base + (extra == null || extra.isEmpty() ? "" : "," + extra) + "}";
    }

    @SuppressWarnings("unchecked")
    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath().replace("/api/v1", "");
        String action = path.substring(path.lastIndexOf('/') + 1);
        byte[] in = ex.getRequestBody().readAllBytes();
        Map<String, Object> body = in.length == 0 ? null : JSON.readValue(in, Map.class);
        calls.add(new Call(ex.getRequestMethod(), path, action, ex.getRequestHeaders().getFirst("Idempotency-Key"), body));
        int status = 204;
        String out = "";
        if (action.equals("fetch-and-lock")) {
            String batch;
            synchronized (this) {
                batch = batches.poll();
            }
            if (batch == null) {
                try {
                    Thread.sleep(25);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                batch = "[]";
            }
            status = 200;
            out = batch;
        } else {
            synchronized (this) {
                Deque<int[]> s = statuses.get(action);
                if (s != null && !s.isEmpty()) {
                    status = s.poll()[0];
                    out = bodies.get(action).poll();
                }
            }
        }
        byte[] bytes = out.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", status >= 400 ? "application/problem+json" : "application/json");
        ex.sendResponseHeaders(status, status == 204 || bytes.length == 0 ? -1 : bytes.length);
        if (status != 204 && bytes.length > 0) {
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        }
        ex.close();
    }

    @Override
    public void close() {
        server.stop(0);
        pool.shutdownNow();
    }
}
