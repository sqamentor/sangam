// SPDX-License-Identifier: Apache-2.0
package org.sangam.modeller.replay.operaton;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import org.sangam.core.engine.SangamEngineAdapter.LeasedWork;
import org.sangam.core.time.BusinessClock;
import org.sangam.engine.OperatonEngineAdapter;
import org.sangam.engine.replay.CaptureListener;
import org.sangam.engine.replay.CapturedInput;
import org.sangam.modeller.replay.RecordedInputs;
import org.sangam.modeller.replay.ReplayDriver;
import org.sangam.modeller.replay.ReplayException;
import org.sangam.modeller.replay.ReplayException.Code;
import org.sangam.modeller.replay.ReplayFixture;
import org.sangam.modeller.replay.ReplayFixture.Event;
import org.sangam.modeller.replay.ReplayFixture.EventType;
import org.sangam.modeller.replay.ReplayFixture.ModelKind;
import org.sangam.modeller.replay.ReplayFixture.ModelRef;
import org.sangam.modeller.replay.ReplayFixture.Transition;
import org.sangam.modeller.replay.ReplayFixture.Value;
import org.sangam.modeller.replay.ReplayFixture.ValueType;
import org.sangam.modeller.replay.ReplayFixtures;

/**
 * M-136 (decision 151): the Operaton-backed {@link ReplayDriver}. Each replay runs on a fresh isolated Sangam engine
 * ({@link ReplayEngines.Factory}) whose capture listener is the replay itself: the engine reports every input it takes
 * through the same capture points as at capture, and each one is answered from or checked against the fixture.
 *
 * <ol>
 *   <li>Before the engine: every model is the fixture's (inline content, or a reference resolved by
 *       {@link #withReferences} and checked against its SHA-256), within {@link ReplayModelPolicy}; the tenant's
 *       business zone is the fixture's.</li>
 *   <li>The engine: its configuration ({@link ReplayEngines#configuration}) and FEEL evaluator must be the fixture's;
 *       every model deploys at the recorded definition version (a fresh engine: never a "latest" lookup); the engine
 *       clock is the {@link RecordedInputs} clock, set to the fixture's start and then to each stimulus's time.</li>
 *   <li>The run: the instance starts from the initial state as the fixture's tenant. Each recorded stimulus is peeked
 *       ({@link RecordedInputs#peekStimulus()}) and applied when the replayed instance waits for it: an external-task
 *       result (lease with the replay worker, then complete, fail with the recorded retries, BPMN error, or let the
 *       lease expire for a {@code TIMEOUT}, taken one millisecond before the recorded sweep), a message correlation, or due jobs at a timer's firing time. The engine's
 *       own report of the input consumes it; a stimulus the instance does not wait for stays unconsumed and the replay
 *       stops there (the run diverged; EXACT reports it as an INPUT difference).</li>
 *   <li>On-demand inputs: a FEEL clock read and a timer scheduling consume the next recorded event of their execution
 *       and must have happened at the recorded time; the computed due date is the deadline compared.</li>
 *   <li>Every input the engine takes must be the fixture's next one (type, activity, attempt, outcome, time and
 *       payload); anything else stops the replay with {@link Code#MISSING_EVENT}. An input without an execution, a
 *       value without an exact form or an input the engine reports unsupported stops it with
 *       {@link Code#UNSUPPORTED_SCENARIO}. A {@link ReplayException} is kept and rethrown after the engine call, so the
 *       engine's own job error handling never swallows it.</li>
 *   <li>Execution ids: bound on first use by {@link RecordedInputs}; executions that took no input are bound by aligning
 *       the replayed transitions with the captured ones while activity and kind agree, never one execution to two
 *       (the binding is reported with the comparison).</li>
 * </ol>
 * The replay engine has no connector, mail, payment or channel plugin and never calls a worker: external tasks are
 * compared as side-effect intents. Jobs run only when a recorded timer firing or lease expiry asks for them (the policy
 * refuses asynchronous continuations).
 *
 * <p>{@link #withChangedModel} replays the same fixture against a changed model (decision 151 changed-rule proof); its
 * result must differ, never pass.
 */
public final class OperatonReplayDriver implements ReplayDriver {

    /** The smallest step of the engine clock (Operaton stores milliseconds). */
    private static final Duration TICK = Duration.ofMillis(1);

