package org.sangam.spike.govb.support;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.history.HistoricProcessInstance;
import org.operaton.bpm.engine.history.HistoricTaskInstance;
import org.operaton.bpm.engine.runtime.CaseExecution;
import org.operaton.bpm.engine.runtime.Job;

import static org.sangam.spike.govb.support.Rec.nat;

/** {@link Native} on Operaton 2.2.0's public service API. */
final class NativeOperaton implements Native {

    private final ProcessEngine e;

    NativeOperaton(ProcessEngine e) {
        this.e = e;
    }

    @Override
    public long runningInstances(String key) {
        nat("Operaton RuntimeService.createProcessInstanceQuery().processDefinitionKey().count()");
        return e.getRuntimeService().createProcessInstanceQuery().processDefinitionKey(key).count();
    }

    @Override
    public long finishedInstances(String key) {
        nat("Operaton HistoryService.createHistoricProcessInstanceQuery().finished()");
        return e.getHistoryService().createHistoricProcessInstanceQuery().processDefinitionKey(key).finished().count();
    }

    @Override
    public void suspend(String pid) {
        nat("Operaton RuntimeService.suspendProcessInstanceById");
        e.getRuntimeService().suspendProcessInstanceById(pid);
    }

    @Override
    public void activate(String pid) {
        nat("Operaton RuntimeService.activateProcessInstanceById");
        e.getRuntimeService().activateProcessInstanceById(pid);
    }

    @Override
    public boolean isSuspended(String pid) {
        nat("Operaton ProcessInstance.isSuspended");
        return e.getRuntimeService().createProcessInstanceQuery().processInstanceId(pid).singleResult().isSuspended();
    }

    @Override
    public void setVariable(String pid, String name, Object value) {
        nat("Operaton RuntimeService.setVariable");
        e.getRuntimeService().setVariable(pid, name, value);
    }

    @Override
    public List<String> enabledCaseItems(String caseId, String planItemId) {
        nat("Operaton CaseService.createCaseExecutionQuery().activityId().enabled()");
        List<String> ids = new ArrayList<>();
        for (CaseExecution ce : e.getCaseService().createCaseExecutionQuery().caseInstanceId(caseId)
                .activityId(planItemId).enabled().list()) {
            ids.add(ce.getId());
        }
        return ids;
    }

    @Override
    public void startCaseItem(String itemId) {
        nat("Operaton CaseService.manuallyStartCaseExecution");
        e.getCaseService().manuallyStartCaseExecution(itemId);
    }

    @Override
    public List<String> historicByBusinessKey(String key, String bk) {
        nat("Operaton HistoryService.createHistoricProcessInstanceQuery().processInstanceBusinessKey()");
        return e.getHistoryService().createHistoricProcessInstanceQuery().processDefinitionKey(key)
                .processInstanceBusinessKey(bk).list().stream().map(HistoricProcessInstance::getId).toList();
    }

    @Override
    public long runtimeInstancesByVar(String key, String name, Object value) {
        nat("Operaton ProcessInstanceQuery.variableValueEquals");
        return e.getRuntimeService().createProcessInstanceQuery().processDefinitionKey(key)
                .variableValueEquals(name, value).count();
    }

    @Override
    public long runtimeInstancesByVarLike(String key, String name, String like) {
        nat("Operaton ProcessInstanceQuery.variableValueLike");
        return e.getRuntimeService().createProcessInstanceQuery().processDefinitionKey(key)
                .variableValueLike(name, like).count();
    }

    @Override
    public long historicInstancesByVar(String key, String name, Object value) {
        nat("Operaton HistoricProcessInstanceQuery.variableValueEquals");
        return e.getHistoryService().createHistoricProcessInstanceQuery().processDefinitionKey(key)
                .variableValueEquals(name, value).count();
    }

    @Override
    public long tasksByProcessVar(String name, Object value) {
        nat("Operaton TaskQuery.processVariableValueEquals");
        return e.getTaskService().createTaskQuery().processVariableValueEquals(name, value).count();
    }

    @Override
    public long failedWork(String pid) {
        nat("Operaton RuntimeService.createIncidentQuery()");
        return e.getRuntimeService().createIncidentQuery().processInstanceId(pid).count();
    }

