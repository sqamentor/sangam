// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.worker;

import org.sangam.sdk.SangamClient;
import org.sangam.sdk.error.GoneException;
import org.sangam.sdk.error.SangamApiException;
import org.sangam.sdk.error.SangamException;
import org.sangam.sdk.error.StaleLeaseException;
import org.sangam.sdk.error.TransportException;
import org.sangam.sdk.log.SangamLogger;
import org.sangam.sdk.model.BpmnErrorRequest;
import org.sangam.sdk.model.CompleteWorkRequest;
import org.sangam.sdk.model.ExtendLockRequest;
import org.sangam.sdk.model.ExtendLockResult;
import org.sangam.sdk.model.ExternalWork;
import org.sangam.sdk.model.FailWorkRequest;
import org.sangam.sdk.model.FetchAndLockRequest;
import org.sangam.sdk.model.FetchTopic;
import org.sangam.sdk.model.OutcomeUnknownRequest;
import org.sangam.sdk.model.UnlockRequest;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * External-worker runtime for the Sangam worker API (VI.3, A-1, A-2, R9, R20, R36).
 *
 * <ul>
 *   <li><b>Long-poll fetch-and-lock</b> for all registered topics, asking only for as many items as there are free
 *       slots ({@code maxConcurrency}); fetch errors back off exponentially with jitter.</li>
 *   <li><b>A-1:</b> a handler starts only for items that came back LOCKED with a lockVersion and attemptId.</li>
 *   <li><b>Heartbeat</b> ({@code extend-lock}) every lease/3; the answer carries {@code cancelRequested}.</li>
 *   <li><b>Fencing:</b> every call carries {@code (workerId, lockVersion, attemptId)}. A 409 stale-lease or 410
 *       gone on any call means the lease is lost: the handler is interrupted and its result is discarded.</li>
 *   <li><b>Idempotency-Key</b> per call derived from the operationId ({@code <operationId>:<action>:<lockVersion>}),
 *       so a repeated HTTP request is replayed by the server instead of applied twice (R41).</li>
 *   <li><b>Retries:</b> result calls are retried with back-off until the lease expires; handler failures are
 *       reported with decreasing retries and a growing {@code retryTimeoutMs}.</li>
 *   <li><b>Graceful shutdown:</b> {@link #close()} stops fetching, waits for in-flight handlers, then interrupts the
 *       rest and unlocks their items so another worker can take them at once.</li>
 * </ul>
 */
public final class SangamWorker implements AutoCloseable {

    private final SangamClient client;
    private final String workerId;
    private final int maxConcurrency;
    private final int maxTasksPerFetch;
    private final Duration asyncResponseTimeout;
    private final double heartbeatFraction;
    private final Backoff fetchBackoff;
    private final Backoff reportBackoff;
    private final Duration shutdownTimeout;
    private final Boolean usePriority;
    private final SangamLogger log;
    private final Clock clock;
    private final Map<String, TopicSubscription> subscriptions = new ConcurrentHashMap<>();

    private final Semaphore slots;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final Set<Thread> handlerThreads = ConcurrentHashMap.newKeySet();
    private final Map<String, TaskContext> inFlight = new ConcurrentHashMap<>();
    private final AtomicLong heartbeatSeq = new AtomicLong();
    private ExecutorService executor;
    private Thread poller;
    private Thread shutdownHook;

    private SangamWorker(Builder b) {
        this.client = Objects.requireNonNull(b.client, "client");
        this.workerId = b.workerId != null ? b.workerId : defaultWorkerId();
        this.maxConcurrency = b.maxConcurrency;
        this.maxTasksPerFetch = Math.min(b.maxTasksPerFetch, b.maxConcurrency);
        this.asyncResponseTimeout = b.asyncResponseTimeout;
        this.heartbeatFraction = b.heartbeatFraction;
        this.fetchBackoff = b.fetchBackoff;
        this.reportBackoff = b.reportBackoff;
        this.shutdownTimeout = b.shutdownTimeout;
        this.usePriority = b.usePriority;
        this.log = b.logger != null ? b.logger : client.logger();
        this.clock = b.clock;
        this.slots = new Semaphore(maxConcurrency);
        b.subscriptions.forEach(s -> subscriptions.put(s.topic(), s));
    }

    public static Builder builder(SangamClient client) {
        return new Builder(client);
    }

    public String workerId() {
        return workerId;
    }

    /** Registers (or replaces) a handler; takes effect on the next fetch. Flowable-client parity: subscribe at runtime. */
    public SangamWorker subscribe(TopicSubscription subscription) {
        subscriptions.put(subscription.topic(), subscription);
        log.info("worker.subscribed", "subscribed", Map.of("workerId", workerId, "topic", subscription.topic()));
        return this;
    }

    public SangamWorker subscribe(String topic, TaskHandler handler) {
        return subscribe(TopicSubscription.topic(topic, handler).build());
    }

    /** Stops fetching a topic; items already running finish normally. */
    public SangamWorker unsubscribe(String topic) {
        subscriptions.remove(topic);
        log.info("worker.unsubscribed", "unsubscribed", Map.of("workerId", workerId, "topic", topic));
        return this;
    }

    public int inFlightCount() {
        return inFlight.size();
    }

    /** Starts the poller thread and returns. */
    public synchronized SangamWorker start() {
        if (!running.compareAndSet(false, true)) return this;
        stopping.set(false);
        executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("sangam-work-", 0).factory());
        poller = Thread.ofPlatform().name("sangam-poller-" + workerId).daemon(false).start(this::pollLoop);
        log.info("worker.started", "worker started", Map.of("workerId", workerId, "maxConcurrency", maxConcurrency,
                "topics", List.copyOf(subscriptions.keySet())));
        return this;
    }

    /** Starts and blocks until {@link #close()} is called from another thread or the JVM shuts down. */
    public void run() throws InterruptedException {
        registerShutdownHook();
        start();
        poller.join();
        awaitInFlight();
    }

    /** Calls {@link #close()} on SIGTERM / JVM exit. */
    public synchronized SangamWorker registerShutdownHook() {
        if (shutdownHook == null) {
            shutdownHook = new Thread(this::close, "sangam-worker-shutdown");
            Runtime.getRuntime().addShutdownHook(shutdownHook);
        }
        return this;
    }

    @Override
    public void close() {
        Thread p;
        ExecutorService ex;
        synchronized (this) {
            if (!running.get() || !stopping.compareAndSet(false, true)) return;
            p = poller;
            ex = executor;
        }
        log.info("worker.stopping", "stopping: no new fetches", Map.of("workerId", workerId, "inFlight", inFlight.size()));
        if (p != null) p.interrupt(); // aborts the long poll
        try {
            if (p != null) p.join(Duration.ofSeconds(5).toMillis());
            ex.shutdown();
            if (!ex.awaitTermination(shutdownTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn("worker.shutdown.timeout", "interrupting handlers still running",
                        Map.of("workerId", workerId, "inFlight", inFlight.size()), null);
                inFlight.values().forEach(c -> c.shuttingDown = true);
                handlerThreads.forEach(Thread::interrupt);
                ex.awaitTermination(10, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            running.set(false);
            log.info("worker.stopped", "worker stopped", Map.of("workerId", workerId));
        }
    }

    private void awaitInFlight() throws InterruptedException {
        ExecutorService ex = executor;
        if (ex != null) ex.awaitTermination(shutdownTimeout.toMillis() + 15_000, TimeUnit.MILLISECONDS);
    }

    // ------------------------------------------------------------------ fetch loop

    private void pollLoop() {
        int consecutiveErrors = 0;
        while (!stopping.get() && !Thread.currentThread().isInterrupted()) {
            int permits;
            try {
                slots.acquire();
            } catch (InterruptedException e) {
                break;
            }
            permits = 1 + slots.drainPermits();
            if (permits > maxTasksPerFetch) {
                slots.release(permits - maxTasksPerFetch);
                permits = maxTasksPerFetch;
            }
            List<TopicSubscription> subs = List.copyOf(subscriptions.values());
            if (subs.isEmpty()) {
                slots.release(permits);
                if (!sleep(Duration.ofSeconds(1))) break;
                continue;
            }
            List<ExternalWork> items;
            try {
                List<FetchTopic> topics = new ArrayList<>();
                for (TopicSubscription s : subs) {
                    topics.add(new FetchTopic(s.topic(), s.lockDuration().toMillis(), s.variables(), s.localVariables(),
                            s.processDefinitionKey(), s.businessKey()));
                }
                items = client.work().fetchAndLock(new FetchAndLockRequest(workerId, topics, permits,
                        asyncResponseTimeout.toMillis(), usePriority));
                consecutiveErrors = 0;
            } catch (RuntimeException e) {
                slots.release(permits);
                if (stopping.get() || Thread.currentThread().isInterrupted() || isInterrupt(e)) break;
                consecutiveErrors++;
                Duration d = fetchBackoff.delay(consecutiveErrors);
                log.warn("work.fetch.failed", "fetch-and-lock failed; backing off",
                        Map.of("workerId", workerId, "attempt", consecutiveErrors, "delayMs", d.toMillis()), e);
                if (!sleep(d)) break;
                continue;
            }
            int used = 0;
            for (ExternalWork w : items) {
                if (used >= permits) {
                    // more than requested: never run it without a slot; release the lease at once
                    log.warn("work.overdelivered", "server returned more items than requested; unlocking",
                            Map.of("workerId", workerId, "workId", w.id()), null);
                    unlockQuietly(w);
                    continue;
                }
                TopicSubscription sub = subscriptions.get(w.topic());
                if (sub == null) {
                    log.warn("work.unsubscribed", "no handler for topic; unlocking",
                            Map.of("workerId", workerId, "workId", w.id(), "topic", String.valueOf(w.topic())), null);
                    unlockQuietly(w);
                    continue;
                }
                if (w.attemptId() == null || w.id() == null) {
                    // A-1: run only after the LOCKED confirmation with lockVersion + attemptId
                    log.error("work.protocol.violation", "item without LOCKED confirmation (attemptId); not started",
                            Map.of("workerId", workerId, "workId", String.valueOf(w.id())), null);
                    continue;
                }
                used++;
                try {
                    executor.execute(() -> runItem(w, sub));
                } catch (RuntimeException rejected) {
                    used--;
                    unlockQuietly(w);
                }
            }
            if (permits - used > 0) slots.release(permits - used);
        }
        log.debug("worker.poller.exit", "poller exited", Map.of("workerId", workerId));
    }

    private static boolean isInterrupt(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof InterruptedException) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ one item

    private void runItem(ExternalWork work, TopicSubscription sub) {
        Thread self = Thread.currentThread();
        handlerThreads.add(self);
        TaskContext ctx = new TaskContext(work, workerId, client, client.transport().json(), log);
        inFlight.put(work.id(), ctx);
        AtomicBoolean done = new AtomicBoolean();
        Thread heartbeat = Thread.ofVirtual().name("sangam-hb-" + work.id()).start(() -> heartbeatLoop(ctx, sub, done, self));
        try {
            ctx.logger().info("work.started", "handler started", null);
            Outcome outcome = invoke(ctx, sub);
            done.set(true);
            heartbeat.interrupt();
            if (ctx.leaseLost) {
                ctx.logger().warn("work.abandoned", "lease lost; result discarded (another attempt owns it)",
                        Map.of("outcome", outcome.kind), null);
                return;
            }
            report(ctx, sub, outcome);
        } finally {
            done.set(true);
            heartbeat.interrupt();
            inFlight.remove(work.id());
            handlerThreads.remove(self);
            Thread.interrupted(); // clear a late interrupt before the virtual thread ends
            slots.release();
        }
    }

    private record Outcome(String kind, Map<String, Object> variables, Throwable error) { }

    @SuppressWarnings("unchecked")
    private Outcome invoke(TaskContext ctx, TopicSubscription sub) {
        org.sangam.sdk.model.WorkOperation op = ctx.work().operation();
        if (op != null && !op.mayDispatch() && op.succeeded()) {
            // SL-16: the operation already succeeded downstream; complete from the stored outcome, never call it again.
            Map<String, Object> stored = op.storedOutcome() == null ? Map.of() : op.storedOutcome();
            Object out = stored.get("output");
            Object ref = stored.get("providerRef");
            ctx.outcomeReference(ref == null ? null : String.valueOf(ref));
            ctx.logger().info("work.replayed", "operation already succeeded; completing from the stored outcome", null);
            return new Outcome("complete", out instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of(), null);
        }
        try {
            Map<String, Object> vars = sub.handler().handle(ctx);
            return new Outcome("complete", vars, null);
        } catch (BpmnError e) {
            return new Outcome("bpmn-error", e.variables(), e);
        } catch (OutcomeUnknownException e) {
            return new Outcome("outcome-unknown", e.variables(), e);
        } catch (TaskFailedException e) {
            return new Outcome("fail", null, e);
        } catch (TaskCancelledException e) {
            return new Outcome(ctx.cancelRequested ? "cancel-ack" : "unlock", null, e);
        } catch (InterruptedException e) {
            return new Outcome(ctx.cancelRequested ? "cancel-ack" : "unlock", null, e);
        } catch (Exception | Error e) {
            if (ctx.shuttingDown && isInterrupt(e)) return new Outcome("unlock", null, e);
            boolean irreversible = sub.irreversible() || "irreversible".equalsIgnoreCase(ctx.work().effect());
            return new Outcome(irreversible ? "outcome-unknown" : "fail", null, e);
        }
    }

    private void heartbeatLoop(TaskContext ctx, TopicSubscription sub, AtomicBoolean done, Thread handler) {
        long lease = sub.lockDuration().toMillis();
        long interval = Math.max(1_000, (long) (lease * heartbeatFraction));
        int failures = 0;
        while (!done.get()) {
            if (!sleep(Duration.ofMillis(failures == 0 ? interval : Math.min(interval, reportBackoff.delay(failures).toMillis())))) return;
            if (done.get()) return;
            try {
                String key = ctx.operationId() + ":extend-lock:" + ctx.lockVersion() + ":" + heartbeatSeq.incrementAndGet();
                ExtendLockResult r = client.work().extendLock(ctx.workId(),
                        new ExtendLockRequest(workerId, ctx.lockVersion(), ctx.attemptId(), lease), key);
                failures = 0;
                if (r != null) {
                    if (r.lockExpiresAt() != null) ctx.lockExpiresAt.set(r.lockExpiresAt());
                    if (r.lockVersion() != null) ctx.lockVersion.set(r.lockVersion());
                    if (r.cancelRequested() && !ctx.cancelRequested) {
                        ctx.cancelRequested = true;
                        ctx.logger().warn("work.cancel.requested", "server requested cancel", null, null);
                    }
                }
                ctx.logger().debug("work.heartbeat", "lease extended",
                        Map.of("lockExpiresAt", String.valueOf(ctx.lockExpiresAt())));
            } catch (StaleLeaseException | GoneException e) {
                loseLease(ctx, handler, e);
                return;
            } catch (SangamException e) {
                if (isInterrupt(e) && done.get()) return;
                failures++;
                Instant exp = ctx.lockExpiresAt();
                if (exp != null && clock.instant().isAfter(exp)) {
                    loseLease(ctx, handler, e);
                    return;
                }
                ctx.logger().warn("work.heartbeat.failed", "heartbeat failed; will retry before lease expiry",
                        Map.of("failures", failures), e);
            }
        }
    }

    private void loseLease(TaskContext ctx, Thread handler, Throwable cause) {
        ctx.leaseLost = true;
        ctx.logger().warn("work.lease.lost", "lease lost; interrupting handler", null, cause);
        handler.interrupt();
    }

    // ------------------------------------------------------------------ reporting

    private void report(TaskContext ctx, TopicSubscription sub, Outcome o) {
        String action = o.kind;
        Runnable call = switch (action) {
            case "complete" -> () -> client.work().complete(ctx.workId(),
                    new CompleteWorkRequest(workerId, ctx.lockVersion(), ctx.attemptId(), o.variables, null,
                            ctx.outcomeReference() == null ? null : new org.sangam.sdk.model.WorkOutcome(ctx.outcomeReference(), null)), key(ctx, action));
            case "bpmn-error" -> {
                BpmnError be = (BpmnError) o.error;
                yield () -> client.work().bpmnError(ctx.workId(), new BpmnErrorRequest(workerId, ctx.lockVersion(),
                        ctx.attemptId(), be.errorCode(), be.getMessage(), be.variables()), key(ctx, action));
            }
            case "outcome-unknown" -> {
                String reason = o.error instanceof OutcomeUnknownException ? o.error.getMessage()
                        : "unexpected error on irreversible topic: " + o.error;
                String details = o.error instanceof OutcomeUnknownException ou && ou.details() != null
                        ? ou.details() : stackTrace(o.error);
                String detail = details == null ? reason : reason + System.lineSeparator() + details;
                if (detail.length() > 4000) detail = detail.substring(0, 4000);
                String d = detail;
                yield () -> client.work().outcomeUnknown(ctx.workId(), new OutcomeUnknownRequest(workerId,
                        ctx.lockVersion(), ctx.attemptId(), d, o.variables), key(ctx, action));
            }
            case "fail" -> {
                FailWorkRequest req = failRequest(ctx, sub, o.error);
                yield () -> client.work().fail(ctx.workId(), req, key(ctx, action));
            }
            case "cancel-ack" -> () -> client.work().cancelAck(ctx.workId(),
                    new UnlockRequest(workerId, ctx.lockVersion(), ctx.attemptId()), key(ctx, action));
            default -> () -> client.work().unlock(ctx.workId(),
                    new UnlockRequest(workerId, ctx.lockVersion(), ctx.attemptId()), key(ctx, "unlock"));
        };
        int attempt = 0;
        while (true) {
            attempt++;
            try {
                call.run();
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("attempts", attempt);
                if (o.error != null) f.put("error", String.valueOf(o.error.getMessage()));
                ctx.logger().info("work." + action, "reported " + action, f);
                return;
            } catch (StaleLeaseException | GoneException e) {
                ctx.leaseLost = true;
                ctx.logger().warn("work.abandoned", action + " rejected: lease no longer ours", null, e);
                return;
            } catch (SangamApiException e) {
                if (!e.isRetryable() || !retryAllowed(ctx)) {
                    ctx.logger().error("work.report.failed", action + " rejected by server", Map.of("status", e.status()), e);
                    return;
                }
                sleepQuiet(reportBackoff.delay(attempt));
            } catch (TransportException e) {
                if (!retryAllowed(ctx)) {
                    ctx.logger().error("work.report.gave-up",
                            action + " not delivered before lease expiry; the lease will expire and the same operationId is reused",
                            Map.of("attempts", attempt), e);
                    return;
                }
                sleepQuiet(reportBackoff.delay(attempt));
            }
        }
    }

    private boolean retryAllowed(TaskContext ctx) {
        Instant exp = ctx.lockExpiresAt();
        return exp == null || clock.instant().isBefore(exp);
    }

    private FailWorkRequest failRequest(TaskContext ctx, TopicSubscription sub, Throwable error) {
        int base = ctx.work().retries() != null ? ctx.work().retries() : sub.retries();
        int remaining = Math.max(0, base - 1);
        Duration timeout = sub.retryBackoff().delay(Math.max(1, sub.retries() - remaining));
        String details = stackTrace(error);
        if (error instanceof TaskFailedException tf) {
            if (tf.retries() != null) remaining = Math.max(0, tf.retries());
            if (tf.retryTimeout() != null) timeout = tf.retryTimeout();
            if (tf.details() != null) details = tf.details();
        }
        String msg = error == null ? "failed" : String.valueOf(error.getMessage() != null ? error.getMessage() : error.toString());
        if (msg.length() > 1000) msg = msg.substring(0, 1000);
        return new FailWorkRequest(workerId, ctx.lockVersion(), ctx.attemptId(), msg, details, remaining, timeout.toMillis());
    }

    private static String key(TaskContext ctx, String action) {
        return ctx.operationId() + ":" + action + ":" + ctx.lockVersion();
    }

    private void unlockQuietly(ExternalWork w) {
        try {
            String op = w.operationId() != null ? w.operationId() : w.id();
            client.work().unlock(w.id(), new UnlockRequest(workerId, w.lockVersion(), w.attemptId()),
                    op + ":unlock:" + w.lockVersion());
        } catch (RuntimeException e) {
            log.warn("work.unlock.failed", "unlock failed; lease will expire", Map.of("workerId", workerId, "workId", w.id()), e);
        }
    }

    private static String stackTrace(Throwable t) {
        if (t == null) return null;
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        String s = sw.toString();
        return s.length() > 4000 ? s.substring(0, 4000) : s;
    }

    private static boolean sleep(Duration d) {
        try {
            Thread.sleep(Math.max(0, d.toMillis()));
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void sleepQuiet(Duration d) {
        try {
            Thread.sleep(Math.max(0, d.toMillis()));
        } catch (InterruptedException e) {
            // a shutdown interrupt must not drop the report; keep the flag and continue
            Thread.currentThread().interrupt();
        }
    }

    private static String defaultWorkerId() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "worker";
        }
        return host + "-" + ProcessHandle.current().pid() + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    public static final class Builder {
        private final SangamClient client;
        private String workerId;
        private int maxConcurrency = 8;
        private int maxTasksPerFetch = 8;
        private Duration asyncResponseTimeout = Duration.ofSeconds(30);
        private double heartbeatFraction = 1.0 / 3.0;
        private Backoff fetchBackoff = Backoff.fetchDefaults();
        private Backoff reportBackoff = new Backoff(Duration.ofMillis(500), Duration.ofSeconds(15), 2.0, 0.2);
        private Duration shutdownTimeout = Duration.ofSeconds(30);
        private Boolean usePriority;
        private SangamLogger logger;
        private Clock clock = Clock.systemUTC();
        private final List<TopicSubscription> subscriptions = new ArrayList<>();

        Builder(SangamClient client) {
            this.client = client;
        }

        public Builder workerId(String v) { this.workerId = v; return this; }
        /** Maximum handlers running at once (default 8). */
        public Builder maxConcurrency(int v) {
            if (v < 1) throw new IllegalArgumentException("maxConcurrency must be >= 1");
            this.maxConcurrency = v; return this;
        }
        public Builder maxTasksPerFetch(int v) {
            if (v < 1) throw new IllegalArgumentException("maxTasksPerFetch must be >= 1");
            this.maxTasksPerFetch = v; return this;
        }
        /** Long-poll wait on the server (default 30 s). */
        public Builder asyncResponseTimeout(Duration v) { this.asyncResponseTimeout = v; return this; }
        /** Heartbeat interval as a fraction of the lease (VI.3: 1/3). */
        public Builder heartbeatFraction(double v) {
            if (v <= 0 || v >= 1) throw new IllegalArgumentException("heartbeatFraction must be in (0,1)");
            this.heartbeatFraction = v; return this;
        }
        public Builder fetchBackoff(Backoff v) { this.fetchBackoff = v; return this; }
        public Builder reportBackoff(Backoff v) { this.reportBackoff = v; return this; }
        public Builder shutdownTimeout(Duration v) { this.shutdownTimeout = v; return this; }
        /** Ask the server to hand out higher-priority work first. */
        public Builder usePriority(boolean v) { this.usePriority = v; return this; }
        public Builder logger(SangamLogger v) { this.logger = v; return this; }
        public Builder clock(Clock v) { this.clock = v; return this; }
        public Builder subscribe(TopicSubscription s) { subscriptions.add(s); return this; }
        public Builder subscribe(String topic, TaskHandler h) { return subscribe(TopicSubscription.topic(topic, h).build()); }

        public SangamWorker build() {
            return new SangamWorker(this);
        }
    }
}
