// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.runtime.EventSubscription;
import org.operaton.bpm.engine.task.Task;

/**
 * SL-32 {@code run.steps}: the part of an acceptance scenario's "when" that configuration answers cannot express,
 * applied in order on one case (tools/packgen {@code acceptance_runs.py} documents and checks the grammar). A step
 * fires when its activity {@code at} is waiting in the case (an open user task, an asynchronous job, a timer or a
 * message subscription; for a user task the {@code n}-th time it is open). Exactly one of:
 * <ul>
 *   <li>{@code advance}: an ISO-8601 duration; the scenario clock moves forward and the due timers then run;</li>
 *   <li>{@code action = complete}: the {@code n}-th occurrence of user task {@code at} is completed with
 *       {@code vars}, {@code person} and {@code seat} over the configuration answer (return, decline, reschedule);</li>
 *   <li>{@code action = correlate}: the message {@code at} waits for ({@code message}, default the subscription's
 *       name) is correlated with {@code vars} to the process instance holding {@code at};</li>
 *   <li>{@code action = set}: {@code vars} are set on the process instance holding {@code at};</li>
 *   <li>{@code action = start}: one more case of the scenario is started ({@code pack}'s first main process or
 *       {@code process}, business key {@code <scenario>/<key>}, {@code vars} with {@value #CASE} = the scenario's
 *       business key), for scenarios that span several cases (GP-47, GP-28).</li>
 * </ul>
 * {@code correlate} and {@code set} may say {@code refused}: the action must be refused with that code, and the case
 * goes on. Nothing here is pack-specific (ADR-D-20).
 */
final class AcceptanceSteps {

    static final Set<String> ACTIONS = Set.of("complete", "correlate", "set", "start");
    /** In a {@code start} step's variables, this value is replaced by the scenario's business key. */
    static final String CASE = "@case";
    private static final ObjectMapper JSON = new ObjectMapper();

    /** One step of {@code run.steps}. */
    record Step(String at, int n, Duration advance, String action, String message, Map<String, Object> vars,
                String person, String seat, String refused, String pack, String process, String key, String caseKey) {

        @SuppressWarnings("unchecked")
        static Step of(JsonNode s, int index) {
            String where = "run.steps[" + index + "]";
            String at = s.path("at").asText("");
            if (at.isBlank()) {
                throw new IllegalArgumentException(where + ": needs an activity id in \"at\"");
            }
            boolean adv = s.has("advance");
            boolean act = s.has("action");
            if (adv == act) {
                throw new IllegalArgumentException(where + ": give exactly one of \"advance\" and \"action\"");
            }
            Duration d = null;
            if (adv) {
                d = Duration.parse(s.path("advance").asText());
                if (d.isNegative() || d.isZero()) {
                    throw new IllegalArgumentException(where + ": \"advance\" must move the clock forward");
                }
            }
            String action = act ? s.path("action").asText() : null;
            if (act && !ACTIONS.contains(action)) {
                throw new IllegalArgumentException(where + ": unknown action " + action + "; one of " + ACTIONS);
            }
            String refused = s.hasNonNull("refused") ? s.path("refused").asText() : null;
            if (refused != null && !"correlate".equals(action) && !"set".equals(action)) {
                throw new IllegalArgumentException(where + ": \"refused\" applies to correlate and set only");
            }
            int n = s.path("n").asInt(1);
            if (n < 1) {
                throw new IllegalArgumentException(where + ": \"n\" is a positive occurrence number");
            }
            if ("start".equals(action) && (!s.hasNonNull("key") || s.hasNonNull("pack") == s.hasNonNull("process"))) {
                throw new IllegalArgumentException(where + ": start needs \"key\" and exactly one of \"pack\" and"
                        + " \"process\"");
            }
            Map<String, Object> vars = s.has("vars") ? JSON.convertValue(s.path("vars"), Map.class) : Map.of();
            return new Step(at, n, d, action, s.hasNonNull("message") ? s.path("message").asText() : null, vars,
                    s.hasNonNull("person") ? s.path("person").asText() : null,
                    s.hasNonNull("seat") ? s.path("seat").asText() : null, refused,
                    s.hasNonNull("pack") ? s.path("pack").asText() : null,
                    s.hasNonNull("process") ? s.path("process").asText() : null,
                    s.hasNonNull("key") ? s.path("key").asText() : null,
                    s.hasNonNull("case") ? s.path("case").asText() : null);
        }
    }

    private final ProcessEngine engine;
    private final PackDriver driver;
    private final String businessKey;
    private final List<Step> steps;
    private final List<String> refusals = new ArrayList<>();
    private final java.util.function.Function<String, String> mainProcessOf;
    private int cursor;

    AcceptanceSteps(ProcessEngine engine, PackDriver driver, String businessKey, JsonNode steps) {
        this(engine, driver, businessKey, steps, pack -> {
            throw new IllegalArgumentException("no pack registry to resolve " + pack);
        });
    }

    /** {@code mainProcessOf}: a pack id to its first main process (for {@code start} with {@code pack}). */
    AcceptanceSteps(ProcessEngine engine, PackDriver driver, String businessKey, JsonNode steps,
                    java.util.function.Function<String, String> mainProcessOf) {
        this.engine = engine;
        this.driver = driver;
        this.businessKey = businessKey;
        this.mainProcessOf = mainProcessOf;
        List<Step> out = new ArrayList<>();
        int i = 0;
        for (JsonNode s : steps) {
            out.add(Step.of(s, i++));
        }
        this.steps = Collections.unmodifiableList(out);
    }

    List<Step> steps() {
        return steps;
    }

    /** Steps not applied when the case stopped moving (a scenario whose "when" never happened fails). */
    List<Step> pending() {
        return steps.subList(cursor, steps.size());
    }

