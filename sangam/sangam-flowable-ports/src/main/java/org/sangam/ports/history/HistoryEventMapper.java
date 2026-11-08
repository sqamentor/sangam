// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.history;

import org.operaton.bpm.engine.impl.history.event.HistoricActivityInstanceEventEntity;
import org.operaton.bpm.engine.impl.history.event.HistoricCaseActivityInstanceEventEntity;
import org.operaton.bpm.engine.impl.history.event.HistoricCaseInstanceEventEntity;
import org.operaton.bpm.engine.impl.history.event.HistoricProcessInstanceEventEntity;
import org.operaton.bpm.engine.impl.history.event.HistoricScopeInstanceEvent;
import org.operaton.bpm.engine.impl.history.event.HistoricTaskInstanceEventEntity;
import org.operaton.bpm.engine.impl.history.event.HistoricVariableUpdateEventEntity;
import org.operaton.bpm.engine.impl.history.event.HistoryEvent;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Converts Operaton history events to plain maps (JSON-safe; times as ISO-8601 UTC strings) for the async history
 * stream (FLOWABLE-INTO-OPERATON FC-25) and the typed engine event bus (FLOWABLE-INTO-OPERATON FC-43). Variable values are <b>not</b> copied (only name, type and
 * revision), so personal data does not leak into the outbox stream (R61/DPDP); consumers that need values read them
 * through the authorised API.
 */
public final class HistoryEventMapper {

    private HistoryEventMapper() {
    }

    /** Kind of the event: {@code process, activity, task, variable, case, caseActivity, other}. */
    public static String kind(HistoryEvent e) {
        if (e instanceof HistoricProcessInstanceEventEntity) {
            return "process";
        }
        if (e instanceof HistoricActivityInstanceEventEntity) {
            return "activity";
        }
        if (e instanceof HistoricTaskInstanceEventEntity) {
            return "task";
        }
        if (e instanceof HistoricVariableUpdateEventEntity) {
            return "variable";
        }
        if (e instanceof HistoricCaseInstanceEventEntity) {
            return "case";
        }
        if (e instanceof HistoricCaseActivityInstanceEventEntity) {
            return "caseActivity";
        }
        return "other";
    }

    public static String tenant(HistoryEvent e) {
        if (e instanceof HistoricProcessInstanceEventEntity p) {
            return p.getTenantId();
        }
        if (e instanceof HistoricActivityInstanceEventEntity a) {
            return a.getTenantId();
        }
        if (e instanceof HistoricTaskInstanceEventEntity t) {
            return t.getTenantId();
        }
        if (e instanceof HistoricCaseInstanceEventEntity c) {
            return c.getTenantId();
        }
        if (e instanceof HistoricCaseActivityInstanceEventEntity c) {
            return c.getTenantId();
        }
        if (e instanceof HistoricVariableUpdateEventEntity v) {
            return v.getTenantId();
        }
        return null;
    }

    /** Ordering root for the outbox: root process instance, else case instance, else "engine". */
    public static String root(HistoryEvent e) {
        if (e.getRootProcessInstanceId() != null) {
            return e.getRootProcessInstanceId();
        }
        if (e.getProcessInstanceId() != null) {
            return e.getProcessInstanceId();
        }
        if (e.getCaseInstanceId() != null) {
            return e.getCaseInstanceId();
        }
        return "engine";
    }

