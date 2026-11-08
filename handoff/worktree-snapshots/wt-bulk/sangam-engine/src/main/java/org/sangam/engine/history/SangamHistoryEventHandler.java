// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.history;

import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.operaton.bpm.engine.impl.context.Context;
import org.operaton.bpm.engine.impl.history.event.HistoricActivityInstanceEventEntity;
import org.operaton.bpm.engine.impl.history.event.HistoricCaseActivityInstanceEventEntity;
import org.operaton.bpm.engine.impl.history.event.HistoricCaseInstanceEventEntity;
import org.operaton.bpm.engine.impl.history.event.HistoricIncidentEventEntity;
import org.operaton.bpm.engine.impl.history.event.HistoricProcessInstanceEventEntity;
import org.operaton.bpm.engine.impl.history.event.HistoricTaskInstanceEventEntity;
import org.operaton.bpm.engine.impl.history.event.HistoryEvent;
import org.operaton.bpm.engine.impl.history.handler.HistoryEventHandler;
import org.sangam.core.engine.AuditSink;
import org.sangam.core.engine.EngineEventSink;
import org.sangam.engine.tx.CommandState;

/**
 * History hook (R42, EC-09, FLOWABLE-INTO-OPERATON FC-43, FLOWABLE-INTO-OPERATON FC-25/FLOWABLE-INTO-OPERATON D-41, FLOWABLE-INTO-OPERATON D-25), registered next to Operaton's own DB history handler. Each
 * history event is handled <b>inside the engine command, on the engine connection</b>:
 * <ul>
 *   <li>the configured event types become class-B audit events on the root's hash chain ({@link AuditSink});</li>
 *   <li>every (or every configured) event becomes a typed engine event ({@link EngineEventSink}, typically an
 *       outbox row), the base of async history projections and webhooks;</li>
 *   <li><b>CMMN audit projection (FLOWABLE-INTO-OPERATON D-25)</b>: a human task that Operaton's history ends as "terminated" although it was
 *       completed through TaskService (E1) is projected as {@code CASE_TASK_COMPLETED}; an exit by sentry as
 *       {@code CASE_TASK_EXITED}; any other termination as {@code CASE_TASK_TERMINATED}. The engine's own history row
 *       is left unchanged; the projection records both ({@code engineState}).</li>
 * </ul>
 * Events without a root (standalone tasks) are published but not chained.
 */
public class SangamHistoryEventHandler implements HistoryEventHandler {

    private final AuditSink audit;
    private final EngineEventSink events;
    private final Set<String> audited;
    private final Set<String> published;
    private final String defaultTenant;

    public SangamHistoryEventHandler(AuditSink audit, EngineEventSink events, Set<String> audited, Set<String> published,
                                     String defaultTenant) {
        this.audit = audit == null ? AuditSink.NONE : audit;
        this.events = events == null ? EngineEventSink.NONE : events;
        this.audited = audited;
        this.published = published;
        this.defaultTenant = defaultTenant;
    }

    @Override
    public void handleEvent(HistoryEvent e) {
        if (!Context.hasActiveCommandContext()) {
            return;
        }
        String name = typeName(e);
        Map<String, Object> data = new LinkedHashMap<>();
        String tenant = describe(e, data);
        String root = e.getRootProcessInstanceId() != null ? e.getRootProcessInstanceId()
                : e.getCaseInstanceId() != null ? e.getCaseInstanceId() : e.getProcessInstanceId();
        String auditType = name;

        if (e instanceof HistoricTaskInstanceEventEntity t && "TASK_INSTANCE_COMPLETE".equals(name)
                && t.getCaseExecutionId() != null) {
            CommandState s = CommandState.current();
            if (s != null) {
                s.caseTaskOutcome(t.getCaseExecutionId(), "COMPLETED");
            }
        }
        if (e instanceof HistoricCaseActivityInstanceEventEntity ca && "CASE_ACTIVITY_INSTANCE_END".equals(name)
                && "humanTask".equals(ca.getCaseActivityType())) {
            String outcome = caseTaskOutcome(ca);
            data.put("outcome", outcome);
            auditType = "CASE_TASK_" + outcome;
        }

        boolean doAudit = audited.contains(name) && root != null;
        boolean doPublish = published.isEmpty() || published.contains(name);
        if (!doAudit && !doPublish) {
            return;
        }
        Connection c = CommandState.currentConnection();
        String t = tenant == null ? defaultTenant : tenant;
        if (doAudit) {
            audit.append(c, t, root, auditType, data);
        }
        if (doPublish) {
            events.publish(c, t, root, auditType, data);
        }
    }

    @Override
    public void handleEvents(List<HistoryEvent> historyEvents) {
        for (HistoryEvent e : historyEvents) {
            handleEvent(e);
        }
    }

