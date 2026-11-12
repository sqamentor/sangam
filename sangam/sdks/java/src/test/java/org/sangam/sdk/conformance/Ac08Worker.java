// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.conformance;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.sangam.sdk.SangamClient;
import org.sangam.sdk.error.StaleLeaseException;
import org.sangam.sdk.http.HttpTransport;
import org.sangam.sdk.log.SangamLogger;
import org.sangam.sdk.model.CompleteWorkRequest;
import org.sangam.sdk.model.ExternalWork;
import org.sangam.sdk.model.FetchAndLockRequest;
import org.sangam.sdk.model.FetchTopic;
import org.sangam.sdk.model.TypedValue;
import org.sangam.sdk.worker.BpmnError;
import org.sangam.sdk.worker.OutcomeUnknownException;
import org.sangam.sdk.worker.SangamWorker;
import org.sangam.sdk.worker.TaskContext;
import org.sangam.sdk.worker.TaskFailedException;
import org.sangam.sdk.worker.TopicSubscription;

/**
 * AC-08 conformance worker of the Java SDK (SANGAM.md IX.8 AC-08, M-023). The cross-language orchestrator starts the
 * same program in every Tier-1 SDK against one Sangam server and compares the JSON lines they print.
 *
 * <p>Environment: SANGAM_BASE_URL, SANGAM_TOKEN (optional), AC08_TOPIC, AC08_WORKER_ID, AC08_SCENARIO
 * (complete | bpmn-error | fail-retry | outcome-unknown | stale-lease), AC08_TASKS (default 1), AC08_TIMEOUT_S
 * (default 60), AC08_LOCK_MS (default 30000). Output: one JSON line per task, then exit 0; exit 1 on timeout.
 *
 * <p>Every scenario runs on the SDK worker runtime except {@code stale-lease}: the runtime heart-beats at lease/3, so
 * its lease never lapses while it works. That scenario is the "old worker resumes" case (T03) and uses the WorkApi
 * directly: fetch, stop for AC08_LOCK_MS + 2 s without heart-beating, then complete and expect 409 STALE_LEASE.
 *
 * <p>Test scope: it is not shipped in the SDK jar. Run with the module's test classpath, e.g.
 * {@code java -cp target/classes:target/test-classes:<jackson jars> org.sangam.sdk.conformance.Ac08Worker}.
 */
public final class Ac08Worker {

    private static final ObjectMapper JSON = HttpTransport.defaultMapper();

    private Ac08Worker() { }

    public static void main(String[] args) throws Exception {
        String base = env("SANGAM_BASE_URL", null);
        String topic = env("AC08_TOPIC", null);
        String workerId = env("AC08_WORKER_ID", "ac08-java");
        String scenario = env("AC08_SCENARIO", "complete");
        int tasks = Integer.parseInt(env("AC08_TASKS", "1"));
        int timeoutS = Integer.parseInt(env("AC08_TIMEOUT_S", "60"));
        long lockMs = Long.parseLong(env("AC08_LOCK_MS", "30000"));
        if (base == null || topic == null) {
            System.err.println("SANGAM_BASE_URL and AC08_TOPIC are required");
            System.exit(2);
        }
        SangamClient.Builder cb = SangamClient.builder().baseUrl(base).logger(SangamLogger.noop());
        String token = System.getenv("SANGAM_TOKEN");
        if (token != null && !token.isBlank()) cb.token(token);
        SangamClient client = cb.build();
        boolean ok = scenario.equals("stale-lease")
                ? staleLease(client, topic, workerId, tasks, timeoutS, lockMs)
                : runtime(client, topic, workerId, scenario, tasks, timeoutS, lockMs);
        System.out.flush();
        System.exit(ok ? 0 : 1);
    }

    private static boolean runtime(SangamClient client, String topic, String workerId, String scenario, int tasks,
                                   int timeoutS, long lockMs) throws InterruptedException {
        CountDownLatch done = new CountDownLatch(tasks);
        Map<String, Boolean> failedOnce = new ConcurrentHashMap<>();
        Map<String, Boolean> printed = new ConcurrentHashMap<>();
        SangamLogger results = (level, event, message, fields, error) -> {
            if (fields == null || !(fields.get("workId") instanceof String workId)) return;
            String result = switch (event) {
                case "work.complete" -> failedOnce.containsKey(workId) ? "failed-then-completed" : "completed";
                case "work.bpmn-error" -> "bpmn-error";
                case "work.outcome-unknown" -> "outcome-unknown";
                case "work.abandoned" -> "stale-lease";
                case "work.report.failed", "work.report.gave-up" -> "error";
                default -> null;
            };
            if (result == null || printed.putIfAbsent(workId, true) != null) return;
            print(fields, scenario, result, error == null ? message : String.valueOf(error.getMessage()));
            done.countDown();
        };
        TopicSubscription sub = TopicSubscription.topic(topic, ctx -> handle(ctx, scenario, failedOnce))
                .lockDuration(Duration.ofMillis(lockMs)).retries(2).build();
        SangamWorker worker = SangamWorker.builder(client).workerId(workerId).maxConcurrency(Math.max(1, Math.min(tasks, 8)))
                .logger(results).subscribe(sub).build().start();
        try {
            return done.await(timeoutS, TimeUnit.SECONDS);
        } finally {
            worker.close();
        }
    }

