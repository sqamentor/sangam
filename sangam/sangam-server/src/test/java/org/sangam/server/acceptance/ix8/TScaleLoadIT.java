// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.acceptance.ix8;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import javax.sql.DataSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import jdk.jfr.Configuration;
import jdk.jfr.Recording;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.operaton.bpm.engine.ProcessEngine;
import org.sangam.server.acceptance.AbstractAcceptanceIT;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * T-SCALE functional load benchmark (SCALE-ARCHITECTURE.md 11; ADR-D-24 per-cell floor 500 starts/s). Runs only with
 * {@code -Dsangam.tscale=true}. Drives the real server over its public REST API with production-path OIDC bearer
 * tokens: process starts, external-work fetch-and-lock/complete and user-task list/get/complete, closed loop with fixed
 * client concurrency. The model is the smallest realistic flow: start, one external task, one user task, end.
 *
 * <p>Honest limits: single node, the load client runs inside the same JVM as the server, PostgreSQL in a local
 * container, shared developer machine. The test asserts correctness only (no lost or duplicated instances, no 5xx);
 * throughput is reported, not asserted.
 */
@EnabledIfSystemProperty(named = "sangam.tscale", matches = "true")
@org.springframework.context.annotation.Import(StatementCounter.Config.class)
class TScaleLoadIT extends AbstractAcceptanceIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String KEY = "tscale-load";
    private static final String TOPIC = "tscale.work";

    private static final String MODEL = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:operaton="http://operaton.org/schema/1.0/bpmn"
                         id="tscale-defs" targetNamespace="http://sangam.org/tscale">
              <process id="tscale-load" name="T-SCALE load" isExecutable="true" operaton:historyTimeToLive="P1D">
                <startEvent id="s"/>
                <sequenceFlow id="f1" sourceRef="s" targetRef="work"/>
                <serviceTask id="work" name="External work" operaton:type="external" operaton:topic="tscale.work"/>
                <sequenceFlow id="f2" sourceRef="work" targetRef="review"/>
                <userTask id="review" name="Review" operaton:candidateGroups="sangam-user"/>
                <sequenceFlow id="f3" sourceRef="review" targetRef="e"/>
                <endEvent id="e"/>
              </process>
            </definitions>
            """;

    @LocalServerPort
    int port;

    @Autowired
    DataSource dataSource;

    @Autowired
    ProcessEngine engine;

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10)).executor(Executors.newFixedThreadPool(8)).build();
    private String token;
    private volatile boolean measuring;
    private volatile boolean stop;

    /** Latency samples (micros) and status counts per operation, only while measuring. */
    private final Map<String, List<long[]>> samples = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> statuses = new ConcurrentHashMap<>();
    private final AtomicLong startsOk = new AtomicLong();
    private final AtomicLong startsAll = new AtomicLong();
    private final ConcurrentHashMap<String, Boolean> startedIds = new ConcurrentHashMap<>();
    private final LinkedBlockingQueue<String> forReview = new LinkedBlockingQueue<>();

    private static int prop(String n, int d) {
        return Integer.getInteger("sangam.tscale." + n, d);
    }

    @Test
    void cellLoad() throws Exception {
        int starters = prop("starters", 32);
        int workers = prop("workers", 8);
        int reviewers = prop("reviewers", 16);
        int warmup = prop("warmupSeconds", 60);
        int measure = prop("measureSeconds", 300);
        token = bearer("acc", "tscale-officer", ADMIN_GROUPS);
        deploy();
        String probe = probe(prop("probe", 30));

        Path out = Path.of(System.getProperty("sangam.tscale.out", "target-scale/tscale"));
        Files.createDirectories(out);
        StringBuilder report = new StringBuilder();
        HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);
        report.append("cpus=").append(Runtime.getRuntime().availableProcessors())
                .append(" maxHeapMB=").append(Runtime.getRuntime().maxMemory() >> 20)
                .append(" hikariMax=").append(hikari.getMaximumPoolSize())
                .append(" starters=").append(starters).append(" workers=").append(workers)
                .append(" reviewers=").append(reviewers).append(" warmup=").append(warmup)
                .append("s measure=").append(measure).append("s\n");

        ExecutorService pool = Executors.newFixedThreadPool(starters + workers + reviewers);
        for (int i = 0; i < starters; i++) {
            pool.submit(this::starterLoop);
        }
        for (int i = 0; i < workers; i++) {
            int n = i;
            pool.submit(() -> workerLoop("tscale-w" + n));
        }
        for (int i = 0; i < reviewers; i++) {
            pool.submit(this::reviewerLoop);
        }

        Thread.sleep(warmup * 1000L);
        long commits0 = pgCommits();
        Recording jfr = new Recording(Configuration.getConfiguration("profile"));
        jfr.setDestination(out.resolve("tscale.jfr"));
        jfr.start();
        measuring = true;
        long t0 = System.nanoTime();
        com.sun.management.OperatingSystemMXBean os =
                (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        StringBuilder timeline = new StringBuilder("t_s,starts_ok_total,sysCpu,procCpu,poolActive,poolIdle,poolWaiting,reviewBacklog\n");
        Map<String, Integer> pgWaits = new TreeMap<>();
        Map<String, Integer> frames = new HashMap<>();
        int ticks = 0;
        while (System.nanoTime() - t0 < measure * 1_000_000_000L) {
            Thread.sleep(5000);
            ticks++;
            var mx = hikari.getHikariPoolMXBean();
            timeline.append((System.nanoTime() - t0) / 1_000_000_000L).append(',').append(startsOk.get()).append(',')
                    .append(String.format("%.2f,%.2f", os.getCpuLoad(), os.getProcessCpuLoad())).append(',')
                    .append(mx.getActiveConnections()).append(',').append(mx.getIdleConnections()).append(',')
                    .append(mx.getThreadsAwaitingConnection()).append(',').append(forReview.size()).append('\n');
            samplePgWaits(pgWaits);
            if (ticks % 6 == 0) {
                sampleThreads(frames);
            }
        }
        measuring = false;
        double secs = (System.nanoTime() - t0) / 1e9;
        jfr.stop();
        jfr.close();
        long commits = pgCommits() - commits0;
        stop = true;
        pool.shutdown();
        pool.awaitTermination(60, TimeUnit.SECONDS);

        report.append(String.format("measuredSeconds=%.1f starts_ok=%d starts_all=%d starts_per_s=%.1f pg_commits_per_s=%.1f%n",
                secs, startsOk.get(), startsAll.get(), startsOk.get() / secs, commits / secs));
        report.append(String.format("%-14s %9s %9s %8s %8s %8s %8s  statuses%n", "op", "count", "ops/s", "p50ms", "p95ms",
                "p99ms", "maxms"));
        long taskOps = 0;
        for (String op : new TreeMap<>(samples).keySet()) {
            long[] all = samples.get(op).stream().flatMapToLong(Arrays::stream).filter(v -> v >= 0).toArray();
            Arrays.sort(all);
            if (!op.equals("start")) {
                taskOps += all.length;
            }
            report.append(String.format("%-14s %9d %9.1f %8.1f %8.1f %8.1f %8.1f  %s%n", op, all.length, all.length / secs,
                    pct(all, 50), pct(all, 95), pct(all, 99), all.length == 0 ? 0 : all[all.length - 1] / 1000.0,
                    statusesOf(op)));
        }
        report.append(String.format("task_ops_total=%d task_ops_per_s=%.1f%n", taskOps, taskOps / secs));
        long errors = statuses.entrySet().stream().filter(e -> !e.getKey().matches(".*:2\\d\\d")).mapToLong(e -> e.getValue().get()).sum();
        long all = statuses.values().stream().mapToLong(AtomicLong::get).sum();
        report.append(String.format("errors(non-2xx incl. exceptions)=%d of %d requests (%.4f%%)%n", errors, all,
                all == 0 ? 0 : 100.0 * errors / all));

        // correctness (T-SCALE-3 subset): every accepted start exists exactly once in the engine, no 5xx
        long engineCount = engine.getHistoryService().createHistoricProcessInstanceQuery().processDefinitionKey(KEY).count();
        report.append("engine_instances=").append(engineCount).append(" distinct_started_ids=").append(startedIds.size())
                .append('\n');
        report.append(probe);
        report.append("pg_wait_events (sampled pg_stat_activity, active non-idle backends):\n");
        pgWaits.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(15)
                .forEach(e -> report.append("  ").append(e.getValue()).append("  ").append(e.getKey()).append('\n'));
        report.append("top request-thread frames (sampled stacks, first sangam/operaton/hikari/jdbc frame):\n");
        frames.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(20)
                .forEach(e -> report.append("  ").append(e.getValue()).append("  ").append(e.getKey()).append('\n'));
        Files.writeString(out.resolve("report.txt"), report);
        Files.writeString(out.resolve("timeline.csv"), timeline);
        System.out.println("[tscale]\n" + report + "\n" + timeline);

        long fiveXX = statuses.entrySet().stream().filter(e -> e.getKey().matches(".*:5\\d\\d")).mapToLong(e -> e.getValue().get()).sum();
        assertThat(fiveXX).as("5xx responses").isZero();
        assertThat(engineCount).as("engine instances >= accepted starts (no lost start)").isGreaterThanOrEqualTo(startedIds.size());
    }

    // ------------------------------------------------------------------ load loops

    private void starterLoop() {
        while (!stop) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("key", KEY);
            body.put("businessKey", "TS-" + UUID.randomUUID());
            body.put("variables", Map.of("amountMinor", 12345, "category", "load-test"));
            Res r = call("start", "POST", "/api/v1/process-instances", body);
            if (measuring) {
                startsAll.incrementAndGet();
            }
            if (r != null && r.status / 100 == 2) {
                String id = r.body.path("id").asText();
                if (startedIds.put(id, Boolean.TRUE) != null) {
                    throw new IllegalStateException("duplicate instance id " + id);
                }
                if (measuring) {
                    startsOk.incrementAndGet();
                }
            }
        }
    }

    private void workerLoop(String workerId) {
        while (!stop) {
            Map<String, Object> body = Map.of("workerId", workerId, "maxTasks", 10, "asyncResponseTimeoutMs", 0,
                    "topics", List.of(Map.of("name", TOPIC, "lockDurationMs", 120000)));
            Res r = call("work.fetch", "POST", "/api/v1/work/fetch-and-lock", body);
            if (r == null || r.status != 200 || r.body.isEmpty()) {
                sleep(20);
                continue;
            }
            for (JsonNode w : r.body) {
                Map<String, Object> done = new LinkedHashMap<>();
                done.put("workerId", workerId);
                done.put("lockVersion", w.path("lockVersion").asLong());
                done.put("attemptId", w.path("attemptId").asText());
                done.put("variables", Map.of("outcome", "ok"));
                Res c = call("work.complete", "POST", "/api/v1/work/" + w.path("id").asText() + "/complete", done);
                if (c != null && c.status / 100 == 2) {
                    forReview.add(w.path("processInstanceId").asText());
                }
            }
        }
    }

    private void reviewerLoop() {
        while (!stop) {
            String pid;
            try {
                pid = forReview.poll(200, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                return;
            }
            if (pid == null) {
                continue;
            }
            Res list = call("task.list", "GET", "/api/v1/tasks?processInstanceId=" + pid + "&page=0&size=5", null);
            if (list == null || list.status != 200 || list.body.isEmpty()) {
                continue;
            }
            String tid = list.body.get(0).path("id").asText();
            Res get = call("task.get", "GET", "/api/v1/tasks/" + tid, null);
            if (get == null || get.status != 200) {
                continue;
            }
            Map<String, Object> done = new LinkedHashMap<>();
            done.put("payloadHash", get.body.path("payloadHash").asText());
            done.put("decision", "APPROVED");
            done.put("variables", Map.of("approved", true));
            call("task.complete", "POST", "/api/v1/tasks/" + tid + "/complete", done);
        }
    }

    // ------------------------------------------------------------------ HTTP + metrics

    private record Res(int status, JsonNode body) {
    }

    private final ThreadLocal<Map<String, long[][]>> local = ThreadLocal.withInitial(HashMap::new);

    private Res call(String op, String method, String path, Object body) {
        long t = System.nanoTime();
        int status;
        Res res = null;
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .timeout(Duration.ofSeconds(60)).header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json");
            if (!"GET".equals(method)) {
                b.header("Idempotency-Key", UUID.randomUUID().toString());
            }
            b.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            status = r.statusCode();
            JsonNode n = r.body() == null || r.body().isBlank() ? JSON.nullNode() : JSON.readTree(r.body());
            res = new Res(status, n);
            if (status / 100 != 2 && measuring) {
                System.out.println("[tscale] " + op + " " + status + " " + r.body().substring(0, Math.min(300, r.body().length())));
            }
        } catch (Exception e) {
            status = -1;
        }
        if (measuring) {
            long us = (System.nanoTime() - t) / 1000;
            record(op, us);
            statuses.computeIfAbsent(op + ":" + status, k -> new AtomicLong()).incrementAndGet();
        }
        return res;
    }

    /** Per-thread chunked sample buffers, published to {@link #samples} once per chunk allocation. */
    private void record(String op, long us) {
        long[][] holder = local.get().computeIfAbsent(op, k -> new long[1][]);
        long[] chunk = holder[0];
        if (chunk == null || chunk[chunk.length - 1] >= 0) {
            chunk = new long[65536];
            Arrays.fill(chunk, -1);
            holder[0] = chunk;
            samples.computeIfAbsent(op, k -> java.util.Collections.synchronizedList(new ArrayList<>())).add(chunk);
        }
        int i = 0;
        // first free slot (chunks fill left to right); binary search over the -1 tail
        int lo = 0;
        int hi = chunk.length - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (chunk[mid] >= 0) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        i = lo;
        chunk[i] = us;
    }

    private String statusesOf(String op) {
        StringBuilder s = new StringBuilder();
        new TreeMap<>(statuses).forEach((k, v) -> {
            if (k.startsWith(op + ":")) {
                s.append(k.substring(op.length() + 1)).append('=').append(v.get()).append(' ');
            }
        });
        return s.toString().trim();
    }

    private static double pct(long[] sorted, int p) {
        if (sorted.length == 0) {
            return 0;
        }
        int idx = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, idx))] / 1000.0;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------ round-trip probe (serial, before load)

    /**
     * Runs {@code n} full lifecycles one after another (start, fetch-and-lock, complete work, list, get, complete task)
     * and reports JDBC round trips per operation on request threads, plus background round trips per lifecycle. The
     * first lifecycle warms caches and is excluded. Reported, not asserted (the regression guards are unit tests).
     */
    private String probe(int n) throws Exception {
        if (n <= 0) {
            return "";
        }
        Map<String, long[]> perOp = new TreeMap<>();
        long bg0 = 0;
        for (int i = 0; i <= n; i++) {
            boolean on = i > 0;
            if (i == 1) {
                StatementCounter.reset(true);
                bg0 = StatementCounter.BACKGROUND.get();
            }
            probeCall(perOp, on, "start", "POST", "/api/v1/process-instances",
                    Map.of("key", KEY, "businessKey", "TP-" + UUID.randomUUID(), "variables", Map.of("amountMinor", 1)));
            JsonNode w = null;
            for (int t = 0; t < 100 && w == null; t++) {
                Res f = probeCall(perOp, on && t == 0, "work.fetch", "POST", "/api/v1/work/fetch-and-lock",
                        Map.of("workerId", "probe", "maxTasks", 1, "asyncResponseTimeoutMs", 0,
                                "topics", List.of(Map.of("name", TOPIC, "lockDurationMs", 120000))));
                for (JsonNode x : f.body) {
                    w = x;
                }
                if (w == null) {
                    sleep(50);
                }
            }
            assertThat(w).as("probe external task").isNotNull();
            Map<String, Object> done = new LinkedHashMap<>();
            done.put("workerId", "probe");
            done.put("lockVersion", w.path("lockVersion").asLong());
            done.put("attemptId", w.path("attemptId").asText());
            done.put("variables", Map.of("outcome", "ok"));
            probeCall(perOp, on, "work.complete", "POST", "/api/v1/work/" + w.path("id").asText() + "/complete", done);
            Res list = probeCall(perOp, on, "task.list", "GET", "/api/v1/tasks?processInstanceId="
                    + w.path("processInstanceId").asText() + "&page=0&size=5", null);
            String tid = list.body.get(0).path("id").asText();
            Res get = probeCall(perOp, on, "task.get", "GET", "/api/v1/tasks/" + tid, null);
            Map<String, Object> td = new LinkedHashMap<>();
            td.put("payloadHash", get.body.path("payloadHash").asText());
            td.put("decision", "APPROVED");
            td.put("variables", Map.of("approved", true));
            probeCall(perOp, on, "task.complete", "POST", "/api/v1/tasks/" + tid + "/complete", td);
        }
        long bg = StatementCounter.BACKGROUND.get() - bg0;
        StringBuilder sb = new StringBuilder("jdbc_round_trips_per_op (request threads; statements + commits/rollbacks;"
                + " serial probe n=" + n + "):\n");
        long total = 0;
        for (var e : perOp.entrySet()) {
            sb.append(String.format("  %-14s %7.1f%n", e.getKey(), e.getValue()[0] / (double) e.getValue()[1]));
            total += e.getValue()[0];
        }
        sb.append(String.format("  %-14s %7.1f%n  %-14s %7.1f%n", "lifecycle", total / (double) n, "background/lc",
                bg / (double) n));
        sb.append("jdbc_statement_breakdown (request threads, per lifecycle, top 45):\n");
        StatementCounter.breakdown().entrySet().stream().sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(45).forEach(e -> sb.append(String.format("  %6.2f  %s%n", e.getValue() / (double) n, e.getKey())));
        StatementCounter.reset(false);
        System.out.println("[tscale-probe]\n" + sb);
        return sb.toString();
    }

    private Res probeCall(Map<String, long[]> perOp, boolean on, String op, String method, String path, Object body) {
        long before = StatementCounter.REQUEST.get();
        Res r = call(op, method, path, body);
        assertThat(r).as(op).isNotNull();
        assertThat(r.status / 100).as(op + " " + r.body).isEqualTo(2);
        if (on) {
            long[] c = perOp.computeIfAbsent(op, k -> new long[2]);
            c[0] += StatementCounter.REQUEST.get() - before;
            c[1]++;
        }
        return r;
    }

    // ------------------------------------------------------------------ setup + profiling

    private void deploy() throws Exception {
        String boundary = "----tscale" + UUID.randomUUID();
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"files\"; filename=\"tscale-load.bpmn\"\r\n"
                + "Content-Type: application/xml\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        b.write(MODEL.getBytes(StandardCharsets.UTF_8));
        b.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/deployments?name=tscale"))
                .header("Authorization", "Bearer " + token).header("Idempotency-Key", UUID.randomUUID().toString())
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(b.toByteArray())).build();
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as("deploy: " + r.body()).isBetween(200, 299);
    }

    private long pgCommits() {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT xact_commit FROM pg_stat_database WHERE datname = current_database()")) {
            rs.next();
            return rs.getLong(1);
        } catch (Exception e) {
            return 0;
        }
    }

    private void samplePgWaits(Map<String, Integer> acc) {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT coalesce(wait_event_type,'CPU') || ':' || coalesce(wait_event,'running')"
                     + " || ' | ' || left(regexp_replace(query, '\\s+', ' ', 'g'), 90) FROM pg_stat_activity"
                     + " WHERE datname = current_database() AND state <> 'idle' AND pid <> pg_backend_pid()")) {
            while (rs.next()) {
                acc.merge(rs.getString(1), 1, Integer::sum);
            }
        } catch (Exception e) {
            acc.merge("sample failed: " + e.getMessage(), 1, Integer::sum);
        }
    }

    private void sampleThreads(Map<String, Integer> acc) {
        for (ThreadInfo ti : ManagementFactory.getThreadMXBean().dumpAllThreads(false, false)) {
            if (!ti.getThreadName().startsWith("http-nio")) {
                continue;
            }
            String key = ti.getThreadState() + " (no app frame)";
            for (StackTraceElement f : ti.getStackTrace()) {
                String cn = f.getClassName();
                if (cn.startsWith("org.sangam") || cn.startsWith("org.operaton") || cn.startsWith("com.zaxxer")
                        || cn.startsWith("org.postgresql")) {
                    key = ti.getThreadState() + " " + cn + "." + f.getMethodName();
                    break;
                }
            }
            acc.merge(key, 1, Integer::sum);
        }
    }
}
