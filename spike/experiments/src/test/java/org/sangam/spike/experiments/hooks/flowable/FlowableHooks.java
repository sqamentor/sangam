package org.sangam.spike.experiments.hooks.flowable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.flowable.bpmn.model.FlowNode;
import org.flowable.cmmn.api.history.HistoricPlanItemInstance;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.history.HistoricActivityInstance;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.repository.DeploymentBuilder;
import org.flowable.engine.runtime.Execution;
import org.flowable.engine.runtime.ProcessInstance;
import org.sangam.spike.api.EngineAdapter;
import org.sangam.spike.experiments.support.Db;
import org.sangam.spike.experiments.support.EngineHooks;
import org.sangam.spike.flowable.FlowableEngineAdapter;

/** Flowable 8.0.0 side of {@link EngineHooks}; public Flowable API only. */
public final class FlowableHooks implements EngineHooks {

    @Override
    public String engine() {
        return "flowable";
    }

    @Override
    public EngineAdapter create(Db.Target db) {
        return FlowableEngineAdapter.create(db.url(), db.user(), db.password());
    }

    private static ProcessEngine pe(EngineAdapter a) {
        return ((FlowableEngineAdapter) a).processEngine();
    }

    @Override
    public List<String> historicActivityIds(EngineAdapter a, String processInstanceId) {
        List<String> out = new ArrayList<>();
        for (HistoricActivityInstance h : pe(a).getHistoryService().createHistoricActivityInstanceQuery()
                .processInstanceId(processInstanceId).orderByHistoricActivityInstanceStartTime().asc().list()) {
            out.add(h.getActivityId());
        }
        return out;
    }

    @Override
    public List<String> runningInstances(EngineAdapter a, String processKey) {
        List<String> out = new ArrayList<>();
        for (ProcessInstance p : pe(a).getRuntimeService().createProcessInstanceQuery().processDefinitionKey(processKey).list()) {
            out.add(p.getId());
        }
        return out;
    }

    @Override
    public List<String> allInstances(EngineAdapter a, String processKey) {
        List<String> out = new ArrayList<>();
        for (HistoricProcessInstance p : pe(a).getHistoryService().createHistoricProcessInstanceQuery()
                .processDefinitionKey(processKey).list()) {
            out.add(p.getId());
        }
        return out;
    }

    @Override
    public void broadcastSignal(EngineAdapter a, String signalName) {
        pe(a).getRuntimeService().signalEventReceived(signalName);
    }

    @Override
    public List<String> evaluateConditionalStart(EngineAdapter a, Map<String, Object> variables) {
        throw new UnsupportedOperationException("Flowable 8.0.0 has no API to evaluate process-level conditional start"
                + " events; RuntimeService.evaluateConditionalEvents(processInstanceId[, vars]) exists only for"
                + " conditional events inside a RUNNING instance");
    }

    private static String adhocExecution(EngineAdapter a, String processInstanceId) {
        List<Execution> ex = pe(a).getRuntimeService().getAdhocSubProcessExecutions(processInstanceId);
        if (ex.isEmpty()) {
            throw new IllegalStateException("no ad-hoc sub-process execution in " + processInstanceId);
        }
        return ex.get(0).getId();
    }

    @Override
    public List<String> adhocEnabledActivities(EngineAdapter a, String processInstanceId) {
        List<String> out = new ArrayList<>();
        for (FlowNode n : pe(a).getRuntimeService().getEnabledActivitiesFromAdhocSubProcess(adhocExecution(a, processInstanceId))) {
            out.add(n.getId());
        }
        return out;
    }

    @Override
    public void adhocExecute(EngineAdapter a, String processInstanceId, String activityId) {
        pe(a).getRuntimeService().executeActivityInAdhocSubProcess(adhocExecution(a, processInstanceId), activityId);
    }

    @Override
    public String deployWithoutValidation(EngineAdapter a, String name, Map<String, byte[]> resources) {
        DeploymentBuilder b = pe(a).getRepositoryService().createDeployment().name(name).disableBpmnValidation();
        resources.forEach(b::addBytes);
        return b.deploy().getId();
    }

    @Override
    public List<String> caseHistory(EngineAdapter a, String caseInstanceId) {
        List<HistoricPlanItemInstance> list = new ArrayList<>(((FlowableEngineAdapter) a).cmmnEngineConfiguration()
                .getCmmnHistoryService().createHistoricPlanItemInstanceQuery()
                .planItemInstanceCaseInstanceId(caseInstanceId).list());
        list.removeIf(p -> !("humantask".equals(p.getPlanItemDefinitionType()) || "milestone".equals(p.getPlanItemDefinitionType())));
        list.sort(Comparator.comparing(FlowableHooks::endOf));
        List<String> out = new ArrayList<>();
        for (HistoricPlanItemInstance p : list) {
            String state = p.getOccurredTime() != null ? "completed" : p.getState();
            out.add(p.getElementId() + ":" + p.getPlanItemDefinitionType() + ":" + state);
        }
        return out;
    }

    private static Date endOf(HistoricPlanItemInstance p) {
        if (p.getOccurredTime() != null) {
            return p.getOccurredTime();
        }
        if (p.getCompletedTime() != null) {
            return p.getCompletedTime();
        }
        return p.getEndedTime() != null ? p.getEndedTime() : new Date(Long.MAX_VALUE);
    }

    @Override
    public int executeDueJobsBounded(EngineAdapter a, int maxExecutions) {
        // same steps as FlowableEngineAdapter.executeDueJobs (BPMN only: E8 has no CMMN), bounded per job execution
        var ms = pe(a).getManagementService();
        int n = 0;
        while (n < maxExecutions) {
            int progress = 0;
            for (org.flowable.job.api.Job timer : ms.createTimerJobQuery().executable().list()) {
                ms.moveTimerToExecutableJob(timer.getId());
                progress++;
            }
            for (org.flowable.job.api.Job job : ms.createJobQuery().list()) {
                if (n >= maxExecutions) {
                    break;
                }
                try {
                    ms.executeJob(job.getId());
                } catch (RuntimeException e) {
                    // left to the failed-job handler
                }
                n++;
                progress++;
            }
            if (progress == 0) {
                break;
            }
        }
        return n;
    }

    @Override
    public List<String> timerJobs(EngineAdapter a, String processInstanceId) {
        List<String> out = new ArrayList<>();
        for (org.flowable.job.api.Job j : pe(a).getManagementService().createTimerJobQuery()
                .processInstanceId(processInstanceId).list()) {
            String repeat = j instanceof org.flowable.job.service.impl.persistence.entity.TimerJobEntity t ? t.getRepeat() : "?";
            out.add("due=" + (j.getDuedate() == null ? null : j.getDuedate().toInstant()) + " repeat=" + repeat);
        }
        return out;
    }

    @Override
    public void resetClock(EngineAdapter a) {
        pe(a).getProcessEngineConfiguration().getClock().reset();
    }

    @Override
    public List<String> configurationWorkarounds() {
        return List.of("DMN and CMMN engine configurators registered on the process engine (VARIANTS.md config table)",
                "async executor off; timers/jobs and external-worker continuations run through executeDueJobs()");
    }
}
