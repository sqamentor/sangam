// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.packs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.sangam.integration.connector.ConnectorRegistry;
import org.sangam.packs.port.ConnectorRequest;
import org.sangam.packs.port.ConnectorResult;
import org.sangam.packs.port.OperationStatus;

/**
 * Register E-26 (the wait of BA-0001). A pack connector can be bound to an integration connector that is not
 * registered on this server: the pack manifests bind {@code notify.sms} to {@code sms-dlt-sim} and
 * {@code notify.email} to {@code smtp}, and a production server registers neither. A query to such a binding has
 * always answered CONNECTOR_NOT_CONFIGURED. A submit was queued instead (PENDING): its dispatch then failed on every
 * relay attempt ("no connector 'sms-dlt-sim' for tenant GOI.FIN") and was retried, and the deliveries queued after it
 * were not reached: on the demo sequence (PostgreSQL, CI run 44861638229) 15 pack dispatches stood PENDING after 2 to
 * 5 attempts with that error and 3 had not been tried once, the {@code ledger.distribute} BA-0001 waits for among
 * them, 90 seconds after it was submitted. A submit to a connector nobody registered is refused at once, as the
 * query is; nothing is stored or queued for it.
 */
class SubmitToUnregisteredConnectorTest {

    /** No engine, store, journal or outbox: a refusal must touch none of them. */
    private static IntegrationConnectorGateway gateway(PackConnectorBindings bindings) {
        return new IntegrationConnectorGateway(null, null, null, bindings, new ConnectorRegistry(), null, null, null, null,
                null);
    }

    private static ConnectorRequest send(String packConnector) {
        return new ConnectorRequest("GOI.FIN", packConnector, "send", "0b0e3f0e-6d2c-4d4e-9f55-0d2f3b7a1c11", "BK/1", "pi-1",
                Map.of("template", "case-closed"), "h");
    }

    @Test
    void aSubmitToABindingWhoseConnectorIsNotRegisteredIsNotConfiguredAndNothingIsQueued() {
        IntegrationConnectorGateway g = gateway(new PackConnectorBindings().bind("notify.sms", "sms-dlt-sim", "send"));
        ConnectorResult r = g.submit(send("notify.sms"));
        assertEquals(OperationStatus.FAILED, r.status());
        assertEquals("CONNECTOR_NOT_CONFIGURED", r.reasonCode());
        assertTrue(r.message().contains("sms-dlt-sim") && r.message().contains("not registered"), r.message());
        // the query has always said the same
        assertEquals("CONNECTOR_NOT_CONFIGURED", g.query(send("notify.sms")).reasonCode());
    }

    @Test
    void aSubmitWithNoBindingIsNotConfiguredAsBefore() {
        ConnectorResult r = gateway(new PackConnectorBindings()).submit(send("notify.inapp"));
        assertEquals(OperationStatus.FAILED, r.status());
        assertEquals("CONNECTOR_NOT_CONFIGURED", r.reasonCode());
    }
}
