// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.bridge.operaton;

import org.operaton.bpm.engine.delegate.BpmnError;
import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.Expression;
import org.operaton.bpm.engine.delegate.JavaDelegate;
import org.sangam.core.engine.OperationIdentity;
import org.sangam.integration.connector.ConnectorInvoker;
import org.sangam.integration.connector.Json;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * BPMN service task → connector as a {@link JavaDelegate} (Flowable FC-06 HTTP task / FC-07 mail task parity;
 * Operaton connect-plugin B-16 replacement with R27 egress and R36 identity).
 *
 * <pre>{@code
 * <serviceTask id="notify" operaton:asyncBefore="true" operaton:class="org.sangam.integration.bridge.operaton.SangamConnectorDelegate">
 *   <extensionElements>
 *     <operaton:field name="connector" stringValue="smtp-default"/>
 *     <operaton:field name="operation" stringValue="send"/>
 *     <operaton:field name="input" expression="${mailInput}"/>        <!-- a Map or a JSON string -->
 *     <operaton:field name="resultVariable" stringValue="mailResult"/>
 *   </extensionElements>
 * </serviceTask>
 * }</pre>
 *
 * <p>The operationId is UUIDv5(tenant, root instance, activityInstanceId, loopCounter[, retrySafeKey]) and is set as
 * {@code <resultVariable>_operationId}. The intent is committed before the call through the bridge's own transactions
 * (R37). Rules, because a delegate runs inside an engine transaction (VI.1 rule 5):
 * <ul>
 *   <li>Mark the task {@code asyncBefore} so the call runs in its own job and a retry re-runs only the job.</li>
 *   <li>Prefer the external-task path ({@code ConnectorExternalTaskWorker}) for slow or class (c) connectors.</li>
 * </ul>
 * Outcomes: completed → result variables; failed → {@link BpmnError} with the code; retry → exception (job retry with
 * the same operationId); unknown → exception carrying {@code UNKNOWN_OUTCOME}: the job's retries run out into an
 * incident (held) and reconciliation releases it.
 */
public final class SangamConnectorDelegate implements JavaDelegate {

    private Expression connector;
    private Expression operation;
    private Expression input;
    private Expression resultVariable;
    private Expression retrySafeKey;

    public void setConnector(Expression connector) {
        this.connector = connector;
    }

    public void setOperation(Expression operation) {
        this.operation = operation;
    }

    public void setInput(Expression input) {
        this.input = input;
    }

    public void setResultVariable(Expression resultVariable) {
        this.resultVariable = resultVariable;
    }

    public void setRetrySafeKey(Expression retrySafeKey) {
        this.retrySafeKey = retrySafeKey;
    }

    @Override
    public void execute(DelegateExecution execution) {
        IntegrationBridge bridge = IntegrationBridge.get();
        String connectorId = text(connector, execution, null);
        if (connectorId == null) {
            throw new IllegalStateException("field 'connector' is required on " + execution.getCurrentActivityId());
        }
        String op = text(operation, execution, "invoke");
        String result = text(resultVariable, execution, execution.getCurrentActivityId() + "_result");
        ConnectorInvoker.Invocation inv = new ConnectorInvoker.Invocation(
                EngineContext.tenant(execution, bridge.defaultTenant()), EngineContext.rootId(execution),
                execution.getActivityInstanceId(), EngineContext.loopCounter(execution), text(retrySafeKey, execution, null),
                execution.getProcessBusinessKey(), connectorId, op, inputOf(execution), UUID.randomUUID().toString(), 0L, null, mintedId(execution, bridge));
        ConnectorInvoker.Outcome o = bridge.delegateInvoker().invoke(inv);
        execution.setVariable(result + "_operationId", o.operationId().toString());
        switch (o) {
            case ConnectorInvoker.Completed c -> {
                execution.setVariable(result, Json.write(c.output()));
                if (c.providerRef() != null) {
                    execution.setVariable(result + "_providerRef", c.providerRef());
                }
            }
            case ConnectorInvoker.Failed f -> throw new BpmnError(f.code(), f.message());
            case ConnectorInvoker.Retry r -> throw new IllegalStateException("connector " + connectorId + " transient failure: " + r.message());
            case ConnectorInvoker.Unknown u -> throw new IllegalStateException("UNKNOWN_OUTCOME (operation " + u.operationId()
                    + "): held for reconciliation: " + u.reason());
            case ConnectorInvoker.Stale s -> throw new IllegalStateException("stale: " + s.message());
        }
    }

    /** The same T35 identity the engine adapter mints for external tasks (a delegate has no adapter call). */
    private UUID mintedId(DelegateExecution execution, IntegrationBridge bridge) {
        String activity = execution.getCurrentActivityId();
        long visit = execution.getProcessEngineServices().getHistoryService().createHistoricActivityInstanceQuery()
                .processInstanceId(execution.getProcessInstanceId()).activityId(activity).finished().count();
        Object loop = execution.getVariable("loopCounter");
        String bk = execution.getProcessBusinessKey();
        if (bk == null || bk.isEmpty()) {
            bk = EngineContext.rootId(execution);
        }
        return OperationIdentity.of(EngineContext.tenant(execution, bridge.defaultTenant()), bk, activity,
                OperationIdentity.scope(visit, loop instanceof Number n ? n.intValue() : null, text(retrySafeKey, execution, null)));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> inputOf(DelegateExecution execution) {
        if (input == null) {
            return Map.of();
        }
        Object v = input.getValue(execution);
        if (v instanceof Map<?, ?> m) {
            return new LinkedHashMap<>((Map<String, Object>) m);
        }
        if (v instanceof String s) {
            return Json.readMap(s);
        }
        if (v == null) {
            return Map.of();
        }
        return Json.MAPPER.convertValue(v, Map.class);
    }

    private static String text(Expression e, DelegateExecution execution, String fallback) {
        if (e == null) {
            return fallback;
        }
        Object v = e.getValue(execution);
        return v == null ? fallback : v.toString();
    }
}
