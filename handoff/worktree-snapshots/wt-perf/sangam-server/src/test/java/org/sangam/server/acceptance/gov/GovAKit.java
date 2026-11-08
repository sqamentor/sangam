// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.acceptance.gov;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.history.HistoricIdentityLinkLog;
import org.operaton.bpm.engine.history.HistoricProcessInstance;
import org.operaton.bpm.engine.migration.MigrationInstructionsBuilder;
import org.operaton.bpm.engine.migration.MigrationPlan;
import org.operaton.bpm.engine.runtime.Job;
import org.operaton.bpm.engine.runtime.ProcessInstance;
import org.operaton.bpm.engine.task.Task;
import org.sangam.core.api.EngineAdapter;

/**
 * The Operaton side of the spike's gov-a {@code Kit} (model variants + the native engine API the cases use), over the
 * server's {@link ProcessEngine} bean. Key-based queries are scoped to tenant {@link GovATenantAdapter#TENANT}. The
 * {@code EngineAdapter} parameters are kept so the ported scenario bodies read as in the spike.
 */
final class GovAKit {

    private static final String T = GovATenantAdapter.TENANT;
    private final ProcessEngine pe;
    private final GovATenantAdapter scoped;

    GovAKit(ProcessEngine pe, GovATenantAdapter scoped) {
        this.pe = pe;
        this.scoped = scoped;
    }

    String engine() {
        return "operaton";
    }

    String api(String op) {
        return switch (op) {
            case "historicActivityCount" -> "HistoryService.createHistoricActivityInstanceQuery()";
            case "taskIds" -> "TaskService.createTaskQuery()";
            case "countTasksWithProcessVariable" -> "TaskQuery.processVariableValueEquals()";
            case "setAssignee" -> "TaskService.setAssignee()";
            case "setDueDate" -> "Task.setDueDate() + TaskService.saveTask()";
            case "delegate" -> "TaskService.delegateTask() / resolveTask()";
            case "assignmentHistory" -> "HistoryService.createHistoricIdentityLinkLogQuery()";
            case "broadcastSignal" -> "RuntimeService.createSignalEvent().tenantId().send()";
            case "timers" -> "ManagementService.createJobQuery().timers()";
            case "rescheduleTimer" -> "ManagementService.setJobDuedate()";
            case "migrate" -> "RuntimeService.createMigrationPlan().mapEqualActivities().build() + newMigration(plan).execute()";
            case "moveActivity" -> "RuntimeService.createProcessInstanceModification()";
            case "bulkDelete" -> "RuntimeService.deleteProcessInstances(List, reason, false, true)";
            case "hasMessageSubscription" -> "RuntimeService.createExecutionQuery().messageEventSubscriptionName()";
            case "definitions" -> "RepositoryService.createProcessDefinitionQuery()";
            case "instances" -> "RuntimeService.createProcessInstanceQuery() / HistoryService.createHistoricProcessInstanceQuery()";
            default -> op;
        };
    }

    // ------------------------------------------------------------------ variants

    String ns() {
        return "xmlns:operaton=\"http://operaton.org/schema/1.0/bpmn\"";
    }

    String externalTask(String id, String topic, String extraAttrs) {
        return "<serviceTask id=\"" + id + "\" operaton:type=\"external\" operaton:topic=\""
                + GovATenantAdapter.TOPIC_PREFIX + topic + "\" " + (extraAttrs == null ? "" : extraAttrs) + "/>";
    }

    String parallelExternalTaskAttrs() {
        return "";
    }

    String dmnTask(String id, String decisionKey, String resultVar, boolean sameDeployment) {
        return "<businessRuleTask id=\"" + id + "\" operaton:decisionRef=\"" + decisionKey + "\""
                + (sameDeployment ? " operaton:decisionRefBinding=\"deployment\"" : "")
                + " operaton:resultVariable=\"" + resultVar + "\" operaton:mapDecisionResult=\"singleEntry\"/>";
    }

