// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.sangam.integration.relay.LeaderElection;
import org.sangam.resources.ResourcesSweepers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Leader-elected run of the resources sweepers (resources README "Scheduled sweepers"): one tick a minute renews the
 * {@code INTEGRATION_LEADER} lease {@value #LEADER}; the leader runs {@code sweepers.runAll(tenant)} for every tenant
 * that has resources data plus the configured tenants, every {@code interval}. Each sweeper also takes its own
 * {@code (tenant, sweeper)} lease in {@code RES_SWEEP_LEASE} with a fencing counter, so a leader change during a
 * sweep cannot double-apply it; every sweep is idempotent.
 */
public final class ResourcesSweepScheduler implements SmartLifecycle {

    public static final String LEADER = "sangam-resources-sweepers";
    private static final Logger LOG = LoggerFactory.getLogger(ResourcesSweepScheduler.class);

    private final ResourcesSweepers sweepers;
    private final LeaderElection leader;
    private final Supplier<List<String>> configuredTenants;
    private final Duration interval;
    private ScheduledExecutorService executor;
    private volatile boolean running;
    private volatile Instant next = Instant.EPOCH;

    public ResourcesSweepScheduler(ResourcesSweepers sweepers, LeaderElection leader,
                                   Supplier<List<String>> configuredTenants, Duration interval) {
        this.sweepers = sweepers;
        this.leader = leader;
        this.configuredTenants = configuredTenants;
        this.interval = interval;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sangam-resources-sweepers");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleWithFixedDelay(this::tick, 45, 60, TimeUnit.SECONDS);
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
            if (now.isBefore(next)) {
                return;
            }
            next = now.plus(interval);
            Set<String> tenants = new LinkedHashSet<>(sweepers.tenants());
            tenants.addAll(configuredTenants.get());
            for (String t : tenants) {
                try {
                    for (ResourcesSweepers.SweepResult r : sweepers.runAll(t)) {
                        if (r.ran() && r.count() > 0) {
                            LOG.info("resources sweeper {} for tenant {}: {} rows", r.sweeper(), t, r.count());
                        }
                    }
                } catch (RuntimeException e) {
                    LOG.warn("resources sweepers failed for tenant {}: {}", t, e.toString());
                }
            }
        } catch (RuntimeException e) {
            LOG.warn("resources sweep tick failed: {}", e.toString());
        }
    }
}
