// SPDX-License-Identifier: Apache-2.0
package org.sangam.assembly.integration;

import org.sangam.core.api.ReadWork;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.sangam.assembly.host.HostScheduler;
import org.sangam.assembly.host.Lifecycle;
import org.sangam.assembly.host.SangamProperties;
import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.PendingAudit;
import org.sangam.core.layer.PendingAuditDrainer;
import org.sangam.core.layer.Transactions;

/**
 * Owner decision 315: the assembly's own sweeps (pending audit, idempotency cleanup) run on the host's scheduler with
 * the Spring host's keys and defaults; the drainer gets no sweep thread of its own; a failing sweep is retried.
 */
class IntegrationSweepsTest {

    /** A host scheduler that records what is scheduled and runs it only when the test says so. */
    private static final class RecordingScheduler implements HostScheduler {
        record Task(String name, Duration initialDelay, Duration delay, Runnable run, boolean[] cancelled) {
        }

        final List<Task> tasks = new ArrayList<>();

        @Override
        public Scheduled scheduleWithFixedDelay(String name, Duration initialDelay, Duration delay, Runnable task) {
            Task t = new Task(name, initialDelay, delay, task, new boolean[1]);
            tasks.add(t);
            return () -> t.cancelled()[0] = true;
        }
    }

    /** Counts the product transactions; the work never runs (no database), so a sweep finds nothing or fails. */
    private static final class CountingTx implements Transactions {
        final AtomicInteger opened = new AtomicInteger();
        boolean fail;

        @Override
        @SuppressWarnings("unchecked")
        public <T> T inTransaction(Function<Connection, T> work) {
            opened.incrementAndGet();
            if (fail) {
                throw new IllegalStateException("database unavailable");
            }
            return (T) List.of();
        }

        public <T> T read(ReadWork<T> work) {
            opened.incrementAndGet();
            if (fail) {
                throw new IllegalStateException("database unavailable");
            }
            return (T) List.of();
        }
    }

    private static PendingAudit pending(Transactions tx) {
        return new PendingAudit(tx, Dialect.POSTGRESQL, new AuditChain(Dialect.POSTGRESQL));
    }

    @Test
    void thePendingAuditSettingsAreTheSpringHostsKeysAndDefaults() {
        PendingAuditDrainer.Settings d = IntegrationSweeps.pendingAuditSettings(SangamProperties.empty());
        assertEquals(PendingAuditDrainer.Settings.defaults(), d);
        PendingAuditDrainer.Settings s = IntegrationSweeps.pendingAuditSettings(SangamProperties.of(Map.of(
                "sangam.audit.pending.workers", "5", "sangam.audit.pending.sweep-interval", "45s",
                "sangam.audit.pending.sweep-min-age", "PT20S", "sangam.audit.pending.sweep-batch", "50")));
        assertEquals(new PendingAuditDrainer.Settings(5, Duration.ofSeconds(45), Duration.ofSeconds(20), 50), s);
        assertThrows(IllegalArgumentException.class, () -> IntegrationSweeps.pendingAuditSettings(
                SangamProperties.of(Map.of("sangam.audit.pending.sweep-interval", "-1s"))));
    }

    @Test
    void thePendingAuditSweepRunsOnTheHostSchedulerAtTheConfiguredInterval() {
        RecordingScheduler scheduler = new RecordingScheduler();
        CountingTx tx = new CountingTx();
        IntegrationSweeps.PendingAuditSweep sweep = IntegrationSweeps.pendingAudit(pending(tx),
                SangamProperties.of(Map.of("sangam.audit.pending.sweep-interval", "45s")), scheduler);
        assertTrue(scheduler.tasks.isEmpty(), "nothing is scheduled before start");
        sweep.start();
        sweep.start(); // idempotent
        assertEquals(1, scheduler.tasks.size());
        RecordingScheduler.Task t = scheduler.tasks.get(0);
        assertEquals(IntegrationSweeps.PENDING_AUDIT_SWEEP, t.name());
        assertEquals(Duration.ofSeconds(45), t.initialDelay());
        assertEquals(Duration.ofSeconds(45), t.delay());
        t.run().run();
        assertEquals(1, tx.opened.get(), "one run of the task is one sweep of the drainer");
        sweep.stop(Duration.ZERO);
        assertTrue(t.cancelled()[0]);
    }

