// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.sangam.core.layer.Outbox;
import org.sangam.core.layer.Publisher;
import org.sangam.integration.relay.PermanentPublishException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Relay publisher for {@value #DESTINATION}: delivers each accounting entry produced by the ledger's event mapping
 * (cash or accrual basis) to the configured general-ledger connector, once per outbox event (the event id is the
 * operation identity). No entry is dropped (RM-05, owner decision 126): a transient failure or a stale lease is thrown,
 * so the relay retries (and dead-letters after its attempts); a permanent refusal is a {@link PermanentPublishException},
 * which the relay parks with an audit row and an OPEN dead letter; an unknown outcome is already a reconciliation item
 * of the connector invoker (no blind resend, VI.3 rule 5). The posting is never blocked or rolled back by the export.
 */
public final class LedgerGlPublisher {

    public static final String DESTINATION = "ledger:gl";
    public static final String EVENT_TYPE = "sangam.ledger.accounting";
    private static final Logger LOG = LoggerFactory.getLogger(LedgerGlPublisher.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** One exactly-once GL connector call (the {@link ComplianceConnectors#call} signature); a seam for tests. */
    @FunctionalInterface
    interface GlCall {
        ComplianceConnectors.Result call(String tenantId, String rootId, String activityKey, String connectorId,
                                         String operation, Map<String, Object> input);
    }

    private final GlCall connectors;
    private final String connectorId;
    private final String operation;

    public LedgerGlPublisher(ComplianceConnectors connectors, String connectorId, String operation) {
        this(connectors::call, connectorId, operation);
    }

    LedgerGlPublisher(GlCall connectors, String connectorId, String operation) {
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
        if (r.retry() || "STALE".equals(r.code())) {
            // transient, or another attempt holds the operation lease: the relay retries with back-off (the operation
            // ledger keeps the call exactly-once) and dead-letters the delivery once its attempts are exhausted
            throw new IllegalStateException("GL connector " + connectorId + " " + r.code() + " for " + event.eventId()
                    + ": " + r.message());
        }
        if ("OUTCOME_UNKNOWN".equals(r.code())) {
            // the connector invoker already holds a reconciliation item for this operation: no blind resend (VI.3 rule 5)
            LOG.warn("accounting entry {} sent to {} with unknown outcome: {}", event.eventId(), connectorId, r.message());
            return;
        }
        // RM-05: a permanent refusal must never drop the entry. The relay parks the delivery with an audit row and an
        // OPEN dead letter (Kind OUTBOX) for the operator to requeue or skip; the posting itself is untouched.
        throw new PermanentPublishException("GL_EXPORT_REFUSED: accounting entry " + event.eventId() + " refused by GL"
                + " connector " + connectorId + ": " + r.code() + " " + r.message());
    }
}
