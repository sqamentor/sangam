// SPDX-License-Identifier: Apache-2.0
package org.sangam.ops.flowable;

import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.TaskService;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.bpm.engine.impl.persistence.entity.JobEntity;
import org.operaton.bpm.engine.impl.persistence.entity.TimerEntity;
import org.operaton.bpm.engine.repository.ProcessDefinitionQuery;
import org.operaton.bpm.engine.runtime.ActivityInstance;
import org.operaton.bpm.engine.runtime.EventSubscription;
import org.operaton.bpm.engine.runtime.Job;
import org.operaton.bpm.engine.runtime.ProcessInstance;
import org.operaton.bpm.engine.runtime.ProcessInstantiationBuilder;
import org.operaton.bpm.engine.runtime.TransitionInstance;
import org.operaton.bpm.engine.task.IdentityLink;
import org.operaton.bpm.engine.task.IdentityLinkType;
import org.operaton.bpm.engine.task.Task;
import org.operaton.bpm.model.bpmn.BpmnModelInstance;
import org.operaton.bpm.model.bpmn.instance.Activity;
import org.operaton.bpm.model.bpmn.instance.BoundaryEvent;
import org.operaton.bpm.model.bpmn.instance.CallActivity;
import org.operaton.bpm.model.bpmn.instance.EventBasedGateway;
import org.operaton.bpm.model.bpmn.instance.ExtensionElements;
import org.operaton.bpm.model.bpmn.instance.FlowNode;
import org.operaton.bpm.model.bpmn.instance.IntermediateCatchEvent;
import org.operaton.bpm.model.bpmn.instance.MultiInstanceLoopCharacteristics;
import org.operaton.bpm.model.bpmn.instance.ReceiveTask;
import org.operaton.bpm.model.bpmn.instance.ServiceTask;
import org.operaton.bpm.model.bpmn.instance.SubProcess;
import org.operaton.bpm.model.bpmn.instance.UserTask;
import org.operaton.bpm.model.bpmn.instance.operaton.OperatonTaskListener;
import org.operaton.bpm.model.xml.instance.ModelElementInstance;
import org.sangam.ops.flowable.InstancePlan.TaskState;
import org.sangam.ops.flowable.InstancePlan.TimerState;
import org.sangam.ops.flowable.InstancePlan.VariableValue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * M-135: the Operaton implementation of {@link OperatonStateWriter} and its {@link OperatonStateWriter.TargetModel}.
 *
 * <p>One engine transaction per instance ({@code getCommandExecutorTxRequired}; the service calls inside it join the
 * same command context): start at the plan's wait activities with {@code execute(skipCustomListeners=true,
 * skipIoMappings=true)}, so no start, execution or create-task listener and no input mapping runs; then the task
 * identity, delegation, priority and due date, the candidate links (exactly the source set), task-local variables, the
 * original timer due dates and suspension. A count that does not match the plan (no task, two tasks, a missing timer
 * job) throws, and the engine rolls the whole instance back.
 *
 * <p>Provenance: the process variable {@value #PROVENANCE_VARIABLE} holds {@code <source system>:<Flowable instance
 * id>}; {@link #findMigrated} finds an instance created by an earlier attempt whose answer was lost.
 */
public final class OperatonEngineStateWriter implements OperatonStateWriter, OperatonStateWriter.TargetModel {

    public static final String PROVENANCE_VARIABLE = "sangamMigrationSource";

    private final ProcessEngine engine;
    private final Map<String, BpmnModelInstance> models = new ConcurrentHashMap<>();

    public OperatonEngineStateWriter(ProcessEngine engine) {
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    // ------------------------------------------------------------------ target model

    @Override
    public Optional<String> definitionId(String key, int version, String tenantId) {
        ProcessDefinitionQuery q = engine.getRepositoryService().createProcessDefinitionQuery().processDefinitionKey(key)
                .processDefinitionVersion(version);
        q = tenantId == null ? q.withoutTenantId() : q.tenantIdIn(tenantId);
        var d = q.singleResult();
        return d == null ? Optional.empty() : Optional.of(d.getId());
    }

    @Override
    public ActivityFacts activity(String definitionId, String activityId) {
        if (activityId == null) {
            return ActivityFacts.missing();
        }
        BpmnModelInstance model = models.computeIfAbsent(definitionId, id -> engine.getRepositoryService().getBpmnModelInstance(id));
        ModelElementInstance el = model == null ? null : model.getModelElementById(activityId);
        if (!(el instanceof FlowNode node)) {
            return ActivityFacts.missing();
        }
        boolean async = node.isOperatonAsyncBefore();
        for (ModelElementInstance p = el; p != null; p = p.getParentElement()) {
            if (p instanceof Activity a && a.getLoopCharacteristics() instanceof MultiInstanceLoopCharacteristics) {
                return new ActivityFacts(ActivityKind.IN_MULTI_INSTANCE, async, false);
            }
            if (p instanceof SubProcess sp && sp.triggeredByEvent()) {
                return new ActivityFacts(ActivityKind.IN_EVENT_SUBPROCESS, async, false);
            }
        }
        ActivityKind kind = switch (node) {
            case BoundaryEvent b -> ActivityKind.BOUNDARY_EVENT;
            case CallActivity c -> ActivityKind.CALL_ACTIVITY;
            case UserTask u -> ActivityKind.USER_TASK;
            case ReceiveTask r -> ActivityKind.RECEIVE_TASK;
            case IntermediateCatchEvent i -> ActivityKind.CATCH_EVENT;
            case EventBasedGateway g -> ActivityKind.EVENT_GATEWAY;
            case ServiceTask s when "external".equals(s.getOperatonType()) -> ActivityKind.EXTERNAL_TASK;
            default -> ActivityKind.OTHER;
        };
        return new ActivityFacts(kind, async, kind == ActivityKind.USER_TASK && hasTaskListeners(node));
    }

    private static boolean hasTaskListeners(FlowNode node) {
        ExtensionElements ext = node.getExtensionElements();
        return ext != null && !ext.getElementsQuery().filterByType(OperatonTaskListener.class).list().isEmpty();
    }

    // ------------------------------------------------------------------ writer

    @Override
    public Optional<String> findMigrated(String provenance) {
        List<ProcessInstance> found = engine.getRuntimeService().createProcessInstanceQuery()
                .variableValueEquals(PROVENANCE_VARIABLE, provenance).list();
        if (found.size() > 1) {
            throw new IllegalStateException(found.size() + " target instances carry provenance " + provenance + "; resolve by hand");
        }
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0).getId());
    }

    @Override
    public String apply(InstancePlan plan, String provenance) {
        if (plan.status() != InstancePlan.Status.SUPPORTED || plan.targetDefinitionId() == null) {
            throw new IllegalArgumentException("only a SUPPORTED plan with a target definition is applied: " + plan.sourceInstanceId());
        }
        ProcessEngineConfigurationImpl cfg = (ProcessEngineConfigurationImpl) engine.getProcessEngineConfiguration();
        return cfg.getCommandExecutorTxRequired().execute(ctx -> applyInTransaction(plan, provenance));
    }

    private String applyInTransaction(InstancePlan plan, String provenance) {
        RuntimeService rs = engine.getRuntimeService();
        Map<String, Object> vars = new LinkedHashMap<>();
        for (VariableValue v : plan.variables()) {
            vars.put(v.name(), v.value());
        }
        vars.put(PROVENANCE_VARIABLE, provenance);
        ProcessInstantiationBuilder b = rs.createProcessInstanceById(plan.targetDefinitionId()).businessKey(plan.businessKey())
                .setVariables(vars);
        for (String act : plan.startActivities()) {
            b.startBeforeActivity(act);
        }
        String pid = b.execute(true, true).getId();
        TaskService ts = engine.getTaskService();
        for (TaskState t : plan.tasks()) {
            List<Task> created = ts.createTaskQuery().processInstanceId(pid).taskDefinitionKey(t.activityId()).list();
            if (created.size() != 1) {
                throw new IllegalStateException("expected one task at " + t.activityId() + " of " + plan.sourceInstanceId()
                        + ", found " + created.size());
            }
            recreateTask(ts, created.get(0), t);
        }
        Map<String, List<TimerState>> timersByActivity = new LinkedHashMap<>();
        plan.timers().forEach(t -> timersByActivity.computeIfAbsent(t.activityId(), k -> new ArrayList<>()).add(t));
        for (Map.Entry<String, List<TimerState>> e : timersByActivity.entrySet()) {
            List<Job> jobs = new ArrayList<>(engine.getManagementService().createJobQuery().processInstanceId(pid)
                    .activityId(e.getKey()).timers().list());
            if (jobs.size() != e.getValue().size()) {
                throw new IllegalStateException("expected " + e.getValue().size() + " timer(s) at " + e.getKey() + " of "
                        + plan.sourceInstanceId() + ", found " + jobs.size());
            }
            jobs.sort(Comparator.comparing(Job::getId));
            for (int i = 0; i < jobs.size(); i++) {
                engine.getManagementService().setJobDuedate(jobs.get(i).getId(), new Date(e.getValue().get(i).dueDateMillis()));
            }
        }
        if (plan.suspended()) {
            rs.suspendProcessInstanceById(pid);
        }
        return pid;
    }

    private static void recreateTask(TaskService ts, Task task, TaskState t) {
        if (t.name() != null) {
            task.setName(t.name());
        }
        task.setPriority(t.priority());
        task.setDueDate(t.dueDateMillis() == null ? null : new Date(t.dueDateMillis()));
        ts.saveTask(task);
        String id = task.getId();
        if (("PENDING".equals(t.delegation()) || "RESOLVED".equals(t.delegation())) && t.owner() != null) {
            ts.setAssignee(id, t.owner());
            ts.delegateTask(id, t.assignee());     // owner = the previous assignee, delegation PENDING
            if ("RESOLVED".equals(t.delegation())) {
                ts.resolveTask(id);                // delegation RESOLVED, assignee back to the owner
                if (!Objects.equals(t.assignee(), t.owner())) {
                    ts.setAssignee(id, t.assignee());
                }
            }
        } else {
            ts.setOwner(id, t.owner());
            ts.setAssignee(id, t.assignee());
        }
        Set<String> users = new HashSet<>(t.candidateUsers());
        Set<String> groups = new HashSet<>(t.candidateGroups());
        for (IdentityLink l : ts.getIdentityLinksForTask(id)) {
            if (!IdentityLinkType.CANDIDATE.equals(l.getType())) {
                continue;
            }
            if (l.getUserId() != null && !users.remove(l.getUserId())) {
                ts.deleteCandidateUser(id, l.getUserId());
            }
            if (l.getGroupId() != null && !groups.remove(l.getGroupId())) {
                ts.deleteCandidateGroup(id, l.getGroupId());
            }
        }
        users.forEach(u -> ts.addCandidateUser(id, u));
        groups.forEach(g -> ts.addCandidateGroup(id, g));
        if (!t.localVariables().isEmpty()) {
            Map<String, Object> locals = new LinkedHashMap<>();
            t.localVariables().forEach(v -> locals.put(v.name(), v.value()));
            ts.setVariablesLocal(id, locals);
        }
    }

    @Override
    public TargetState state(String pid) {
        RuntimeService rs = engine.getRuntimeService();
        ProcessInstance pi = rs.createProcessInstanceQuery().processInstanceId(pid).singleResult();
        if (pi == null) {
            return TargetState.absent();
        }
        List<String> active = new ArrayList<>();
        leaves(rs.getActivityInstance(pid), active, true);
        active.sort(Comparator.naturalOrder());
        TaskService ts = engine.getTaskService();
        List<TaskState> tasks = new ArrayList<>();
        for (Task t : ts.createTaskQuery().processInstanceId(pid).list()) {
            List<String> users = new ArrayList<>();
            List<String> groups = new ArrayList<>();
            for (IdentityLink l : ts.getIdentityLinksForTask(t.getId())) {
                if (IdentityLinkType.CANDIDATE.equals(l.getType())) {
                    if (l.getUserId() != null) {
                        users.add(l.getUserId());
                    }
                    if (l.getGroupId() != null) {
                        groups.add(l.getGroupId());
                    }
                }
            }
            users.sort(Comparator.naturalOrder());
            groups.sort(Comparator.naturalOrder());
            List<VariableValue> locals = new ArrayList<>();
            ts.getVariablesLocal(t.getId()).forEach((k, v) -> locals.add(VariableValue.of(k, v)));
            locals.sort(Comparator.comparing(VariableValue::name));
            tasks.add(new TaskState(t.getTaskDefinitionKey(), t.getName(), t.getAssignee(), t.getOwner(),
                    t.getDelegationState() == null ? null : t.getDelegationState().name(), t.getPriority(),
                    t.getDueDate() == null ? null : t.getDueDate().getTime(), users, groups, locals));
        }
        tasks.sort(Comparator.comparing(TaskState::activityId));
        List<TimerState> timers = new ArrayList<>();
        for (Job j : engine.getManagementService().createJobQuery().processInstanceId(pid).timers().list()) {
            String act = j instanceof JobEntity e ? e.getActivityId() : null;
            String repeat = j instanceof TimerEntity te ? te.getRepeat() : null;
            timers.add(new TimerState(act, j.getDuedate() == null ? 0 : j.getDuedate().getTime(), repeat));
        }
        timers.sort(Comparator.comparing((TimerState x) -> String.valueOf(x.activityId())).thenComparingLong(TimerState::dueDateMillis));
        List<String> messages = new ArrayList<>();
        for (EventSubscription s : rs.createEventSubscriptionQuery().processInstanceId(pid).list()) {
            if (("message".equals(s.getEventType()) || "signal".equals(s.getEventType())) && s.getEventName() != null) {
                messages.add(s.getEventName());
            }
        }
        messages.sort(Comparator.naturalOrder());
        List<VariableValue> vars = new ArrayList<>();
        rs.getVariables(pid).forEach((k, v) -> {
            if (!PROVENANCE_VARIABLE.equals(k)) {
                vars.add(VariableValue.of(k, v));
            }
        });
        vars.sort(Comparator.comparing(VariableValue::name));
        long finished = engine.getHistoryService().createHistoricTaskInstanceQuery().processInstanceId(pid).finished().count();
        long executed = engine.getHistoryService().createHistoricJobLogQuery().processInstanceId(pid).successLog().count();
        return new TargetState(true, pi.isSuspended(), active, tasks, timers, messages, vars, finished, executed);
    }

    /** Leaf activity instances plus transition instances (a pending asyncBefore job is a transition instance). */
    private static void leaves(ActivityInstance ai, List<String> out, boolean root) {
        if (ai == null) {
            return;
        }
        for (TransitionInstance t : ai.getChildTransitionInstances()) {
            out.add(t.getActivityId());
        }
        if (ai.getChildActivityInstances().length == 0 && ai.getChildTransitionInstances().length == 0 && !root) {
            out.add(ai.getActivityId());
        }
        for (ActivityInstance c : ai.getChildActivityInstances()) {
            leaves(c, out, false);
        }
    }

    @Override
    public void delete(String pid, String reason) {
        engine.getRuntimeService().deleteProcessInstance(pid, reason, true, true, true, false);
    }
}
