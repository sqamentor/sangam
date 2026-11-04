package org.sangam.spike.experiments.hooks.operaton;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.history.HistoricActivityInstance;
import org.operaton.bpm.engine.history.HistoricCaseActivityInstance;
import org.operaton.bpm.engine.history.HistoricProcessInstance;
import org.operaton.bpm.engine.runtime.ProcessInstance;
import org.sangam.spike.api.EngineAdapter;
import org.sangam.spike.experiments.support.Db;
import org.sangam.spike.experiments.support.EngineHooks;
import org.sangam.spike.operaton.OperatonEngineAdapter;

/** Operaton 2.2.0 side of {@link EngineHooks}; public Operaton API only. */
public final class OperatonHooks implements EngineHooks {

    @Override
    public String engine() {
        return "operaton";
    }

    @Override
    public EngineAdapter create(Db.Target db) {
        return new OperatonEngineAdapter(db.url(), db.user(), db.password());
    }

    private static ProcessEngine pe(EngineAdapter a) {
        return ((OperatonEngineAdapter) a).processEngine();
    }

    @Override
    public List<String> historicActivityIds(EngineAdapter a, String processInstanceId) {
        List<String> out = new ArrayList<>();
        for (HistoricActivityInstance h : pe(a).getHistoryService().createHistoricActivityInstanceQuery()
                .processInstanceId(processInstanceId).orderByHistoricActivityInstanceStartTime().asc()
                .orderPartiallyByOccurrence().asc().list()) {
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
        List<String> out = new ArrayList<>();
        for (ProcessInstance p : pe(a).getRuntimeService().createConditionEvaluation().setVariables(variables)
                .evaluateStartConditions()) {
            out.add(p.getId());
        }
        return out;
    }

    @Override
    public List<String> adhocEnabledActivities(EngineAdapter a, String processInstanceId) {
        throw new UnsupportedOperationException("Operaton 2.2.0 has no BPMN ad-hoc sub-process runtime API"
                + " (CMMN discretionary items are the native equivalent)");
    }

    @Override
    public void adhocExecute(EngineAdapter a, String processInstanceId, String activityId) {
        throw new UnsupportedOperationException("Operaton 2.2.0 has no BPMN ad-hoc sub-process runtime API");
    }

    @Override
    public String deployWithoutValidation(EngineAdapter a, String name, Map<String, byte[]> resources) {
        throw new UnsupportedOperationException("Operaton 2.2.0 DeploymentBuilder has no switch to disable BPMN parse validation");
    }

    @Override
    public List<String> caseHistory(EngineAdapter a, String caseInstanceId) {
        List<HistoricCaseActivityInstance> list = new ArrayList<>(pe(a).getHistoryService()
                .createHistoricCaseActivityInstanceQuery().caseInstanceId(caseInstanceId).list());
        list.removeIf(h -> !("humanTask".equals(h.getCaseActivityType()) || "milestone".equals(h.getCaseActivityType())));
        list.sort(Comparator.comparing(h -> h.getEndTime() == null ? new Date(Long.MAX_VALUE) : h.getEndTime()));
        List<String> out = new ArrayList<>();
        for (HistoricCaseActivityInstance h : list) {
            String state = h.isCompleted() ? "completed" : h.isTerminated() ? "terminated" : h.isActive() ? "active"
                    : h.isAvailable() ? "available" : h.isEnabled() ? "enabled" : h.isDisabled() ? "disabled" : "other";
            out.add(h.getCaseActivityId() + ":" + h.getCaseActivityType() + ":" + state);
        }
        return out;
    }

    @Override
    public int executeDueJobsBounded(EngineAdapter a, int maxExecutions) {
        var ms = pe(a).getManagementService();
        int n = 0;
        while (n < maxExecutions) {
            List<org.operaton.bpm.engine.runtime.Job> due = ms.createJobQuery().executable().active().orderByJobId().asc()
                    .listPage(0, 1);
            if (due.isEmpty()) {
                break;
            }
            try {
                ms.executeJob(due.get(0).getId());
            } catch (RuntimeException e) {
                // retries decremented by the engine, as in the adapter
            }
            n++;
        }
        return n;
    }

    @Override
    public List<String> timerJobs(EngineAdapter a, String processInstanceId) {
        List<String> out = new ArrayList<>();
        for (org.operaton.bpm.engine.runtime.Job j : pe(a).getManagementService().createJobQuery()
                .processInstanceId(processInstanceId).timers().list()) {
            String repeat = j instanceof org.operaton.bpm.engine.impl.persistence.entity.TimerEntity t ? t.getRepeat() : "?";
            out.add("due=" + (j.getDuedate() == null ? null : j.getDuedate().toInstant()) + " repeat=" + repeat);
        }
        return out;
    }

    @Override
    public void resetClock(EngineAdapter a) {
        a.setClock(null);
    }

    @Override
    public List<String> configurationWorkarounds() {
        return List.of("enforceHistoryTimeToLive=false (ledger W-3; else ENGINE-12018 on every shared model)",
                "job executor off; timers run through executeDueJobs(); ClockUtil is JVM-wide (W-7)");
    }
}
