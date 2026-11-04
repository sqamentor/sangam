// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.packs;

import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.runtime.MessageCorrelationBuilder;
import org.operaton.bpm.engine.runtime.MessageCorrelationResult;
import org.sangam.integration.connector.Json;
import org.sangam.integration.inbox.InboxHandler;

import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Inbox handler for the pack messages ({@link PackMessages}): correlates the message to every execution waiting for it
 * whose correlation variable equals the operationId (sangam-packs README §4), in the tenant of the inbox row, with the
 * message variables as process variables. Runs in the inbox processing transaction, which is the engine transaction.
 * No waiting execution yet → {@code NO_SUBSCRIPTION}: the message stays BUFFERED until its TTL (D-33).
 */
public final class PackMessageInboxHandler implements InboxHandler {

    private final ProcessEngine engine;

    public PackMessageInboxHandler(ProcessEngine engine) {
        this.engine = engine;
    }

    @Override
    public Disposition handle(Connection c, Envelope m) {
        Map<String, Object> vars = new LinkedHashMap<>(Json.readMap(m.dataJson()));
        vars.remove("_processInstanceId");
        List<String> keys = PackMessages.CORRELATION_VARIABLES.getOrDefault(m.eventType(), List.of("operationId"));
        // the gateway stores a pack call without tenant as "default"; such instances have no engine tenant
        List<String> tenants = "default".equals(m.tenantId()) ? java.util.Arrays.asList("default", null)
                : java.util.Collections.singletonList(m.tenantId());
        for (String tenant : tenants) {
            for (String var : keys) {
                if (!hasSubscription(tenant, m.eventType(), var, m.correlationKey())) {
                    continue;
                }
                MessageCorrelationBuilder b = engine.getRuntimeService().createMessageCorrelation(m.eventType())
                        .processInstanceVariableEquals(var, m.correlationKey()).setVariables(vars);
                b = tenant == null ? b.withoutTenantId() : b.tenantId(tenant);
                List<MessageCorrelationResult> r = b.correlateAllWithResult();
                if (!r.isEmpty()) {
                    return Disposition.PROCESSED;
                }
            }
        }
        return Disposition.NO_SUBSCRIPTION;
    }

    private boolean hasSubscription(String tenant, String message, String var, String value) {
        var q = engine.getRuntimeService().createExecutionQuery().messageEventSubscriptionName(message)
                .processVariableValueEquals(var, value);
        q = tenant == null ? q.withoutTenantId() : q.tenantIdIn(tenant);
        return q.count() > 0;
    }
}