    String callActivity(String id, String calledElement, List<String[]> ins, List<String[]> outs) {
        StringBuilder sb = new StringBuilder("<callActivity id=\"" + id + "\" calledElement=\"" + calledElement
                + "\"><extensionElements>");
        for (String[] in : ins) {
            sb.append("<operaton:in sourceExpression=\"").append(in[0]).append("\" target=\"").append(in[1]).append("\"/>");
        }
        for (String[] out : outs) {
            sb.append("<operaton:out source=\"").append(out[0]).append("\" target=\"").append(out[1]).append("\"/>");
        }
        return sb.append("</extensionElements></callActivity>").toString();
    }

    String dueDateAttr(String expression) {
        return "operaton:dueDate=\"" + expression + "\"";
    }

    // ------------------------------------------------------------------ native

    long historicActivityCount(EngineAdapter a, String pid, String activityId) {
        return pe.getHistoryService().createHistoricActivityInstanceQuery().processInstanceId(pid)
                .activityId(activityId).count();
    }

    List<String> taskIds(EngineAdapter a, String pid, String processKey, String taskKey, int max) {
        var q = pe.getTaskService().createTaskQuery().tenantIdIn(T);
        if (pid != null) {
            q.processInstanceId(pid);
        }
        if (processKey != null) {
            q.processDefinitionKey(processKey);
        }
        if (taskKey != null) {
            q.taskDefinitionKey(taskKey);
        }
        List<String> out = new ArrayList<>();
        for (Task t : q.orderByTaskId().asc().listPage(0, max)) {
            out.add(t.getId());
        }
        return out;
    }

    long countTasksWithProcessVariable(EngineAdapter a, String processKey, String variable, Object value) {
        return pe.getTaskService().createTaskQuery().tenantIdIn(T).processDefinitionKey(processKey)
                .processVariableValueEquals(variable, value).count();
    }

    void setAssignee(EngineAdapter a, String taskId, String userId) {
        pe.getTaskService().setAssignee(taskId, userId);
    }

    String assignee(EngineAdapter a, String taskId) {
        Task t = pe.getTaskService().createTaskQuery().taskId(taskId).singleResult();
        return t == null ? null : t.getAssignee();
    }

    void setDueDate(EngineAdapter a, String taskId, Date due) {
        var ts = pe.getTaskService();
        Task t = ts.createTaskQuery().taskId(taskId).singleResult();
        t.setDueDate(due);
        ts.saveTask(t);
    }

    Date dueDate(EngineAdapter a, String taskId) {
        Task t = pe.getTaskService().createTaskQuery().taskId(taskId).singleResult();
        return t == null ? null : t.getDueDate();
    }

    void delegate(EngineAdapter a, String taskId, String userId) {
        pe.getTaskService().delegateTask(taskId, userId);
    }

    void resolve(EngineAdapter a, String taskId, Map<String, Object> variables) {
        pe.getTaskService().resolveTask(taskId, variables);
    }

    String owner(EngineAdapter a, String taskId) {
        Task t = pe.getTaskService().createTaskQuery().taskId(taskId).singleResult();
        return t == null ? null : t.getOwner();
    }

    List<String> assignmentHistory(EngineAdapter a, String taskId) {
        List<String> out = new ArrayList<>();
        for (HistoricIdentityLinkLog l : pe.getHistoryService().createHistoricIdentityLinkLogQuery().taskId(taskId)
                .orderByTime().asc().list()) {
            out.add(l.getType() + ":" + l.getOperationType() + ":" + (l.getUserId() != null ? l.getUserId() : "group=" + l.getGroupId()));
        }
        return out;
    }

    /** Tenant-scoped broadcast (the spike broadcast to its private engine). */
    void broadcastSignal(EngineAdapter a, String signalName) {
        pe.getRuntimeService().createSignalEvent(signalName).tenantId(T).send();
    }

    List<String[]> timers(EngineAdapter a, String pid) {
        List<String[]> out = new ArrayList<>();
        for (Job j : pe.getManagementService().createJobQuery().processInstanceId(pid).timers().orderByJobDuedate()
                .asc().list()) {
            out.add(new String[]{j.getId(), j.getDuedate() == null ? null : j.getDuedate().toInstant().toString()});
        }
        return out;
    }

