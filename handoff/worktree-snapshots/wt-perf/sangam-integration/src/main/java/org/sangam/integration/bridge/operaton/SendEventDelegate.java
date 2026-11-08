// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.bridge.operaton;

import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.Expression;
import org.operaton.bpm.engine.delegate.JavaDelegate;
import org.sangam.core.layer.Outbox;
import org.sangam.integration.connector.Json;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Send-event task (FLOWABLE-INTO-OPERATON FC-02 {@code SendEventTaskActivityBehavior}; R4, R71): appends an outbox event <b>in the
 * engine transaction</b> of the current command, so the event leaves only if the step commits. The relay publishes it
 * to the subscribed destinations (Kafka, JMS, webhook) through WS-PORTS' channels.
 *
 * <p>Fields: {@code eventType} (required), {@code payload} (Map or JSON string), {@code channel} (optional fixed
 * destination; otherwise the subscription resolver decides), {@code ordered} (default true).
 */
public final class SendEventDelegate implements JavaDelegate {

    private Expression eventType;
    private Expression payload;
    private Expression channel;
    private Expression ordered;

    public void setEventType(Expression eventType) {
        this.eventType = eventType;
    }

    public void setPayload(Expression payload) {
        this.payload = payload;
    }

    public void setChannel(Expression channel) {
        this.channel = channel;
    }

    public void setOrdered(Expression ordered) {
        this.ordered = ordered;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void execute(DelegateExecution execution) {
        IntegrationBridge bridge = IntegrationBridge.get();
        Object typeValue = eventType == null ? null : eventType.getValue(execution);
        if (typeValue == null) {
            throw new IllegalStateException("field 'eventType' is required on " + execution.getCurrentActivityId());
        }
        Object p = payload == null ? null : payload.getValue(execution);
        Map<String, Object> body = p instanceof Map<?, ?> m ? new LinkedHashMap<>((Map<String, Object>) m)
                : p instanceof String s ? Json.readMap(s) : new LinkedHashMap<>();
        body.putIfAbsent("processInstanceId", execution.getProcessInstanceId());
        body.putIfAbsent("activityId", execution.getCurrentActivityId());
        body.putIfAbsent("businessKey", execution.getProcessBusinessKey());
        String tenant = EngineContext.tenant(execution, bridge.defaultTenant());
        String root = EngineContext.rootId(execution);
        Object ch = channel == null ? null : channel.getValue(execution);
        if (ch != null) {
            boolean ord = ordered == null || !"false".equalsIgnoreCase(String.valueOf(ordered.getValue(execution)));
            bridge.outboxWriter().append(EngineContext.connection(), tenant, root, typeValue.toString(),
                    org.sangam.core.layer.CanonicalJson.write(body), List.of(new Outbox.Destination(ch.toString(), ord)));
        } else {
            bridge.outboxWriter().append(EngineContext.connection(), tenant, root, typeValue.toString(), body);
        }
    }
}
