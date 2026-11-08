// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.bridge;

import org.sangam.core.api.EngineAdapter;
import org.sangam.core.api.StaleLeaseException;
import org.sangam.core.layer.Tombstones;
import org.sangam.integration.connector.ConnectorInvoker;
import org.sangam.integration.connector.Json;
import org.sangam.integration.lifecycle.InstanceLifecycleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * BPMN service task → connector bridge over external work (SANGAM.md VI.3, A-2, R36, R71; FLOWABLE-INTO-OPERATON FC-06/FLOWABLE-INTO-OPERATON FC-07 HTTP and
 * mail tasks). Engine-neutral: it uses only the core {@link EngineAdapter} worker calls, so the lease fencing of
 * {@code SANGAM_LEASE} (lockVersion, attemptId) applies to every call.
 *
 * <p>Per fetched item:
 * <ol>
 *   <li>A cancel-requested tombstone (VI.5) is acknowledged without dispatch and the work is released.</li>
 *   <li>{@link ConnectorInvoker#invoke} with the engine identity (tenant, root, activityInstanceId, loopCounter) → the
 *       stable operationId, the attemptId and the lockVersion of this lease; a heartbeat extends the lease every
 *       lease/3 while the call runs (VI.3.2 rule 3).</li>
 *   <li>Outcome → engine call, all fenced by lockVersion: completed → {@code completeExternal} with the output in
 *       {@code resultVariable} plus {@code operationId}; failed → {@code bpmnErrorExternal(code)}; retry →
 *       {@code failExternal} with retries left and back-off; unknown → {@code failExternal(retries=0)}: the work is held
 *       as an incident until reconciliation releases it; stale → dropped.</li>
 *   <li>A {@link StaleLeaseException} with reason {@code NO_LEASE} on completion means the instance was deleted
 *       meanwhile: the result is reported to {@link InstanceLifecycleService#onWorkerResult} (410, audited,
 *       {@code LATE_RESULT_AFTER_DELETE} item when an effect happened).</li>
 * </ol>
 */
public final class ConnectorExternalTaskWorker implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(ConnectorExternalTaskWorker.class);

    private final EngineAdapter engine;
    private final ConnectorInvoker invoker;
    private final WorkContextResolver contexts;
    private final InstanceLifecycleService lifecycle;
    private final String workerId;
    private final Map<String, TopicBinding> bindings = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService heartbeats = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "sangam-connector-heartbeat");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean running = new AtomicBoolean();
    private final int maxTasksPerFetch;
    private final Duration idlePoll;

    public ConnectorExternalTaskWorker(EngineAdapter engine, ConnectorInvoker invoker, WorkContextResolver contexts,
                                       InstanceLifecycleService lifecycleOrNull, String workerId, int maxTasksPerFetch,
                                       Duration idlePoll) {
        this.engine = engine;
        this.invoker = invoker;
        this.contexts = contexts;
        this.lifecycle = lifecycleOrNull;
        this.workerId = workerId;
        this.maxTasksPerFetch = maxTasksPerFetch;
        this.idlePoll = idlePoll == null ? Duration.ofSeconds(1) : idlePoll;
    }

    public ConnectorExternalTaskWorker bind(TopicBinding binding) {
        bindings.put(binding.topic(), binding);
        return this;
    }

    /** Starts one fetch loop per bound topic on virtual threads. */
    public void start() {
        if (running.compareAndSet(false, true)) {
            for (TopicBinding b : bindings.values()) {
                executor.execute(() -> loop(b));
            }
        }
    }

    private void loop(TopicBinding b) {
        while (running.get()) {
            try {
                if (pollOnce(b) == 0) {
                    Thread.sleep(idlePoll.toMillis());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                LOG.warn("fetch for topic {} failed", b.topic(), e);
                try {
                    Thread.sleep(idlePoll.toMillis());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** One fetch-and-handle pass for a topic; returns the number of items handled. */
    public int pollOnce(TopicBinding b) {
        List<EngineAdapter.ExternalWork> items = engine.fetchAndLock(workerId, b.topic(), maxTasksPerFetch, b.lockDuration());
        for (EngineAdapter.ExternalWork w : items) {
            handle(b, w);
        }
        return items.size();
    }

    void handle(TopicBinding b, EngineAdapter.ExternalWork w) {
        WorkContextResolver.WorkContext ctx = contexts.resolve(w);
        if (lifecycle != null && lifecycle.cancelRequested(ctx.tenantId(), w.id())) {
            lifecycle.acknowledgeCancel(ctx.tenantId(), w.id(), w.lockVersion());
            safe(() -> engine.unlockExternal(w.id(), workerId, w.lockVersion()));
            return;
        }
        Map<String, Object> input = input(b, w);
        String retrySafe = b.retrySafeKeyVar() == null ? null : String.valueOf(w.variables().get(b.retrySafeKeyVar()));
        Instant deadline = w.lockExpiresAt() == null ? null : w.lockExpiresAt().minusSeconds(5);
        ConnectorInvoker.Invocation inv = new ConnectorInvoker.Invocation(ctx.tenantId(), ctx.rootId(),
                w.activityInstanceId() == null ? w.id() : w.activityInstanceId(), ctx.loopCounter(), retrySafe, ctx.businessKey(),
                b.connectorId(), b.operation(), input, w.attemptId(), w.lockVersion(), deadline, mintedId(w));
        ScheduledFuture<?> hb = heartbeat(w, b.lockDuration());
        ConnectorInvoker.Outcome outcome;
        try {
            outcome = invoker.invoke(inv);
        } finally {
            hb.cancel(false);
        }
        try {
            apply(b, w, ctx, outcome);
        } catch (StaleLeaseException e) {
            if (e.reason() == StaleLeaseException.Reason.NO_LEASE && lifecycle != null) {
                Tombstones.ResultKind kind = outcome instanceof ConnectorInvoker.Completed ? Tombstones.ResultKind.COMPLETE
                        : outcome instanceof ConnectorInvoker.Unknown ? Tombstones.ResultKind.OUTCOME_UNKNOWN : Tombstones.ResultKind.FAIL;
                boolean effect = outcome instanceof ConnectorInvoker.Completed;
                lifecycle.onWorkerResult(ctx.tenantId(), w.id(), w.lockVersion(), kind, effect, Json.write(describe(outcome)));
            } else {
                LOG.info("result for {} dropped: {}", w.id(), e.getMessage());
            }
        }
    }

    private void apply(TopicBinding b, EngineAdapter.ExternalWork w, WorkContextResolver.WorkContext ctx, ConnectorInvoker.Outcome o) {
        switch (o) {
            case ConnectorInvoker.Completed c -> {
                Map<String, Object> vars = new LinkedHashMap<>();
                vars.put(b.resultVariable(), Json.write(c.output()));
                vars.put(b.resultVariable() + "_operationId", c.operationId().toString());
                if (c.providerRef() != null) {
                    vars.put(b.resultVariable() + "_providerRef", c.providerRef());
                }
                engine.completeExternal(w.id(), workerId, w.lockVersion(), vars);
            }
            case ConnectorInvoker.Failed f -> {
                Map<String, Object> vars = new LinkedHashMap<>();
                vars.put(b.resultVariable() + "_errorCode", f.code());
                vars.put(b.resultVariable() + "_errorMessage", f.message());
                vars.put(b.resultVariable() + "_operationId", f.operationId().toString());
                engine.bpmnErrorExternal(w.id(), workerId, w.lockVersion(), f.code(), vars);
            }
            case ConnectorInvoker.Retry r -> {
                int left = w.retries() <= 0 ? b.maxRetries() - 1 : w.retries() - 1;
                Duration backoff = r.retryAfter() != null ? r.retryAfter() : b.retryBackoff();
                engine.failExternal(w.id(), workerId, w.lockVersion(), r.message(), Math.max(0, left), backoff);
            }
            case ConnectorInvoker.Unknown u -> engine.failExternal(w.id(), workerId, w.lockVersion(),
                    "UNKNOWN_OUTCOME (operation " + u.operationId() + "): held for reconciliation"
                            + (u.reconciliationItemId() == null ? "" : " item " + u.reconciliationItemId()) + ": " + u.reason(),
                    0, Duration.ZERO);
            case ConnectorInvoker.Stale s -> LOG.info("stale lease for {}: {}", w.id(), s.message());
        }
    }

    /** The adapter's T35 operationId; null (invoker warns and derives) when absent or not a UUID. */
    private static java.util.UUID mintedId(EngineAdapter.ExternalWork w) {
        if (w.operationId() == null || w.operationId().isBlank()) {
            return null;
        }
        try {
            return java.util.UUID.fromString(w.operationId());
        } catch (IllegalArgumentException e) {
            LOG.warn("work {} carries a malformed operationId {}", w.id(), w.operationId());
            return null;
        }
    }

    private ScheduledFuture<?> heartbeat(EngineAdapter.ExternalWork w, Duration lease) {
        long every = Math.max(1000, lease.toMillis() / 3);
        return heartbeats.scheduleAtFixedRate(() -> safe(() -> engine.extendLock(w.id(), workerId, w.lockVersion(), lease)),
                every, every, TimeUnit.MILLISECONDS);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> input(TopicBinding b, EngineAdapter.ExternalWork w) {
        Map<String, Object> in = new LinkedHashMap<>(b.fixedInput());
        if (!b.inputVariables().isEmpty()) {
            b.inputVariables().forEach((var, key) -> {
                if (w.variables().containsKey(var)) {
                    in.put(key, decode(w.variables().get(var)));
                }
            });
            return in;
        }
        Object ci = w.variables().get("connectorInput");
        if (ci != null) {
            Object d = decode(ci);
            if (d instanceof Map<?, ?> m) {
                in.putAll((Map<String, Object>) m);
                return in;
            }
        }
        w.variables().forEach((k, v) -> {
            if (!k.startsWith("sangam")) {
                in.put(k, v);
            }
        });
        return in;
    }

    /** JSON strings (typed JSON variables, R33) are decoded to maps/lists. */
    private static Object decode(Object v) {
        if (v instanceof String s) {
            String t = s.trim();
            if ((t.startsWith("{") && t.endsWith("}")) || (t.startsWith("[") && t.endsWith("]"))) {
                var tree = Json.tree(t);
                if (tree != null) {
                    return Json.MAPPER.convertValue(tree, Object.class);
                }
            }
        }
        return v;
    }

    private static Map<String, Object> describe(ConnectorInvoker.Outcome o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("operationId", o.operationId().toString());
        m.put("outcome", o.getClass().getSimpleName());
        if (o instanceof ConnectorInvoker.Completed c && c.providerRef() != null) {
            m.put("providerRef", c.providerRef());
        }
        return m;
    }

    private static void safe(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            LOG.debug("worker call failed: {}", e.getMessage());
        }
    }

    @Override
    public void close() {
        running.set(false);
        executor.shutdownNow();
        heartbeats.shutdownNow();
    }
}
