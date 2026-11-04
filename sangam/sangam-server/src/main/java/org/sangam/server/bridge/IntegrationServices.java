// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.sangam.integration.IntegrationMaintenance;
import org.sangam.integration.bridge.ConnectorExternalTaskWorker;
import org.sangam.integration.inbox.InboxService;
import org.sangam.integration.reconciliation.ReconciliationEngine;
import org.sangam.integration.relay.OutboxRelayService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Starts and stops the sangam-integration background services (README "Wiring for sangam-server"): inbox, outbox
 * relay, reconciliation engine, maintenance (leader-elected capacity sampling, retention, lifecycle sweeps) and the
 * connector external-task worker. Phase {@value #PHASE}: after every bean is initialised, so the event-registry and
 * packs wiring ({@code EventRegistryWiring.wire}, {@code PacksIntegration.wire}, done in bean initialisation) is in
 * place before the first message is processed; stopped before the Operaton job executor on shutdown.
 */
public final class IntegrationServices implements SmartLifecycle {

    public static final int PHASE = Integer.MAX_VALUE - 100;
    private static final Logger LOG = LoggerFactory.getLogger(IntegrationServices.class);

    private final InboxService inbox;
    private final OutboxRelayService relay;
    private final ReconciliationEngine reconciliation;
    private final IntegrationMaintenance maintenance;
    private final ConnectorExternalTaskWorker workerOrNull;
    private final Duration capacityEvery;
    private final Duration maintenanceEvery;
    private final boolean startBackground;
    private volatile boolean running;

    public IntegrationServices(InboxService inbox, OutboxRelayService relay, ReconciliationEngine reconciliation,
                               IntegrationMaintenance maintenance, ConnectorExternalTaskWorker workerOrNull,
                               Duration capacityEvery, Duration maintenanceEvery, boolean startBackground) {
        this.inbox = inbox;
        this.relay = relay;
        this.reconciliation = reconciliation;
        this.maintenance = maintenance;
        this.workerOrNull = workerOrNull;
        this.capacityEvery = capacityEvery;
        this.maintenanceEvery = maintenanceEvery;
        this.startBackground = startBackground;
    }

    @Override
    public void start() {
        if (!startBackground) {
            LOG.info("sangam-integration background services not started (sangam.integration.background.enabled=false)");
            running = true;
            return;
        }
        inbox.start();
        relay.start();
        reconciliation.start();
        maintenance.start(capacityEvery, maintenanceEvery);
        if (workerOrNull != null) {
            workerOrNull.start();
        }
        running = true;
        LOG.info("sangam-integration started: inbox, outbox relay, reconciliation, maintenance{}",
                workerOrNull == null ? "" : ", connector worker");
    }

    @Override
    public void stop() {
        List<AutoCloseable> order = new ArrayList<>();
        if (workerOrNull != null) {
            order.add(workerOrNull);
        }
        order.add(maintenance);
        order.add(reconciliation);
        order.add(relay);
        order.add(inbox);
        for (AutoCloseable c : order) {
            try {
                c.close();
            } catch (Exception e) {
                LOG.warn("stopping {} failed: {}", c.getClass().getSimpleName(), e.toString());
            }
        }
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }
}
