// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.bridge.operaton;

import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.runtime.MessageCorrelationResult;
import org.sangam.integration.inbox.MessageCorrelationPort;

import java.sql.Connection;
import java.util.List;
import java.util.Map;

/**
 * Operaton implementation of the inbox → engine port (R38; FLOWABLE-INTO-OPERATON FC-02). Correlation key match: the process business
 * key, else a process variable {@code sangamCorrelationKey} (e.g. the operationId a payment step waits on).
 * {@link #hasSubscription} checks message event subscriptions first, so an early message is buffered instead of
 * raising {@code MismatchingMessageCorrelationException} (FLOWABLE-INTO-OPERATON D-33).
 *
 * <p>Runs inside the inbox processing transaction; that transaction must be the engine's
 * ({@code Transactions.of(engineAdapter)}) so the correlation and the inbox PROCESSED mark commit together.
 */
public final class OperatonMessageCorrelationPort implements MessageCorrelationPort {

    public static final String CORRELATION_VARIABLE = "sangamCorrelationKey";

    private final ProcessEngine engine;

    public OperatonMessageCorrelationPort(ProcessEngine engine) {
        this.engine = engine;
    }

    @Override
    public boolean hasSubscription(Connection c, String tenantId, String messageName, String correlationKey) {
        RuntimeService rs = engine.getRuntimeService();
        if (rs.createEventSubscriptionQuery().eventType("message").eventName(messageName).tenantIdIn(tenantId).count() == 0) {
            return false;
        }
        long byKey = rs.createExecutionQuery().messageEventSubscriptionName(messageName).processInstanceBusinessKey(correlationKey)
                .tenantIdIn(tenantId).count();
        if (byKey > 0) {
            return true;
        }
        return rs.createExecutionQuery().messageEventSubscriptionName(messageName)
                .processVariableValueEquals(CORRELATION_VARIABLE, correlationKey).tenantIdIn(tenantId).count() > 0;
    }

    @Override
    public int correlate(Connection c, String tenantId, String messageName, String correlationKey, Map<String, Object> variables) {
        RuntimeService rs = engine.getRuntimeService();
        List<MessageCorrelationResult> r = rs.createMessageCorrelation(messageName).tenantId(tenantId)
                .processInstanceBusinessKey(correlationKey).setVariables(variables).correlateAllWithResult();
        if (r.isEmpty()) {
            r = rs.createMessageCorrelation(messageName).tenantId(tenantId)
                    .processInstanceVariableEquals(CORRELATION_VARIABLE, correlationKey).setVariables(variables).correlateAllWithResult();
        }
        return r.size();
    }

    @Override
    public String start(Connection c, String tenantId, String definitionKey, String businessKey, Map<String, Object> variables) {
        var b = engine.getRuntimeService().createProcessInstanceByKey(definitionKey).businessKey(businessKey).setVariables(variables);
        if (tenantId != null) {
            b = b.processDefinitionTenantId(tenantId);
        }
        return b.execute().getId();
    }
}
