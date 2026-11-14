// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.connector;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Owner decision 299: a connector whose successful operation is itself a catalogued audit event (SANGAM.md X.4.0.3),
 * for example a DigiLocker pull ({@code DOCUMENT_FETCHED}) or push ({@code DOCUMENT_PUSHED}).
 *
 * <p>Connectors hold no connection, so they only name the events. {@link ConnectorInvoker} writes them in the
 * transaction that records the successful outcome in the operation ledger, on the root's chain beside
 * {@code OPERATION_OUTCOME}, through the same direct or deferring audit path (SL-133); the operationId is added to each
 * payload. Because the ledger records an outcome once, the events are written once per operation; a replayed outcome
 * writes nothing.
 */
public interface AuditedConnector {

    /** One audit event: its catalogued type and payload (JSON-able; no personal identifiers beyond what X.4.0.3 names). */
    record Event(String type, Map<String, Object> payload) {
        public Event {
            payload = payload == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
        }
    }

    /**
     * @param operation the connector operation that succeeded
     * @param input     its input
     * @param output    its output (from the call, or from an outcome lookup that confirmed it)
     * @return the events to write; empty when the operation is not an audited one
     */
    List<Event> auditEvents(String operation, Map<String, Object> input, Map<String, Object> output);
}
