package org.sangam.spike.gova.kit.operaton;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.history.HistoricIdentityLinkLog;
import org.operaton.bpm.engine.history.HistoricProcessInstance;
import org.operaton.bpm.engine.impl.calendar.BusinessCalendar;
import org.operaton.bpm.engine.impl.calendar.DurationBusinessCalendar;
import org.operaton.bpm.engine.impl.calendar.MapBusinessCalendarManager;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.bpm.engine.impl.util.ClockUtil;
import org.operaton.bpm.engine.migration.MigrationInstructionsBuilder;
import org.operaton.bpm.engine.migration.MigrationPlan;
import org.operaton.bpm.engine.runtime.Job;
import org.operaton.bpm.engine.runtime.ProcessInstance;
import org.operaton.bpm.engine.task.Task;
import org.sangam.spike.api.EngineAdapter;
import org.sangam.spike.gova.support.BizCal;
import org.sangam.spike.gova.support.Db;
import org.sangam.spike.gova.support.Kit;
import org.sangam.spike.operaton.OperatonEngineAdapter;

/** Operaton 2.2.0 side of {@link Kit}. */
public final class OperatonKit implements Kit {

    @Override
    public String engine() {
        return "operaton";
    }

    @Override
    public EngineAdapter create(Db.Target t) {
        return new OperatonEngineAdapter(t.url(), t.user(), t.password());
    }

    @Override
    public String historyLevel() {
        return "full (adapter: HISTORY_FULL)";
    }

    @Override
    public List<String> configurationNotes() {
        return List.of("adapter as installed: HISTORY_FULL, job executor off (timers via executeDueJobs), "
                        + "enforceHistoryTimeToLive=false (ledger W-3), DMN and CMMN on, authorization off",
                "ClockUtil is JVM-wide (ledger W-7)",
                "javaSerializationFormatEnabled=false (engine default): no List/Map variables are used by the models");
    }

    private static ProcessEngine pe(EngineAdapter a) {
        return ((OperatonEngineAdapter) a).processEngine();
    }

    private static ProcessEngineConfigurationImpl cfg(EngineAdapter a) {
        return (ProcessEngineConfigurationImpl) pe(a).getProcessEngineConfiguration();
    }

    @Override
    public String api(String op) {
        return switch (op) {
            case "historicActivityCount" -> "HistoryService.createHistoricActivityInstanceQuery()";
            case "taskIds" -> "TaskService.createTaskQuery()";
            case "countTasksWithProcessVariable" -> "TaskQuery.processVariableValueEquals()";
            case "setAssignee" -> "TaskService.setAssignee()";
            case "setDueDate" -> "Task.setDueDate() + TaskService.saveTask()";
            case "delegate" -> "TaskService.delegateTask() / resolveTask()";
            case "assignmentHistory" -> "HistoryService.createHistoricIdentityLinkLogQuery()";
            case "broadcastSignal" -> "RuntimeService.signalEventReceived()";
            case "timers" -> "ManagementService.createJobQuery().timers()";
            case "rescheduleTimer" -> "ManagementService.setJobDuedate()";
            case "suspend" -> "RuntimeService.suspendProcessInstanceById() / activateProcessInstanceById()";
            case "migrate" -> "RuntimeService.createMigrationPlan().mapEqualActivities().build() + newMigration(plan).execute()";
            case "moveActivity" -> "RuntimeService.createProcessInstanceModification().cancelAllForActivity().startBeforeActivity()";
            case "bulkDelete" -> "RuntimeService.deleteProcessInstances(List, reason, false, true)";
            case "registerWorkingDayCalendar" -> "ProcessEngineConfigurationImpl.getBusinessCalendarManager() "
                    + "(MapBusinessCalendarManager.addBusinessCalendar(\"duration\", ...), impl API)";
            case "hasMessageSubscription" -> "RuntimeService.createExecutionQuery().messageEventSubscriptionName()";
            case "definitions" -> "RepositoryService.createProcessDefinitionQuery()";
            case "instances" -> "RuntimeService.createProcessInstanceQuery() / HistoryService.createHistoricProcessInstanceQuery()";
            default -> op;
        };
    }

    // ------------------------------------------------------------------ variants

    @Override
    public String ns() {
        return "xmlns:operaton=\"http://operaton.org/schema/1.0/bpmn\"";
    }

    @Override
    public String externalTask(String id, String topic, String extraAttrs) {
        return "<serviceTask id=\"" + id + "\" operaton:type=\"external\" operaton:topic=\"" + topic + "\" "
                + (extraAttrs == null ? "" : extraAttrs) + "/>";
    }

    @Override
    public String parallelExternalTaskAttrs() {
        return "";
    }