    public static Map<String, Object> toMap(HistoryEvent e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", kind(e));
        m.put("eventType", e.getEventType());
        m.put("id", e.getId());
        m.put("tenantId", tenant(e));
        m.put("processInstanceId", e.getProcessInstanceId());
        m.put("rootProcessInstanceId", e.getRootProcessInstanceId());
        m.put("executionId", e.getExecutionId());
        m.put("processDefinitionId", e.getProcessDefinitionId());
        m.put("processDefinitionKey", e.getProcessDefinitionKey());
        m.put("caseInstanceId", e.getCaseInstanceId());
        m.put("caseExecutionId", e.getCaseExecutionId());
        m.put("caseDefinitionKey", e.getCaseDefinitionKey());
        m.put("sequenceCounter", e.getSequenceCounter());
        if (e instanceof HistoricScopeInstanceEvent s) {
            m.put("startTime", iso(s.getStartTime()));
            m.put("endTime", iso(s.getEndTime()));
        }
        if (e instanceof HistoricProcessInstanceEventEntity p) {
            m.put("businessKey", p.getBusinessKey());
            m.put("state", p.getState());
            m.put("superProcessInstanceId", p.getSuperProcessInstanceId());
            m.put("superCaseInstanceId", p.getSuperCaseInstanceId());
            m.put("deleteReason", p.getDeleteReason());
            m.put("startUserId", p.getStartUserId());
        } else if (e instanceof HistoricActivityInstanceEventEntity a) {
            m.put("activityId", a.getActivityId());
            m.put("activityType", a.getActivityType());
            m.put("activityInstanceId", a.getActivityInstanceId());
            m.put("parentActivityInstanceId", a.getParentActivityInstanceId());
            m.put("calledProcessInstanceId", a.getCalledProcessInstanceId());
            m.put("calledCaseInstanceId", a.getCalledCaseInstanceId());
            m.put("taskId", a.getTaskId());
            m.put("canceled", a.isCanceled());
        } else if (e instanceof HistoricTaskInstanceEventEntity t) {
            m.put("taskId", t.getTaskId());
            m.put("name", t.getName());
            m.put("taskDefinitionKey", t.getTaskDefinitionKey());
            m.put("assignee", t.getAssignee());
            m.put("owner", t.getOwner());
            m.put("parentTaskId", t.getParentTaskId());
            m.put("priority", t.getPriority());
            m.put("dueDate", iso(t.getDueDate()));
            m.put("deleteReason", t.getDeleteReason());
            m.put("taskState", t.getTaskState());
        } else if (e instanceof HistoricVariableUpdateEventEntity v) {
            m.put("variableName", v.getVariableName());
            m.put("variableInstanceId", v.getVariableInstanceId());
            m.put("serializerName", v.getSerializerName());
            m.put("revision", v.getRevision());
            m.put("scopeActivityInstanceId", v.getScopeActivityInstanceId());
        } else if (e instanceof HistoricCaseInstanceEventEntity c) {
            m.put("businessKey", c.getBusinessKey());
            m.put("state", caseState(c));
            m.put("superProcessInstanceId", c.getSuperProcessInstanceId());
            m.put("superCaseInstanceId", c.getSuperCaseInstanceId());
        } else if (e instanceof HistoricCaseActivityInstanceEventEntity c) {
            m.put("caseActivityId", c.getCaseActivityId());
            m.put("caseActivityType", c.getCaseActivityType());
            m.put("taskId", c.getTaskId());
            m.put("calledProcessInstanceId", c.getCalledProcessInstanceId());
            m.put("calledCaseInstanceId", c.getCalledCaseInstanceId());
            m.put("state", caseActivityState(c));
        }
        return m;
    }

    static String caseState(HistoricCaseInstanceEventEntity c) {
        if (c.isCompleted()) {
            return "completed";
        }
        if (c.isTerminated()) {
            return "terminated";
        }
        if (c.isClosed()) {
            return "closed";
        }
        if (c.isSuspended()) {
            return "suspended";
        }
        if (c.isFailed()) {
            return "failed";
        }
        return "active";
    }

    /** Raw CMMN state; the FLOWABLE-INTO-OPERATON D-25 audit label (exited vs terminated) is WS-ENGINE's projection. */
    static String caseActivityState(HistoricCaseActivityInstanceEventEntity c) {
        if (c.isCompleted()) {
            return "completed";
        }
        if (c.isTerminated()) {
            return "terminated";
        }
        if (c.isActive()) {
            return "active";
        }
        if (c.isEnabled()) {
            return "enabled";
        }
        if (c.isDisabled()) {
            return "disabled";
        }
        if (c.isSuspended()) {
            return "suspended";
        }
        return "available";
    }

    static String iso(Date d) {
        return d == null ? null : d.toInstant().toString();
    }
}
