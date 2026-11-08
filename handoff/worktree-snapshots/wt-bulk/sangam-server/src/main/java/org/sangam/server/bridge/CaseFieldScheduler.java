// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.sangam.integration.relay.LeaderElection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Leader-elected periodic jobs of a module (the {@code ComplianceScheduler} pattern) on the integration
 * {@link LeaderElection} ({@code INTEGRATION_LEADER}, one lease per module). A tick every {@code tick} renews the lease;
 * only the leader runs the tasks that are due. Per-tenant tasks run once per tenant, each in the task's own
 * transaction; a failure of one tenant or task is logged and does not stop the others. All tasks are idempotent, so a
 * leader change that repeats a run is harmless.
 *
 * <p>Used by sangam-case ({@code DeadlineService.sweep}) and sangam-field ({@code ScheduleService.runDue},
 * {@code AssignmentService.sweepOverdue}, {@code DispatchService.expireOffers}).
 */
public final class CaseFieldScheduler implements SmartLifecycle {

    /** A job: {@code perTenant} tasks receive each tenant id; global tasks receive {@code null}. */
    public record Task(String name, Duration every, boolean perTenant, Consumer<String> action) {
    }

    private static final Logger LOG = LoggerFactory.getLogger(CaseFieldScheduler.class);

    private final String leaseName;
    private final LeaderElection leader;
    private final Supplier<List<String>> tenants;
    private final List<Task> tasks;
    private final Duration tick;
    private final Map<String, Instant> nextRun = new LinkedHashMap<>();
    private ScheduledExecutorService executor;
    private volatile boolean running;

    public CaseFieldScheduler(String leaseName, LeaderElection leader, Supplier<List<String>> tenants, List<Task> tasks,
                              Duration tick) {
        this.leaseName = leaseName;
        this.leader = leader;
        this.tenants = tenants;
        this.tasks = List.copyOf(tasks);
        this.tick = tick;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        Instant now = Instant.now();
        long stagger = 0;
        for (Task t : tasks) {
            nextRun.put(t.name(), now.plusSeconds(30 + 20 * stagger++));
        }
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, leaseName);
            t.setDaemon(true);
            return t;
        });
        long period = Math.max(5, tick.toSeconds());
        executor.scheduleWithFixedDelay(this::tick, period, period, TimeUnit.SECONDS);
        running = true;
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (executor != null) {
            executor.shutdownNow();
        }
        try {
            leader.release(leaseName);
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
        // After the integration services (MAX_VALUE - 100), like the compliance schedule.
        return Integer.MAX_VALUE - 90;
    }

    void tick() {
        try {
            if (leader.tryAcquire(leaseName) < 0) {
                return;
            }
            Instant now = Instant.now();
            List<String> ts = null;
            for (Task t : tasks) {
                Instant due = nextRun.get(t.name());
                if (due != null && now.isBefore(due)) {
                    continue;
                }
                nextRun.put(t.name(), now.plus(t.every()));
                if (!t.perTenant()) {
                    run(t, null);
                    continue;
                }
                if (ts == null) {
                    ts = tenants.get();
                }
                for (String tenant : ts) {
                    run(t, tenant);
                }
            }
        } catch (RuntimeException e) {
            LOG.warn("{} tick failed: {}", leaseName, e.toString());
        }
    }

    private void run(Task t, String tenant) {
        try {
            t.action().accept(tenant);
        } catch (RuntimeException e) {
            LOG.warn("{} task {} failed{}: {}", leaseName, t.name(), tenant == null ? "" : " for tenant " + tenant,
                    e.toString());
        }
    }
}
