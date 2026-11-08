// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.events;

import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.ExecutionListener;
import org.operaton.bpm.engine.delegate.Expression;
import org.operaton.bpm.engine.delegate.JavaDelegate;
import org.operaton.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.sangam.ports.PortsContext;
import org.sangam.ports.support.EngineTx;

import java.util.Arrays;
import java.util.List;

/**
 * Send-event task (FLOWABLE-INTO-OPERATON FC-02, Flowable {@code SendEventTaskActivityBehavior}). Use on a send/service task:
 * {@code operaton:class="org.sangam.ports.events.SendEventDelegate"} with fields {@code eventKey} and
 * {@code channels} (comma separated), or let {@link EventRegistryParseListener} attach it to a throw/end event
 * carrying {@code sangam:eventType}. The event goes to the outbox in the engine transaction (R4); the message id is
 * derived from the activity instance id, so a retried job sends the same message id.
 */
public class SendEventDelegate implements JavaDelegate, ExecutionListener {

    private Expression eventKey;
    private Expression channels;

    public SendEventDelegate() {
    }

    public SendEventDelegate(Expression eventKey, Expression channels) {
        this.eventKey = eventKey;
        this.channels = channels;
    }

    public void setEventKey(Expression eventKey) {
        this.eventKey = eventKey;
    }

    public void setChannels(Expression channels) {
        this.channels = channels;
    }

    @Override
    public void execute(DelegateExecution execution) {
        send(execution);
    }

    @Override
    public void notify(DelegateExecution execution) {
        send(execution);
    }

    private void send(DelegateExecution execution) {
        // fields, else input parameters sangamEventKey / sangamChannels (used by the CMMN send-event pattern)
        Object k = eventKey != null ? eventKey.getValue(execution) : execution.getVariableLocal("sangamEventKey");
        Object ch = channels != null ? channels.getValue(execution) : execution.getVariableLocal("sangamChannels");
        if (k == null || ch == null) {
            throw new IllegalStateException("send event at " + execution.getCurrentActivityId() + ": eventKey and channels are required");
        }
        String key = String.valueOf(k);
        List<String> chs = Arrays.stream(String.valueOf(ch).split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
        String root = execution instanceof ExecutionEntity e && e.getRootProcessInstanceId() != null
                ? e.getRootProcessInstanceId() : execution.getProcessInstanceId();
        PortsContext.get().outboundEvents().send(EngineTx.connection(), execution.getTenantId(), root, key, chs,
                execution.getVariables(), execution.getActivityInstanceId() + "|" + execution.getCurrentActivityId());
    }
}