    @Override
    public String dmnTask(String id, String decisionKey, String resultVar, boolean sameDeployment) {
        return "<businessRuleTask id=\"" + id + "\" operaton:decisionRef=\"" + decisionKey + "\""
                + (sameDeployment ? " operaton:decisionRefBinding=\"deployment\"" : "")
                + " operaton:resultVariable=\"" + resultVar + "\" operaton:mapDecisionResult=\"singleEntry\"/>";
    }

    @Override
    public String callActivity(String id, String calledElement, List<String[]> ins, List<String[]> outs) {
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

    @Override
    public String dueDateAttr(String expression) {
        return "operaton:dueDate=\"" + expression + "\"";
    }

    @Override
    public String timerCalendarAttr(String calendarName) {
        return ""; // no per-timer business calendar in Operaton 2.2.0
    }

    // ------------------------------------------------------------------ native

    @Override
    public long historicActivityCount(EngineAdapter a, String pid, String activityId) {
        return pe(a).getHistoryService().createHistoricActivityInstanceQuery().processInstanceId(pid)
                .activityId(activityId).count();
    }

    @Override
    public List<String> taskIds(EngineAdapter a, String pid, String processKey, String taskKey, int max) {
        var q = pe(a).getTaskService().createTaskQuery();
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

    @Override
    public long countTasksWithProcessVariable(EngineAdapter a, String processKey, String variable, Object value) {
        return pe(a).getTaskService().createTaskQuery().processDefinitionKey(processKey)
                .processVariableValueEquals(variable, value).count();
    }

    @Override
    public void setAssignee(EngineAdapter a, String taskId, String userId) {
        pe(a).getTaskService().setAssignee(taskId, userId);
    }

    @Override
    public String assignee(EngineAdapter a, String taskId) {
        Task t = pe(a).getTaskService().createTaskQuery().taskId(taskId).singleResult();
        return t == null ? null : t.getAssignee();
    }

    @Override
    public void setDueDate(EngineAdapter a, String taskId, Date due) {
        var ts = pe(a).getTaskService();
        Task t = ts.createTaskQuery().taskId(taskId).singleResult();
        t.setDueDate(due);
        ts.saveTask(t);
    }

    @Override
    public Date dueDate(EngineAdapter a, String taskId) {
        Task t = pe(a).getTaskService().createTaskQuery().taskId(taskId).singleResult();
        return t == null ? null : t.getDueDate();
    }

    @Override
    public void delegate(EngineAdapter a, String taskId, String userId) {
        pe(a).getTaskService().delegateTask(taskId, userId);
    }

    @Override
    public void resolve(EngineAdapter a, String taskId, Map<String, Object> variables) {
        pe(a).getTaskService().resolveTask(taskId, variables);
    }

    @Override
    public String owner(EngineAdapter a, String taskId) {
        Task t = pe(a).getTaskService().createTaskQuery().taskId(taskId).singleResult();
        return t == null ? null : t.getOwner();
    }

    @Override
    public List<String> assignmentHistory(EngineAdapter a, String taskId) {
        List<String> out = new ArrayList<>();
        for (HistoricIdentityLinkLog l : pe(a).getHistoryService().createHistoricIdentityLinkLogQuery().taskId(taskId)
                .orderByTime().asc().list()) {
            out.add(l.getType() + ":" + l.getOperationType() + ":" + (l.getUserId() != null ? l.getUserId() : "group=" + l.getGroupId()));
        }
        return out;
    }

    @Override
    public void broadcastSignal(EngineAdapter a, String signalName) {
        pe(a).getRuntimeService().signalEventReceived(signalName);
    }

    @Override
    public List<String[]> timers(EngineAdapter a, String pid) {
        List<String[]> out = new ArrayList<>();
        for (Job j : pe(a).getManagementService().createJobQuery().processInstanceId(pid).timers().orderByJobDuedate()
                .asc().list()) {
            out.add(new String[]{j.getId(), j.getDuedate() == null ? null : j.getDuedate().toInstant().toString()});
        }
        return out;
    }

    @Override
    public String rescheduleTimer(EngineAdapter a, String jobId, Date newDue) {
        pe(a).getManagementService().setJobDuedate(jobId, newDue);
        return jobId;
    }

    @Override
    public void suspend(EngineAdapter a, String pid) {
        pe(a).getRuntimeService().suspendProcessInstanceById(pid);
    }

    @Override
    public void activate(EngineAdapter a, String pid) {
        pe(a).getRuntimeService().activateProcessInstanceById(pid);
    }

    @Override
    public boolean isSuspended(EngineAdapter a, String pid) {
        ProcessInstance p = pe(a).getRuntimeService().createProcessInstanceQuery().processInstanceId(pid).singleResult();
        return p != null && p.isSuspended();
    }

    @Override
    public String latestDefinitionId(EngineAdapter a, String key) {
        return pe(a).getRepositoryService().createProcessDefinitionQuery().processDefinitionKey(key).latestVersion()
                .singleResult().getId();
    }

    @Override
    public String definitionIdOf(EngineAdapter a, String pid) {
        ProcessInstance p = pe(a).getRuntimeService().createProcessInstanceQuery().processInstanceId(pid).singleResult();
        if (p != null) {
            return p.getProcessDefinitionId();
        }
        HistoricProcessInstance h = pe(a).getHistoryService().createHistoricProcessInstanceQuery().processInstanceId(pid)
                .singleResult();
        return h == null ? null : h.getProcessDefinitionId();
    }

    @Override
    public void migrate(EngineAdapter a, List<String> pids, String targetDefinitionId, Map<String, String> mapping) {
        var rs = pe(a).getRuntimeService();
        String source = definitionIdOf(a, pids.get(0));
        MigrationInstructionsBuilder b = rs.createMigrationPlan(source, targetDefinitionId).mapEqualActivities();
        mapping.forEach(b::mapActivities);
        MigrationPlan plan = b.build();
        rs.newMigration(plan).processInstanceIds(pids).execute();
    }

    @Override
    public void moveActivity(EngineAdapter a, String pid, String fromActivity, String toActivity) {
        pe(a).getRuntimeService().createProcessInstanceModification(pid).cancelAllForActivity(fromActivity)
                .startBeforeActivity(toActivity).execute();
    }

    @Override
    public void bulkDelete(EngineAdapter a, List<String> pids, String reason) {
        pe(a).getRuntimeService().deleteProcessInstances(pids, reason, false, true);
    }

    @Override
    public void registerWorkingDayCalendar(EngineAdapter a, String name, BizCal cal) {
        MapBusinessCalendarManager m = (MapBusinessCalendarManager) cfg(a).getBusinessCalendarManager();
        m.addBusinessCalendar(DurationBusinessCalendar.NAME, new WorkingDayCalendar(cal));
    }

    /** "PnD" = n working days; everything else as the standard duration calendar. */
    static final class WorkingDayCalendar implements BusinessCalendar {
        private final BizCal cal;
        private final DurationBusinessCalendar standard = new DurationBusinessCalendar();

        WorkingDayCalendar(BizCal cal) {
            this.cal = cal;
        }

        @Override
        public Date resolveDuedate(String duedate) {
            return resolveDuedate(duedate, (Date) null);
        }

        @Override
        public Date resolveDuedate(String duedate, Date startDate) {
            int days = BizCal.workingDays(duedate);
            if (days >= 0) {
                return cal.addWorkingDays(startDate != null ? startDate : ClockUtil.getCurrentTime(), days);
            }
            return standard.resolveDuedate(duedate, startDate);
        }

        @Override
        public Date resolveDuedate(String duedate, Task task) {
            return resolveDuedate(duedate, (Date) null);
        }
    }

    @Override
    public boolean hasMessageSubscription(EngineAdapter a, String messageName, String businessKey) {
        return pe(a).getRuntimeService().createExecutionQuery().messageEventSubscriptionName(messageName)
                .processInstanceBusinessKey(businessKey).count() > 0;
    }

    @Override
    public long runningInstances(EngineAdapter a, String key) {
        return pe(a).getRuntimeService().createProcessInstanceQuery().processDefinitionKey(key).count();
    }

    @Override
    public long finishedInstances(EngineAdapter a, String key) {
        return pe(a).getHistoryService().createHistoricProcessInstanceQuery().processDefinitionKey(key).finished().count();
    }

    @Override
    public List<String> subProcessInstances(EngineAdapter a, String pid) {
        List<String> out = new ArrayList<>();
        for (HistoricProcessInstance h : pe(a).getHistoryService().createHistoricProcessInstanceQuery()
                .superProcessInstanceId(pid).list()) {
            out.add(h.getId());
        }
        return out;
    }

    @Override
    public List<String> jobFailures(EngineAdapter a, String pid) {
        List<String> out = new ArrayList<>();
        for (Job j : pe(a).getManagementService().createJobQuery().processInstanceId(pid).withException().list()) {
            out.add(j.getExceptionMessage());
        }
        return out;
    }

    @Override
    public List<String> externalWorkInfo(EngineAdapter a, String pid) {
        List<String> out = new ArrayList<>();
        for (var t : pe(a).getExternalTaskService().createExternalTaskQuery().processInstanceId(pid).list()) {
            out.add(t.getTopicName() + "|" + t.getWorkerId() + "|" + t.getLockExpirationTime() + "|" + t.getRetries()
                    + "|elem=" + t.getActivityId());
        }
        return out;
    }

    @Override
    public List<String> allInstanceIds(EngineAdapter a, String key) {
        List<String> out = new ArrayList<>();
        for (HistoricProcessInstance h : pe(a).getHistoryService().createHistoricProcessInstanceQuery()
                .processDefinitionKey(key).orderByProcessInstanceStartTime().asc().list()) {
            out.add(h.getId());
        }
        return out;
    }
}
