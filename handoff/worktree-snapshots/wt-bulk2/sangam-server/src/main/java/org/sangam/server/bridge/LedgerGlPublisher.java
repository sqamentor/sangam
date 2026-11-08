// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.sangam.core.layer.Outbox;
import org.sangam.core.layer.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Relay publisher for {@value #DESTINATION}: delivers each accounting entry produced by the ledger's event mapping
 * (cash or accrual basis) to the configured general-ledger connector, once per outbox event (the event id is the
 * operation identity). A transient connector failure is thrown, so the relay retries; a final failure or an unknown
 * outcome is left to the connector ledger and reconciliation (no blind resend, VI.3 rule 5).
 */
public final class LedgerGlPublisher {

    public static final String DESTINATION = "ledger:gl";
    public static final String EVENT_TYPE = "sangam.ledger.accounting";
    private static final Logger LOG = LoggerFactory.getLogger(LedgerGlPublisher.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ComplianceConnectors connectors;
    private final String connectorId;
    private final String operation;

    public LedgerGlPublisher(ComplianceConnectors connectors, String connectorId, String operation) {
        this.connectors = connectors;
        this.connectorId = connectorId;
        this.operation = operation;
    }

    public Publisher publisher() {
        return this::deliver;
    }

    @SuppressWarnings("unchecked")
    private void deliver(String destination, Outbox.OutboxEvent event) throws Exception {
        Map<String, Object> entry = JSON.readValue(event.payload(), Map.class);
        ComplianceConnectors.Result r = connectors.call(event.tenantId(), event.rootId(),
                "gl:" + event.eventId(), connectorId, operation, entry);
        if (r.ok()) {
            return;
        }
        if (r.retry()) {
            throw new IllegalStateException("GL connector " + connectorId + " retry for " + event.eventId() + ": "
                    + r.message());
        }
        LOG.warn("accounting entry {} not accepted by {}: {} {}", event.eventId(), connectorId, r.code(), r.message());
    }
}
