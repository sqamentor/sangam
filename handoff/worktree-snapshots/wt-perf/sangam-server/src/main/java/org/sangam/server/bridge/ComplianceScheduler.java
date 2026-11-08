// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.sangam.compliance.SangamCompliance;
import org.sangam.compliance.logs.LogArchiveService;
import org.sangam.integration.relay.LeaderElection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Leader-elected compliance schedule (compliance README §3), on the integration {@link LeaderElection}
 * ({@code INTEGRATION_LEADER}, lease {@value #LEADER}). One tick a minute renews the lease; only the leader runs the
 * tasks that are due:
 * <ul>
 *   <li>hourly: {@code retention.runCycle(tenant, batch)} (pre-erasure notices, due erasures) and {@code ntp.check};</li>
 *   <li>every 5 minutes: {@code rights.escalateOverdue}, {@code breaches.overdue}, {@code incidents.overdue};</li>
 *   <li>daily: {@code logs.seal} (from the last sealed segment to the start of today, UTC), then {@code purgeExpired};
 *       {@code integrity.verifyTenant}.</li>
 * </ul>
 * Every tenant runs in its own transaction; a failure of one tenant or task is logged and does not stop the others.
 * The tasks are idempotent, so a leader change that repeats a run is harmless.
 */
public final class ComplianceScheduler implements SmartLifecycle {

    public static final String LEADER = "sangam-compliance-scheduler";
    private static final Logger LOG = LoggerFactory.getLogger(ComplianceScheduler.class);

    private final SangamCompliance compliance;
    private final LeaderElection leader;
    private final Supplier<List<String>> tenants;
    private final int retentionBatch;
    private final int maxChains;
    private final Map<String, Instant> nextRun = new LinkedHashMap<>();
    private ScheduledExecutorService executor;
    private volatile boolean running;

    public ComplianceScheduler(SangamCompliance compliance, LeaderElection leader, Supplier<List<String>> tenants,
                               int retentionBatch, int maxChains) {
        this.compliance = compliance;
        this.leader = leader;
        this.tenants = tenants;
        this.retentionBatch = retentionBatch;
        this.maxChains = maxChains;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        Instant now = Instant.now();
        // First runs shortly after start-up, staggered.
        nextRun.put("deadlines", now.plus(Duration.ofMinutes(1)));
        nextRun.put("retention", now.plus(Duration.ofMinutes(2)));
        nextRun.put("ntp", now.plus(Duration.ofMinutes(3)));
        nextRun.put("logs", now.plus(Duration.ofMinutes(10)));
        nextRun.put("integrity", now.plus(Duration.ofMinutes(15)));
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sangam-compliance-scheduler");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleWithFixedDelay(this::tick, 30, 60, TimeUnit.SECONDS);
        running = true;
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (executor != null) {
            executor.shutdownNow();
        }
        try {
            leader.release(LEADER);
        } catch (RuntimeException e) {
            LOG.debug("leader release failed: {}", e.toString());
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        // After the integration services (MAX_VALUE - 100), before web traffic stops.
        return Integer.MAX_VALUE - 90;
    }

    void tick() {
        try {
            if (leader.tryAcquire(LEADER) < 0) {
                return;
            }
            Instant now = Instant.now();
            List<String> ts = tenants.get();
            due("deadlines", now, Duration.ofMinutes(5), () -> forEach(ts, "deadlines", t -> compliance.tx.inTransaction(c -> {
                compliance.rights.escalateOverdue(c, t);
                compliance.breaches.overdue(c, t);
                compliance.incidents.overdue(c, t);
                return null;
            })));
            due("retention", now, Duration.ofHours(1), () -> forEach(ts, "retention",
                    t -> compliance.retention.runCycle(t, retentionBatch)));
            due("ntp", now, Duration.ofHours(1), () -> forEach(ts, "ntp",
                    t -> compliance.tx.inTransaction(c -> compliance.ntp.check(c, t))));
            due("logs", now, Duration.ofDays(1), () -> forEach(ts, "logs", this::sealAndPurge));
            due("integrity", now, Duration.ofDays(1), () -> forEach(ts, "integrity",
                    t -> compliance.tx.inTransaction(c -> compliance.integrity.verifyTenant(c, t, maxChains))));
        } catch (RuntimeException e) {
            LOG.warn("compliance schedule tick failed: {}", e.toString());
        }
    }

    private void sealAndPurge(String tenant) {
        compliance.tx.inTransaction(c -> {
            Instant to = Instant.now().truncatedTo(ChronoUnit.DAYS);
            Instant from = compliance.logs.latest(c, tenant).map(LogArchiveService.Segment::to)
                    .orElse(to.minus(Duration.ofDays(1)));
            if (from.isBefore(to)) {
                compliance.logs.seal(c, tenant, from, to, "system:retention");
            }
            return null;
        });
        compliance.tx.inTransaction(c -> compliance.logs.purgeExpired(c, tenant, "system:retention"));
    }

    private void due(String task, Instant now, Duration every, Runnable work) {
        Instant next = nextRun.get(task);
        if (next != null && now.isBefore(next)) {
            return;
        }
        nextRun.put(task, now.plus(every));
        work.run();
    }

    private static void forEach(List<String> tenants, String task, Consumer<String> work) {
        for (String t : tenants) {
            try {
                work.accept(t);
            } catch (RuntimeException e) {
                LOG.warn("compliance task {} failed for tenant {}: {}", task, t, e.toString());
            }
        }
    }
}
