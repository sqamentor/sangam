// SPDX-License-Identifier: Apache-2.0
package org.sangam.ops.flowable;

import org.sangam.ops.flowable.FlowableSnapshot.Execution;
import org.sangam.ops.flowable.FlowableSnapshot.Instance;
import org.sangam.ops.flowable.FlowableSnapshot.Job;
import org.sangam.ops.flowable.FlowableSnapshot.JobKind;
import org.sangam.ops.flowable.FlowableSnapshot.Subscription;
import org.sangam.ops.flowable.FlowableSnapshot.Task;
import org.sangam.ops.flowable.FlowableSnapshot.Variable;
import org.sangam.ops.flowable.InstancePlan.Finding;
import org.sangam.ops.flowable.InstancePlan.Severity;
import org.sangam.ops.flowable.InstancePlan.TaskState;
import org.sangam.ops.flowable.InstancePlan.TimerState;
import org.sangam.ops.flowable.InstancePlan.VariableValue;
import org.sangam.ops.flowable.OperatonStateWriter.ActivityFacts;
import org.sangam.ops.flowable.OperatonStateWriter.ActivityKind;
import org.sangam.ops.flowable.OperatonStateWriter.TargetModel;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * M-135 step 2, classification: decides per Flowable instance whether its state can be recreated on Operaton without
 * replaying any effect, and builds the plan. The supported subset is deliberately small; everything else is REJECTED
 * with a stable code and stays on Flowable (finish it there, or migrate it by hand).
 *
 * <p>Supported wait states (each must match the target activity exactly): a user task (USER_TASK), a message or
 * signal catch (receive task or intermediate catch event), an intermediate timer, an event-based gateway, a pending
 * async continuation (the target activity is asyncBefore, so starting it creates a job and runs nothing), and a pending
 * external-worker job (target external task). Boundary events are recreated with their activity; their timers keep the
 * original due date.
 *
 * <p>REJECT codes: {@value #NO_DEFINITION_MAPPING}, {@value #TARGET_DEFINITION_MISSING}, {@value #CASE_LINKED},
 * {@value #CALL_ACTIVITY_PARENT}, {@value #CALL_ACTIVITY_CHILD}, {@value #MULTI_INSTANCE}, {@value #EVENT_SUBPROCESS},
 * {@value #COMPENSATION}, {@value #LOCKED_JOB}, {@value #FAILED_JOB}, {@value #DEADLETTER_JOB},
 * {@value #AMBIGUOUS_LOCAL_TIME}, {@value #SUBTASK}, {@value #UNKNOWN_WAIT_STATE}, {@value #NO_WAIT_STATE},
 * {@value #TARGET_ACTIVITY_MISSING}, {@value #TARGET_ACTIVITY_MISMATCH}, {@value #DUPLICATE_ACTIVITY},
 * {@value #BINARY_VARIABLE}, {@value #UNSUPPORTED_VARIABLE_TYPE}, {@value #EXECUTION_LOCAL_VARIABLE},
 * {@value #TIMER_ELEMENT_MISSING}, {@value #TASK_LISTENERS}, {@value #UNSUPPORTED_JOB_HANDLER},
 * {@value #LONG_TEXT_VARIABLE}, {@value #JSON_VARIABLE}, {@value #SOURCE_MODEL_UNREAD}, and
 * {@value #SOURCE_DELETE_LISTENER} and {@value #SOURCE_EVENT_LISTENER} unless acknowledged. WARN codes:
 * {@value #DOUBLE_VARIABLE}, {@value #UUID_AS_STRING}, {@value #REPEATING_TIMER}, {@value #OVERDUE_TIMER}, and
 * {@value #SOURCE_DELETE_LISTENER} and {@value #SOURCE_EVENT_LISTENER} of an acknowledged definition.
 *
 * <p>Decision 169, checked against the Flowable source (reference copy, {@code modules/}): only a job with handler
 * {@code async-continuation} is a not-yet-started activity ({@code ContinueProcessOperation.java:187}); an
 * {@code async-after} or {@code async-trigger} job means the activity already ran
 * ({@code TakeOutgoingSequenceFlowsOperation.java:133}, {@code TriggerExecutionOperation.java:64}), so every handler
 * other than the one expected for the job table is rejected. {@code longString} is always stored as bytes and
 * {@code json} or {@code longJson} above the text limit too ({@code LongStringType.java:45-51},
 * {@code JsonType.java:121-141}); {@code json} in TEXT_ has no target type (the reactor has no JSON variable type).
 * The source listener findings describe what ending the instance on Flowable after cut-over fires
 * ({@link FlowableModelListeners}).
 *
 * <p>Owner decision 231: those listener findings REJECT by default. An operator may acknowledge them per source process
 * definition ({@link FlowableMigrationPipeline#acknowledgeListeners}); the acknowledgement is bound to the
 * {@link FlowableModelListeners.Found#fingerprint() fingerprint} of the listeners seen, and only an acknowledgement
 * with the same fingerprint turns them into WARN. A model that cannot be read always rejects: nothing can be
 * acknowledged about listeners nobody has seen.
 */
public final class StateClassifier {

    public static final String NO_DEFINITION_MAPPING = "NO_DEFINITION_MAPPING";
    public static final String TARGET_DEFINITION_MISSING = "TARGET_DEFINITION_MISSING";
    public static final String CASE_LINKED = "CASE_LINKED";
    public static final String CALL_ACTIVITY_PARENT = "CALL_ACTIVITY_PARENT";
    public static final String CALL_ACTIVITY_CHILD = "CALL_ACTIVITY_CHILD";
    public static final String MULTI_INSTANCE = "MULTI_INSTANCE";
    public static final String EVENT_SUBPROCESS = "EVENT_SUBPROCESS";
    public static final String COMPENSATION = "COMPENSATION";
    public static final String LOCKED_JOB = "LOCKED_JOB";
    public static final String FAILED_JOB = "FAILED_JOB";
    public static final String DEADLETTER_JOB = "DEADLETTER_JOB";
    public static final String AMBIGUOUS_LOCAL_TIME = "AMBIGUOUS_LOCAL_TIME";
    public static final String SUBTASK = "SUBTASK";
    public static final String UNKNOWN_WAIT_STATE = "UNKNOWN_WAIT_STATE";
    public static final String NO_WAIT_STATE = "NO_WAIT_STATE";
    public static final String TARGET_ACTIVITY_MISSING = "TARGET_ACTIVITY_MISSING";
    public static final String TARGET_ACTIVITY_MISMATCH = "TARGET_ACTIVITY_MISMATCH";
    public static final String DUPLICATE_ACTIVITY = "DUPLICATE_ACTIVITY";
    public static final String BINARY_VARIABLE = "BINARY_VARIABLE";
    public static final String UNSUPPORTED_VARIABLE_TYPE = "UNSUPPORTED_VARIABLE_TYPE";
    public static final String EXECUTION_LOCAL_VARIABLE = "EXECUTION_LOCAL_VARIABLE";
    public static final String TIMER_ELEMENT_MISSING = "TIMER_ELEMENT_MISSING";
    public static final String TASK_LISTENERS = "TASK_LISTENERS";
    public static final String UNSUPPORTED_JOB_HANDLER = "UNSUPPORTED_JOB_HANDLER";
    public static final String LONG_TEXT_VARIABLE = "LONG_TEXT_VARIABLE";
    public static final String JSON_VARIABLE = "JSON_VARIABLE";
    public static final String SOURCE_DELETE_LISTENER = "SOURCE_DELETE_LISTENER";
    public static final String SOURCE_EVENT_LISTENER = "SOURCE_EVENT_LISTENER";
    public static final String SOURCE_MODEL_UNREAD = "SOURCE_MODEL_UNREAD";
    public static final String DOUBLE_VARIABLE = "DOUBLE_VARIABLE";
    public static final String UUID_AS_STRING = "UUID_AS_STRING";
    public static final String REPEATING_TIMER = "REPEATING_TIMER";
    public static final String OVERDUE_TIMER = "OVERDUE_TIMER";

    /** What holds a source execution at its activity. */
    enum Wait { TASK, CATCH, TIMER, ASYNC, EXTERNAL, GATEWAY }

    private static final Set<String> CARRIED_TYPES = Set.of("string", "long", "integer", "short", "boolean", "date", "null");
    /** Text types Flowable keeps in ACT_GE_BYTEARRAY: always for longString, above the text limit for json. */
    private static final Set<String> LONG_TEXT_TYPES = Set.of("longString", "json", "longJson");

    private final List<DefinitionMapping> mappings;
    private final TargetModel model;
    private final int defaultRetries;
    private final Map<String, String> acknowledged;

    /** Without any listener acknowledgement (decision 231 default: source listeners reject). */
    public StateClassifier(List<DefinitionMapping> mappings, TargetModel model, int defaultRetries) {
        this(mappings, model, defaultRetries, Map.of());
    }

    /**
     * @param defaultRetries the source engine's initial job retries (Flowable default 3); a job below it has failed
     *                       at least once, so an external effect may already have happened
     * @param acknowledged   Flowable process definition id to the listener fingerprint an operator acknowledged
     */
    public StateClassifier(List<DefinitionMapping> mappings, TargetModel model, int defaultRetries,
                           Map<String, String> acknowledged) {
        this.mappings = List.copyOf(mappings);
        this.model = Objects.requireNonNull(model, "model");
        this.defaultRetries = defaultRetries;
        this.acknowledged = Map.copyOf(acknowledged);
    }

    public InstancePlan classify(Instance in, Instant now) {
        List<Finding> f = new ArrayList<>();
        DefinitionMapping m = mapping(in.definitionKey(), in.definitionVersion());
        String target = null;
        if (m == null) {
            reject(f, NO_DEFINITION_MAPPING, null, "no mapping for Flowable definition " + in.definitionKey() + " v" + in.definitionVersion());
        } else {
            target = model.definitionId(m.targetKey(), m.targetVersion(), in.tenantId()).orElse(null);
            if (target == null) {
                reject(f, TARGET_DEFINITION_MISSING, null, "Operaton definition " + m.targetKey() + " v" + m.targetVersion()
                        + " is not deployed for tenant " + in.tenantId());
            }
        }
        instanceChecks(in, f);
        jobChecks(in, f);
        List<String> starts = new ArrayList<>();
        List<TaskState> tasks = new ArrayList<>();
        List<TimerState> timers = new ArrayList<>();
        List<String> messages = new ArrayList<>();
        Map<String, List<VariableValue>> taskLocals = new HashMap<>();
        List<VariableValue> vars = variables(in, f, taskLocals);
        if (target != null) {
            waits(in, m, target, f, starts, tasks, taskLocals);
            timers(in, m, target, now, f, timers);
        }
        sourceListeners(in, f);
        for (Subscription s : in.subscriptions()) {
            if ("compensate".equals(s.eventType())) {
                reject(f, COMPENSATION, s.activityId(), "compensation state cannot be carried over");
            } else if (s.eventName() != null) {
                messages.add(s.eventName());
            }
        }
        starts.sort(Comparator.naturalOrder());
        messages.sort(Comparator.naturalOrder());
        tasks.sort(Comparator.comparing(TaskState::activityId));
        timers.sort(Comparator.comparing(TimerState::activityId).thenComparingLong(TimerState::dueDateMillis));
        boolean rejected = f.stream().anyMatch(x -> x.severity() == Severity.REJECT);
        return new InstancePlan(in.id(), in.definitionId(), in.definitionKey(), in.definitionVersion(), in.tenantId(), target,
                in.businessKey(), in.suspended(), starts, tasks, timers, messages, vars,
                rejected ? InstancePlan.Status.REJECTED : InstancePlan.Status.SUPPORTED, f, in.fingerprint());
    }

    private DefinitionMapping mapping(String key, int version) {
        DefinitionMapping any = null;
        for (DefinitionMapping m : mappings) {
            if (m.matches(key, version)) {
                if (m.flowableVersion() != null) {
                    return m;
                }
                any = any == null ? m : any;
            }
        }
        return any;
    }

    private static void instanceChecks(Instance in, List<Finding> f) {
        if (in.callbackType() != null) {
            reject(f, CASE_LINKED, null, "the instance belongs to a CMMN case (" + in.callbackType() + ")");
        }
        if (in.superExecutionId() != null) {
            reject(f, CALL_ACTIVITY_CHILD, null, "started by a call activity; the parent link cannot be recreated");
        }
        if (in.hasSubInstance()) {
            reject(f, CALL_ACTIVITY_PARENT, null, "waits for a called instance; starting the call activity again would start a second child");
        }
        for (Execution e : in.executions()) {
            if (e.multiInstanceRoot()) {
                reject(f, MULTI_INSTANCE, e.activityId(), "multi-instance loop state (counters, completed instances) cannot be recreated");
            }
            if (e.eventScope()) {
                reject(f, COMPENSATION, e.activityId(), "an event-scope execution holds compensation state");
            }
        }
        for (String p : in.timeProblems()) {
            reject(f, AMBIGUOUS_LOCAL_TIME, null, p);
        }
        for (Task t : in.tasks()) {
            if (t.parentTaskId() != null) {
                reject(f, SUBTASK, t.taskDefinitionKey(), "sub-task " + t.id() + " of " + t.parentTaskId());
            }
            if ("cmmn".equals(t.scopeType())) {
                reject(f, CASE_LINKED, t.taskDefinitionKey(), "case task " + t.id());
            }
        }
    }

    private void jobChecks(Instance in, List<Finding> f) {
        for (Job j : in.jobs()) {
            if (j.kind() == JobKind.DEADLETTER) {
                reject(f, DEADLETTER_JOB, j.elementId(), "dead-letter job " + j.id() + ": its work failed; resolve it on Flowable first");
            } else if (j.lockOwner() != null) {
                reject(f, LOCKED_JOB, j.elementId(), "job " + j.id() + " is locked by " + j.lockOwner()
                        + "; it may be running: freeze and drain the Flowable workers");
            } else if (j.exceptionMessage() != null || j.retries() < defaultRetries) {
                reject(f, FAILED_JOB, j.elementId(), "job " + j.id() + " has failed before (retries " + j.retries()
                        + "); an external effect may already have happened");
            }
            String expected = expectedHandler(j.kind());
            if (expected != null && !expected.equals(j.handlerType())) {
                reject(f, UNSUPPORTED_JOB_HANDLER, j.elementId(), "job " + j.id() + " in the " + j.kind() + " table has handler "
                        + j.handlerType() + " (only " + expected + " is recreated): its activity may already have run");
            }
        }
    }

    /** The one handler each job table may hold for a migrated instance; null for dead letters (rejected anyway). */
    static String expectedHandler(JobKind kind) {
        return switch (kind) {
            case ASYNC, SUSPENDED_ASYNC -> FlowableTables.HANDLER_ASYNC_CONTINUATION;
            case TIMER, SUSPENDED_TIMER -> FlowableTables.HANDLER_TRIGGER_TIMER;
            case EXTERNAL -> FlowableTables.HANDLER_EXTERNAL_WORKER_COMPLETE;
            case DEADLETTER -> null;
        };
    }

    /**
     * What ending this instance on Flowable after cut-over fires (decisions 169 and 231): the delete or all task
     * listeners of each open user task and the process-scoped event listeners. REJECT unless an operator acknowledged
     * this definition's listeners with the same fingerprint, then WARN; an unread model always rejects.
     */
    private void sourceListeners(Instance in, List<Finding> f) {
        FlowableModelListeners.Found l = in.listeners();
        if (!l.read()) {
            reject(f, SOURCE_MODEL_UNREAD, null, "the deployed Flowable model was not read (" + l.problem() + "); the "
                    + "listeners that ending the instance on Flowable fires are unknown and cannot be acknowledged");
            return;
        }
        String ack = in.definitionId() == null ? null : acknowledged.get(in.definitionId());
        boolean ok = ack != null && ack.equals(l.fingerprint());
        Severity sev = ok ? Severity.WARN : Severity.REJECT;
        String tail = ok ? " (acknowledged for definition " + in.definitionId() + ", decision 231)"
                : ack == null ? "; acknowledge definition " + in.definitionId() + " to migrate it (decision 231)"
                : "; the acknowledgement of definition " + in.definitionId() + " was for other listeners: acknowledge again";
        Set<String> seen = new HashSet<>();
        for (Task t : in.tasks()) {
            if (t.taskDefinitionKey() != null && l.deleteListener(t.taskDefinitionKey()) && seen.add(t.taskDefinitionKey())) {
                f.add(new Finding(sev, SOURCE_DELETE_LISTENER, t.taskDefinitionKey(), "user task " + t.taskDefinitionKey()
                        + " declares task listeners for " + l.taskListeners().get(t.taskDefinitionKey()) + "; ending the "
                        + "instance on Flowable deletes the task and runs them" + tail));
            }
        }
        if (!l.processListeners().isEmpty()) {
            f.add(new Finding(sev, SOURCE_EVENT_LISTENER, null, "the process declares event listeners " + l.processListeners()
                    + "; ending the instance on Flowable dispatches events to them" + tail));
        }
    }

    private List<VariableValue> variables(Instance in, List<Finding> f, Map<String, List<VariableValue>> taskLocals) {
        List<VariableValue> out = new ArrayList<>();
        for (Variable v : in.variables()) {
            if (v.binary() && LONG_TEXT_TYPES.contains(v.type())) {
                reject(f, LONG_TEXT_VARIABLE, v.name(), "variable " + v.name() + " (" + v.type() + ") is long text stored as "
                        + "bytes; not migrated");
                continue;
            }
            if (!v.binary() && ("json".equals(v.type()) || "longJson".equals(v.type()))) {
                reject(f, JSON_VARIABLE, v.name(), "variable " + v.name() + " (" + v.type() + ") is JSON; the target engine has "
                        + "no JSON variable type, and carrying it as a string would change its type");
                continue;
            }
            if (v.binary()) {
                reject(f, BINARY_VARIABLE, v.name(), "variable " + v.name() + " (" + v.type() + ") is stored as bytes; not migrated");
                continue;
            }
            String type = v.type();
            if ("uuid".equals(type)) {
                warn(f, UUID_AS_STRING, v.name(), "uuid variable " + v.name() + " becomes a string");
                type = "string";
            } else if ("double".equals(type)) {
                warn(f, DOUBLE_VARIABLE, v.name(), "double variable " + v.name() + " is carried as a double; a money or "
                        + "decimal value must become the engine decimal type (decision 138) before it is used");
            } else if (!CARRIED_TYPES.contains(type)) {
                reject(f, UNSUPPORTED_VARIABLE_TYPE, v.name(), "variable " + v.name() + " of type " + type + " is not carried");
                continue;
            }
            VariableValue value = new VariableValue(v.name(), type, v.text());
            if (v.taskId() != null) {
                taskLocals.computeIfAbsent(v.taskId(), k -> new ArrayList<>()).add(value);
            } else if (in.id().equals(v.executionId())) {
                out.add(value);
            } else {
                reject(f, EXECUTION_LOCAL_VARIABLE, v.name(), "variable " + v.name() + " is local to execution " + v.executionId());
            }
        }
        out.sort(Comparator.comparing(VariableValue::name));
        return out;
    }

    private void waits(Instance in, DefinitionMapping m, String target, List<Finding> f, List<String> starts,
                       List<TaskState> tasks, Map<String, List<VariableValue>> taskLocals) {
        Map<String, ActivityFacts> facts = new HashMap<>();
        java.util.function.Function<String, ActivityFacts> factsOf = a -> facts.computeIfAbsent(a, x -> model.activity(target, m.target(x)));
        Set<String> parentsOfNonBoundary = new HashSet<>();
        for (Execution e : in.executions()) {
            if (e.active() && e.activityId() != null && e.parentId() != null && factsOf.apply(e.activityId()).kind() != ActivityKind.BOUNDARY_EVENT) {
                parentsOfNonBoundary.add(e.parentId());
            }
        }
        Set<String> seen = new HashSet<>();
        for (Execution e : in.executions()) {
            if (!e.active() || e.activityId() == null || parentsOfNonBoundary.contains(e.id())) {
                continue;
            }
            ActivityFacts af = factsOf.apply(e.activityId());
            if (af.kind() == ActivityKind.BOUNDARY_EVENT) {
                continue; // recreated with the activity it is attached to
            }
            String act = m.target(e.activityId());
            Wait w = waitOf(in, e, af);
            if (w == null) {
                reject(f, UNKNOWN_WAIT_STATE, e.activityId(), "execution " + e.id() + " at " + e.activityId() + " has no task, "
                        + "subscription, timer or job holding it");
                continue;
            }
            if (!targetFits(w, af, e.activityId(), f)) {
                continue;
            }
            if (!seen.add(act)) {
                reject(f, DUPLICATE_ACTIVITY, e.activityId(), "two executions wait at " + e.activityId());
                continue;
            }
            starts.add(act);
            if (w == Wait.TASK) {
                for (Task t : in.tasks()) {
                    if (e.id().equals(t.executionId())) {
                        tasks.add(new TaskState(act, t.name(), t.assignee(), t.owner(), t.delegation(), t.priority(),
                                t.dueDate() == null ? null : t.dueDate().toEpochMilli(), t.candidateUsers(), t.candidateGroups(),
                                taskLocals.getOrDefault(t.id(), List.of())));
                    }
                }
            }
        }
        if (starts.isEmpty() && f.stream().noneMatch(x -> x.severity() == Severity.REJECT)) {
            reject(f, NO_WAIT_STATE, null, "no active wait state found");
        }
    }

    private static Wait waitOf(Instance in, Execution e, ActivityFacts af) {
        if (in.tasks().stream().anyMatch(t -> e.id().equals(t.executionId()))) {
            return Wait.TASK;
        }
        for (Job j : in.jobs()) {
            if (!e.id().equals(j.executionId())) {
                continue;
            }
            if (j.timer() && e.activityId().equals(j.elementId())) {
                return Wait.TIMER;
            }
            if ((j.kind() == JobKind.ASYNC || j.kind() == JobKind.SUSPENDED_ASYNC)
                    && FlowableTables.HANDLER_ASYNC_CONTINUATION.equals(j.handlerType())) {
                return Wait.ASYNC;
            }
            if (j.kind() == JobKind.EXTERNAL) {
                return Wait.EXTERNAL;
            }
        }
        if (in.subscriptions().stream().anyMatch(s -> e.id().equals(s.executionId()) && e.activityId().equals(s.activityId())
                && ("message".equals(s.eventType()) || "signal".equals(s.eventType())))) {
            return Wait.CATCH;
        }
        return af.kind() == ActivityKind.EVENT_GATEWAY ? Wait.GATEWAY : null;
    }

    private static boolean targetFits(Wait w, ActivityFacts af, String activity, List<Finding> f) {
        switch (af.kind()) {
            case MISSING -> {
                reject(f, TARGET_ACTIVITY_MISSING, activity, "the target definition has no activity " + activity);
                return false;
            }
            case IN_MULTI_INSTANCE -> {
                reject(f, MULTI_INSTANCE, activity, "the target activity is (inside) a multi-instance activity");
                return false;
            }
            case IN_EVENT_SUBPROCESS -> {
                reject(f, EVENT_SUBPROCESS, activity, "the wait state is inside an event subprocess");
                return false;
            }
            case CALL_ACTIVITY -> {
                reject(f, CALL_ACTIVITY_PARENT, activity, "starting a call activity would start a new child instance");
                return false;
            }
            default -> {
            }
        }
        boolean ok = switch (w) {
            case TASK -> af.kind() == ActivityKind.USER_TASK && !af.asyncBefore();
            case CATCH -> (af.kind() == ActivityKind.RECEIVE_TASK || af.kind() == ActivityKind.CATCH_EVENT) && !af.asyncBefore();
            case TIMER -> af.kind() == ActivityKind.CATCH_EVENT && !af.asyncBefore();
            case GATEWAY -> af.kind() == ActivityKind.EVENT_GATEWAY && !af.asyncBefore();
            case EXTERNAL -> af.kind() == ActivityKind.EXTERNAL_TASK && !af.asyncBefore();
            case ASYNC -> af.asyncBefore();
        };
        if (ok && w == Wait.TASK && af.customTaskListeners()) {
            reject(f, TASK_LISTENERS, activity, "the target user task declares task listeners; setting its assignee, "
                    + "candidates and dates would fire them again");
            return false;
        }
        if (!ok) {
            reject(f, TARGET_ACTIVITY_MISMATCH, activity, "source waits as " + w + " but the target activity is " + af.kind()
                    + (af.asyncBefore() ? " (asyncBefore)" : "") + "; starting it would not recreate the same wait state");
        }
        return ok;
    }

    private void timers(Instance in, DefinitionMapping m, String target, Instant now, List<Finding> f, List<TimerState> timers) {
        for (Job j : in.jobs()) {
            if (!j.timer() || j.dueDate() == null) {
                continue;
            }
            String act = m.target(j.elementId());
            if (j.elementId() == null || model.activity(target, act).kind() == ActivityKind.MISSING) {
                reject(f, TIMER_ELEMENT_MISSING, j.elementId(), "timer job " + j.id() + " belongs to no target activity");
                continue;
            }
            if (j.repeat() != null) {
                warn(f, REPEATING_TIMER, j.elementId(), "repeating timer: the next due date is kept, later cycles follow the target definition");
            }
            if (j.dueDate().isBefore(now)) {
                warn(f, OVERDUE_TIMER, j.elementId(), "timer was due at " + j.dueDate() + " and stays overdue (fires when the job executor runs)");
            }
            timers.add(new TimerState(act, j.dueDate().toEpochMilli(), j.repeat()));
        }
    }

    private static void reject(List<Finding> f, String code, String element, String message) {
        f.add(new Finding(Severity.REJECT, code, element, message));
    }

    private static void warn(List<Finding> f, String code, String element, String message) {
        f.add(new Finding(Severity.WARN, code, element, message));
    }
}