    /** FLOWABLE-INTO-OPERATON D-25 mapping; see the class comment. */
    static String caseTaskOutcome(HistoricCaseActivityInstanceEventEntity ca) {
        CommandState s = CommandState.current();
        String recorded = s == null ? null : s.caseTaskOutcome(ca.getCaseExecutionId());
        if (recorded != null) {
            return recorded;
        }
        if (ca.isCompleted()) {
            return "COMPLETED";
        }
        return ca.isTerminated() ? "TERMINATED" : "ENDED";
    }

    /** Operaton {@code HistoryEventTypes} name of an event, from its entity class and event name. */
    static String typeName(HistoryEvent e) {
        String ev = e.getEventType() == null ? "UNKNOWN" : e.getEventType().toUpperCase(java.util.Locale.ROOT);
        String prefix;
        if (e instanceof HistoricProcessInstanceEventEntity) {
            prefix = "PROCESS_INSTANCE";
        } else if (e instanceof HistoricTaskInstanceEventEntity) {
            prefix = "TASK_INSTANCE";
        } else if (e instanceof HistoricCaseActivityInstanceEventEntity) {
            prefix = "CASE_ACTIVITY_INSTANCE";
        } else if (e instanceof HistoricCaseInstanceEventEntity) {
            prefix = "CASE_INSTANCE";
        } else if (e instanceof HistoricActivityInstanceEventEntity) {
            prefix = "ACTIVITY_INSTANCE";
        } else if (e instanceof HistoricIncidentEventEntity) {
            prefix = "INCIDENT";
        } else {
            prefix = e.getClass().getSimpleName().replace("EventEntity", "").replace("Entity", "").toUpperCase(java.util.Locale.ROOT);
        }
        return prefix + "_" + ev;
    }

    /** Minimum event fields (X.2.3.2) per type; returns the tenant. No variable values are copied (privacy, R53). */
    private static String describe(HistoryEvent e, Map<String, Object> d) {
        d.put("historyEventId", e.getId());
        d.put("processInstanceId", e.getProcessInstanceId());
        d.put("processDefinitionKey", e.getProcessDefinitionKey());
        d.put("processDefinitionId", e.getProcessDefinitionId());
        d.put("caseInstanceId", e.getCaseInstanceId());
        d.put("caseDefinitionKey", e.getCaseDefinitionKey());
        if (e instanceof HistoricProcessInstanceEventEntity p) {
            d.put("businessKey", p.getBusinessKey());
            d.put("startUserId", p.getStartUserId());
            d.put("state", p.getState());
            d.put("deleteReason", p.getDeleteReason());
            d.put("superProcessInstanceId", p.getSuperProcessInstanceId());
            return p.getTenantId();
        }
        if (e instanceof HistoricTaskInstanceEventEntity t) {
            d.put("taskId", t.getTaskId());
            d.put("taskDefinitionKey", t.getTaskDefinitionKey());
            d.put("name", t.getName());
            d.put("assignee", t.getAssignee());
            d.put("owner", t.getOwner());
            d.put("deleteReason", t.getDeleteReason());
            d.put("activityInstanceId", t.getActivityInstanceId());
            d.put("caseExecutionId", t.getCaseExecutionId());
            return t.getTenantId();
        }
        if (e instanceof HistoricCaseActivityInstanceEventEntity ca) {
            d.put("caseExecutionId", ca.getCaseExecutionId());
            d.put("caseActivityId", ca.getCaseActivityId());
            d.put("caseActivityType", ca.getCaseActivityType());
            d.put("taskId", ca.getTaskId());
            d.put("engineState", ca.isCompleted() ? "completed" : ca.isTerminated() ? "terminated"
                    : String.valueOf(ca.getCaseActivityInstanceState()));
            return ca.getTenantId();
        }
        if (e instanceof HistoricCaseInstanceEventEntity ci) {
            d.put("businessKey", ci.getBusinessKey());
            d.put("state", ci.getState());
            return ci.getTenantId();
        }
        if (e instanceof HistoricActivityInstanceEventEntity a) {
            d.put("activityId", a.getActivityId());
            d.put("activityType", a.getActivityType());
            d.put("activityInstanceId", a.getActivityInstanceId());
            d.put("taskId", a.getTaskId());
            d.put("canceled", a.isCanceled());
            return a.getTenantId();
        }
        if (e instanceof HistoricIncidentEventEntity i) {
            d.put("incidentType", i.getIncidentType());
            d.put("activityId", i.getActivityId());
            d.put("configuration", i.getConfiguration());
            d.put("message", i.getIncidentMessage());
            return i.getTenantId();
        }
        return null;
    }
}
