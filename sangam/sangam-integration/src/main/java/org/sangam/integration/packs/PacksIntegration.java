// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.packs;

import org.operaton.bpm.engine.ProcessEngine;
import org.sangam.integration.inbox.InboxService;
import org.sangam.integration.reconciliation.ReconciliationEngine;
import org.sangam.integration.relay.PublisherRegistry;
import org.sangam.packs.port.PackRuntime;

/**
 * One-call wiring of the packs to the integration layer (sangam-packs README §8), done by sangam-server at start-up:
 * <ol>
 *   <li>the relay publishes {@value IntegrationConnectorGateway#DISPATCH_DESTINATION} through {@link PackDispatchPublisher};</li>
 *   <li>the inbox correlates {@code sangam.operationResult}, {@code sangam.reconciliationResolved},
 *       {@code sangam.cancelAck} and {@code sangam.signatureResult} with {@link PackMessageInboxHandler};</li>
 *   <li>the reconciliation engine gets the {@link PackReconciliationHooks}, and the hooks get the engine for the human
 *       items of operations whose re-dispatches are used up (decision 290);</li>
 *   <li>{@link PackRuntime} gets the {@link IntegrationConnectorGateway}, keeping the other ports as installed.</li>
 * </ol>
 * The inbox must run in the engine transaction ({@code Transactions.of(engineAdapter)}).
 */
public final class PacksIntegration {

    private PacksIntegration() {
    }

    public static void wire(ProcessEngine engine, InboxService inbox, PublisherRegistry publishers, ReconciliationEngine reconciliation,
                            IntegrationConnectorGateway gateway, PackDispatchPublisher dispatcher, PackReconciliationHooks hooks) {
        publishers.register(IntegrationConnectorGateway.DISPATCH_DESTINATION, dispatcher);
        PackMessageInboxHandler handler = new PackMessageInboxHandler(engine);
        for (String message : PackMessages.CORRELATION_VARIABLES.keySet()) {
            inbox.registerHandler(message, handler);
        }
        if (reconciliation != null) {
            reconciliation.hooks(hooks, hooks);
            hooks.humans(humans(reconciliation));
        }
        PackRuntime.install(PackRuntime.ports().withGateway(gateway));
    }

    /** Decision 290: the reconciliation engine opens the human items of operations whose re-dispatches are used up. */
    static PackReconciliationHooks.HumanReconciliation humans(ReconciliationEngine reconciliation) {
        return new PackReconciliationHooks.HumanReconciliation() {
            @Override
            public long open(java.sql.Connection c, java.util.UUID ledgerOperationId, String reason) {
                return reconciliation.openForHuman(c, ledgerOperationId, reason);
            }

            @Override
            public void escalate(long itemId, String reason) {
                reconciliation.escalateOpened(itemId, reason);
            }
        };
    }
}
