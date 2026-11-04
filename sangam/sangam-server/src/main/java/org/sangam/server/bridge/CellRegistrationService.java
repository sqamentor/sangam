// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.sangam.cell.registration.CellDescriptor;
import org.sangam.cell.registration.CellRegistrationAgent;
import org.sangam.cell.registration.ControlPlaneClient;
import org.sangam.integration.relay.LeaderElection;
import org.sangam.rest.support.DocumentStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * IR-2 cell registration on every node, heartbeat from one node per cell.
 * <ul>
 *   <li>Every node registers the cell once (idempotent PUT; retried in the background until it succeeds) and records
 *       its presence (REST_DOCUMENT kind {@code cell:node}, refreshed every tick), which gives {@code activeNodes}.</li>
 *   <li>The leader of {@value #LEADER_NAME} (integration {@link LeaderElection}, table INTEGRATION_LEADER) runs the
 *       {@link CellRegistrationAgent} with the load figures; leadership is re-checked every tick, and a node that loses
 *       it stops its agent, so only one node reports load for the cell.</li>
 *   <li>{@link #drain()} (shutdown) switches the status to DRAINING and sends one last heartbeat when leading.</li>
 * </ul>
 */
public final class CellRegistrationService implements AutoCloseable {

    public static final String LEADER_NAME = "sangam-cell-heartbeat";
    public static final String NODE_KIND = "cell:node";

    private static final Logger LOG = LoggerFactory.getLogger(CellRegistrationService.class);

    private final ControlPlaneClient client;
    private final CellDescriptor descriptor;
    private final CellLoadSampler sampler;
    private final LeaderElection leader;
    private final DocumentStore documents;
    private final String node;
    private final long intervalMs;
    private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "sangam-cell-registration");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean registered;
    private volatile CellRegistrationAgent agent;

    public CellRegistrationService(ControlPlaneClient client, CellDescriptor descriptor, CellLoadSampler sampler,
                                   LeaderElection leader, DocumentStore documents, String node, long intervalMs) {
        this.client = client;
        this.descriptor = descriptor;
        this.sampler = sampler;
        this.leader = leader;
        this.documents = documents;
        this.node = node;
        this.intervalMs = Math.max(1000, intervalMs);
    }

    public void start() {
        exec.scheduleWithFixedDelay(this::tick, 0, intervalMs, TimeUnit.MILLISECONDS);
    }

    /** Number of nodes seen within three heartbeat intervals. */
    public int activeNodes() {
        long cutoff = System.currentTimeMillis() - 3 * intervalMs;
        return (int) documents.all(DocumentStore.GLOBAL, NODE_KIND, DocumentStore.Filter.ref(descriptor.cellId())).stream()
                .filter(d -> d.updatedAt() != null && d.updatedAt().toEpochMilli() >= cutoff).count();
    }

    void tick() {
        try {
            documents.upsert(DocumentStore.GLOBAL, NODE_KIND, node, node, "UP", null, descriptor.cellId(),
                    java.util.Map.of("node", node, "cellId", descriptor.cellId()), "system");
        } catch (RuntimeException e) {
            LOG.debug("cell node presence not recorded: {}", e.toString());
        }
        if (!registered) {
            try {
                client.register(descriptor);
                registered = true;
                LOG.info("Cell {} registered with the control plane (node {})", descriptor.cellId(), node);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                LOG.warn("Control plane unreachable for cell {}: {}", descriptor.cellId(), e.toString());
            }
        }
        boolean leading;
        try {
            leading = leader.tryAcquire(LEADER_NAME) >= 0;
        } catch (RuntimeException e) {
            LOG.warn("cell heartbeat leadership check failed: {}", e.toString());
            leading = false;
        }
        if (leading && agent == null) {
            CellRegistrationAgent a = new CellRegistrationAgent(client, descriptor, sampler::sample,
                    java.time.Duration.ofMillis(intervalMs));
            a.start();
            agent = a;
            LOG.info("Node {} reports load figures for cell {}", node, descriptor.cellId());
        } else if (!leading && agent != null) {
            agent.close();
            agent = null;
        }
    }

    /** Shutdown: status DRAINING, one final heartbeat from the leader. */
    public void drain() {
        sampler.status("DRAINING");
        if (agent != null) {
            try {
                client.heartbeat(sampler.sample());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                LOG.warn("final DRAINING heartbeat failed: {}", e.toString());
            }
        }
    }

    @Override
    public void close() {
        exec.shutdownNow();
        CellRegistrationAgent a = agent;
        agent = null;
        if (a != null) {
            a.close();
        }
        try {
            leader.release(LEADER_NAME);
        } catch (RuntimeException e) {
            LOG.debug("leadership release failed: {}", e.toString());
        }
        try {
            documents.delete(DocumentStore.GLOBAL, NODE_KIND, node);
        } catch (RuntimeException e) {
            LOG.debug("node presence not removed: {}", e.toString());
        }
    }
}
