// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.acceptance;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.sangam.core.api.EngineAdapter.ExternalWork;
import org.sangam.core.api.StaleLeaseException;
import org.sangam.server.acceptance.engine.EngineScenarioSupport;

/**
 * Decision 147 (M-106 D-19) vendor evidence for the baseline fetch locking on the lane's real database (PostgreSQL,
 * MySQL, Oracle and SQL Server lanes; NOT_RUN until each lane's matrix runs, no support claim before): competing
 * workers with overlapping fetches claim every item exactly once in bounded batches, a renewed lease stays exclusive,
 * an expired lease is re-offered with a new fencing token and the stale worker's completion is rejected, and retries
 * end the lease. The H2 twin with the deferred-adapter acceptance criteria is sangam-engine
 * {@code FetchLockVerificationH2Test}. Contention and latency are reported as details, for the decision on any
 * optimisation; they are not pass criteria here.
 */
class FetchLockVerificationIT extends EngineScenarioSupport {

    private static final String MODEL = """
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:operaton="http://operaton.org/schema/1.0/bpmn" targetNamespace="d147">
              <process id="d147" isExecutable="true">
                <startEvent id="s"/><sequenceFlow id="f1" sourceRef="s" targetRef="x"/>
                <serviceTask id="x" operaton:type="external" operaton:topic="TOPIC"/>
                <sequenceFlow id="f2" sourceRef="x" targetRef="e"/><endEvent id="e"/>
              </process>
            </definitions>
            """;

    /** A model with its own topic per scenario, so scenarios never see each other's work. */
    private String deploy(String topic, String processId) {
        String xml = MODEL.replace("TOPIC", topic).replace("\"d147\" isExecutable", "\"" + processId + "\" isExecutable");
        adapter.deploy(SCENARIO_TENANT, processId + "-" + RUN, Map.of(processId + ".bpmn", xml.getBytes(StandardCharsets.UTF_8)));
        return processId;
    }

    @Test
    void competingWorkersClaimEveryItemExactlyOnce() {
        scenario("D147-competing-" + AcceptanceDatabase.name(), ctx -> {
            String topic = key("d147c");
            String process = deploy(topic, "d147c" + RUN.replace("-", ""));
            for (int i = 0; i < 40; i++) {
                adapter.startProcess(SCENARIO_TENANT, process, key("d147c"), Map.of());
            }
            int workers = 8;
            CyclicBarrier barrier = new CyclicBarrier(workers);
            ExecutorService pool = Executors.newFixedThreadPool(workers);
            Map<String, String> owner = new ConcurrentHashMap<>();
            List<String> duplicates = Collections.synchronizedList(new ArrayList<>());
            List<Long> latenciesMs = Collections.synchronizedList(new ArrayList<>());
            int total = 0;
            int maxBatch = 0;
            try {
                List<Future<int[]>> results = new ArrayList<>();
                for (int w = 0; w < workers; w++) {
                    String worker = "w" + w + "-" + RUN;
                    results.add(pool.submit(() -> {
                        barrier.await(30, TimeUnit.SECONDS);
                        int got = 0;
                        int biggest = 0;
                        for (int round = 0; round < 12; round++) {
                            long t0 = System.nanoTime();
                            List<ExternalWork> batch = adapter.fetchAndLock(worker, topic, 5, Duration.ofMinutes(5));
                            latenciesMs.add((System.nanoTime() - t0) / 1_000_000);
                            biggest = Math.max(biggest, batch.size());
                            for (ExternalWork item : batch) {
                                if (owner.putIfAbsent(item.id(), worker) != null) {
                                    duplicates.add(item.id());
                                }
                                got++;
                            }
                        }
                        return new int[]{got, biggest};
                    }));
                }
                for (Future<int[]> f : results) {
                    int[] r = f.get(120, TimeUnit.SECONDS);
                    total += r[0];
                    maxBatch = Math.max(maxBatch, r[1]);
                }
            } finally {
                pool.shutdownNow();
            }
            List<Long> sorted = new ArrayList<>(latenciesMs);
            Collections.sort(sorted);
            ctx.detail("fetches", sorted.size());
            ctx.detail("fetchLatencyP50Ms", sorted.isEmpty() ? null : sorted.get(sorted.size() / 2));
            ctx.detail("fetchLatencyP95Ms", sorted.isEmpty() ? null : sorted.get((int) Math.floor(sorted.size() * 0.95)));
            ctx.check("no item held by two live leases", duplicates.isEmpty());
            ctx.check("every item delivered exactly once (40)", total == 40 && owner.size() == 40);
            ctx.check("batches bounded by maxItems (5)", maxBatch <= 5);
        });
    }

