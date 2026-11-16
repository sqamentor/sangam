// SPDX-License-Identifier: Apache-2.0
package org.sangam.modeller.replay.operaton;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

import org.operaton.bpm.engine.HistoryService;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.externaltask.ExternalTask;
import org.operaton.bpm.engine.externaltask.ExternalTaskQuery;
import org.operaton.bpm.engine.history.HistoricActivityInstance;
import org.operaton.bpm.engine.history.HistoricDecisionInputInstance;
import org.operaton.bpm.engine.history.HistoricDecisionInstance;
import org.operaton.bpm.engine.history.HistoricDecisionOutputInstance;
import org.operaton.bpm.engine.history.HistoricExternalTaskLog;
import org.operaton.bpm.engine.history.HistoricProcessInstance;
import org.operaton.bpm.engine.history.HistoricTaskInstance;
import org.operaton.bpm.engine.history.HistoricVariableInstance;
import org.sangam.core.engine.SangamEngineAdapter.LeasedWork;
import org.sangam.engine.OperatonEngineAdapter;
import org.sangam.modeller.replay.ReplayFixture.Deadline;
import org.sangam.modeller.replay.ReplayFixture.Decision;
import org.sangam.modeller.replay.ReplayFixture.ModelKind;
import org.sangam.modeller.replay.ReplayFixture.Observation;
import org.sangam.modeller.replay.ReplayFixture.SideEffectIntent;
import org.sangam.modeller.replay.ReplayFixture.Transition;
import org.sangam.modeller.replay.ReplayFixture.TransitionKind;
import org.sangam.modeller.replay.ReplayFixture.Value;

/**
 * M-136 (decision 151): what one capture or replay run produced on its isolated engine, read the same way on both sides
 * so the comparison sees like with like. The engine holds only this run, so every history row belongs to it.
 *
 * <ul>
 *   <li>end state: {@code INCIDENT} while an incident is open, else the root instance's {@code COMPLETED},
 *       {@code TERMINATED} (externally or internally) or {@code ACTIVE} (also suspended);</li>
 *   <li>outputs: the root instance's process-scope variables (local variables of inner scopes are not outputs);</li>
 *   <li>transitions: every activity instance in start order (ties by occurrence), {@code STARTED} and, once ended,
 *       {@code COMPLETED} or {@code CANCELLED};</li>
 *   <li>decisions: every decision evaluation in activity order, inputs by clause id, matched rules in rule order and
 *       one output map per matched rule;</li>
 *   <li>deadlines: each timer as it was scheduled ({@code TIMER}, its due time), then user task due and follow-up
 *       dates ({@code DUE_DATE}, {@code FOLLOW_UP});</li>
 *   <li>side-effect intents: one per external task created (the work a live run hands to a worker, which a replay
 *       never performs), channel {@code external-task:<topic>}, payload the SHA-256 of the variables a worker is
 *       offered at its first fetch (or, never fetched, at the end of the run; null when its execution is gone). The
 *       idempotency key is not part of the intent: Sangam's operation id is generated per run.</li>
 * </ul>
 * A value without an exact fixture form is reported to {@code unsupported} and left out.
 */
final class EngineObserver {

    /** The worker id the adapter fetches with; never a live worker. */
    static final String WORKER = "sangam-replay";
    /** Upper bound of one fetch; an isolated run holds a handful of tasks. */
    private static final int FETCH_ALL = 10_000;
    /** The lease of a fetch whose result is applied at once (capture and replay alike). */
    static final Duration RESULT_LEASE = Duration.ofMinutes(1);

    private final List<Deadline> timers = new ArrayList<>();
    private final Map<String, String> offered = new HashMap<>();

    /** The deployed version of a BPMN process or DMN decision definition, or null when the deployment has none. */
    static Integer versionOf(OperatonEngineAdapter engine, String deploymentId, ModelKind kind, String key) {
        var repo = engine.processEngine().getRepositoryService();
        if (kind == ModelKind.BPMN) {
            var d = repo.createProcessDefinitionQuery().deploymentId(deploymentId).processDefinitionKey(key).singleResult();
            return d == null ? null : d.getVersion();
        }
        if (kind == ModelKind.DMN) {
            var d = repo.createDecisionDefinitionQuery().deploymentId(deploymentId).decisionDefinitionKey(key).singleResult();
            return d == null ? null : d.getVersion();
        }
        return null;
    }

    void timerScheduled(String activityId, Instant dueAt) {
        timers.add(new Deadline(activityId, "TIMER", dueAt == null ? null : dueAt.toString()));
    }

