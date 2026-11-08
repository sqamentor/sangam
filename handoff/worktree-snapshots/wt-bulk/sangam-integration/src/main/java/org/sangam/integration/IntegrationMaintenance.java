// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration;

import org.sangam.integration.idempotency.IdempotencyService;
import org.sangam.integration.lifecycle.InstanceLifecycleService;
import org.sangam.integration.relay.LeaderElection;
import org.sangam.integration.relay.OutboxCapacityMonitor;
import org.sangam.integration.relay.OutboxRetention;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Leader-elected maintenance of the integration layer (R29, VI.2.4–VI.2.5, X.2.2.4 step 5, VI.5 rule 4):
 * <ul>
 *   <li>every node: outbox capacity sampling (the {@code BackpressureGate} of each node reads its own sample);</li>
 *   <li>leader only: outbox retention, idempotency cleanup (compact / delete), tombstone sweep and purge.</li>
 * </ul>
 */
public final class IntegrationMaintenance implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(IntegrationMaintenance.class);
    public static final String LEADER_NAME = "sangam-integration-maintenance";

    private final LeaderElection leader;
    private final OutboxCapacityMonitor capacity;
    private final OutboxRetention retention;
    private final IdempotencyService idempotency;
    private final InstanceLifecycleService lifecycle;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "sangam-integration-maintenance");
        t.setDaemon(true);
        return t;
    });

    public IntegrationMaintenance(LeaderElection leader, OutboxCapacityMonitor capacityOrNull, OutboxRetention retentionOrNull,
                                  IdempotencyService idempotencyOrNull, InstanceLifecycleService lifecycleOrNull) {
        this.leader = leader;
        this.capacity = capacityOrNull;
        this.retention = retentionOrNull;
        this.idempotency = idempotencyOrNull;
        this.lifecycle = lifecycleOrNull;
    }

    public void start(Duration capacityEvery, Duration maintenanceEvery) {
        if (capacity != null) {
            scheduler.scheduleWithFixedDelay(() -> guard("capacity", capacity::sample), 0, capacityEvery.toMillis(), TimeUnit.MILLISECONDS);
        }
        scheduler.scheduleWithFixedDelay(this::leaderWork, maintenanceEvery.toMillis(), maintenanceEvery.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** One maintenance pass if this node is the leader. Returns false when another node leads. */
    public boolean leaderWork() {
        boolean lead;
        try {
            lead = leader.isLeader(LEADER_NAME);
        } catch (RuntimeException e) {
            LOG.warn("leader election failed", e);
            return false;
        }
        if (!lead) {
            return false;
        }
        if (retention != null) {
            guard("outbox retention", retention::run);
        }
        if (idempotency != null) {
            guard("idempotency cleanup", idempotency::cleanup);
        }
        if (lifecycle != null) {
            guard("tombstones", lifecycle::maintain);
        }
        return true;
    }

    private static void guard(String what, Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            LOG.warn("{} failed", what, e);
        }
    }

    private static void guard(String what, java.util.function.Supplier<?> s) {
        guard(what, (Runnable) s::get);
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