    private final ReplayEngines.Factory engines;
    private final Map<String, String> changedModels;
    private final Function<ModelRef, byte[]> references;

    public OperatonReplayDriver(ReplayEngines.Factory engines) {
        this(engines, Map.of(), null);
    }

    private OperatonReplayDriver(ReplayEngines.Factory engines, Map<String, String> changedModels,
                                 Function<ModelRef, byte[]> references) {
        this.engines = Objects.requireNonNull(engines, "engines");
        this.changedModels = Map.copyOf(changedModels);
        this.references = references;
    }

    /** A driver that deploys {@code content} instead of the fixture's resource {@code resource} (a changed model). */
    public OperatonReplayDriver withChangedModel(String resource, String content) {
        Map<String, String> m = new HashMap<>(changedModels);
        m.put(Objects.requireNonNull(resource, "resource"), Objects.requireNonNull(content, "content"));
        return new OperatonReplayDriver(engines, m, references);
    }

    /** A driver that resolves a referenced model (an immutable reference) to its bytes; checked against its SHA-256. */
    public OperatonReplayDriver withReferences(Function<ModelRef, byte[]> resolver) {
        return new OperatonReplayDriver(engines, changedModels, Objects.requireNonNull(resolver, "resolver"));
    }

    @Override
    public String engineVersion() {
        return ReplayEngines.engineVersion();
    }

    @Override
    public String evaluatorVersion() {
        return ReplayEngines.evaluatorVersion();
    }

    @Override
    public ReplayFixture.Observation replay(ReplayFixture fixture, RecordedInputs inputs) {
        return new Run(fixture, inputs).go();
    }

    /** One replay. */
    private final class Run implements CaptureListener {
        private final ReplayFixture fixture;
        private final RecordedInputs inputs;
        private final EngineObserver observer = new EngineObserver();
        private final ZoneId zone;
        private final String tenant;
        /** Replay timer job id to captured timer id. */
        private final Map<String, String> timers = new HashMap<>();
        private ReplayException failure;
        private long applying = -1;
        private boolean taken;

        Run(ReplayFixture fixture, RecordedInputs inputs) {
            this.fixture = fixture;
            this.inputs = inputs;
            this.zone = ZoneId.of(fixture.tenant().zoneId());
            this.tenant = fixture.tenant().tenantId();
        }

        ReplayFixture.Observation go() {
            Map<String, byte[]> resources = resources();
            ZoneId here = EngineValues.named(BusinessClock.system().zone(tenant));
            if (!here.equals(zone)) {
                throw new ReplayException(Code.INCOMPATIBLE_VERSION, "tenant " + tenant + " is in business zone " + here
                        + " here and " + zone + " in the fixture");
            }
            try (ReplayEngines.Started started = engines.start(this)) {
                OperatonEngineAdapter engine = started.engine();
                if (!started.configuration().equals(fixture.configuration())) {
                    throw new ReplayException(Code.INCOMPATIBLE_VERSION, "engine configuration " + started.configuration()
                            + " is not the fixture's " + fixture.configuration());
                }
                String evaluator = ReplayEngines.evaluatorOf(engine);
                if (!evaluator.equals(fixture.versions().evaluator())) {
                    throw new ReplayException(Code.INCOMPATIBLE_VERSION, "the replay engine runs FEEL " + evaluator
                            + ", the fixture " + fixture.versions().evaluator());
                }
                engine.setClock(inputs.currentTime());
                String deployment = engine.deploy(tenant, fixture.fixtureId(), resources);
                for (ModelRef m : fixture.models()) {
                    Integer v = EngineObserver.versionOf(engine, deployment, m.kind(), m.definitionKey());
                    if (v == null) {
                        throw new ReplayException(Code.INCOMPLETE_FIXTURE, "model " + m.resource() + " deploys no "
                                + m.kind() + " definition " + m.definitionKey());
                    }
                    if (!v.equals(m.version())) {
                        throw new ReplayException(Code.INCOMPATIBLE_VERSION, "model " + m.resource() + " deploys "
                                + m.definitionKey() + " version " + v + " on the fresh replay engine, the fixture ran "
                                + "version " + m.version());
                    }
                }
                ReplayFixture.InitialState start = fixture.initialState();
                String root = call(() -> engine.startProcess(tenant, start.definitionKey(), start.businessKey(),
                        engineValues(start.variables())));
                for (Event s = inputs.peekStimulus(); s != null; s = inputs.peekStimulus()) {
                    applying = s.seq();
                    taken = false;
                    if (!apply(engine, s) || !taken) {
                        break; // the replayed instance does not take this stimulus: it stays unconsumed
                    }
                }
                applying = -1;
                ReplayFixture.Observation observed = observer.observe(engine, root, zone, reason -> {
                    throw new ReplayException(Code.UNSUPPORTED_SCENARIO, reason);
                });
                align(fixture.observed().transitions(), observed.transitions());
                return observed;
            }
        }

