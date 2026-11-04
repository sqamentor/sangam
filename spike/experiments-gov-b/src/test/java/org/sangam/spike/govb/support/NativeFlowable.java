package org.sangam.spike.govb.support;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.flowable.cmmn.api.history.HistoricPlanItemInstance;
import org.flowable.cmmn.api.runtime.PlanItemInstance;
import org.flowable.cmmn.engine.CmmnEngineConfiguration;
import org.flowable.dmn.engine.DmnEngineConfiguration;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.job.api.Job;
import org.flowable.task.api.history.HistoricTaskInstance;

import org.sangam.spike.flowable.FlowableEngineAdapter;

import static org.sangam.spike.govb.support.Rec.nat;

/** {@link Native} on Flowable 8.0.0's public service API (process, CMMN and DMN engines). */
final class NativeFlowable implements Native {

    private final ProcessEngine e;
    private final CmmnEngineConfiguration cmmn;
    private final DmnEngineConfiguration dmn;

    NativeFlowable(FlowableEngineAdapter a) {
        this.e = a.processEngine();
        this.cmmn = a.cmmnEngineConfiguration();
        this.dmn = a.dmnEngineConfiguration();
    }

    @Override
    public long runningInstances(String key) {
        nat("Flowable RuntimeService.createProcessInstanceQuery().processDefinitionKey().count()");
        return e.getRuntimeService().createProcessInstanceQuery().processDefinitionKey(key).count();
    }

    @Override
    public long finishedInstances(String key) {
        nat("Flowable HistoryService.createHistoricProcessInstanceQuery().finished()");
        return e.getHistoryService().createHistoricProcessInstanceQuery().processDefinitionKey(key).finished().count();
    }

    @Override
    public void suspend(String pid) {
        nat("Flowable RuntimeService.suspendProcessInstanceById");
        e.getRuntimeService().suspendProcessInstanceById(pid);
    }

    @Override
    public void activate(String pid) {
        nat("Flowable RuntimeService.activateProcessInstanceById");
        e.getRuntimeService().activateProcessInstanceById(pid);
    }

    @Override
    public boolean isSuspended(String pid) {
        nat("Flowable ProcessInstance.isSuspended");
        return e.getRuntimeService().createProcessInstanceQuery().processInstanceId(pid).singleResult().isSuspended();
    }

    @Override
    public void setVariable(String pid, String name, Object value) {
        nat("Flowable RuntimeService.setVariable");
        e.getRuntimeService().setVariable(pid, name, value);
    }

    @Override
    public List<String> enabledCaseItems(String caseId, String planItemId) {
        nat("Flowable CmmnRuntimeService.createPlanItemInstanceQuery().planItemInstanceStateEnabled()");
        List<String> ids = new ArrayList<>();
        for (PlanItemInstance p : cmmn.getCmmnRuntimeService().createPlanItemInstanceQuery().caseInstanceId(caseId)
                .planItemInstanceElementId(planItemId).planItemInstanceStateEnabled().list()) {
            ids.add(p.getId());
        }
        return ids;
    }

    @Override
    public void startCaseItem(String itemId) {
        nat("Flowable CmmnRuntimeService.startPlanItemInstance");
        cmmn.getCmmnRuntimeService().startPlanItemInstance(itemId);
    }

    @Override
    public List<String> historicByBusinessKey(String key, String bk) {
        nat("Flowable HistoryService.createHistoricProcessInstanceQuery().processInstanceBusinessKey()");
        return e.getHistoryService().createHistoricProcessInstanceQuery().processDefinitionKey(key)
                .processInstanceBusinessKey(bk).list().stream().map(HistoricProcessInstance::getId).toList();
    }

    @Override
    public long runtimeInstancesByVar(String key, String name, Object value) {
        nat("Flowable ProcessInstanceQuery.variableValueEquals");
        return e.getRuntimeService().createProcessInstanceQuery().processDefinitionKey(key)
                .variableValueEquals(name, value).count();
    }

