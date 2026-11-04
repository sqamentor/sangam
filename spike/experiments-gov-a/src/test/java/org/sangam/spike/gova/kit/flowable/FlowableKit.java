package org.sangam.spike.gova.kit.flowable;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.flowable.common.engine.impl.calendar.BusinessCalendarImpl;
import org.flowable.common.engine.impl.calendar.DurationBusinessCalendar;
import org.flowable.common.engine.impl.calendar.MapBusinessCalendarManager;
import org.flowable.common.engine.impl.runtime.ClockReader;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.flowable.engine.migration.ActivityMigrationMapping;
import org.flowable.engine.migration.ProcessInstanceMigrationBuilder;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.identitylink.api.history.HistoricIdentityLink;
import org.flowable.job.api.Job;
import org.flowable.task.api.Task;
import org.sangam.spike.api.EngineAdapter;
import org.sangam.spike.flowable.FlowableEngineAdapter;
import org.sangam.spike.gova.support.BizCal;
import org.sangam.spike.gova.support.Db;
import org.sangam.spike.gova.support.Kit;

/** Flowable 8.0.0 side of {@link Kit}. */
public final class FlowableKit implements Kit {

    private String historyLevel = "audit (Flowable default; the adapter does not set it)";

    @Override
    public String engine() {
        return "flowable";
    }

    @Override
    public EngineAdapter create(Db.Target t) {
        FlowableEngineAdapter a = FlowableEngineAdapter.create(t.url(), t.user(), t.password());
        historyLevel = a.processEngine().getProcessEngineConfiguration().getHistoryLevel()
                + " (Flowable default; the adapter does not set it)";
        return a;
    }

    @Override
    public String historyLevel() {
        return historyLevel;
    }

    @Override
    public List<String> configurationNotes() {
        return List.of("adapter as installed: async executor off (timers via executeDueJobs), DMN and CMMN configurators,"
                        + " history level not set by the adapter (engine default)",
                "the engine clock is per engine configuration (shared by the DMN/CMMN engines)");
    }