    /**
     * Leases the external task waiting at {@code activityId} (in {@code executionId} when given) as the replay worker.
     * Every available task of its topic is fetched (a fetch cannot name one task); the others are unlocked again at
     * once, the same on both sides. Null when no such task is waiting.
     *
     * @throws IllegalStateException when more than one task waits there: a result could not be tied to one of them
     */
    LeasedWork lease(OperatonEngineAdapter engine, String activityId, String executionId, Duration lock, ZoneId zone,
                     Consumer<String> unsupported) {
        ExternalTaskQuery q = engine.processEngine().getExternalTaskService().createExternalTaskQuery()
                .activityId(activityId).withRetriesLeft();
        if (executionId != null) {
            q.executionId(executionId);
        }
        // the fetch's own rule (Operaton 2.2.0 ExternalTask.xml selectExternalTasksForTopics): a task is free when its
        // lock expired at or before now, the same instant the lease sweep ends a lease (expires_at_ms <= now). The
        // query's notLocked() is strict (LOCK_EXP_TIME_ < now) and would miss a task the fetch hands out.
        Instant now = engine.clock().now();
        List<ExternalTask> waiting = q.list().stream()
                .filter(t -> t.getLockExpirationTime() == null || !t.getLockExpirationTime().toInstant().isAfter(now))
                .toList();
        if (waiting.isEmpty()) {
            return null;
        }
        if (waiting.size() > 1) {
            throw new IllegalStateException("decision 151: " + waiting.size() + " external tasks wait at " + activityId
                    + "; a recorded result cannot be tied to one of them");
        }
        ExternalTask target = waiting.get(0);
        LeasedWork mine = null;
        for (LeasedWork w : engine.fetchAndLockWork(WORKER, target.getTopicName(), FETCH_ALL, lock)) {
            if (w.work().id().equals(target.getId())) {
                mine = w;
            } else {
                engine.unlockExternal(w.work().id(), WORKER, w.work().lockVersion());
            }
        }
        if (mine != null) {
            offered.putIfAbsent(mine.work().id(), sha(mine.work().variables(), zone, "external task " + activityId,
                    unsupported));
        }
        return mine;
    }

