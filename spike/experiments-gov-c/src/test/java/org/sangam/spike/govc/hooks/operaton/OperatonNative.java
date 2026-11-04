package org.sangam.spike.govc.hooks.operaton;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.history.HistoricActivityInstance;
import org.operaton.bpm.engine.history.HistoricDetail;
import org.operaton.bpm.engine.history.HistoricProcessInstance;
import org.operaton.bpm.engine.history.HistoricVariableUpdate;
import org.operaton.bpm.engine.management.JobDefinition;
import org.operaton.bpm.engine.runtime.Execution;
import org.operaton.bpm.engine.runtime.Job;
import org.operaton.bpm.engine.runtime.ProcessInstance;
import org.sangam.spike.api.EngineAdapter;
import org.sangam.spike.govc.support.Db;
import org.sangam.spike.govc.support.Models;
import org.sangam.spike.govc.support.Native;
import org.sangam.spike.govc.support.NativeUse;
import org.sangam.spike.operaton.OperatonEngineAdapter;

/** Operaton 2.2.0 side of {@link Native}; public Operaton API only. */
public final class OperatonNative implements Native {

    private static final String NS = "xmlns:operaton=\"http://operaton.org/schema/1.0/bpmn\"";

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

    private static void use(String api) {
        NativeUse.record(api);
    }

    @Override
    public Models.Dialect dialect() {
        return new Models.Dialect() {
            @Override
            public String namespace() {
                return NS;
            }

            @Override
            public String ext(String arg) {
                String topic = arg.split(";")[0]; // ";nonexclusive" has no meaning for Operaton external tasks (not jobs)
                return "operaton:type=\"external\" operaton:topic=\"" + topic + "\"";
            }

            @Override
            public String expr(String expression) {
                return "operaton:expression=\"" + Models.attr(expression) + "\"";
            }

            @Override
            public String callIo(List<String> in, List<String> out, boolean businessKey) {
                StringBuilder sb = new StringBuilder("<extensionElements>");
                if (businessKey) {
                    sb.append("<operaton:in businessKey=\"#{execution.processBusinessKey}\"/>");
                }
                in.forEach(v -> sb.append("<operaton:in source=\"").append(v).append("\" target=\"").append(v).append("\"/>"));
                out.forEach(v -> sb.append("<operaton:out source=\"").append(v).append("\" target=\"").append(v).append("\"/>"));
                return sb.append("</extensionElements>").toString();
            }

            @Override
            public String callBusinessKey() {
                return "";
            }
        };
    }

    // ------------------------------------------------------------------ timers

    private Timer timer(EngineAdapter a, Job j) {
        String activity = null;
        if (j.getJobDefinitionId() != null) {
            JobDefinition d = pe(a).getManagementService().createJobDefinitionQuery().jobDefinitionId(j.getJobDefinitionId())
                    .singleResult();
            activity = d == null ? null : d.getActivityId();
        }
        return new Timer(j.getId(), activity, j.getDuedate() == null ? null : j.getDuedate().toInstant(), j.isSuspended(),
                j.getExecutionId());
    }

    @Override
    public List<Timer> timers(EngineAdapter a, String processInstanceId) {
        List<Timer> out = new ArrayList<>();
        for (Job j : pe(a).getManagementService().createJobQuery().processInstanceId(processInstanceId).timers().list()) {
            out.add(timer(a, j));
        }
        return out;
    }

    @Override
    public List<Timer> startTimers(EngineAdapter a, String processKey) {
        List<Timer> out = new ArrayList<>();
        for (Job j : pe(a).getManagementService().createJobQuery().processDefinitionKey(processKey).timers().list()) {
            if (j.getProcessInstanceId() == null) {
                out.add(timer(a, j));
            }
        }
        return out;
    }

    @Override
    public void setTimerDue(EngineAdapter a, String jobId, java.time.Instant due) {
        use("ManagementService.setJobDuedate");
        pe(a).getManagementService().setJobDuedate(jobId, Date.from(due));
    }

    @Override
    public String recalculateTimerFromModel(EngineAdapter a, String jobId) {
        use("ManagementService.recalculateJobDuedate");
        pe(a).getManagementService().recalculateJobDuedate(jobId, false);
        return null;
    }

    // ------------------------------------------------------------------ suspension

    @Override
    public void suspendInstance(EngineAdapter a, String processInstanceId) {
        use("RuntimeService.suspendProcessInstanceById");
        pe(a).getRuntimeService().suspendProcessInstanceById(processInstanceId);
    }

    @Override
    public void activateInstance(EngineAdapter a, String processInstanceId) {
        use("RuntimeService.activateProcessInstanceById");
        pe(a).getRuntimeService().activateProcessInstanceById(processInstanceId);
    }

    @Override
    public boolean isSuspended(EngineAdapter a, String processInstanceId) {
        ProcessInstance p = pe(a).getRuntimeService().createProcessInstanceQuery().processInstanceId(processInstanceId)
                .singleResult();
        return p != null && p.isSuspended();
    }

    // ------------------------------------------------------------------ variables / executions

    @Override
    public void setVariable(EngineAdapter a, String id, String name, Object value) {
        use("RuntimeService.setVariable");
        pe(a).getRuntimeService().setVariable(id, name, value);
    }

    @Override
    public Object getVariable(EngineAdapter a, String id, String name) {
        use("RuntimeService.getVariable");
        return pe(a).getRuntimeService().getVariable(id, name);
    }

    @Override
    public void setVariablesLocal(EngineAdapter a, String executionId, Map<String, Object> vars) {
        use("RuntimeService.setVariablesLocal");
        pe(a).getRuntimeService().setVariablesLocal(executionId, vars);
    }