    @Override
    public long runtimeInstancesByVarLike(String key, String name, String like) {
        nat("Flowable ProcessInstanceQuery.variableValueLike");
        return e.getRuntimeService().createProcessInstanceQuery().processDefinitionKey(key)
                .variableValueLike(name, like).count();
    }

    @Override
    public long historicInstancesByVar(String key, String name, Object value) {
        nat("Flowable HistoricProcessInstanceQuery.variableValueEquals");
        return e.getHistoryService().createHistoricProcessInstanceQuery().processDefinitionKey(key)
                .variableValueEquals(name, value).count();
    }

    @Override
    public long tasksByProcessVar(String name, Object value) {
        nat("Flowable TaskQuery.processVariableValueEquals");
        return e.getTaskService().createTaskQuery().processVariableValueEquals(name, value).count();
    }

    @Override
    public long failedWork(String pid) {
        nat("Flowable ManagementService.createDeadLetterJobQuery() / createExternalWorkerJobQuery()");
        long dead = e.getManagementService().createDeadLetterJobQuery().processInstanceId(pid).count();
        long zeroRetries = e.getManagementService().createExternalWorkerJobQuery().processInstanceId(pid).list().stream()
                .filter(j -> j.getRetries() <= 0).count();
        return dead + zeroRetries;
    }

