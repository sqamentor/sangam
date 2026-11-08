// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.relay;

import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Relay;
import org.sangam.core.layer.Transactions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Production outbox relay (SANGAM.md VI.2.3, R1, R4, A-5) on top of the proven {@link Relay} primitive.
 *
 * <ul>
 *   <li><b>Multi-node safe.</b> Claims use skip-locked head-of-line selection with {@code claim_epoch} fencing (core
 *       {@link Relay#claim}); any number of nodes run this service against the same database. With DB-per-tenant,
 *       one service instance runs per tenant database (A-5); nothing spans databases.</li>
 *   <li><b>Scheduled.</b> A loop per service: claim → publish outside any transaction → mark. A full batch triggers
 *       the next cycle at once; otherwise the loop waits {@code pollInterval} (default 1 s; R1 authoritative poll). The
 *       expired-claim sweeper runs every {@code sweepInterval}.</li>
 *   <li><b>Parallel publish.</b> The deliveries of one claim are published concurrently (bounded by
 *       {@code publishConcurrency}); this is safe because one claim holds at most the head of each ordered line.</li>
 *   <li><b>Poison handling.</b> {@link PermanentPublishException} parks (ordered) or skips (unordered) the delivery
 *       at once with an audit entry and a dead letter; ordinary failures back off exponentially and are parked/skipped
 *       after {@code maxAttempts} (default 10), also dead-lettered.</li>
 *   <li><b>Destination back-pressure.</b> A per-destination {@link CircuitBreaker}: while a destination is open its
 *       deliveries are deferred without consuming attempts.</li>
 *   <li><b>Wake-up.</b> One payload-free {@link WakeupNotifier} call per cycle that published (R1).</li>
 * </ul>
 */
public final class OutboxRelayService implements AutoCloseable, org.sangam.core.layer.OutboxRepair {

    private static final Logger LOG = LoggerFactory.getLogger(OutboxRelayService.class);

    public record Settings(Relay.Config relay, Duration pollInterval, Duration sweepInterval, int publishConcurrency) {
        public static Settings defaults(String node) {
            return new Settings(Relay.Config.defaults(node), Duration.ofSeconds(1), Duration.ofSeconds(15), 16);
        }
    }

    /** Counters for the operator view and metrics ({@code /ops/outbox}). */
    public record Stats(long cycles, long claimed, long published, long failed, long poisoned, long fencedOut, long deferred,
                        Map<String, Integer> openBreakers) {
    }

    private final Dialect dialect;
    private final Transactions tx;
    private final PublisherRegistry publishers;
    private final Settings settings;
    private final Relay relay;
    private final DeadLetterStore deadLetters;
    private final CircuitBreaker breaker;
    private final WakeupNotifier wakeup;
    private final ScheduledExecutorService loop;
    private final ExecutorService publishPool;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicLong cycles = new AtomicLong();
    private final AtomicLong claimed = new AtomicLong();
    private final AtomicLong published = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong poisoned = new AtomicLong();
    private final AtomicLong fenced = new AtomicLong();
    private final AtomicLong deferred = new AtomicLong();
    private volatile long lastSweep;

    public OutboxRelayService(Dialect dialect, Transactions tx, PublisherRegistry publishers, Settings settings,
                              AuditChain auditOrNull, DeadLetterStore deadLetters, CircuitBreaker breaker, WakeupNotifier wakeup) {
        this.dialect = dialect;
        this.tx = tx;
        this.publishers = publishers;
        this.settings = settings;
        this.deadLetters = deadLetters;
        // the dead letter of a repaired delivery is closed in the repair's own transaction (atomic with the state change)
        this.relay = new Relay(dialect, tx, publishers, settings.relay(), auditOrNull,
                deadLetters == null ? null : (c, ev, destination, target, actor, reason) -> closeDeadLetter(c,
                        ev.outboxId(), destination, "PENDING".equals(target) ? DeadLetterStore.State.REQUEUED
                                : DeadLetterStore.State.DISCARDED, actor, reason));
        this.breaker = breaker == null ? CircuitBreaker.defaults() : breaker;
        this.wakeup = wakeup == null ? WakeupNotifier.NONE : wakeup;
        this.loop = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sangam-outbox-relay-" + settings.relay().node());
            t.setDaemon(true);
            return t;
        });
        this.publishPool = Executors.newVirtualThreadPerTaskExecutor();
    }

    public Relay relay() {
        return relay;
    }

    /** Starts the relay loop. Idempotent. */
    public void start() {
        if (running.compareAndSet(false, true)) {
            loop.execute(this::loopOnce);
        }
    }

    private void loopOnce() {
        if (!running.get()) {
            return;
        }
        long delay = settings.pollInterval().toMillis();
        try {
            if (System.currentTimeMillis() - lastSweep >= settings.sweepInterval().toMillis()) {
                int swept = relay.sweep();
                if (swept > 0) {
                    LOG.info("outbox sweeper reset {} expired claims", swept);
                }
                lastSweep = System.currentTimeMillis();
            }
            int n = cycle();
            if (n >= settings.relay().batchSize()) {
                delay = 0;
            }
        } catch (RuntimeException e) {
            LOG.warn("outbox relay cycle failed; retrying after the poll interval", e);
        }
        if (running.get()) {
            loop.schedule(this::loopOnce, delay, TimeUnit.MILLISECONDS);
        }
    }

    /** One claim → publish → mark cycle. Returns the number of deliveries claimed. */
    public int cycle() {
        List<Relay.Claimed> batch = relay.claim();
        cycles.incrementAndGet();
        claimed.addAndGet(batch.size());
        if (batch.isEmpty()) {
            return 0;
        }
        List<Future<Boolean>> futures = new ArrayList<>(batch.size());
        java.util.concurrent.Semaphore permits = new java.util.concurrent.Semaphore(Math.max(1, settings.publishConcurrency()));
        for (Relay.Claimed cl : batch) {
            futures.add(publishPool.submit(() -> {
                permits.acquire();
                try {
                    return deliver(cl);
                } finally {
                    permits.release();
                }
            }));
        }
        int ok = 0;
        for (Future<Boolean> f : futures) {
            try {
                if (Boolean.TRUE.equals(f.get())) {
                    ok++;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (java.util.concurrent.ExecutionException e) {
                LOG.warn("delivery task failed", e.getCause());
            }
        }
        if (ok > 0) {
            wakeup.cycleCompleted(ok);
        }
        return batch.size();
    }

    /** Publishes one claimed delivery; true when it was published and marked DONE. */
    boolean deliver(Relay.Claimed cl) {
        String dest = cl.destination();
        if (!breaker.allow(dest)) {
            defer(cl, Math.max(1000, breaker.remainingOpenMillis(dest)));
            return false;
        }
        try {
            publishers.publish(dest, cl.event());
        } catch (PermanentPublishException e) {
            breaker.success(dest);   // the destination answered; the event is the problem
            poison(cl, "POISON: " + e.getMessage());
            return false;
        } catch (Exception e) {
            breaker.failure(dest);
            failed.incrementAndGet();
            String err = e.getClass().getSimpleName() + ": " + e.getMessage();
            boolean exhausted = cl.attempts() + 1 >= settings.relay().maxAttempts();
            if (!relay.markFailed(cl, err)) {
                fenced.incrementAndGet();
            } else if (exhausted) {
                deadLetter(cl, err);
            }
            return false;
        }
        breaker.success(dest);
        published.incrementAndGet();
        if (!relay.markDone(cl)) {
            fenced.incrementAndGet();   // lost the claim: the publish may be duplicated (allowed, consumers dedup on event_id)
        }
        return true;
    }

    private void poison(Relay.Claimed cl, String error) {
        poisoned.incrementAndGet();
        // Re-use the core fencing + audit path: present the claim as being on its last attempt.
        Relay.Claimed last = new Relay.Claimed(cl.event(), cl.destination(), cl.epoch(),
                Math.max(cl.attempts(), settings.relay().maxAttempts() - 1), cl.ordered());
        if (relay.markFailed(last, error)) {
            deadLetter(cl, error);
        } else {
            fenced.incrementAndGet();
        }
    }

    private void deadLetter(Relay.Claimed cl, String error) {
        if (deadLetters == null) {
            return;
        }
        try {
            deadLetters.record(cl.event().tenantId(), DeadLetterStore.Kind.OUTBOX, cl.event().outboxId() + ":" + cl.destination(),
                    cl.destination(), cl.event().rootId(), cl.event().eventType(), cl.event().payload(), error);
        } catch (RuntimeException e) {
            LOG.warn("cannot record dead letter for outbox {} / {}", cl.event().outboxId(), cl.destination(), e);
        }
    }

    /** Releases a claim back to PENDING later without consuming an attempt (fenced by the claim epoch). */
    private void defer(Relay.Claimed cl, long millis) {
        int n = tx.inTransaction(c -> Jdbc.update(c,
                "UPDATE OUTBOX_DELIVERY SET state='PENDING', claim_owner=NULL, claim_expires_at=NULL, next_attempt_at="
                        + dialect.nowPlusMillisParam() + " WHERE outbox_id=? AND destination=? AND claim_epoch=? AND state='CLAIMED'",
                millis, cl.event().outboxId(), cl.destination(), cl.epoch()));
        if (n == 1) {
            deferred.incrementAndGet();
        } else {
            fenced.incrementAndGet();
        }
    }

    /** Operator repair: requeue a PARKED delivery (audited) and close its dead letter as REQUEUED, atomically. */
    @Override
    public boolean requeue(long outboxId, String destination, String actor, String reason) {
        return relay.requeue(outboxId, destination, actor, reason);
    }

    /** Operator repair: skip a PARKED delivery (audited) and close its dead letter as DISCARDED, atomically. */
    @Override
    public boolean skip(long outboxId, String destination, String actor, String reason) {
        return relay.skip(outboxId, destination, actor, reason);
    }

    private void closeDeadLetter(java.sql.Connection c, long outboxId, String destination, DeadLetterStore.State state,
                                 String actor, String reason) {
        Jdbc.query(c, "SELECT id FROM INTEGRATION_DEAD_LETTER WHERE kind='OUTBOX' AND ref_id=? AND state='OPEN'",
                rs -> rs.getLong(1), outboxId + ":" + destination).forEach(id -> deadLetters.resolve(c, id, state, actor, reason));
    }

    public Stats stats() {
        return new Stats(cycles.get(), claimed.get(), published.get(), failed.get(), poisoned.get(), fenced.get(), deferred.get(),
                breaker.openDestinations());
    }

    @Override
    public void close() {
        running.set(false);
        loop.shutdown();
        publishPool.shutdown();
        try {
            loop.awaitTermination(5, TimeUnit.SECONDS);
            publishPool.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
