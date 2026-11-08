// SPDX-License-Identifier: Apache-2.0
package org.sangam.cell.registration;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs in every cell node (sangam-server, IR-2): registers the cell (idempotent PUT), then heartbeats. A control-plane
 * outage is logged and retried; the cell keeps serving because it never needs the control plane on the request path.
 */
public final class CellRegistrationAgent implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(CellRegistrationAgent.class);

    private final ControlPlaneClient client;
    private final CellDescriptor descriptor;
    private final Supplier<CellHeartbeat> heartbeat;
    private final Duration interval;
    private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "sangam-cell-heartbeat");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean registered;

    public CellRegistrationAgent(ControlPlaneClient client, CellDescriptor descriptor, Supplier<CellHeartbeat> heartbeat,
                                 Duration interval) {
        this.client = client;
        this.descriptor = descriptor;
        this.heartbeat = heartbeat;
        this.interval = interval == null ? Duration.ofSeconds(10) : interval;
    }

    public void start() {
        exec.scheduleWithFixedDelay(this::tick, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    void tick() {
        try {
            if (!registered) {
                client.register(descriptor);
                registered = true;
                LOG.info("Cell {} registered with the control plane", descriptor.cellId());
            }
            client.heartbeat(heartbeat.get());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            registered = false;
            LOG.warn("Control plane unreachable for cell {}: {}", descriptor.cellId(), e.toString());
        }
    }

    public boolean registered() {
        return registered;
    }

    @Override
    public void close() {
        exec.shutdownNow();
    }
}