    @Override
    public List<String> messageSubscriptions(EngineAdapter a, String processInstanceId, String messageName) {
        use("RuntimeService.createExecutionQuery().messageEventSubscriptionName");
        List<String> out = new ArrayList<>();
        for (Execution e : pe(a).getRuntimeService().createExecutionQuery().processInstanceId(processInstanceId)
                .messageEventSubscriptionName(messageName).list()) {
            out.add(e.getId());
        }
        return out;
    }

    @Override
    public void deliverMessage(EngineAdapter a, String executionId, String messageName, String localScopeExecutionId,
                               Map<String, Object> localVars) {
        use("RuntimeService.setVariablesLocal");
        use("RuntimeService.messageEventReceived(messageName, executionId)");
        a.inEngineTransaction(c -> {
            pe(a).getRuntimeService().setVariablesLocal(localScopeExecutionId, localVars);
            pe(a).getRuntimeService().messageEventReceived(messageName, executionId);
            return null;
        });
    }

    @Override
    public String executionWithLocalVariable(EngineAdapter a, String processInstanceId, String name, Object value) {
        use("RuntimeService.createExecutionQuery().variableValueEquals (local)");
        List<Execution> l = pe(a).getRuntimeService().createExecutionQuery().processInstanceId(processInstanceId)
                .variableValueEquals(name, value).list();
        return l.size() == 1 ? l.get(0).getId() : null;
    }

    @Override
    public String taskExecutionId(EngineAdapter a, String taskId) {
        use("TaskService.createTaskQuery().getExecutionId");
        return pe(a).getTaskService().createTaskQuery().taskId(taskId).singleResult().getExecutionId();
    }

    @Override
    public String startAdditionalActivity(EngineAdapter a, String processInstanceId, String activityId, String runningActivityId) {
        use("RuntimeService.createProcessInstanceModification().startBeforeActivity");
        pe(a).getRuntimeService().createProcessInstanceModification(processInstanceId).startBeforeActivity(activityId).execute();
        return null;
    }

    @Override
    public boolean evaluateConditionalEvents(EngineAdapter a, String processInstanceId) {
        return false; // Operaton evaluates conditional events on every variable change in scope
    }

    // ------------------------------------------------------------------ hierarchy and links

    @Override
    public String superInstance(EngineAdapter a, String childInstanceId) {
        use("ProcessInstanceQuery.subProcessInstanceId");
        ProcessInstance p = pe(a).getRuntimeService().createProcessInstanceQuery().subProcessInstanceId(childInstanceId)
                .singleResult();
        return p == null ? null : p.getId();
    }

    @Override
    public List<String> subInstances(EngineAdapter a, String processInstanceId) {
        use("ProcessInstanceQuery.superProcessInstanceId");
        List<String> out = new ArrayList<>();
        for (ProcessInstance p : pe(a).getRuntimeService().createProcessInstanceQuery().superProcessInstanceId(processInstanceId).list()) {
            out.add(p.getId());
        }
        return out;
    }

    @Override
    public String startLinked(EngineAdapter a, String processKey, String businessKey, Map<String, Object> vars,
                              String referenceId, String referenceType) {
        // Operaton has no reference field on a process instance: the link is kept in two variables.
        Map<String, Object> v = new java.util.LinkedHashMap<>(vars);
        v.put("linkRefId", referenceId);
        v.put("linkRefType", referenceType);
        return a.startProcess(null, processKey, businessKey, v);
    }

    @Override
    public List<String> linkedInstances(EngineAdapter a, String referenceId, String referenceType) {
        use("ProcessInstanceQuery.variableValueEquals");
        List<String> out = new ArrayList<>();
        for (ProcessInstance p : pe(a).getRuntimeService().createProcessInstanceQuery()
                .variableValueEquals("linkRefId", referenceId).variableValueEquals("linkRefType", referenceType).list()) {
            out.add(p.getId());
        }
        return out;
    }

    // ------------------------------------------------------------------ history

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
    public List<Object> variableHistory(EngineAdapter a, String processInstanceId, String name) {
        use("HistoryService.createHistoricDetailQuery().variableUpdates");
        List<Object> out = new ArrayList<>();
        for (HistoricDetail d : pe(a).getHistoryService().createHistoricDetailQuery().processInstanceId(processInstanceId)
                .variableUpdates().orderPartiallyByOccurrence().asc().list()) {
            if (d instanceof HistoricVariableUpdate u && name.equals(u.getVariableName())) {
                out.add(u.getValue());
            }
        }
        return out;
    }

    @Override
    public List<String> historicInstancesByBusinessKey(EngineAdapter a, String processKey, String businessKey) {
        use("HistoryService.createHistoricProcessInstanceQuery().processInstanceBusinessKey");
        List<String> out = new ArrayList<>();
        for (HistoricProcessInstance h : pe(a).getHistoryService().createHistoricProcessInstanceQuery()
                .processDefinitionKey(processKey).processInstanceBusinessKey(businessKey).list()) {
            out.add(h.getId());
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
    public void deleteHistoricInstance(EngineAdapter a, String processInstanceId) {
        use("HistoryService.deleteHistoricProcessInstance");
        pe(a).getHistoryService().deleteHistoricProcessInstance(processInstanceId);
    }

    @Override
    public void resetClock(EngineAdapter a) {
        a.setClock(null);
    }

    @Override
    public List<String> configurationNotes() {
        return List.of("adapter as built by OperatonEngineAdapter: history FULL, enforceHistoryTimeToLive=false (ledger W-3)",
                "job executor off; timers and jobs run through executeDueJobs(); ClockUtil is JVM-wide (W-7)",
                "one fresh engine + database per scenario");
    }
}
