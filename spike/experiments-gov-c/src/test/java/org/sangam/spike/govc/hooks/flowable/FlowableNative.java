package org.sangam.spike.govc.hooks.flowable;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.flowable.bpmn.model.BoundaryEvent;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.Event;
import org.flowable.bpmn.model.EventDefinition;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.TimerEventDefinition;
import org.flowable.common.engine.impl.history.HistoryLevel;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.history.HistoricActivityInstance;
import org.flowable.engine.history.HistoricDetail;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.flowable.engine.runtime.Execution;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.job.api.Job;
import org.flowable.variable.api.history.HistoricVariableInstance;
import org.sangam.spike.api.EngineAdapter;
import org.sangam.spike.flowable.FlowableEngineAdapter;
import org.sangam.spike.govc.support.Db;
import org.sangam.spike.govc.support.Models;
import org.sangam.spike.govc.support.Native;
import org.sangam.spike.govc.support.NativeUse;

/** Flowable 8.0.0 side of {@link Native}; public Flowable API unless noted. */
public final class FlowableNative implements Native {

    private static final String NS = "xmlns:flowable=\"http://flowable.org/bpmn\"";

    @Override
    public String engine() {
        return "flowable";
    }

    /**
     * The spike adapter leaves Flowable at its default history level (audit), which records no variable-update
     * details; Operaton's adapter runs at FULL. To compare the engines (not the adapters' settings) the level is raised
     * to FULL on the built configuration (impl class ProcessEngineConfigurationImpl). Reported as an adapter need.
     */
    @Override
    public EngineAdapter create(Db.Target db) {
        FlowableEngineAdapter a = FlowableEngineAdapter.create(db.url(), db.user(), db.password());
        ProcessEngineConfigurationImpl cfg = (ProcessEngineConfigurationImpl) a.processEngine().getProcessEngineConfiguration();
        cfg.setHistoryLevel(HistoryLevel.FULL);
        return a;
    }

    private static ProcessEngine pe(EngineAdapter a) {
        return ((FlowableEngineAdapter) a).processEngine();
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
                String topic = arg.split(";")[0];
                // Flowable external-worker jobs are exclusive by default: two jobs of the same process instance cannot be
                // acquired together (the second process-instance lock fails and the whole acquire returns nothing).
                String excl = arg.contains(";nonexclusive") ? " flowable:exclusive=\"false\"" : "";
                return "flowable:type=\"external-worker\" flowable:topic=\"" + topic + "\"" + excl;
            }

            @Override
            public String expr(String expression) {
                return "flowable:expression=\"" + Models.attr(expression) + "\"";
            }

            @Override
            public String callIo(List<String> in, List<String> out, boolean businessKey) {
                StringBuilder sb = new StringBuilder("<extensionElements>");
                in.forEach(v -> sb.append("<flowable:in source=\"").append(v).append("\" target=\"").append(v).append("\"/>"));
                out.forEach(v -> sb.append("<flowable:out source=\"").append(v).append("\" target=\"").append(v).append("\"/>"));
                return sb.append("</extensionElements>").toString();
            }