    private static Map<String, Object> handle(TaskContext ctx, String scenario, Map<String, Boolean> failedOnce)
            throws Exception {
        switch (scenario) {
            case "bpmn-error" -> throw new BpmnError("AC08_ERROR", "ac08 business error");
            case "outcome-unknown" -> throw new OutcomeUnknownException("ac08: downstream outcome unknown");
            case "fail-retry" -> {
                if (failedOnce.putIfAbsent(ctx.workId(), true) == null) {
                    throw new TaskFailedException("ac08 transient failure", null, 1, Duration.ZERO, null);
                }
            }
            default -> { }
        }
        return outputs(ctx.variables(), ctx);
    }

    /** The input values decoded by the SDK and encoded again (R33), as out_*. */
    static Map<String, Object> outputs(Map<String, Object> vars, TaskContext ctx) {
        Map<String, Object> out = new LinkedHashMap<>();
        BigDecimal amount = TypedValue.unwrap(vars.get("amount"), BigDecimal.class, JSON);
        Long count = TypedValue.unwrap(vars.get("count"), Long.class, JSON);
        Instant when = TypedValue.unwrap(vars.get("when"), Instant.class, JSON);
        LocalDate day = TypedValue.unwrap(vars.get("day"), LocalDate.class, JSON);
        String note = TypedValue.unwrap(vars.get("note"), String.class, JSON);
        out.put("out_amount", amount == null ? null : TypedValue.decimal(amount));
        out.put("out_count", count == null ? null : TypedValue.ofLong(count));
        out.put("out_when", when == null ? null : TypedValue.date(when));
        out.put("out_day", day == null ? null : TypedValue.localDate(day));
        out.put("out_note", note == null ? null : TypedValue.string(note));
        return out;
    }

    private static boolean staleLease(SangamClient client, String topic, String workerId, int tasks, int timeoutS,
                                      long lockMs) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutS);
        int seen = 0;
        while (seen < tasks && System.nanoTime() < end) {
            List<ExternalWork> items = client.work().fetchAndLock(new FetchAndLockRequest(workerId,
                    List.of(new FetchTopic(topic, lockMs, null, null, null, null)), 1, 5_000L, null));
            for (ExternalWork w : items) {
                seen++;
                Thread.sleep(lockMs + 2_000);
                Map<String, Object> fields = fields(w);
                try {
                    client.work().complete(w.id(), new CompleteWorkRequest(workerId, w.lockVersion(), w.attemptId(),
                            Map.of(), null, null), w.operationId() + ":complete:" + w.lockVersion());
                    print(fields, "stale-lease", "completed", "the expired lease was accepted");
                } catch (StaleLeaseException e) {
                    print(fields, "stale-lease", "stale-lease", String.valueOf(e.problem().extensions().get("reason")));
                } catch (RuntimeException e) {
                    print(fields, "stale-lease", "error", e.toString());
                }
            }
        }
        return seen >= tasks;
    }

    private static Map<String, Object> fields(ExternalWork w) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("workId", w.id());
        f.put("operationId", w.operationId());
        f.put("lockVersion", w.lockVersion());
        f.put("attemptId", w.attemptId());
        return f;
    }

    private static synchronized void print(Map<String, ?> fields, String scenario, String result, String detail) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("lang", "java");
        line.put("workId", fields.get("workId"));
        line.put("operationId", fields.get("operationId"));
        line.put("lockVersion", fields.get("lockVersion"));
        line.put("attemptId", fields.get("attemptId"));
        line.put("scenario", scenario);
        line.put("result", result);
        line.put("detail", detail);
        try {
            System.out.println(JSON.writeValueAsString(line));
        } catch (Exception e) {
            System.out.println("{\"lang\":\"java\",\"result\":\"error\"}");
        }
    }

    private static String env(String name, String dflt) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? dflt : v;
    }
}
