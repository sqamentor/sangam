// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.runtime.MessageCorrelationResult;
import org.sangam.integration.inbox.InboxHandler;

/**
 * Inbox handler for {@value CellCrossCellInboxFilter#EVENT_TYPE} (IR-3): the stored payload
 * {@code {operationId, messageName, businessKey, variables}} is correlated to the instances of the target tenant
 * waiting for {@code messageName} with that business key, inside the inbox's engine transaction. No waiting instance
 * → {@code NO_SUBSCRIPTION} (buffered and re-polled until the inbox TTL, R38). A message without a name is rejected.
 */
public final class CellCrossCellInboxHandler implements InboxHandler {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final RuntimeService runtime;

    public CellCrossCellInboxHandler(RuntimeService runtime) {
        this.runtime = runtime;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Disposition handle(Connection c, Envelope m) {
        Map<String, Object> envelope;
        try {
            envelope = m.dataJson() == null ? Map.of() : JSON.readValue(m.dataJson(), Map.class);
        } catch (java.io.IOException e) {
            return Disposition.REJECTED;
        }
        // InboxService stores {eventType, principal, data}; data is the cross-cell payload.
        Object dataObj = envelope.containsKey("data") ? envelope.get("data") : envelope;
        if (!(dataObj instanceof Map<?, ?> data) || data.get("messageName") == null) {
            return Disposition.REJECTED;
        }
        Map<String, Object> vars = new LinkedHashMap<>();
        if (data.get("variables") instanceof Map<?, ?> v) {
            ((Map<String, Object>) v).forEach(vars::put);
        }
        vars.put("sangamXcellSource", m.source());
        vars.put("sangamXcellOperationId", data.get("operationId") == null ? m.messageId() : data.get("operationId").toString());
        var correlation = runtime.createMessageCorrelation(data.get("messageName").toString()).setVariables(vars);
        if (m.tenantId() != null) {
            correlation.tenantId(m.tenantId());
        }
        Object bk = data.get("businessKey");
        if (bk != null && !bk.toString().isBlank()) {
            correlation.processInstanceBusinessKey(bk.toString());
        }
        List<MessageCorrelationResult> results = correlation.correlateAllWithResult();
        return results.isEmpty() ? Disposition.NO_SUBSCRIPTION : Disposition.PROCESSED;
    }
}