    private static ProcessEngine pe(EngineAdapter a) {
        return ((FlowableEngineAdapter) a).processEngine();
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
            case "setDueDate" -> "TaskService.setDueDate()";
            case "delegate" -> "TaskService.delegateTask() / resolveTask()";
            case "assignmentHistory" -> "HistoryService.getHistoricIdentityLinksForTask()";
            case "broadcastSignal" -> "RuntimeService.signalEventReceived()";
            case "timers" -> "ManagementService.createTimerJobQuery()";
            case "rescheduleTimer" -> "ManagementService.rescheduleTimeDateJob()";
            case "suspend" -> "RuntimeService.suspendProcessInstanceById() / activateProcessInstanceById()";
            case "migrate" -> "ProcessMigrationService.createProcessInstanceMigrationBuilder().migrateToProcessDefinition()"
                    + ".addActivityMigrationMapping().migrate()";
            case "moveActivity" -> "RuntimeService.createChangeActivityStateBuilder().moveActivityIdTo().changeState()";
            case "bulkDelete" -> "RuntimeService.bulkDeleteProcessInstances()";
            case "registerWorkingDayCalendar" -> "ProcessEngineConfigurationImpl.getBusinessCalendarManager() "
                    + "(MapBusinessCalendarManager.addBusinessCalendar(name, ...), impl API) + flowable:businessCalendarName";
            case "hasMessageSubscription" -> "RuntimeService.createExecutionQuery().messageEventSubscriptionName()";
            case "definitions" -> "RepositoryService.createProcessDefinitionQuery()";
            case "instances" -> "RuntimeService.createProcessInstanceQuery() / HistoryService.createHistoricProcessInstanceQuery()";
            default -> op;
        };
    }

    // ------------------------------------------------------------------ variants

    @Override
    public String ns() {
        return "xmlns:flowable=\"http://flowable.org/bpmn\"";
    }

    @Override
    public String externalTask(String id, String topic, String extraAttrs) {
        return "<serviceTask id=\"" + id + "\" flowable:type=\"external-worker\" flowable:topic=\"" + topic + "\" "
                + (extraAttrs == null ? "" : extraAttrs) + "/>";
    }

    @Override
    public String parallelExternalTaskAttrs() {
        return "flowable:exclusive=\"false\"";
    }

    @Override
    public String dmnTask(String id, String decisionKey, String resultVar, boolean sameDeployment) {
        return "<serviceTask id=\"" + id + "\" flowable:type=\"dmn\"><extensionElements>"
                + "<flowable:field name=\"decisionTableReferenceKey\"><flowable:string>" + decisionKey
                + "</flowable:string></flowable:field>"
                + "<flowable:field name=\"decisionTaskThrowErrorOnNoHits\"><flowable:string>false</flowable:string></flowable:field>"
                + (sameDeployment ? "<flowable:field name=\"sameDeployment\"><flowable:string>true</flowable:string></flowable:field>" : "")
                + "</extensionElements></serviceTask>";
    }

    @Override
    public String callActivity(String id, String calledElement, List<String[]> ins, List<String[]> outs) {
        StringBuilder sb = new StringBuilder("<callActivity id=\"" + id + "\" calledElement=\"" + calledElement
                + "\"><extensionElements>");
        for (String[] in : ins) {
            sb.append("<flowable:in sourceExpression=\"").append(in[0]).append("\" target=\"").append(in[1]).append("\"/>");
        }
        for (String[] out : outs) {
            sb.append("<flowable:out source=\"").append(out[0]).append("\" target=\"").append(out[1]).append("\"/>");
        }
        return sb.append("</extensionElements></callActivity>").toString();
    }

    @Override
    public String dueDateAttr(String expression) {
        return "flowable:dueDate=\"" + expression + "\"";
    }

    @Override
    public String timerCalendarAttr(String calendarName) {
        return "flowable:businessCalendarName=\"" + calendarName + "\"";
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
        pe(a).getTaskService().setDueDate(taskId, due);
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
        for (HistoricIdentityLink l : pe(a).getHistoryService().getHistoricIdentityLinksForTask(taskId)) {
            out.add(l.getType() + ":" + (l.getUserId() != null ? l.getUserId() : "group=" + l.getGroupId()));
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
        for (Job j : pe(a).getManagementService().createTimerJobQuery().processInstanceId(pid).orderByJobDuedate()
                .asc().list()) {
            out.add(new String[]{j.getId(), j.getDuedate() == null ? null : j.getDuedate().toInstant().toString()});
        }
        return out;
    }

    @Override
    public String rescheduleTimer(EngineAdapter a, String jobId, Date newDue) {
        String iso = ZonedDateTime.ofInstant(newDue.toInstant(), BizCal.IST).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        Job j = pe(a).getManagementService().rescheduleTimeDateJob(jobId, iso);
        return j.getId();
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
        ProcessInstanceMigrationBuilder b = pe(a).getProcessMigrationService().createProcessInstanceMigrationBuilder()
                .migrateToProcessDefinition(targetDefinitionId);
        mapping.forEach((from, to) -> b.addActivityMigrationMapping(ActivityMigrationMapping.createMappingFor(from, to)));
        for (String pid : pids) {
            b.migrate(pid);
        }
    }

    @Override
    public void moveActivity(EngineAdapter a, String pid, String fromActivity, String toActivity) {
        pe(a).getRuntimeService().createChangeActivityStateBuilder().processInstanceId(pid)
                .moveActivityIdTo(fromActivity, toActivity).changeState();
    }

    @Override
    public void bulkDelete(EngineAdapter a, List<String> pids, String reason) {
        pe(a).getRuntimeService().bulkDeleteProcessInstances(pids, reason);
    }

    @Override
    public void registerWorkingDayCalendar(EngineAdapter a, String name, BizCal cal) {
        ProcessEngineConfigurationImpl c = cfg(a);
        MapBusinessCalendarManager m = (MapBusinessCalendarManager) c.getBusinessCalendarManager();
        m.addBusinessCalendar(name, new WorkingDayCalendar(c.getClock(), cal));
    }

    /** "PnD" = n working days; everything else as the standard duration calendar. */
    static final class WorkingDayCalendar extends BusinessCalendarImpl {
        private final BizCal cal;
        private final DurationBusinessCalendar standard;

        WorkingDayCalendar(ClockReader clock, BizCal cal) {
            super(clock);
            this.cal = cal;
            this.standard = new DurationBusinessCalendar(clock);
        }

        @Override
        public Date resolveDuedate(String duedate, int maxIterations) {
            int days = BizCal.workingDays(duedate);
            if (days >= 0) {
                return cal.addWorkingDays(clockReader.getCurrentTime(), days);
            }
            return standard.resolveDuedate(duedate, maxIterations);
        }
    }

    @Override
    public boolean hasMessageSubscription(EngineAdapter a, String messageName, String businessKey) {
        return pe(a).getRuntimeService().createExecutionQuery().messageEventSubscriptionName(messageName)
                .processInstanceBusinessKey(businessKey, true).count() > 0;
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
        var ms = pe(a).getManagementService();
        for (Job j : ms.createTimerJobQuery().processInstanceId(pid).withException().list()) {
            out.add("timer: " + j.getExceptionMessage());
        }
        for (Job j : ms.createJobQuery().processInstanceId(pid).withException().list()) {
            out.add("job: " + j.getExceptionMessage());
        }
        for (Job j : ms.createDeadLetterJobQuery().processInstanceId(pid).list()) {
            out.add("deadletter: " + j.getExceptionMessage());
        }
        return out;
    }

    @Override
    public List<String> externalWorkInfo(EngineAdapter a, String pid) {
        List<String> out = new ArrayList<>();
        for (var j : pe(a).getManagementService().createExternalWorkerJobQuery().processInstanceId(pid).list()) {
            out.add(j.getJobHandlerConfiguration() + "|" + j.getLockOwner() + "|" + j.getLockExpirationTime() + "|"
                    + j.getRetries() + "|scope=" + j.getScopeType() + "|elem=" + j.getElementId());
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
