// SPDX-License-Identifier: Apache-2.0
package org.sangam.modeller.replay;

import java.time.Instant;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.sangam.modeller.replay.ReplayException.Code;
import org.sangam.modeller.replay.ReplayFixture.Event;
import org.sangam.modeller.replay.ReplayFixture.EventType;
import org.sangam.modeller.replay.ReplayFixture.Value;

/**
 * The only source of time and outside input during a replay (decision 151): a controlled clock and the fixture's
 * recorded events. A {@link ReplayDriver} asks here instead of the system clock, the network or a random source; an
 * input the fixture does not hold stops the replay ({@link Code#MISSING_EVENT}), and any live call stops it
 * ({@link Code#UNEXPECTED_EXTERNAL_CALL}). Nothing is ever substituted.
 *
 * <p>Two kinds of input:
 * <ul>
 *   <li><b>stimuli</b> (timer firings, delivered messages, external-task results) come from outside the engine, so the
 *       driver pulls them in recorded order with {@link #nextStimulus()} and applies each one;</li>
 *   <li><b>on-demand inputs</b> (clock reads, timer scheduling, random values, generated ids, external lookups) are
 *       asked for by the engine while it runs.</li>
 * </ul>
 *
 * <p>Matching:
 * <ul>
 *   <li>{@link ReplayRun.Mode#EXACT}: each captured execution's events are consumed strictly in their recorded order.
 *       Execution ids generated in the replay are bound to captured ones on first use (same event type and
 *       activity), and the binding is reported ({@link #executionBinding()}); a request that does not match the next
 *       recorded event of its execution, or a stimulus whose execution still has an earlier unconsumed input, stops
 *       the replay.</li>
 *   <li>{@link ReplayRun.Mode#UPGRADE}: a changed model may ask in another order, so an on-demand input is the earliest
 *       unconsumed event of the same type and activity, in any execution. A missing input still stops the replay;
 *       unconsumed inputs are reported, not failed.</li>
 * </ul>
 * The clock starts at the fixture's {@code startedAt} and moves only to the time of each consumed event; it never goes
 * back. Not thread-safe: one replay drives one instance.
 */
public final class RecordedInputs {

    private static final Set<EventType> STIMULI = Set.of(EventType.TIMER_FIRED, EventType.MESSAGE_DELIVERED,
            EventType.EXTERNAL_TASK_RESULT);

    private final ReplayFixture fixture;
    private final ReplayRun.Mode mode;
    private final List<Event> events;
    private final BitSet consumed;
    private final Map<String, String> binding = new LinkedHashMap<>();
    private final List<String> refusedCalls = new ArrayList<>();
    private Instant clock;

    RecordedInputs(ReplayFixture fixture, ReplayRun.Mode mode) {
        this.fixture = fixture;
        this.mode = Objects.requireNonNull(mode, "mode");
        this.events = fixture.events();
        this.consumed = new BitSet(events.size());
        this.clock = ReplayFixtures.instant(fixture.startedAt(), "startedAt");
    }

    /** The controlled clock: the fixture's start, or the time of the last consumed event. Never the system clock. */
    public Instant currentTime() {
        return clock;
    }

    /** The tenant context of the fixture (the replay runs as this tenant only). */
    public ReplayFixture.TenantContext tenant() {
        return fixture.tenant();
    }

    /**
     * The next recorded stimulus (timer firing, message delivery or external-task result) in capture order; the clock
     * moves to its time. Null when every stimulus has been consumed (the scenario has no more outside input).
     */
    public Event nextStimulus() {
        for (int i = 0; i < events.size(); i++) {
            Event e = events.get(i);
            if (consumed.get(i) || !STIMULI.contains(e.type())) {
                continue;
            }
            if (mode == ReplayRun.Mode.EXACT) {
                int first = firstUnconsumed(e.executionId());
                if (first != i) {
                    Event pending = events.get(first);
                    throw new ReplayException(Code.MISSING_EVENT, "recorded " + pending.type() + " seq " + pending.seq()
                            + " of execution " + pending.executionId() + " at " + pending.activityId()
                            + " was never asked for before stimulus seq " + e.seq());
                }
            }
            return consume(i);
        }
        return null;
    }

    /**
     * Binds a replay execution to the captured execution a stimulus belonged to, when the driver applies the
     * stimulus to its own execution. Refused if either side is already bound elsewhere (EXACT).
     */
    public void bind(String replayExecutionId, String capturedExecutionId) {
        String old = binding.get(replayExecutionId);
        if (old != null && !old.equals(capturedExecutionId)) {
            throw new ReplayException(Code.MISSING_EVENT, "replay execution " + replayExecutionId + " is bound to "
                    + old + ", not " + capturedExecutionId);
        }
        if (old == null && mode == ReplayRun.Mode.EXACT && binding.containsValue(capturedExecutionId)) {
            throw new ReplayException(Code.MISSING_EVENT, "captured execution " + capturedExecutionId
                    + " is already bound to another replay execution");
        }
        binding.put(replayExecutionId, capturedExecutionId);
    }

    /** A clock read (FEEL {@code now()}, {@code today()}, engine time): the recorded value; the clock moves to it. */
    public Instant clockRead(String executionId, String activityId) {
        return ReplayFixtures.instant(onDemand(EventType.CLOCK_READ, executionId, activityId).at(), "clock read");
    }

