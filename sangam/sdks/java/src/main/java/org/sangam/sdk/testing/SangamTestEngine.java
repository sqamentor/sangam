// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.testing;

import static org.sangam.sdk.testing.EngineModel.defaultTitle;
import static org.sangam.sdk.testing.EngineModel.endLease;
import static org.sangam.sdk.testing.EngineModel.lockDuration;
import static org.sangam.sdk.testing.EngineModel.notFound;
import static org.sangam.sdk.testing.EngineModel.text;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import org.sangam.sdk.testing.EngineModel.Answer;
import org.sangam.sdk.testing.EngineModel.Problem;
import org.sangam.sdk.testing.EngineModel.Recon;
import org.sangam.sdk.testing.EngineModel.Stored;
import org.sangam.sdk.testing.EngineModel.Work;
import org.sangam.sdk.SangamClient;
import org.sangam.sdk.http.HttpTransport;
import org.sangam.sdk.http.RetryPolicy;

/**
 * R18 test engine of the Java SDK (M-021): an in-process fake of the Sangam worker API ({@code /api/v1/work/**}) and of
 * {@code POST /api/v1/process-instances}, served on 127.0.0.1 by the JDK HTTP server (no dependency). Workers and
 * clients under test talk to it through the real {@link SangamClient} and {@link org.sangam.sdk.worker.SangamWorker},
 * so transport, fencing, Idempotency-Key and error mapping are exercised end to end without a Sangam server.
 *
 * <p>The state machine follows the server (VI.3, VI.5; {@code OperatonWorkAdapter}, {@code LeaseTable},
 * {@code Tombstones}, {@code IdempotencyStore}, {@code ApiProblem}):
 * <ul>
 *   <li>a grant sets LOCKED, {@code lockVersion} + 1 (1 for new work) and a new {@code attemptId}; the lease is
 *       {@code lockDurationMs} (default 5 min, capped at 24 h);</li>
 *   <li>every worker call is fenced by (workerId, lockVersion, attemptId), checked in the server's order NO_LEASE,
 *       STALE_VERSION, NOT_OWNER, EXPIRED, ATTEMPT_MISMATCH: {@code 409 STALE_LEASE} and nothing changes;</li>
 *   <li>before every request, expired leases end ({@code lockVersion} + 1): the work becomes AVAILABLE again, or
 *       UNKNOWN_OUTCOME with a reconciliation item for an irreversible topic or a deleted instance that was not
 *       acknowledged (VI.3 rule 5, VI.5 rule 4);</li>
 *   <li>fail with retries &gt; 0 makes the work AVAILABLE after {@code retryTimeoutMs}; retries = 0 is an INCIDENT;
 *       fail on an irreversible topic with retries left goes to UNKNOWN_OUTCOME instead (VI.3 rule 5);</li>
 *   <li>a repeated identical completion for the same lockVersion is answered 204 (implicit key, X.2.2.1);</li>
 *   <li>results for a deleted instance get {@code 410 GONE_INSTANCE_DELETED}; a heartbeat then reports
 *       {@code cancelRequested}; {@code cancel-ack} moves the tombstone to CANCELLED;</li>
 *   <li>non-fenced mutations ({@code POST /process-instances}) use the R41 store: the same Idempotency-Key with the
 *       same body replays the first answer with {@code Idempotent-Replayed: true}, with another body it is
 *       {@code 422 IDEMPOTENCY_KEY_REUSED}.</li>
 * </ul>
 * Differences from the server, by design: one tenant and no authentication; time is the real clock plus the offset
 * set by {@link #advance(Duration)}; fetch-and-lock waits for work at most {@code min(asyncResponseTimeoutMs,
 * longPollCap)} (default cap 1 s) so shutdown stays fast; every other path answers {@code 501 CAPABILITY_NOT_INSTALLED}.
 *
 * <pre>{@code
 * try (SangamTestEngine engine = SangamTestEngine.start()) {
 *     String workId = engine.addWork("pay", Map.of("amount", TypedValue.decimal("12.50")));
 *     SangamWorker worker = SangamWorker.builder(engine.client()).clock(engine.clock())
 *         .subscribe("pay", ctx -> Map.of("paid", true)).build().start();
 *     engine.awaitState(workId, "COMPLETED", Duration.ofSeconds(5));
 * }
 * }</pre>
 */