    /** The refusal messages of the steps that expected one. */
    List<String> refusals() {
        return Collections.unmodifiableList(refusals);
    }

    /**
     * The driver's turn hook: applies the next step when its activity is waiting; true when it moved the case.
     * {@code complete} steps are applied by {@link #answer} instead.
     */
    boolean beforeTurn(List<String> done) {
        if (cursor >= steps.size()) {
            return false;
        }
        Step s = steps.get(cursor);
        if ("complete".equals(s.action())) {
            return false;
        }
        if (s.advance() != null && driver.hasDueJobs()) {
            return false; // time moves only when the case has settled: what is due now runs first
        }
        Optional<String> pid = waitingAt(s, done);
        if (pid.isEmpty()) {
            return false;
        }
        cursor++;
        if (s.advance() != null) {
            driver.advance(s.advance());
            return true;
        }
        try {
            if ("start".equals(s.action())) {
                start(s);
            } else if ("correlate".equals(s.action())) {
                String message = s.message() != null ? s.message() : messageAt(s.at(), pid.get());
                engine.getRuntimeService().createMessageCorrelation(message).processInstanceId(pid.get())
                        .setVariables(s.vars()).correlate();
            } else {
                engine.getRuntimeService().setVariables(pid.get(), s.vars());
            }
        } catch (RuntimeException e) {
            String m = refusalText(e);
            if (s.refused() == null || !m.contains(s.refused())) {
                throw e;
            }
            refusals.add(m);
            return true;
        }
        if (s.refused() != null) {
            throw new AssertionError("step " + s.action() + " at " + s.at() + " was not refused with " + s.refused());
        }
        return true;
    }

    /** The answer for a task: the configuration answer, or the next {@code complete} step's values when it is due. */
    PackDriver.Answer answer(Task t, PackDriver.Answer base, List<String> done) {
        if (cursor >= steps.size()) {
            return base;
        }
        Step s = steps.get(cursor);
        if (!"complete".equals(s.action()) || !s.at().equals(t.getTaskDefinitionKey())
                || Collections.frequency(done, s.at()) != s.n() - 1 || !inCase(s, t)) {
            return base;
        }
        cursor++;
        Map<String, Object> vars = new HashMap<>(base.vars());
        vars.putAll(s.vars());
        return new PackDriver.Answer(s.person() != null ? s.person() : base.person(),
                s.seat() != null ? s.seat() : base.seat(), vars);
    }

    /** The business key of the case a step names ({@code case}: one the scenario started). */
    private String caseOf(Step s) {
        return businessKey + "/" + s.caseKey();
    }

    /** True when the step names no case, or the task belongs to the case it names (or a block it called). */
    private boolean inCase(Step s, Task t) {
        if (s.caseKey() == null) {
            return true;
        }
        var p = engine.getRuntimeService().createProcessInstanceQuery().processInstanceId(t.getProcessInstanceId())
                .singleResult();
        return p != null && caseOf(s).equals(p.getBusinessKey());
    }

    /** The process instance of the case in which {@code at} waits (n-th occurrence for a user task). */
    Optional<String> waitingAt(Step s, List<String> done) {
        List<String> pids = new ArrayList<>();
        var q = engine.getRuntimeService().createProcessInstanceQuery();
        (s.caseKey() == null ? q.processInstanceBusinessKeyLike(businessKey + "%")
                : q.processInstanceBusinessKey(caseOf(s))).list().forEach(p -> pids.add(p.getId()));
        for (String pid : pids) {
            boolean task = engine.getTaskService().createTaskQuery().processInstanceId(pid)
                    .taskDefinitionKey(s.at()).count() > 0;
            if (task) {
                if (Collections.frequency(done, s.at()) == s.n() - 1) {
                    return Optional.of(pid);
                }
                continue;
            }
            if (engine.getRuntimeService().createExecutionQuery().processInstanceId(pid).activityId(s.at()).count() > 0
                    || engine.getRuntimeService().createEventSubscriptionQuery().processInstanceId(pid)
                    .activityId(s.at()).count() > 0
                    || engine.getManagementService().createJobQuery().processInstanceId(pid).activityId(s.at())
                    .count() > 0) {
                return Optional.of(pid);
            }
        }
        return Optional.empty();
    }

    /**
     * Starts one more case of the scenario (GP-47 / GP-28: applications next to a campaign): business key
     * {@code <scenario key>/<key>}, so the driver follows it; a variable whose value is {@value #CASE} gets the
     * scenario's business key (an application naming its campaign).
     */
    private void start(Step s) {
        String key = s.process() != null ? s.process() : mainProcessOf.apply(s.pack());
        Map<String, Object> vars = new HashMap<>();
        s.vars().forEach((k, v) -> vars.put(k, CASE.equals(v) ? businessKey : v));
        engine.getRuntimeService().createProcessInstanceByKey(key).businessKey(businessKey + "/" + s.key())
                .setVariables(vars).processDefinitionWithoutTenantId().execute();
    }

    /** The messages of the exception and its causes (the engine may wrap the delegate's refusal). */
    static String refusalText(Throwable e) {
        StringBuilder b = new StringBuilder();
        for (Throwable c = e; c != null && b.length() < 4000; c = c.getCause() == c ? null : c.getCause()) {
            b.append(c.getMessage()).append(" | ");
        }
        return b.toString();
    }

    private String messageAt(String at, String pid) {
        Iterator<EventSubscription> it = engine.getRuntimeService().createEventSubscriptionQuery()
                .processInstanceId(pid).activityId(at).eventType("message").list().iterator();
        if (!it.hasNext()) {
            throw new IllegalStateException("no message subscription at " + at + " in " + pid);
        }
        return it.next().getEventName();
    }
}