        private Map<String, byte[]> resources() {
            Map<String, byte[]> out = new LinkedHashMap<>();
            for (ModelRef m : fixture.models()) {
                byte[] bytes;
                if (changedModels.containsKey(m.resource())) {
                    bytes = changedModels.get(m.resource()).getBytes(StandardCharsets.UTF_8);
                } else if (m.content() != null) {
                    bytes = m.content().getBytes(StandardCharsets.UTF_8);
                } else {
                    if (references == null) {
                        throw new ReplayException(Code.INCOMPLETE_FIXTURE, "model " + m.resource() + " is the reference "
                                + m.reference() + " and this driver resolves no references");
                    }
                    bytes = references.apply(m);
                    if (bytes == null || !ReplayFixtures.sha256(bytes).equals(m.sha256())) {
                        throw new ReplayException(Code.HASH_MISMATCH, "model " + m.resource() + ": the resolved reference "
                                + m.reference() + " does not match sha256 " + m.sha256());
                    }
                }
                List<String> refused = ReplayModelPolicy.check(m.resource(), m.kind(), bytes);
                if (!refused.isEmpty()) {
                    throw new ReplayException(Code.UNSUPPORTED_SCENARIO, String.join("; ", refused));
                }
                out.put(m.resource(), bytes);
            }
            changedModels.keySet().forEach(r -> {
                if (!out.containsKey(r)) {
                    throw new IllegalArgumentException("changed model " + r + " is not a model of fixture "
                            + fixture.fixtureId());
                }
            });
            return out;
        }

        // ------------------------------------------------------------------ stimuli

        /** Applies {@code s} when the instance waits for it; false when it does not (nothing was done). */
        private boolean apply(OperatonEngineAdapter engine, Event s) {
            Instant at = Instant.parse(s.at());
            String execution = replayExecutionOf(s.executionId());
            switch (s.type()) {
                case EXTERNAL_TASK_RESULT -> {
                    if (s.outcome() == ReplayFixture.TaskOutcome.TIMEOUT) {
                        // the capture's lease was taken after the previous input and had expired at the recorded
                        // sweep; nothing was recorded in between, so the replay takes it one tick before the sweep
                        // for one tick (taken at the previous input's instant, a task failed with no back-off is
                        // still locked until that instant)
                        Instant takenAt = at.minus(TICK);
                        if (!takenAt.isAfter(inputs.currentTime())) {
                            throw new ReplayException(Code.INCOMPLETE_FIXTURE, "TIMEOUT seq " + s.seq() + " at " + at
                                    + " leaves no time after the previous input to take the lease that expired");
                        }
                        engine.setClock(takenAt);
                        if (lease(engine, s, execution, TICK) == null) {
                            return false;
                        }
                        engine.setClock(at);
                        call(engine::executeDueJobs);
                        return true;
                    }
                    engine.setClock(at);
                    LeasedWork w = lease(engine, s, execution, EngineObserver.RESULT_LEASE);
                    if (w == null) {
                        return false;
                    }
                    String id = w.work().id();
                    long version = w.work().lockVersion();
                    switch (s.outcome()) {
                        case SUCCESS -> run(() -> engine.completeExternal(id, EngineObserver.WORKER, version,
                                engineValues(s.payload())));
                        case FAILURE -> {
                            if (s.retriesLeft() == null) {
                                throw new ReplayException(Code.INCOMPLETE_FIXTURE, "FAILURE seq " + s.seq()
                                        + " has no retriesLeft");
                            }
                            String message = text(s.payload().get("errorMessage"));
                            run(() -> engine.failExternal(id, EngineObserver.WORKER, version, message, s.retriesLeft(),
                                    Duration.ZERO));
                        }
                        case BPMN_ERROR -> {
                            Map<String, Value> vars = new LinkedHashMap<>(s.payload());
                            String code = text(vars.remove("errorCode"));
                            run(() -> engine.bpmnErrorExternal(id, EngineObserver.WORKER, version, code,
                                    engineValues(vars)));
                        }
                        case TIMEOUT -> throw new IllegalStateException("handled above");
                    }
                    return true;
                }
                case MESSAGE_DELIVERED -> {
                    Map<String, Value> vars = new LinkedHashMap<>(s.payload());
                    String name = text(vars.remove("messageName"));
                    String key = text(vars.remove("correlationKey"));
                    var q = engine.processEngine().getRuntimeService().createEventSubscriptionQuery().eventType("message")
                            .eventName(name);
                    if (execution != null) {
                        q.executionId(execution);
                    }
                    if (q.count() == 0) {
                        return false;
                    }
                    engine.setClock(at);
                    call(() -> engine.correlateMessage(tenant, name, key, engineValues(vars)));
                    return true;
                }
                case TIMER_FIRED -> {
                    String job = replayTimerOf(text(s.payload().get("timerId")));
                    engine.setClock(at);
                    if (job == null || engine.processEngine().getManagementService().createJobQuery().jobId(job)
                            .executable().count() == 0) {
                        return false; // never scheduled in this replay, or not due at the recorded firing time
                    }
                    call(engine::executeDueJobs);
                    return true;
                }
                default -> throw new ReplayException(Code.UNSUPPORTED_SCENARIO, s.type() + " seq " + s.seq()
                        + " is not a stimulus the Operaton replay applies");
            }
        }