    @Test
    void aFailingSweepNeverLeavesTheTask() {
        RecordingScheduler scheduler = new RecordingScheduler();
        CountingTx tx = new CountingTx();
        tx.fail = true;
        IntegrationSweeps.PendingAuditSweep sweep = IntegrationSweeps.pendingAudit(pending(tx),
                SangamProperties.empty(), scheduler);
        sweep.start();
        scheduler.tasks.get(0).run().run(); // must not throw: a host scheduler may drop a task that throws
        scheduler.tasks.get(0).run().run();
        assertEquals(2, tx.opened.get());
        sweep.stop(Duration.ZERO);
    }

    @Test
    void aZeroIntervalMeansNoSweep() {
        RecordingScheduler scheduler = new RecordingScheduler();
        IntegrationSweeps.PendingAuditSweep sweep = IntegrationSweeps.pendingAudit(pending(new CountingTx()),
                SangamProperties.of(Map.of("sangam.audit.pending.sweep-interval", "0")), scheduler);
        sweep.start();
        assertTrue(scheduler.tasks.isEmpty());
        assertTrue(sweep.interval().isZero());
        sweep.stop(Duration.ZERO);
    }

    @Test
    void thePendingAuditSweepStartsBeforeAndStopsAfterTheIntegrationServices() {
        IntegrationSweeps.PendingAuditSweep sweep = IntegrationSweeps.pendingAudit(pending(new CountingTx()),
                SangamProperties.empty(), new RecordingScheduler());
        assertTrue(sweep.phase() < IntegrationLifecycles.PHASE);
        sweep.stop(Duration.ZERO);
    }

    @Test
    void theCleanupPeriodAndLeadershipAreTheSpringHosts() {
        assertEquals(60, IntegrationSweeps.cleanupMinutes(SangamProperties.empty()));
        assertEquals(15, IntegrationSweeps.cleanupMinutes(
                SangamProperties.of(Map.of("sangam.rest.idempotency-cleanup-minutes", "15"))));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> IntegrationSweeps
                .cleanupMinutes(SangamProperties.of(Map.of("sangam.rest.idempotency-cleanup-minutes", "0"))));
        assertTrue(e.getMessage().contains("sangam.rest.idempotency-cleanup-minutes"), e.getMessage());
        assertEquals(Duration.ofMinutes(3), IntegrationSweeps.leaderTtl(1));
        assertEquals(Duration.ofMinutes(120), IntegrationSweeps.leaderTtl(60));
        assertEquals("sangam-idempotency-cleanup", IntegrationSweeps.IDEMPOTENCY_CLEANUP);
    }

    @Test
    void theCleanupRunsOnTheHostSchedulerOnTheLeaderOnly() {
        RecordingScheduler scheduler = new RecordingScheduler();
        long[] epoch = {-1};
        AtomicInteger cleaned = new AtomicInteger();
        Lifecycle cleanup = IntegrationSweeps.idempotencyCleanup(scheduler, 15, () -> epoch[0], () -> {
            cleaned.incrementAndGet();
            return 7;
        });
        assertTrue(cleanup.phase() > IntegrationLifecycles.Service.WORKER.phase());
        cleanup.start();
        cleanup.start(); // idempotent
        assertEquals(1, scheduler.tasks.size());
        RecordingScheduler.Task t = scheduler.tasks.get(0);
        assertEquals(IntegrationSweeps.IDEMPOTENCY_CLEANUP, t.name());
        assertEquals(Duration.ofMinutes(5), t.initialDelay());
        assertEquals(Duration.ofMinutes(15), t.delay());
        t.run().run();
        assertEquals(0, cleaned.get(), "not the leader: nothing is cleaned");
        epoch[0] = 4;
        t.run().run();
        assertEquals(1, cleaned.get());
        assertFalse(t.cancelled()[0]);
        cleanup.stop(Duration.ZERO);
        assertTrue(t.cancelled()[0]);
    }

    @Test
    void aFailingCleanupNeverLeavesTheTask() {
        RecordingScheduler scheduler = new RecordingScheduler();
        Lifecycle cleanup = IntegrationSweeps.idempotencyCleanup(scheduler, 15, () -> {
            throw new IllegalStateException("database unavailable");
        }, () -> 0);
        cleanup.start();
        scheduler.tasks.get(0).run().run(); // must not throw
        cleanup.stop(Duration.ZERO);
    }
}