            @Override
            public String callBusinessKey() {
                return "flowable:inheritBusinessKey=\"true\"";
            }
        };
    }

    // ------------------------------------------------------------------ timers

    private static Timer timer(Job j, boolean suspended) {
        return new Timer(j.getId(), j.getElementId(), j.getDuedate() == null ? null : j.getDuedate().toInstant(), suspended,
                j.getExecutionId());
    }

    @Override
    public List<Timer> timers(EngineAdapter a, String processInstanceId) {
        var ms = pe(a).getManagementService();
        List<Timer> out = new ArrayList<>();
        for (Job j : ms.createTimerJobQuery().processInstanceId(processInstanceId).list()) {
            out.add(timer(j, false));
        }
        for (Job j : ms.createSuspendedJobQuery().processInstanceId(processInstanceId).timers().list()) {
            out.add(timer(j, true));
        }
        return out;
    }

    @Override
    public List<Timer> startTimers(EngineAdapter a, String processKey) {
        List<Timer> out = new ArrayList<>();
        for (Job j : pe(a).getManagementService().createTimerJobQuery().processDefinitionKey(processKey).withoutProcessInstanceId().list()) {
            out.add(timer(j, false));
        }
        return out;
    }

    @Override
    public void setTimerDue(EngineAdapter a, String jobId, java.time.Instant due) {
        use("ManagementService.rescheduleTimeDateJob");
        pe(a).getManagementService().rescheduleTimeDateJob(jobId, due.toString());
    }

    /**
     * Flowable has no "recalculate from the model" call; rescheduleTimeDateJob accepts an expression and evaluates it
     * against the job's execution, so the timer expression is read from the BpmnModel and passed back in.
     */
    @Override
    public String recalculateTimerFromModel(EngineAdapter a, String jobId) {
        var ms = pe(a).getManagementService();
        Job j = ms.createTimerJobQuery().jobId(jobId).singleResult();
        if (j == null) {
            throw new IllegalStateException("no timer job " + jobId);
        }
        use("RepositoryService.getBpmnModel");
        BpmnModel model = pe(a).getRepositoryService().getBpmnModel(j.getProcessDefinitionId());
        FlowElement fe = model.getFlowElement(j.getElementId());
        TimerEventDefinition ted = null;
        if (fe instanceof Event ev) {
            for (EventDefinition d : ev.getEventDefinitions()) {
                if (d instanceof TimerEventDefinition t) {
                    ted = t;
                }
            }
        }
        if (ted == null || ted.getTimeDate() == null) {
            throw new IllegalStateException("element " + j.getElementId() + " has no timeDate timer"
                    + (fe instanceof BoundaryEvent ? " (boundary)" : ""));
        }
        use("ManagementService.rescheduleTimeDateJob(expression)");
        ms.rescheduleTimeDateJob(jobId, ted.getTimeDate());
        return "no recalculate operation: timer expression read from RepositoryService.getBpmnModel and passed to"
                + " ManagementService.rescheduleTimeDateJob (evaluated against the job's execution)";
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

    /**
     * Flowable's ChangeActivityStateBuilder only MOVES executions; to add an activity next to a running one the
     * running activity is moved to itself plus the new one, so the running activity is re-created (new task id).
     */
    @Override
    public String startAdditionalActivity(EngineAdapter a, String processInstanceId, String activityId, String runningActivityId) {
        use("RuntimeService.createChangeActivityStateBuilder().moveSingleActivityIdToActivityIds");
        pe(a).getRuntimeService().createChangeActivityStateBuilder().processInstanceId(processInstanceId)
                .moveSingleActivityIdToActivityIds(runningActivityId, List.of(runningActivityId, activityId)).changeState();
        return "no 'start an activity' operation: the running activity " + runningActivityId
                + " is moved to {itself, " + activityId + "} and therefore re-created (its task gets a new id)";
    }

    @Override
    public boolean evaluateConditionalEvents(EngineAdapter a, String processInstanceId) {
        use("RuntimeService.evaluateConditionalEvents");
        pe(a).getRuntimeService().evaluateConditionalEvents(processInstanceId);
        return true;
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
        use("ProcessInstanceBuilder.referenceId/referenceType");
        return pe(a).getRuntimeService().createProcessInstanceBuilder().processDefinitionKey(processKey)
                .businessKey(businessKey).variables(vars).referenceId(referenceId).referenceType(referenceType).start().getId();
    }

    @Override
    public List<String> linkedInstances(EngineAdapter a, String referenceId, String referenceType) {
        use("ProcessInstanceQuery.processInstanceReferenceId/Type");
        List<String> out = new ArrayList<>();
        for (ProcessInstance p : pe(a).getRuntimeService().createProcessInstanceQuery()
                .processInstanceReferenceId(referenceId).processInstanceReferenceType(referenceType).list()) {
            out.add(p.getId());
        }
        return out;
    }

    // ------------------------------------------------------------------ history

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
    public List<Object> variableHistory(EngineAdapter a, String processInstanceId, String name) {
        use("HistoryService.createHistoricDetailQuery().variableUpdates");
        List<Object> out = new ArrayList<>();
        for (HistoricDetail d : pe(a).getHistoryService().createHistoricDetailQuery().processInstanceId(processInstanceId)
                .variableUpdates().orderByTime().asc().list()) {
            if (d instanceof org.flowable.engine.history.HistoricVariableUpdate u && name.equals(u.getVariableName())) {
                out.add(u.getValue());
            }
        }
        if (out.isEmpty()) {
            // fall back to the last value only (history level below FULL)
            HistoricVariableInstance v = pe(a).getHistoryService().createHistoricVariableInstanceQuery()
                    .processInstanceId(processInstanceId).variableName(name).singleResult();
            if (v != null) {
                out.add(v.getValue());
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
        pe(a).getProcessEngineConfiguration().getClock().reset();
    }

    @Override
    public List<String> configurationNotes() {
        return List.of("adapter as built by FlowableEngineAdapter.create (DMN + CMMN configurators, async executor off,"
                        + " continuation AFTER_COMMIT)",
                "history level raised from the adapter default (audit) to FULL after build through"
                        + " ProcessEngineConfigurationImpl.setHistoryLevel (impl class), to match Operaton's adapter (FULL)",
                "one fresh engine + database per scenario");
    }

    @SuppressWarnings("unused")
    private static Date d(java.time.Instant i) {
        return Date.from(i);
    }
}