public final class SangamTestEngine implements AutoCloseable {

    /** Default lease when the subscription sends none (VI.3 rule 3). */
    public static final Duration DEFAULT_LOCK = Duration.ofMinutes(5);
    /** Maximum lease (VI.3 rule 3). */
    public static final Duration MAX_LOCK = Duration.ofHours(24);

    private static final String TYPE_BASE = "https://sangam.dev/problems/";

    /** One request as the engine received it. */
    public record Call(String method, String path, String action, String idempotencyKey, JsonNode body) { }

    /** A reconciliation item opened by the engine (VI.3 rule 4, VI.5). */
    public record ReconciliationItem(String id, String kind, String workId, String operationId, String state) { }

    /** A process instance started through {@code POST /process-instances}. */
    public record Instance(String id, String key, String businessKey, JsonNode variables, Instant startTime) { }

    private final ObjectMapper json = HttpTransport.defaultMapper();
    private final HttpServer server;
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "sangam-test-engine");
        t.setDaemon(true);
        return t;
    });
    private final Object lock = new Object();
    private final Map<String, Work> works = new LinkedHashMap<>();
    private final Map<String, Recon> recon = new LinkedHashMap<>();
    private final Map<String, Instance> instances = new LinkedHashMap<>();
    private final Map<String, Stored> idempotency = new LinkedHashMap<>();
    private final Set<String> irreversible = new HashSet<>();
    private final Map<String, Integer> lose = new LinkedHashMap<>();
    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private final AtomicLong seq = new AtomicLong();
    private volatile Duration offset = Duration.ZERO;
    private volatile Duration longPollCap = Duration.ofSeconds(1);
    private final Clock clock = new Clock() {
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.now().plus(offset); }
    };
    private final EngineWorkCalls workCalls = new EngineWorkCalls(works, irreversible, clock, json, this::openRecon);
    private final EngineSteps journal = new EngineSteps(works, workCalls, clock, json);

    private SangamTestEngine() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(pool);
        server.start();
    }

    /** Starts an engine on a free loopback port. */
    public static SangamTestEngine start() {
        try {
            return new SangamTestEngine();
        } catch (IOException e) {
            throw new IllegalStateException("cannot start the Sangam test engine", e);
        }
    }

    /** Base URL of the engine, e.g. {@code http://127.0.0.1:53021}. */
    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** A client on this engine without transport retries (the worker's own report retries stay visible). */
    public SangamClient client() {
        return clientBuilder().retryPolicy(RetryPolicy.none()).build();
    }

    /** A client builder already pointing at this engine. */
    public SangamClient.Builder clientBuilder() {
        return SangamClient.builder().baseUrl(baseUrl());
    }

    /** The engine's clock (real time plus {@link #advance(Duration)}); pass it to the worker so both agree on expiry. */
    public Clock clock() {
        return clock;
    }

    /** Moves the engine clock forward; leases that end by then expire on the next request (or {@link #sweep()}). */
    public SangamTestEngine advance(Duration d) {
        offset = offset.plus(d);
        sweep();
        return this;
    }

    /** Upper bound of a long-poll wait inside the engine (default 1 s). */
    public SangamTestEngine longPollCap(Duration d) {
        longPollCap = d;
        return this;
    }

    /** Marks a topic {@code effect=irreversible} (server property irreversibleTopics). */
    public SangamTestEngine irreversibleTopic(String topic) {
        synchronized (lock) {
            irreversible.add(topic);
        }
        return this;
    }

    /** Adds AVAILABLE work; variables are a VariableMap (plain JSON or R33 TypedValues). Returns the work id. */
    public String addWork(String topic, Map<String, ?> variables) {
        return addWork(topic, variables, null, 0L);
    }

    /** Adds AVAILABLE work with initial retries (null = engine default) and priority. Returns the work id. */
    public String addWork(String topic, Map<String, ?> variables, Integer retries, long priority) {
        Work w = new Work();
        w.id = "work-" + seq.incrementAndGet();
        w.topic = topic;
        w.operationId = UUID.randomUUID().toString();
        w.processInstanceId = "pi-" + seq.incrementAndGet();
        w.activityInstanceId = "act-" + seq.incrementAndGet();
        w.variables = variables == null ? json.createObjectNode() : json.valueToTree(variables);
        w.retries = retries;
        w.priority = priority;
        w.state = "AVAILABLE";
        w.availableAt = Instant.MIN;
        synchronized (lock) {
            works.put(w.id, w);
            lock.notifyAll();
        }
        return w.id;
    }

    /**
     * Deletes the instance of a work item (VI.5): a leased item gets a CANCEL_REQUESTED tombstone, so the next
     * heartbeat answers {@code cancelRequested: true}, results get 410 and {@code cancel-ack} closes it. This is the
     * server's only source of {@code cancelRequested}.
     */
    public SangamTestEngine deleteInstance(String workId) {
        synchronized (lock) {
            Work w = require(workId);
            if ("LOCKED".equals(w.state) && w.leaseVersion != null) {
                w.tombstone = "CANCEL_REQUESTED";
                w.tombstoneVersion = w.leaseVersion;
            }
            w.state = "DELETED";
            lock.notifyAll();
        }
        return this;
    }

    /** Same as {@link #deleteInstance(String)}: a cooperative cancel request for leased work. */
    public SangamTestEngine requestCancel(String workId) {
        return deleteInstance(workId);
    }

    /**
     * Resolves a reconciliation item (VI.3.1: UNKNOWN_OUTCOME --resolve--> COMPLETED | AVAILABLE(same op_id) | FAILED).
     *
     * @param action CONFIRM_SUCCESS, RETRY or MARK_FAILED
     */
    public SangamTestEngine resolveReconciliation(String itemId, String action) {
        synchronized (lock) {
            Recon r = recon.get(itemId);
            if (r == null || !"OPEN".equals(r.state)) throw new IllegalArgumentException("no open reconciliation item " + itemId);
            Work w = works.get(r.workId);
            switch (action) {
                case "CONFIRM_SUCCESS" -> {
                    if (w != null && "UNKNOWN_OUTCOME".equals(w.state)) {
                        w.state = "COMPLETED";
                        w.opState = "SUCCEEDED";
                    }
                }
                case "RETRY" -> {
                    if (w != null && "UNKNOWN_OUTCOME".equals(w.state)) {
                        w.state = "AVAILABLE";
                        w.availableAt = Instant.MIN;
                        w.opState = "INTENT";
                    }
                }
                case "MARK_FAILED" -> {
                    if (w != null && "UNKNOWN_OUTCOME".equals(w.state)) {
                        w.state = "FAILED";
                        w.opState = "FAILED";
                    }
                }
                default -> throw new IllegalArgumentException("action must be CONFIRM_SUCCESS, RETRY or MARK_FAILED");
            }
            r.state = "RESOLVED";
            lock.notifyAll();
        }
        return this;
    }

    /**
     * The next request whose last path segment is {@code action} (for example {@code complete},
     * {@code fetch-and-lock}, {@code process-instances}) is applied, and then its response is lost: the connection
     * ends without an answer, so the client sees a transport error (T02, T04).
     */
    public SangamTestEngine loseNextResponse(String action) {
        synchronized (lock) {
            lose.merge(action, 1, Integer::sum);
        }
        return this;
    }

    /** Ends expired leases now (the engine also does this before every request). */
    public void sweep() {
        synchronized (lock) {
            sweepLocked(clock.instant());
            lock.notifyAll();
        }
    }

    // ------------------------------------------------------------------ inspection

    /**
     * State of a work item: AVAILABLE, LOCKED, COMPLETED, COMPLETED_WITH_ERROR, INCIDENT, UNKNOWN_OUTCOME, CANCELLED,
     * FAILED or DELETED.
     */
    public String state(String workId) {
        synchronized (lock) {
            return require(workId).state;
        }
    }

    /** Current lease version of the item (null before the first grant). */
    public Long lockVersion(String workId) {
        synchronized (lock) {
            return require(workId).leaseVersion;
        }
    }

    /** Worker that holds (or last held) the lease. */
    public String leaseOwner(String workId) {
        synchronized (lock) {
            return require(workId).workerId;
        }
    }

    /** Number of grants of the item (fetch-and-lock answers that carried it). */
    public int grants(String workId) {
        synchronized (lock) {
            return require(workId).grants;
        }
    }

    /** Number of accepted (first) completions; replayed completions are not counted. */
    public int completions(String workId) {
        synchronized (lock) {
            return require(workId).completions;
        }
    }

    /** Operation-ledger state of the item's operationId: INTENT, SUCCEEDED, FAILED or UNKNOWN_OUTCOME. */
    public String operationState(String workId) {
        synchronized (lock) {
            return require(workId).opState;
        }
    }

    public String operationId(String workId) {
        synchronized (lock) {
            return require(workId).operationId;
        }
    }

    /** Variables of the accepted completion exactly as received (JSON), or null. */
    public JsonNode completedVariables(String workId) {
        synchronized (lock) {
            JsonNode n = require(workId).completedVariables;
            return n == null ? null : n.deepCopy();
        }
    }

    /** Variables of the accepted completion as a map (TypedValues stay maps; use {@code TypedValue.unwrap}). */
    @SuppressWarnings("unchecked")
    public Map<String, Object> completedVariableMap(String workId) {
        JsonNode n = completedVariables(workId);
        return n == null ? null : json.convertValue(n, Map.class);
    }

    public List<ReconciliationItem> reconciliationItems() {
        synchronized (lock) {
            List<ReconciliationItem> out = new ArrayList<>();
            recon.values().forEach(r -> out.add(new ReconciliationItem(r.id, r.kind, r.workId, r.operationId, r.state)));
            return out;
        }
    }

    public List<Instance> instances() {
        synchronized (lock) {
            return List.copyOf(instances.values());
        }
    }

    public List<Call> calls() {
        return List.copyOf(calls);
    }

    public List<Call> callsOf(String action) {
        return calls.stream().filter(c -> c.action().equals(action)).toList();
    }

    /** Waits (real time) until the item reaches {@code state}; throws {@link AssertionError} on timeout. */
    public void awaitState(String workId, String state, Duration timeout) {
        long end = System.nanoTime() + timeout.toNanos();
        synchronized (lock) {
            while (!state.equals(require(workId).state)) {
                long left = end - System.nanoTime();
                if (left <= 0) {
                    throw new AssertionError("work " + workId + " is " + require(workId).state + ", expected " + state);
                }
                try {
                    lock.wait(Math.max(1, left / 1_000_000));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("interrupted", e);
                }
            }
        }
    }

    @Override
    public void close() {
        server.stop(0);
        pool.shutdownNow();
        synchronized (lock) {
            lock.notifyAll();
        }
    }

    // ------------------------------------------------------------------ HTTP

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String apiPath = path.startsWith("/api/v1") ? path.substring("/api/v1".length()) : path;
        String action = apiPath.substring(apiPath.lastIndexOf('/') + 1);
        byte[] in = ex.getRequestBody().readAllBytes();
        JsonNode body;
        try {
            body = in.length == 0 ? null : json.readTree(in);
        } catch (IOException e) {
            body = null;
        }
        String key = ex.getRequestHeaders().getFirst("Idempotency-Key");
        String method = ex.getRequestMethod();
        calls.add(new Call(method, apiPath, action, key, body));
        Answer a;
        try {
            a = apiPath.matches("/work/[^/]+/steps(/[^/]+)?")
                    ? steps(method, apiPath, ex.getRequestURI().getRawQuery(), body)
                    : dispatch(method, apiPath, action, key, body, path);
        } catch (Problem p) {
            a = problem(p, path);
        } catch (RuntimeException e) {
            a = problem(new Problem(500, "INTERNAL_ERROR", null, String.valueOf(e)), path);
        }
        boolean drop;
        synchronized (lock) {
            Integer n = lose.get(action);
            drop = n != null && n > 0;
            if (drop) {
                if (n == 1) lose.remove(action); else lose.put(action, n - 1);
            }
        }
        if (drop) {
            // The effect is applied; the answer is lost: the exchange ends without any response, which closes the
            // connection, so the client fails at once with an I/O error. (Promising a body and not sending it does
            // not do that on every JDK: on JDK 25 the connection stays open and the client only ends at its own
            // request timeout.)
            ex.close();
            return;
        }
        a.headers().forEach((k, v) -> ex.getResponseHeaders().set(k, v));
        if (a.body() == null || a.body().length == 0) {
            ex.sendResponseHeaders(a.status(), -1);
        } else {
            ex.getResponseHeaders().set("Content-Type", a.contentType());
            ex.sendResponseHeaders(a.status(), a.body().length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(a.body());
            }
        }
        ex.close();
    }

    private Answer dispatch(String method, String apiPath, String action, String key, JsonNode body, String uri) {
        if (method.equals("POST") && apiPath.equals("/work/fetch-and-lock")) {
            return ok(200, fetchAndLock(body));
        }
        if (method.equals("POST") && apiPath.startsWith("/work/") && apiPath.chars().filter(c -> c == '/').count() == 3) {
            String workId = apiPath.substring("/work/".length(), apiPath.lastIndexOf('/'));
            return work(workId, action, body == null ? json.createObjectNode() : body);
        }
        if (method.equals("POST") && apiPath.equals("/process-instances")) {
            return idempotent(method, apiPath, key, body, () -> startInstance(body));
        }
        if (method.equals("GET") && apiPath.startsWith("/process-instances/") && apiPath.indexOf('/', 19) < 0) {
            synchronized (lock) {
                Instance i = instances.get(apiPath.substring("/process-instances/".length()));
                if (i == null) throw notFound("process instance", apiPath.substring(19));
                return ok(200, instanceJson(i));
            }
        }
        throw new Problem(501, "CAPABILITY_NOT_INSTALLED", null, method + " " + apiPath
                + " is not served by the SDK test engine").with("capability", apiPath).with("module", "sangam-server");
    }

    // ------------------------------------------------------------------ R41 store (non-fenced mutations)

    private Answer idempotent(String method, String path, String key, JsonNode body, java.util.function.Supplier<Answer> op) {
        if (key == null) return op.get();
        String scope = method + " " + path + " " + key;
        JsonNode fp = body == null ? json.nullNode() : body;
        synchronized (lock) {
            Stored s = idempotency.get(scope);
            if (s != null) {
                if (!s.fingerprint().equals(fp)) {
                    throw new Problem(422, "IDEMPOTENCY_KEY_REUSED", null,
                            "Idempotency-Key " + key + " was used with a different request");
                }
                Map<String, String> h = new LinkedHashMap<>();
                h.put("Idempotent-Replayed", "true");
                return new Answer(s.status(), s.body(), "application/json", h);
            }
            Answer a = op.get();
            if (a.status() < 500) idempotency.put(scope, new Stored(fp, a.status(), a.body()));
            return a;
        }
    }

    private Answer startInstance(JsonNode body) {
        String key = text(body, "key");
        if (key == null || key.isBlank()) throw new Problem(400, "VALIDATION_FAILED", null, "key is required");
        Instance i = new Instance("pi-" + seq.incrementAndGet(), key, text(body, "businessKey"),
                body.has("variables") ? body.get("variables") : json.createObjectNode(), clock.instant());
        synchronized (lock) {
            instances.put(i.id(), i);
        }
        return ok(201, instanceJson(i));
    }

    private ObjectNode instanceJson(Instance i) {
        ObjectNode n = json.createObjectNode();
        n.put("id", i.id());
        n.put("processDefinitionKey", i.key());
        if (i.businessKey() != null) n.put("businessKey", i.businessKey());
        n.put("state", "ACTIVE");
        n.put("ended", false);
        n.put("startTime", i.startTime().toString());
        n.put("rootProcessInstanceId", i.id());
        return n;
    }

    // ------------------------------------------------------------------ fetch-and-lock

    private JsonNode fetchAndLock(JsonNode r) {
        String workerId = text(r, "workerId");
        if (workerId == null || workerId.isBlank()) throw new Problem(400, "VALIDATION_FAILED", null, "workerId is required");
        JsonNode topics = r == null ? null : r.get("topics");
        if (topics == null || !topics.isArray() || topics.isEmpty()) {
            throw new Problem(400, "VALIDATION_FAILED", null, "at least one topic is required");
        }
        Map<String, JsonNode> byName = new LinkedHashMap<>();
        for (JsonNode t : topics) {
            String name = text(t, "name");
            if (name == null || name.isBlank()) throw new Problem(400, "TOPIC_NAME_REQUIRED", null, "every topic needs a name");
            byName.put(name, t);
        }
        int max = r.hasNonNull("maxTasks") ? Math.max(1, Math.min(100, r.get("maxTasks").asInt())) : 1;
        boolean usePriority = r.path("usePriority").asBoolean(false);
        long waitMs = Math.min(Math.max(0, r.path("asyncResponseTimeoutMs").asLong(0)), longPollCap.toMillis());
        long end = System.nanoTime() + waitMs * 1_000_000;
        synchronized (lock) {
            while (true) {
                sweepLocked(clock.instant());
                List<JsonNode> out = grant(workerId, byName, max, usePriority);
                long left = end - System.nanoTime();
                if (!out.isEmpty() || left <= 0) {
                    lock.notifyAll();
                    return json.valueToTree(out);
                }
                try {
                    lock.wait(Math.max(1, left / 1_000_000));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return json.createArrayNode();
                }
            }
        }
    }

    private List<JsonNode> grant(String workerId, Map<String, JsonNode> topics, int max, boolean usePriority) {
        Instant now = clock.instant();
        List<Work> candidates = new ArrayList<>();
        for (Work w : works.values()) {
            JsonNode t = topics.get(w.topic);
            if (t == null || !"AVAILABLE".equals(w.state) || w.availableAt.isAfter(now)) continue;
            candidates.add(w);
        }
        if (usePriority) candidates.sort((a, b) -> Long.compare(b.priority, a.priority));
        List<JsonNode> out = new ArrayList<>();
        for (Work w : candidates) {
            if (out.size() >= max) break;
            JsonNode t = topics.get(w.topic);
            Duration d = lockDuration(t.hasNonNull("lockDurationMs") ? t.get("lockDurationMs").asLong() : null);
            w.leaseVersion = w.leaseVersion == null ? 1L : w.leaseVersion + 1;
            w.attemptId = UUID.randomUUID().toString();
            w.workerId = workerId;
            w.expiresAt = now.plus(d);
            w.state = "LOCKED";
            w.attempts++;
            w.grants++;
            out.add(externalWork(w, t));
        }
        return out;
    }

    private ObjectNode externalWork(Work w, JsonNode topic) {
        ObjectNode n = json.createObjectNode();
        n.put("id", w.id);
        n.put("topic", w.topic);
        n.put("processInstanceId", w.processInstanceId);
        n.put("activityInstanceId", w.activityInstanceId);
        JsonNode vars = w.variables;
        JsonNode names = topic.get("variables");
        if (names != null && names.isArray() && vars.isObject()) {
            ObjectNode filtered = json.createObjectNode();
            for (JsonNode name : names) {
                if (vars.has(name.asText())) filtered.set(name.asText(), vars.get(name.asText()));
            }
            vars = filtered;
        }
        n.set("variables", vars.deepCopy());
        n.put("lockVersion", w.leaseVersion);
        n.put("attemptId", w.attemptId);
        n.put("operationId", w.operationId);
        n.put("lockExpiresAt", w.expiresAt.toString());
        if (w.retries != null) n.put("retries", w.retries);
        n.put("priority", w.priority);
        if (w.errorMessage != null) n.put("errorMessage", w.errorMessage);
        ObjectNode op = n.putObject("operation");
        op.put("state", w.opState);
        op.put("attempts", w.attempts);
        op.put("capabilityClass", "C_NEITHER");
        op.put("mayDispatch", "INTENT".equals(w.opState));
        if (w.storedOutcome != null) op.set("storedOutcome", w.storedOutcome.deepCopy());
        return n;
    }


    // ------------------------------------------------------------------ expiry sweep

    private void sweepLocked(Instant now) {
        for (Work w : works.values()) {
            if (w.leaseVersion == null || w.workerId == null || w.expiresAt == null || w.expiresAt.isAfter(now)) continue;
            if ("CANCEL_REQUESTED".equals(w.tombstone)) {
                // VI.5 rule 4: a deleted instance whose lease expired without a cancel ack
                w.tombstone = "UNKNOWN_OUTCOME";
                openRecon("UNKNOWN_OUTCOME", w);
                endLease(w);
            } else if ("LOCKED".equals(w.state)) {
                if (irreversible.contains(w.topic)) {
                    // VI.3 rule 5: no blind re-offer of an irreversible effect
                    w.state = "UNKNOWN_OUTCOME";
                    w.opState = "UNKNOWN_OUTCOME";
                    openRecon("UNKNOWN_OUTCOME", w);
                } else {
                    w.state = "AVAILABLE";
                    w.availableAt = now;
                }
                endLease(w);
            }
        }
    }


    private String openRecon(String kind, Work w) {
        Recon r = new Recon();
        r.id = String.valueOf(seq.incrementAndGet());
        r.kind = kind;
        r.workId = w.id;
        r.operationId = w.operationId;
        recon.put(r.id, r);
        return r.id;
    }

    // ------------------------------------------------------------------ step journal (M-025)

    private Answer steps(String method, String apiPath, String rawQuery, JsonNode body) {
        String[] seg = apiPath.split("/");   // "", "work", id, "steps"[, name]
        String workId = java.net.URLDecoder.decode(seg[2], java.nio.charset.StandardCharsets.UTF_8);
        String name = seg.length > 4 ? java.net.URLDecoder.decode(seg[4], java.nio.charset.StandardCharsets.UTF_8) : null;
        Map<String, String> q = new LinkedHashMap<>();
        if (rawQuery != null) {
            for (String kv : rawQuery.split("&")) {
                int i = kv.indexOf('=');
                if (i > 0) {
                    q.put(kv.substring(0, i), java.net.URLDecoder.decode(kv.substring(i + 1), java.nio.charset.StandardCharsets.UTF_8));
                }
            }
        }
        synchronized (lock) {
            sweepLocked(clock.instant());
            if (name == null && method.equals("GET")) return ok(200, journal.list(workId));
            if (name != null && method.equals("GET")) {
                long lv;
                try {
                    lv = Long.parseLong(q.getOrDefault("lockVersion", ""));
                } catch (NumberFormatException e) {
                    throw new Problem(400, "VALIDATION_FAILED", null, "lockVersion is required");
                }
                return ok(200, journal.get(workId, name, q.get("workerId"), lv, q.get("attemptId")));
            }
            if (name != null && method.equals("PUT")) {
                return ok(200, journal.put(workId, name, body == null ? json.createObjectNode() : body));
            }
            throw new Problem(405, "METHOD_NOT_ALLOWED", null, method + " " + apiPath + " is not served by the SDK test engine");
        }
    }

    // ------------------------------------------------------------------ worker calls

    private Answer work(String workId, String action, JsonNode r) {
        synchronized (lock) {
            sweepLocked(clock.instant());
            try {
                return switch (action) {
                    case "extend-lock" -> ok(200, workCalls.extendLock(workId, r));
                    case "complete" -> {
                        workCalls.complete(workId, r);
                        yield new Answer(204, null, null, Map.of());
                    }
                    case "fail" -> {
                        workCalls.fail(workId, r);
                        yield new Answer(204, null, null, Map.of());
                    }
                    case "bpmn-error" -> {
                        workCalls.bpmnError(workId, r);
                        yield new Answer(204, null, null, Map.of());
                    }
                    case "unlock" -> {
                        workCalls.unlock(workId, r);
                        yield new Answer(204, null, null, Map.of());
                    }
                    case "outcome-unknown" -> ok(202, workCalls.outcomeUnknown(workId, r, text(r, "detail")));
                    case "cancel-ack" -> {
                        workCalls.cancelAck(workId, r);
                        yield new Answer(204, null, null, Map.of());
                    }
                    default -> throw new Problem(501, "CAPABILITY_NOT_INSTALLED", null,
                            "POST /work/{id}/" + action + " is not served by the SDK test engine");
                };
            } finally {
                lock.notifyAll();
            }
        }
    }


    private Work require(String workId) {
        Work w = works.get(workId);
        if (w == null) throw new IllegalArgumentException("unknown work " + workId);
        return w;
    }

    private Answer ok(int status, JsonNode body) {
        try {
            return new Answer(status, json.writeValueAsBytes(body), "application/json", Map.of());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private Answer problem(Problem p, String instance) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", TYPE_BASE + p.code.toLowerCase(Locale.ROOT).replace('_', '-'));
        m.put("title", p.title != null ? p.title : defaultTitle(p.status));
        m.put("status", p.status);
        m.put("detail", p.getMessage());
        m.put("instance", instance);
        m.put("code", p.code);
        p.ext.forEach(m::putIfAbsent);
        try {
            return new Answer(p.status, json.writeValueAsBytes(m), "application/problem+json", Map.of());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

}