    String rescheduleTimer(EngineAdapter a, String jobId, Date newDue) {
        pe.getManagementService().setJobDuedate(jobId, newDue);
        return jobId;
    }

    String latestDefinitionId(EngineAdapter a, String key) {
        return pe.getRepositoryService().createProcessDefinitionQuery().tenantIdIn(T).processDefinitionKey(key)
                .latestVersion().singleResult().getId();
    }

    String definitionIdOf(EngineAdapter a, String pid) {
        ProcessInstance p = pe.getRuntimeService().createProcessInstanceQuery().processInstanceId(pid).singleResult();
        if (p != null) {
            return p.getProcessDefinitionId();
        }
        HistoricProcessInstance h = pe.getHistoryService().createHistoricProcessInstanceQuery().processInstanceId(pid)
                .singleResult();
        return h == null ? null : h.getProcessDefinitionId();
    }

    void migrate(EngineAdapter a, List<String> pids, String targetDefinitionId, Map<String, String> mapping) {
        var rs = pe.getRuntimeService();
        String source = definitionIdOf(a, pids.get(0));
        MigrationInstructionsBuilder b = rs.createMigrationPlan(source, targetDefinitionId).mapEqualActivities();
        mapping.forEach(b::mapActivities);
        MigrationPlan plan = b.build();
        rs.newMigration(plan).processInstanceIds(pids).execute();
    }

    void moveActivity(EngineAdapter a, String pid, String fromActivity, String toActivity) {
        pe.getRuntimeService().createProcessInstanceModification(pid).cancelAllForActivity(fromActivity)
                .startBeforeActivity(toActivity).execute();
    }

    void bulkDelete(EngineAdapter a, List<String> pids, String reason) {
        pe.getRuntimeService().deleteProcessInstances(pids, reason, false, true);
    }

    boolean hasMessageSubscription(EngineAdapter a, String messageName, String businessKey) {
        return pe.getRuntimeService().createExecutionQuery().tenantIdIn(T).messageEventSubscriptionName(messageName)
                .processInstanceBusinessKey(scoped.bk(businessKey)).count() > 0;
    }

    long runningInstances(EngineAdapter a, String key) {
        return pe.getRuntimeService().createProcessInstanceQuery().tenantIdIn(T).processDefinitionKey(key).count();
    }

    long finishedInstances(EngineAdapter a, String key) {
        return pe.getHistoryService().createHistoricProcessInstanceQuery().tenantIdIn(T).processDefinitionKey(key)
                .finished().count();
    }

    List<String> subProcessInstances(EngineAdapter a, String pid) {
        List<String> out = new ArrayList<>();
        for (HistoricProcessInstance h : pe.getHistoryService().createHistoricProcessInstanceQuery()
                .superProcessInstanceId(pid).list()) {
            out.add(h.getId());
        }
        return out;
    }

    List<String> jobFailures(EngineAdapter a, String pid) {
        List<String> out = new ArrayList<>();
        for (Job j : pe.getManagementService().createJobQuery().processInstanceId(pid).withException().list()) {
            out.add(j.getExceptionMessage());
        }
        return out;
    }

    List<String> externalWorkInfo(EngineAdapter a, String pid) {
        List<String> out = new ArrayList<>();
        for (var t : pe.getExternalTaskService().createExternalTaskQuery().processInstanceId(pid).list()) {
            out.add(t.getTopicName() + "|" + t.getWorkerId() + "|" + t.getLockExpirationTime() + "|" + t.getRetries()
                    + "|elem=" + t.getActivityId());
        }
        return out;
    }

    List<String> allInstanceIds(EngineAdapter a, String key) {
        List<String> out = new ArrayList<>();
        for (HistoricProcessInstance h : pe.getHistoryService().createHistoricProcessInstanceQuery().tenantIdIn(T)
                .processDefinitionKey(key).orderByProcessInstanceStartTime().asc().list()) {
            out.add(h.getId());
        }
        return out;
    }
}