        private LeasedWork lease(OperatonEngineAdapter engine, Event s, String execution, Duration lock) {
            try {
                return observer.lease(engine, s.activityId(), execution, lock, zone, reason -> {
                    throw new ReplayException(Code.UNSUPPORTED_SCENARIO, reason);
                });
            } catch (IllegalStateException e) {
                throw new ReplayException(Code.UNSUPPORTED_SCENARIO, e.getMessage());
            } finally {
                rethrow();
            }
        }

        // ------------------------------------------------------------------ the engine's reports

        @Override
        public void onInput(CapturedInput in) {
            rethrow();
            try {
                take(in);
            } catch (ReplayException e) {
                failure = e;
                throw e;
            }
        }

        @Override
        public void unsupported(String reason) {
            rethrow();
            failure = new ReplayException(Code.UNSUPPORTED_SCENARIO, reason);
            throw failure;
        }

        private void take(CapturedInput in) {
            String where = in.type() + " at " + in.activityId();
            if (in.executionId() == null) {
                throw new ReplayException(Code.UNSUPPORTED_SCENARIO, where + " has no execution (a message that starts "
                        + "an instance or a standalone decision)");
            }
            switch (in.type()) {
                case CapturedInput.CLOCK_READ -> {
                    Instant recorded = inputs.clockRead(in.executionId(), in.activityId());
                    if (!recorded.equals(in.at())) {
                        throw new ReplayException(Code.MISSING_EVENT, "the replay read the clock at " + in.at() + " ("
                                + where + "); the recorded read is " + recorded);
                    }
                }
                case CapturedInput.TIMER_SCHEDULED -> {
                    Event recorded = inputs.timerScheduled(in.executionId(), in.activityId());
                    if (!Instant.parse(recorded.at()).equals(in.at())) {
                        throw new ReplayException(Code.MISSING_EVENT, "the replay scheduled " + where + " at " + in.at()
                                + "; the recorded scheduling is at " + recorded.at());
                    }
                    timers.put((String) in.payload().get("timerId"), text(recorded.payload().get("timerId")));
                    observer.timerScheduled(in.activityId(), (Instant) in.payload().get("dueAt"));
                }
                case CapturedInput.TIMER_FIRED, CapturedInput.MESSAGE_DELIVERED, CapturedInput.EXTERNAL_TASK_RESULT -> {
                    Event next = inputs.peekStimulus();
                    String why = mismatch(next, in);
                    if (why != null) {
                        throw new ReplayException(Code.MISSING_EVENT, "the replay took " + where + " (attempt "
                                + in.attempt() + (in.outcome() == null ? "" : ", " + in.outcome()) + "); " + why);
                    }
                    inputs.nextStimulus();
                    inputs.bind(in.executionId(), next.executionId());
                    if (next.seq() == applying) {
                        taken = true;
                    }
                }
                default -> throw new ReplayException(Code.UNSUPPORTED_SCENARIO, where + ": no replay for this input");
            }
        }