    /** A timer being scheduled: the recorded scheduling event (its payload holds the captured {@code dueAt}). */
    public Event timerScheduled(String executionId, String activityId) {
        return onDemand(EventType.TIMER_SCHEDULED, executionId, activityId);
    }

    /** A random value the process asks for: the recorded one. */
    public Value randomValue(String executionId, String activityId) {
        return onDemand(EventType.RANDOM_VALUE, executionId, activityId).payload().get("value");
    }

    /** A generated id (business number, instance id) the process asks for: the recorded one. */
    public Value generatedId(String executionId, String activityId) {
        return onDemand(EventType.GENERATED_ID, executionId, activityId).payload().get("value");
    }

    /**
     * An external lookup (connector read): the recorded response, only when the request equals the recorded request
     * exactly; a different request would need a live call and stops the replay.
     */
    public Value lookup(String executionId, String activityId, Value request) {
        Event e = onDemand(EventType.EXTERNAL_LOOKUP, executionId, activityId);
        if (!e.payload().get("request").equals(request)) {
            String call = "lookup at " + activityId + " with " + request + " (recorded " + e.payload().get("request") + ")";
            refusedCalls.add(call);
            throw new ReplayException(Code.UNEXPECTED_EXTERNAL_CALL, call);
        }
        return e.payload().get("response");
    }

    /**
     * Every live effect the engine would perform (connector call, payment, message send, production write) must be
     * routed here by the driver: it is always refused. Effects are compared as
     * {@link ReplayFixture.SideEffectIntent}s, never performed.
     */
    public void externalCall(String target, String description) {
        String call = target + ": " + description;
        refusedCalls.add(call);
        throw new ReplayException(Code.UNEXPECTED_EXTERNAL_CALL, call);
    }

    /** Replay execution id to captured execution id, in binding order (an explicit normalisation). */
    public Map<String, String> executionBinding() {
        return Collections.unmodifiableMap(binding);
    }

    /** What the replay consumed of the fixture; see {@link ReplayRun.Coverage}. */
    public ReplayRun.Coverage coverage() {
        Map<EventType, Integer> recorded = new EnumMap<>(EventType.class);
        Map<EventType, Integer> used = new EnumMap<>(EventType.class);
        List<Long> unconsumed = new ArrayList<>();
        for (int i = 0; i < events.size(); i++) {
            Event e = events.get(i);
            recorded.merge(e.type(), 1, Integer::sum);
            if (consumed.get(i)) {
                used.merge(e.type(), 1, Integer::sum);
            } else {
                unconsumed.add(e.seq());
            }
        }
        List<EventType> absent = new ArrayList<>();
        for (EventType t : ReplayRun.REQUIRED_CAPTURE) {
            if (!recorded.containsKey(t)) {
                absent.add(t);
            }
        }
        return new ReplayRun.Coverage(recorded, used, unconsumed, absent, Map.copyOf(binding), List.copyOf(refusedCalls));
    }

    private Event onDemand(EventType type, String executionId, String activityId) {
        Objects.requireNonNull(executionId, "executionId");
        int found = mode == ReplayRun.Mode.EXACT ? exactMatch(type, executionId, activityId) : upgradeMatch(type, activityId);
        if (found < 0) {
            throw new ReplayException(Code.MISSING_EVENT, "no recorded " + type + " for execution " + executionId
                    + " at " + activityId + (mode == ReplayRun.Mode.EXACT ? " in recorded order" : ""));
        }
        if (mode == ReplayRun.Mode.EXACT) {
            bind(executionId, events.get(found).executionId());
        }
        return consume(found);
    }

    private int exactMatch(EventType type, String replayExecution, String activityId) {
        String captured = binding.get(replayExecution);
        if (captured != null) {
            int i = firstUnconsumed(captured);
            return i >= 0 && matches(events.get(i), type, activityId) ? i : -1;
        }
        Set<String> bound = new HashSet<>(binding.values());
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < events.size(); i++) {
            Event e = events.get(i);
            if (consumed.get(i) || bound.contains(e.executionId()) || !seen.add(e.executionId())) {
                continue; // only the first unconsumed event of each unbound execution can match
            }
            if (matches(e, type, activityId)) {
                return i;
            }
        }
        return -1;
    }

    private int upgradeMatch(EventType type, String activityId) {
        for (int i = 0; i < events.size(); i++) {
            if (!consumed.get(i) && matches(events.get(i), type, activityId)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean matches(Event e, EventType type, String activityId) {
        return e.type() == type && Objects.equals(e.activityId(), activityId);
    }

    private int firstUnconsumed(String capturedExecution) {
        for (int i = 0; i < events.size(); i++) {
            if (!consumed.get(i) && events.get(i).executionId().equals(capturedExecution)) {
                return i;
            }
        }
        return -1;
    }

    private Event consume(int i) {
        consumed.set(i);
        Event e = events.get(i);
        Instant at = ReplayFixtures.instant(e.at(), "event " + e.seq());
        if (at.isAfter(clock)) {
            clock = at;
        }
        return e;
    }
}