    Observation observe(OperatonEngineAdapter engine, String rootInstanceId, ZoneId zone, Consumer<String> unsupported) {
        ProcessEngine pe = engine.processEngine();
        HistoryService history = pe.getHistoryService();
        List<HistoricActivityInstance> acts = history.createHistoricActivityInstanceQuery()
                .orderByHistoricActivityInstanceStartTime().asc().orderPartiallyByOccurrence().asc().list();
        Map<String, Integer> position = new HashMap<>();
        List<Transition> transitions = new ArrayList<>();
        for (HistoricActivityInstance a : acts) {
            position.put(a.getId(), position.size());
            transitions.add(new Transition(a.getExecutionId(), a.getActivityId(), TransitionKind.STARTED));
            if (a.getEndTime() != null) {
                transitions.add(new Transition(a.getExecutionId(), a.getActivityId(),
                        a.isCanceled() ? TransitionKind.CANCELLED : TransitionKind.COMPLETED));
            }
        }
        Comparator<String> byActivity = Comparator.comparing(id -> id == null ? Integer.MAX_VALUE
                : position.getOrDefault(id, Integer.MAX_VALUE));

        String endState;
        HistoricProcessInstance root = history.createHistoricProcessInstanceQuery().processInstanceId(rootInstanceId)
                .singleResult();
        if (pe.getRuntimeService().createIncidentQuery().count() > 0) {
            endState = "INCIDENT";
        } else if (root == null) {
            throw new IllegalStateException("decision 151: no history of instance " + rootInstanceId);
        } else {
            endState = switch (String.valueOf(root.getState())) {
                case "COMPLETED" -> "COMPLETED";
                case "EXTERNALLY_TERMINATED", "INTERNALLY_TERMINATED" -> "TERMINATED";
                default -> "ACTIVE";
            };
        }

        Map<String, Value> outputs = new TreeMap<>();
        for (HistoricVariableInstance v : history.createHistoricVariableInstanceQuery().processInstanceId(rootInstanceId)
                .disableCustomObjectDeserialization().list()) {
            if (!rootInstanceId.equals(v.getExecutionId())) {
                continue; // a local variable of an inner scope
            }
            put(outputs, v.getName(), v.getErrorMessage() == null ? v.getTypedValue() : new Unreadable(v.getErrorMessage()),
                    zone, "output " + v.getName(), unsupported);
        }

        List<HistoricDecisionInstance> evaluated = new ArrayList<>(history.createHistoricDecisionInstanceQuery()
                .includeInputs().includeOutputs().list());
        evaluated.sort(Comparator.comparing(HistoricDecisionInstance::getActivityInstanceId, byActivity)
                .thenComparing(HistoricDecisionInstance::getEvaluationTime)
                .thenComparing(HistoricDecisionInstance::getDecisionDefinitionKey));
        List<Decision> decisions = new ArrayList<>();
        for (HistoricDecisionInstance d : evaluated) {
            String where = "decision " + d.getDecisionDefinitionKey();
            Map<String, Value> in = new TreeMap<>();
            for (HistoricDecisionInputInstance i : d.getInputs()) {
                put(in, i.getClauseId(), i.getErrorMessage() == null ? i.getTypedValue() : new Unreadable(i.getErrorMessage()),
                        zone, where + " input " + i.getClauseId(), unsupported);
            }
            List<HistoricDecisionOutputInstance> outs = new ArrayList<>(d.getOutputs());
            outs.sort(Comparator.comparing(HistoricDecisionOutputInstance::getRuleOrder,
                    Comparator.nullsLast(Comparator.naturalOrder())));
            Map<String, Map<String, Value>> perRule = new LinkedHashMap<>();
            for (HistoricDecisionOutputInstance o : outs) {
                Map<String, Value> entry = perRule.computeIfAbsent(o.getRuleId(), k -> new TreeMap<>());
                put(entry, o.getVariableName(), o.getErrorMessage() == null ? o.getTypedValue() : new Unreadable(o.getErrorMessage()),
                        zone, where + " output " + o.getVariableName(), unsupported);
            }
            decisions.add(new Decision(d.getActivityId(), d.getDecisionDefinitionKey(), in, new ArrayList<>(perRule.keySet()),
                    new ArrayList<>(perRule.values())));
        }

        List<Deadline> deadlines = new ArrayList<>(timers);
        List<HistoricTaskInstance> tasks = new ArrayList<>(history.createHistoricTaskInstanceQuery().list());
        tasks.sort(Comparator.comparing(HistoricTaskInstance::getActivityInstanceId, byActivity));
        for (HistoricTaskInstance t : tasks) {
            if (t.getDueDate() != null) {
                deadlines.add(new Deadline(t.getTaskDefinitionKey(), "DUE_DATE", t.getDueDate().toInstant().toString()));
            }
            if (t.getFollowUpDate() != null) {
                deadlines.add(new Deadline(t.getTaskDefinitionKey(), "FOLLOW_UP", t.getFollowUpDate().toInstant().toString()));
            }
        }

        List<HistoricExternalTaskLog> created = new ArrayList<>(history.createHistoricExternalTaskLogQuery().creationLog()
                .list());
        created.sort(Comparator.comparing(HistoricExternalTaskLog::getActivityInstanceId, byActivity)
                .thenComparing(HistoricExternalTaskLog::getTimestamp));
        List<SideEffectIntent> effects = new ArrayList<>();
        for (HistoricExternalTaskLog c : created) {
            String sha = offered.get(c.getExternalTaskId());
            if (sha == null && pe.getRuntimeService().createExecutionQuery().executionId(c.getExecutionId()).count() > 0) {
                sha = sha(pe.getRuntimeService().getVariables(c.getExecutionId()), zone, "external task "
                        + c.getActivityId(), unsupported);
            }
            effects.add(new SideEffectIntent(c.getActivityId(), "external-task:" + c.getTopicName(), null, sha));
        }
        return new Observation(endState, outputs, transitions, decisions, deadlines, effects);
    }

    private static String sha(Map<String, Object> variables, ZoneId zone, String where, Consumer<String> unsupported) {
        Map<String, Value> values = new TreeMap<>();
        if (variables != null) {
            variables.forEach((k, v) -> put(values, k, v, zone, where + " variable " + k, unsupported));
        }
        return EngineValues.sha256(values);
    }

    private static void put(Map<String, Value> into, String name, Object raw, ZoneId zone, String where,
                            Consumer<String> unsupported) {
        if (raw instanceof Unreadable u) {
            unsupported.accept(where + " cannot be read: " + u.error());
            return;
        }
        Value v = EngineValues.toValue(raw, zone);
        if (v == null) {
            Object plain = raw instanceof org.operaton.bpm.engine.variable.value.TypedValue tv ? tv.getValue() : raw;
            unsupported.accept(where + " is a " + (plain == null ? "null" : plain.getClass().getName())
                    + " value, which a replay fixture cannot carry exactly");
            return;
        }
        into.put(name, v);
    }

    /** A history value the engine could not read back. */
    private record Unreadable(String error) {
    }
}
