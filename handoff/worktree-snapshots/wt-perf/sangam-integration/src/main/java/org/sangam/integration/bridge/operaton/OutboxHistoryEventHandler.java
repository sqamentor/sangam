// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.bridge.operaton;

import org.operaton.bpm.engine.impl.history.event.HistoricActivityInstanceEventEntity;
import org.operaton.bpm.engine.impl.history.event.HistoricIncidentEventEntity;
import org.operaton.bpm.engine.impl.history.event.HistoricProcessInstanceEventEntity;
import org.operaton.bpm.engine.impl.history.event.HistoricTaskInstanceEventEntity;
import org.operaton.bpm.engine.impl.history.event.HistoryEvent;
import org.operaton.bpm.engine.impl.history.handler.HistoryEventHandler;
import org.sangam.integration.relay.OutboxWriter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * History events onto the outbox (FLOWABLE-INTO-OPERATON FC-25 async history / FLOWABLE-INTO-OPERATON FC-43 global typed event dispatcher; R4, R13, R65 read
 * model feed). Registered as a <b>custom</b> history event handler next to Operaton's DB handler, so synchronous
 * history stays the strict record and the outbox carries typed {@code sangam.history.*} events for projectors,
 * analytics and channels. Written in the engine command's own transaction (same JDBC connection), so an event exists
 * if and only if the history change committed.
 *
 * <p>Only lifecycle events are forwarded (process start/end, activity start/end, task create/complete/delete,
 * incidents); variable updates are not, because they carry personal data the subscriber may not be allowed to see.
 */
public final class OutboxHistoryEventHandler implements HistoryEventHandler {

    private static final Set<String> FORWARDED = Set.of("start", "end", "create", "complete", "delete", "update", "resolve",
            "migrate");

    private final OutboxWriter writer;
    private final String defaultTenant;

    public OutboxHistoryEventHandler(OutboxWriter writer, String defaultTenant) {
        this.writer = writer;
        this.defaultTenant = defaultTenant == null ? "default" : defaultTenant;
    }

    @Override
    public void handleEvent(HistoryEvent e) {
        String kind;
        String tenant;
        Map<String, Object> p = new LinkedHashMap<>();
        switch (e) {
            case HistoricProcessInstanceEventEntity pi -> {
                kind = "process";
                tenant = pi.getTenantId();
                p.put("businessKey", pi.getBusinessKey());
                p.put("state", pi.getState());
                p.put("deleteReason", pi.getDeleteReason());
            }
            case HistoricTaskInstanceEventEntity t -> {
                kind = "task";
                tenant = t.getTenantId();
                p.put("taskId", t.getTaskId());
                p.put("taskDefinitionKey", t.getTaskDefinitionKey());
                p.put("assignee", t.getAssignee());
                p.put("deleteReason", t.getDeleteReason());
            }
            case HistoricIncidentEventEntity i -> {
                kind = "incident";
                tenant = i.getTenantId();
                p.put("incidentId", i.getId());
                p.put("incidentType", i.getIncidentType());
                p.put("activityId", i.getActivityId());
            }
            case HistoricActivityInstanceEventEntity a -> {
                kind = "activity";
                tenant = a.getTenantId();
                p.put("activityId", a.getActivityId());
                p.put("activityType", a.getActivityType());
                p.put("activityInstanceId", a.getActivityInstanceId());
            }
            default -> {
                return;
            }
        }
        if (e.getEventType() == null || !FORWARDED.contains(e.getEventType())) {
            return;
        }
        String root = e.getRootProcessInstanceId() != null ? e.getRootProcessInstanceId() : e.getProcessInstanceId();
        if (root == null) {
            return;
        }
        p.put("historyEventId", e.getId());
        p.put("processInstanceId", e.getProcessInstanceId());
        p.put("processDefinitionKey", e.getProcessDefinitionKey());
        p.put("processDefinitionId", e.getProcessDefinitionId());
        p.put("eventType", e.getEventType());
        writer.append(EngineContext.connection(), tenant == null ? defaultTenant : tenant, root,
                "sangam.history." + kind + "." + e.getEventType(), p);
    }

    @Override
    public void handleEvents(List<HistoryEvent> events) {
        for (HistoryEvent e : events) {
            handleEvent(e);
        }
    }
}