    @Override
    public List<Map<String, Object>> evaluateDecision(String key, String tenant, Map<String, Object> vars) {
        nat("Flowable DmnDecisionService.createExecuteDecisionBuilder().executeDecision()");
        var b = dmn.getDmnDecisionService().createExecuteDecisionBuilder().decisionKey(key).variables(vars);
        if (tenant != null) {
            b.tenantId(tenant);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        b.executeDecision().forEach(m -> out.add(new HashMap<>(m)));
        return out;
    }

    @Override
    public List<String> subProcessInstances(String superPid) {
        nat("Flowable HistoricProcessInstanceQuery.superProcessInstanceId");
        return e.getHistoryService().createHistoricProcessInstanceQuery().superProcessInstanceId(superPid).list()
                .stream().map(HistoricProcessInstance::getId).toList();
    }

    @Override
    public List<String> processInstancesOfCase(String caseId) {
        nat("Flowable CmmnHistoryService.createHistoricPlanItemInstanceQuery() + HistoricProcessInstanceQuery.processInstanceCallbackIds");
        Set<String> ids = new HashSet<>();
        for (HistoricPlanItemInstance p : cmmn.getCmmnHistoryService().createHistoricPlanItemInstanceQuery()
                .planItemInstanceCaseInstanceId(caseId).list()) {
            ids.add(p.getId());
        }
        if (ids.isEmpty()) {
            return List.of();
        }
        return e.getHistoryService().createHistoricProcessInstanceQuery().processInstanceCallbackIds(ids).list()
                .stream().map(HistoricProcessInstance::getId).toList();
    }

    @Override
    public long timerJobs(String pid) {
        nat("Flowable ManagementService.createTimerJobQuery() / createSuspendedJobQuery()");
        return e.getManagementService().createTimerJobQuery().processInstanceId(pid).count()
                + e.getManagementService().createSuspendedJobQuery().processInstanceId(pid).timers().count();
    }

    @Override
    public Instant earliestTimerDue(String pid) {
        nat("Flowable ManagementService.createTimerJobQuery()");
        return e.getManagementService().createTimerJobQuery().processInstanceId(pid).list().stream()
                .map(Job::getDuedate).filter(d -> d != null).map(Date::toInstant).min(Instant::compareTo).orElse(null);
    }

    @Override
    public void rescheduleTimers(String pid, Instant due) {
        nat("Flowable ManagementService.rescheduleTimeDateJob");
        for (Job j : e.getManagementService().createTimerJobQuery().processInstanceId(pid).list()) {
            e.getManagementService().rescheduleTimeDateJob(j.getId(), due.toString());
        }
    }

    @Override
    public String processDefinitionId(String key, String tenant) {
        nat("Flowable RepositoryService.createProcessDefinitionQuery()");
        var q = e.getRepositoryService().createProcessDefinitionQuery().processDefinitionKey(key).latestVersion();
        if (tenant != null) {
            q.processDefinitionTenantId(tenant);
        }
        return q.singleResult().getId();
    }

    @Override
    public long historicActivityCount(String pid, String activityId) {
        nat("Flowable HistoryService.createHistoricActivityInstanceQuery()");
        return e.getHistoryService().createHistoricActivityInstanceQuery().processInstanceId(pid).activityId(activityId)
                .count();
    }

    @Override
    public Map<String, Long> finishedTasksByAssignee(String key) {
        nat("Flowable HistoryService.createHistoricTaskInstanceQuery().finished()");
        Map<String, Long> out = new LinkedHashMap<>();
        for (HistoricTaskInstance t : e.getHistoryService().createHistoricTaskInstanceQuery().processDefinitionKey(key)
                .finished().list()) {
            out.merge(String.valueOf(t.getAssignee()), 1L, Long::sum);
        }
        return out;
    }

    @Override
    public long finishedByEndActivity(String key, String endActivityId) {
        nat("Flowable HistoricProcessInstance.getEndActivityId");
        return e.getHistoryService().createHistoricProcessInstanceQuery().processDefinitionKey(key).finished().list()
                .stream().filter(h -> endActivityId.equals(h.getEndActivityId())).count();
    }

    @Override
    public double avgDurationMs(String key) {
        nat("Flowable HistoricProcessInstance.getDurationInMillis");
        return e.getHistoryService().createHistoricProcessInstanceQuery().processDefinitionKey(key).finished().list()
                .stream().mapToLong(h -> h.getDurationInMillis() == null ? 0 : h.getDurationInMillis()).average()
                .orElse(0);
    }

    @Override
    public String instanceTenant(String pid) {
        nat("Flowable HistoricProcessInstance.getTenantId");
        HistoricProcessInstance h = e.getHistoryService().createHistoricProcessInstanceQuery().processInstanceId(pid)
                .singleResult();
        return h == null ? null : (h.getTenantId() == null || h.getTenantId().isEmpty() ? null : h.getTenantId());
    }

    @Override
    public long eventSubscriptions(String eventName) {
        nat("Flowable RuntimeService.createEventSubscriptionQuery()");
        return e.getRuntimeService().createEventSubscriptionQuery().eventName(eventName).count();
    }

    @Override
    public long caseDefinitions(String caseKey) {
        nat("Flowable CmmnRepositoryService.createCaseDefinitionQuery()");
        return cmmn.getCmmnRepositoryService().createCaseDefinitionQuery().caseDefinitionKey(caseKey).count();
    }

    @Override
    public List<String> adhocEnabled(String pid) {
        nat("Flowable RuntimeService.getEnabledActivitiesFromAdhocSubProcess");
        var rs = e.getRuntimeService();
        List<String> out = new ArrayList<>();
        for (var ex : rs.createExecutionQuery().processInstanceId(pid).list()) {
            if (ex.getActivityId() != null && ex.getParentId() != null) {
                try {
                    rs.getEnabledActivitiesFromAdhocSubProcess(ex.getId()).forEach(fe -> out.add(fe.getId()));
                    if (!out.isEmpty()) {
                        return out;
                    }
                } catch (RuntimeException notAdhoc) {
                    // not the ad-hoc sub-process execution
                }
            }
        }
        return out;
    }

    @Override
    public void adhocStart(String pid, String activityId) {
        nat("Flowable RuntimeService.executeActivityInAdhocSubProcess");
        var rs = e.getRuntimeService();
        for (var ex : rs.createExecutionQuery().processInstanceId(pid).list()) {
            if (ex.getActivityId() != null && ex.getParentId() != null) {
                try {
                    if (!rs.getEnabledActivitiesFromAdhocSubProcess(ex.getId()).isEmpty()) {
                        rs.executeActivityInAdhocSubProcess(ex.getId(), activityId);
                        return;
                    }
                } catch (RuntimeException notAdhoc) {
                    // not the ad-hoc sub-process execution
                }
            }
        }
        throw new IllegalStateException("no ad-hoc sub-process execution in " + pid);
    }

    @Override
    public String correlateAnyExecution(String tenant, String msg, String bk, Map<String, Object> vars) {
        nat("Flowable ExecutionQuery.processInstanceBusinessKey(bk, includeChildExecutions=true) + RuntimeService.messageEventReceived");
        var rs = e.getRuntimeService();
        return e.getManagementService().executeCommand(ctx -> {
            var q = rs.createExecutionQuery().messageEventSubscriptionName(msg).processInstanceBusinessKey(bk, true);
            if (tenant != null) {
                q.executionTenantId(tenant);
            }
            var ex = q.list();
            if (ex.size() != 1) {
                throw new IllegalStateException("message " + msg + " bk " + bk + " matched " + ex.size());
            }
            rs.messageEventReceived(msg, ex.get(0).getId(), vars);
            return ex.get(0).getProcessInstanceId();
        });
    }

    private boolean calleeTenantHook;

    /**
     * Flowable copies the CALLER's tenant onto a called process instance (ExecutionEntityManagerImpl
     * .createSubprocessInstance -> inheritCommonProperties), even when the callee definition belongs to another
     * tenant. Workaround: an engine StartProcessInstanceInterceptor sets the callee definition's tenant on the new
     * sub-process instance and its first child execution before the first activity runs (impl ExecutionEntity).
     */
    @Override
    public synchronized int calledProcessesRunInCalleeTenant() {
        nat("Flowable ProcessEngineConfigurationImpl.setStartProcessInstanceInterceptor + ExecutionEntity.setTenantId (impl)");
        if (!calleeTenantHook) {
            var cfg = (org.flowable.engine.impl.cfg.ProcessEngineConfigurationImpl) e.getProcessEngineConfiguration();
            var previous = cfg.getStartProcessInstanceInterceptor();
            cfg.setStartProcessInstanceInterceptor(new org.flowable.engine.interceptor.StartProcessInstanceInterceptor() {
                @Override
                public void beforeStartProcessInstance(org.flowable.engine.interceptor.StartProcessInstanceBeforeContext c) {
                    if (previous != null) {
                        previous.beforeStartProcessInstance(c);
                    }
                }

                @Override
                public void afterStartProcessInstance(org.flowable.engine.interceptor.StartProcessInstanceAfterContext c) {
                    if (previous != null) {
                        previous.afterStartProcessInstance(c);
                    }
                }

                @Override
                public void beforeStartSubProcessInstance(org.flowable.engine.interceptor.StartSubProcessInstanceBeforeContext c) {
                    if (previous != null) {
                        previous.beforeStartSubProcessInstance(c);
                    }
                }

                @Override
                public void afterStartSubProcessInstance(org.flowable.engine.interceptor.StartSubProcessInstanceAfterContext c) {
                    if (previous != null) {
                        previous.afterStartSubProcessInstance(c);
                    }
                    String t = c.getProcessDefinition().getTenantId();
                    if (t != null && !t.isEmpty() && !t.equals(c.getProcessInstance().getTenantId())) {
                        c.getProcessInstance().setTenantId(t);
                        c.getChildExecution().setTenantId(t);
                    }
                }
            });
            calleeTenantHook = true;
        }
        return 22;
    }

    static Map<String, Object> copy(Map<String, Object> m) {
        return m == null ? Map.of() : new LinkedHashMap<>(m);
    }
}