    @Override
    public List<Map<String, Object>> evaluateDecision(String key, String tenant, Map<String, Object> vars) {
        nat("Operaton DecisionService.evaluateDecisionTableByKey");
        var b = e.getDecisionService().evaluateDecisionTableByKey(key).variables(vars);
        if (tenant != null) {
            b.decisionDefinitionTenantId(tenant);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        b.evaluate().getResultList().forEach(m -> out.add(new HashMap<>(m)));
        return out;
    }

    @Override
    public List<String> subProcessInstances(String superPid) {
        nat("Operaton HistoricProcessInstanceQuery.superProcessInstanceId");
        return e.getHistoryService().createHistoricProcessInstanceQuery().superProcessInstanceId(superPid).list()
                .stream().map(HistoricProcessInstance::getId).toList();
    }

    @Override
    public List<String> processInstancesOfCase(String caseId) {
        nat("Operaton HistoricProcessInstanceQuery.superCaseInstanceId");
        return e.getHistoryService().createHistoricProcessInstanceQuery().superCaseInstanceId(caseId).list()
                .stream().map(HistoricProcessInstance::getId).toList();
    }

    @Override
    public long timerJobs(String pid) {
        nat("Operaton ManagementService.createJobQuery().timers()");
        return e.getManagementService().createJobQuery().processInstanceId(pid).timers().count();
    }

    @Override
    public Instant earliestTimerDue(String pid) {
        nat("Operaton ManagementService.createJobQuery().timers()");
        return e.getManagementService().createJobQuery().processInstanceId(pid).timers().list().stream()
                .map(Job::getDuedate).filter(d -> d != null).map(Date::toInstant).min(Instant::compareTo).orElse(null);
    }

    @Override
    public void rescheduleTimers(String pid, Instant due) {
        nat("Operaton ManagementService.setJobDuedate");
        for (Job j : e.getManagementService().createJobQuery().processInstanceId(pid).timers().list()) {
            e.getManagementService().setJobDuedate(j.getId(), Date.from(due));
        }
    }

    @Override
    public String processDefinitionId(String key, String tenant) {
        nat("Operaton RepositoryService.createProcessDefinitionQuery()");
        var q = e.getRepositoryService().createProcessDefinitionQuery().processDefinitionKey(key).latestVersion();
        if (tenant != null) {
            q.tenantIdIn(tenant);
        }
        return q.singleResult().getId();
    }

    @Override
    public long historicActivityCount(String pid, String activityId) {
        nat("Operaton HistoryService.createHistoricActivityInstanceQuery()");
        return e.getHistoryService().createHistoricActivityInstanceQuery().processInstanceId(pid).activityId(activityId)
                .count();
    }

    @Override
    public Map<String, Long> finishedTasksByAssignee(String key) {
        nat("Operaton HistoryService.createHistoricTaskInstanceQuery().finished()");
        Map<String, Long> out = new LinkedHashMap<>();
        for (HistoricTaskInstance t : e.getHistoryService().createHistoricTaskInstanceQuery().processDefinitionKey(key)
                .finished().list()) {
            out.merge(String.valueOf(t.getAssignee()), 1L, Long::sum);
        }
        return out;
    }

    @Override
    public long finishedByEndActivity(String key, String endActivityId) {
        nat("Operaton HistoricProcessInstance.getEndActivityId");
        return e.getHistoryService().createHistoricProcessInstanceQuery().processDefinitionKey(key).finished().list()
                .stream().filter(h -> endActivityId.equals(h.getEndActivityId())).count();
    }

    @Override
    public double avgDurationMs(String key) {
        nat("Operaton HistoricProcessInstance.getDurationInMillis");
        return e.getHistoryService().createHistoricProcessInstanceQuery().processDefinitionKey(key).finished().list()
                .stream().mapToLong(h -> h.getDurationInMillis() == null ? 0 : h.getDurationInMillis()).average()
                .orElse(0);
    }

    @Override
    public String instanceTenant(String pid) {
        nat("Operaton HistoricProcessInstance.getTenantId");
        HistoricProcessInstance h = e.getHistoryService().createHistoricProcessInstanceQuery().processInstanceId(pid)
                .singleResult();
        return h == null ? null : h.getTenantId();
    }

    @Override
    public long eventSubscriptions(String eventName) {
        nat("Operaton RuntimeService.createEventSubscriptionQuery()");
        return e.getRuntimeService().createEventSubscriptionQuery().eventName(eventName).count();
    }

    @Override
    public long caseDefinitions(String caseKey) {
        nat("Operaton RepositoryService.createCaseDefinitionQuery()");
        return e.getRepositoryService().createCaseDefinitionQuery().caseDefinitionKey(caseKey).count();
    }

    @Override
    public List<String> adhocEnabled(String pid) {
        throw new UnsupportedOperationException("Operaton 2.2.0 has no BPMN adHocSubProcess runtime API");
    }

    @Override
    public void adhocStart(String pid, String activityId) {
        throw new UnsupportedOperationException("Operaton 2.2.0 has no BPMN adHocSubProcess runtime API");
    }

    @Override
    public int calledProcessesRunInCalleeTenant() {
        return 0;   // native: the called instance takes the tenant of the definition named by calledElementTenantId
    }

    @Override
    public String correlateAnyExecution(String tenant, String msg, String bk, Map<String, Object> vars) {
        nat("Operaton RuntimeService.createMessageCorrelation().processInstanceBusinessKey()");
        var b = e.getRuntimeService().createMessageCorrelation(msg).processInstanceBusinessKey(bk).setVariables(vars);
        if (tenant != null) {
            b.tenantId(tenant);
        }
        return b.correlateWithResult().getExecution().getProcessInstanceId();
    }
}