        /** Why {@code in} is not the recorded stimulus {@code next}, or null when it is. */
        private String mismatch(Event next, CapturedInput in) {
            if (next == null) {
                return "the fixture holds no further stimulus";
            }
            String rec = "the next recorded stimulus is seq " + next.seq() + " " + next.type() + " at " + next.activityId();
            if (next.type() != EventType.valueOf(in.type()) || !Objects.equals(next.activityId(), in.activityId())) {
                return rec;
            }
            if (next.attempt() != in.attempt()) {
                return rec + " attempt " + next.attempt();
            }
            if (!Objects.equals(next.outcome() == null ? null : next.outcome().name(), in.outcome())
                    || !Objects.equals(next.retriesLeft(), in.retriesLeft())) {
                return rec + " " + next.outcome() + " retriesLeft " + next.retriesLeft();
            }
            if (!Instant.parse(next.at()).equals(in.at())) {
                return rec + " at " + next.at() + ", taken at " + in.at();
            }
            Map<String, Value> payload = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : in.payload().entrySet()) {
                Value v = EngineValues.toValue(e.getValue(), zone);
                if (v == null) {
                    throw new ReplayException(Code.UNSUPPORTED_SCENARIO, in.type() + " at " + in.activityId()
                            + ": payload " + e.getKey() + " has no exact fixture form");
                }
                payload.put(e.getKey(), v);
            }
            if (EventType.TIMER_FIRED == next.type()) {
                String captured = timers.get(text(payload.remove("timerId")));
                Map<String, Value> recorded = new LinkedHashMap<>(next.payload());
                if (captured == null || !captured.equals(text(recorded.remove("timerId")))) {
                    return rec + ": another timer fired";
                }
                return payload.equals(recorded) ? null : rec + " with payload " + next.payload();
            }
            return payload.equals(next.payload()) ? null : rec + " with payload " + next.payload() + ", taken with "
                    + payload;
        }

        // ------------------------------------------------------------------ plumbing

        /** Runs an engine call; a replay failure recorded inside it wins over whatever the engine made of it. */
        private <T> T call(java.util.function.Supplier<T> engineCall) {
            T out;
            try {
                out = engineCall.get();
            } catch (RuntimeException e) {
                rethrow();
                throw e;
            }
            rethrow();
            return out;
        }

        private void run(Runnable engineCall) {
            call(() -> {
                engineCall.run();
                return null;
            });
        }

        private void rethrow() {
            if (failure != null) {
                throw failure;
            }
        }

        private Map<String, Object> engineValues(Map<String, Value> values) {
            try {
                return EngineValues.toEngine(values);
            } catch (IllegalArgumentException e) {
                throw new ReplayException(Code.UNSUPPORTED_SCENARIO, e.getMessage());
            }
        }

        private String replayExecutionOf(String captured) {
            for (Map.Entry<String, String> b : inputs.executionBinding().entrySet()) {
                if (b.getValue().equals(captured)) {
                    return b.getKey();
                }
            }
            return null;
        }

        private String replayTimerOf(String captured) {
            for (Map.Entry<String, String> t : timers.entrySet()) {
                if (t.getValue().equals(captured)) {
                    return t.getKey();
                }
            }
            return null;
        }

        /** Binds executions that took no input by aligning transitions while activity and kind agree. */
        private void align(List<Transition> captured, List<Transition> replayed) {
            for (int i = 0; i < Math.min(captured.size(), replayed.size()); i++) {
                Transition c = captured.get(i);
                Transition r = replayed.get(i);
                if (!c.activityId().equals(r.activityId()) || c.kind() != r.kind()) {
                    return;
                }
                Map<String, String> bound = inputs.executionBinding();
                if (r.executionId() != null && c.executionId() != null && !bound.containsKey(r.executionId())
                        && !bound.containsValue(c.executionId())) {
                    inputs.bind(r.executionId(), c.executionId());
                }
            }
        }
    }

    private static String text(Value v) {
        if (v == null || v.type() == ValueType.NULL) {
            return null;
        }
        return v.value();
    }
}