    @Test
    void expiryReoffersWithANewTokenAndTheStaleWorkerIsRejected() {
        scenario("D147-expiry-" + AcceptanceDatabase.name(), ctx -> {
            clock(Instant.now().truncatedTo(ChronoUnit.SECONDS));
            String topic = key("d147e");
            String process = deploy(topic, "d147e" + RUN.replace("-", ""));
            String pid = adapter.startProcess(SCENARIO_TENANT, process, key("d147e"), Map.of());
            ExternalWork first = adapter.fetchAndLock("w1", topic, 1, Duration.ofMinutes(1)).getFirst();
            adapter.extendLock(first.id(), "w1", first.lockVersion(), Duration.ofMinutes(10));
            advance(Duration.ofMinutes(5));
            ctx.check("a renewed lease stays exclusive", adapter.fetchAndLock("w2", topic, 1, Duration.ofMinutes(1)).isEmpty());
            advance(Duration.ofMinutes(6)); // w1 crashed
            ExternalWork second = adapter.fetchAndLock("w2", topic, 1, Duration.ofMinutes(1)).getFirst();
            ctx.check("the expired item is re-offered", second.id().equals(first.id()));
            ctx.check("with a greater fencing token", second.lockVersion() > first.lockVersion());
            ctx.check("operationId unchanged", java.util.Objects.equals(first.operationId(), second.operationId()));
            StaleLeaseException.Reason reason = null;
            try {
                adapter.completeExternal(first.id(), "w1", first.lockVersion(), Map.of("late", true));
            } catch (StaleLeaseException e) {
                reason = e.reason();
            }
            ctx.detail("staleReason", reason);
            ctx.check("the stale worker's completion is rejected", reason == StaleLeaseException.Reason.STALE_VERSION
                    || reason == StaleLeaseException.Reason.NOT_OWNER);
            adapter.completeExternal(second.id(), "w2", second.lockVersion(), Map.of());
            ctx.check("the current lease completes the instance", ended(pid));
        });
    }

    @Test
    void retriesEndTheLeaseAndStopAtZero() {
        scenario("D147-retries-" + AcceptanceDatabase.name(), ctx -> {
            String topic = key("d147r");
            String process = deploy(topic, "d147r" + RUN.replace("-", ""));
            adapter.startProcess(SCENARIO_TENANT, process, key("d147r"), Map.of());
            ExternalWork w = adapter.fetchAndLock("w1", topic, 1, Duration.ofMinutes(5)).getFirst();
            adapter.failExternal(w.id(), "w1", w.lockVersion(), "transient", 1, Duration.ZERO);
            ExternalWork again = adapter.fetchAndLock("w2", topic, 1, Duration.ofMinutes(5)).getFirst();
            ctx.check("re-offered after a failure with retries left", again.id().equals(w.id()) && again.retries() == 1
                    && again.lockVersion() > w.lockVersion());
            adapter.failExternal(again.id(), "w2", again.lockVersion(), "permanent", 0, Duration.ZERO);
            ctx.check("no retries left: not offered", adapter.fetchAndLock("w3", topic, 1, Duration.ofMinutes(5)).isEmpty());
        });
    }
}
