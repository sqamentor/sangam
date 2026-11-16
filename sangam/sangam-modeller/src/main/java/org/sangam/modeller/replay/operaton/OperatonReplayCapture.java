// SPDX-License-Identifier: Apache-2.0
package org.sangam.modeller.replay.operaton;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

import org.sangam.core.engine.SangamEngineAdapter.LeasedWork;
import org.sangam.core.time.BusinessClock;
import org.sangam.engine.OperatonEngineAdapter;
import org.sangam.engine.replay.CaptureListener;
import org.sangam.engine.replay.CapturedInput;
import org.sangam.modeller.replay.FixtureRecorder;
import org.sangam.modeller.replay.ReplayCapture;
import org.sangam.modeller.replay.ReplayFixture;
import org.sangam.modeller.replay.ReplayFixture.EventType;
import org.sangam.modeller.replay.ReplayFixture.ModelKind;
import org.sangam.modeller.replay.ReplayFixture.TaskOutcome;
import org.sangam.modeller.replay.ReplayFixture.Value;
import org.sangam.modeller.replay.ReplayFixtures;

/**
 * M-136 (decision 151): the Operaton-backed {@link ReplayCapture}. Runs a scenario live on its own isolated Sangam engine
 * ({@link ReplayEngines.Factory}), takes every input the engine reports through its capture points (FEEL clock reads,
 * timers scheduled and fired, delivered messages, external-task results including failures, BPMN errors and lease
 * timeouts, in engine order; {@code org.sangam.engine.replay.CapturePoints}) and records them with a
 * {@link FixtureRecorder}; the observation is read from the engine by the same {@link EngineObserver} the replay uses.
 *
 * <p>The scenario is data: the models, the start, and the {@link Step}s a live run's outside world takes (a worker
 * completing, failing or abandoning a task, a message arriving, time passing). The engine clock is controlled: it is
 * {@link Scenario#startedAt()} at the start and moves only on an {@link Advance}.
 *
 * <p>Unsupported, never approximate: a model outside {@link ReplayModelPolicy}, an input with no execution (a message
 * that starts an instance, a standalone decision), a value without an exact fixture form or an input the capture listener
 * reports as unsupported marks the fixture unsupported with the reason ({@link FixtureRecorder#unsupported}). A step the
 * engine cannot take (no task waiting) fails the capture.
 */
public final class OperatonReplayCapture implements ReplayCapture {

    /**
     * A model of the scenario.
     *
     * @param resource      the deployment resource name ({@code .bpmn}, {@code .dmn})
     * @param kind          BPMN or DMN
     * @param definitionKey the process key or decision id it defines
     * @param content       the model text (UTF-8)
     */
    public record Model(String resource, ModelKind kind, String definitionKey, String content) {
        public Model {
            Objects.requireNonNull(resource, "resource");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(definitionKey, "definitionKey");
            Objects.requireNonNull(content, "content");
        }
    }

    /** One thing the outside world does during the live run. */
    public sealed interface Step permits Advance, Complete, Fail, BpmnError, Lease, Correlate {
    }

    /** Time passes to {@code to}; due jobs (timers, lease expiry) run. */
    public record Advance(Instant to) implements Step {
    }

    /** A worker takes the external task waiting at {@code activityId} and completes it. */
    public record Complete(String activityId, Map<String, Value> variables) implements Step {
    }

    /** A worker takes the task and reports a failure with the retries left (retried at once, no back-off). */
    public record Fail(String activityId, String errorMessage, int retriesLeft) implements Step {
    }

    /** A worker takes the task and throws a BPMN error. */
    public record BpmnError(String activityId, String errorCode, Map<String, Value> variables) implements Step {
    }

    /** A worker takes the task for {@code lock} and never answers; a later {@link Advance} past the lease times it out. */
    public record Lease(String activityId, Duration lock) implements Step {
    }

    /** A message arrives for a waiting execution. */
    public record Correlate(String messageName, String correlationKey, Map<String, Value> variables) implements Step {
    }

    /**
     * A scenario.
     *
     * @param fixtureId    the fixture's id
     * @param description  what the scenario shows
     * @param models       the models deployed (one deployment, in the scenario's tenant)
     * @param initialState the process started, its business key and start variables
     * @param tenantId     the engine tenant (synthetic)
     * @param actorId      the starting actor (synthetic; recorded)
     * @param startedAt    the controlled clock at the start
     * @param steps        the outside world, in order
     */
    public record Scenario(String fixtureId, String description, List<Model> models, ReplayFixture.InitialState initialState,
                           String tenantId, String actorId, Instant startedAt, List<Step> steps) {
        public Scenario {
            Objects.requireNonNull(fixtureId, "fixtureId");
            Objects.requireNonNull(initialState, "initialState");
            Objects.requireNonNull(tenantId, "tenantId");
            Objects.requireNonNull(startedAt, "startedAt");
            models = List.copyOf(models);
            steps = List.copyOf(steps);
        }
    }

    private final Scenario scenario;
    private final ReplayEngines.Factory engines;

    public OperatonReplayCapture(Scenario scenario, ReplayEngines.Factory engines) {
        this.scenario = Objects.requireNonNull(scenario, "scenario");
        this.engines = Objects.requireNonNull(engines, "engines");
    }

    @Override
    public ReplayFixture capture() {
        List<String> unsupported = new CopyOnWriteArrayList<>();
        Map<String, byte[]> resources = new LinkedHashMap<>();
        for (Model m : scenario.models()) {
            resources.put(m.resource(), m.content().getBytes(StandardCharsets.UTF_8));
            unsupported.addAll(ReplayModelPolicy.check(m.resource(), m.kind(), resources.get(m.resource())));
        }
        ZoneId zone = EngineValues.named(BusinessClock.system().zone(scenario.tenantId()));
        if (!unsupported.isEmpty()) {
            return notRun(zone, unsupported); // never run code the capture cannot see
        }
        EngineObserver observer = new EngineObserver();
        List<CapturedInput> taken = new CopyOnWriteArrayList<>();
        CaptureListener listener = new CaptureListener() {
            @Override
            public void onInput(CapturedInput in) {
                taken.add(in);
                if (CapturedInput.TIMER_SCHEDULED.equals(in.type())) {
                    observer.timerScheduled(in.activityId(), (Instant) in.payload().get("dueAt"));
                }
            }

            @Override
            public void unsupported(String reason) {
                unsupported.add(reason);
            }
        };

        List<ReplayFixture.ModelRef> refs = new ArrayList<>();
        ReplayFixture.Observation observed;
        Map<String, String> configuration;
        try (ReplayEngines.Started started = engines.start(listener)) {
            OperatonEngineAdapter engine = started.engine();
            configuration = started.configuration();
            String evaluator = ReplayEngines.evaluatorOf(engine);
            if (!evaluator.equals(ReplayEngines.evaluatorVersion())) {
                throw new IllegalStateException("decision 151: the engine runs FEEL " + evaluator + ", not the Sangam "
                        + "evaluator " + ReplayEngines.evaluatorVersion());
            }
            engine.setClock(scenario.startedAt());
            String deployment = engine.deploy(scenario.tenantId(), scenario.fixtureId(), resources);
            for (Model m : scenario.models()) {
                Integer version = EngineObserver.versionOf(engine, deployment, m.kind(), m.definitionKey());
                if (version == null) {
                    throw new IllegalStateException("decision 151: model " + m.resource() + " defines no " + m.kind()
                            + " definition " + m.definitionKey());
                }
                refs.add(new ReplayFixture.ModelRef(m.resource(), m.kind(), m.definitionKey(), version,
                        ReplayFixtures.sha256(resources.get(m.resource())), m.content(), null));
            }
            String root = engine.startProcess(scenario.tenantId(), scenario.initialState().definitionKey(),
                    scenario.initialState().businessKey(), EngineValues.toEngine(scenario.initialState().variables()));
            Instant clock = scenario.startedAt();
            int n = 0;
            for (Step step : scenario.steps()) {
                n++;
                clock = run(engine, scenario.tenantId(), observer, step, n, clock, zone, unsupported::add);
            }
            observed = observer.observe(engine, root, zone, unsupported::add);
        }

        FixtureRecorder rec = new FixtureRecorder(new FixtureRecorder.Header(scenario.fixtureId(), scenario.description(),
                true, refs, scenario.initialState(), configuration, new ReplayFixture.TenantContext(scenario.tenantId(),
                scenario.actorId(), zone.getId()), new ReplayFixture.Versions(ReplayEngines.engineVersion(),
                ReplayEngines.evaluatorVersion(), OperatonEngineAdapter.class.getPackage().getImplementationVersion()),
                scenario.startedAt().toString()));
        for (CapturedInput in : taken) {
            record(rec, in, zone, unsupported);
        }
        unsupported.forEach(rec::unsupported);
        return rec.finish(observed);
    }

    /**
     * The fixture of a scenario that was not run because a model is outside {@link ReplayModelPolicy}: unsupported with
     * the reasons, no events, model versions unknown (nothing was deployed), end state {@code NOT_RUN}. Never replayed.
     */
    private ReplayFixture notRun(ZoneId zone, List<String> reasons) {
        List<ReplayFixture.ModelRef> refs = new ArrayList<>();
        for (Model m : scenario.models()) {
            refs.add(new ReplayFixture.ModelRef(m.resource(), m.kind(), m.definitionKey(), null,
                    ReplayFixtures.sha256(m.content().getBytes(StandardCharsets.UTF_8)), m.content(), null));
        }
        FixtureRecorder rec = new FixtureRecorder(new FixtureRecorder.Header(scenario.fixtureId(), scenario.description(),
                true, refs, scenario.initialState(), Map.of(), new ReplayFixture.TenantContext(scenario.tenantId(),
                scenario.actorId(), zone.getId()), new ReplayFixture.Versions(ReplayEngines.engineVersion(),
                ReplayEngines.evaluatorVersion(), null), scenario.startedAt().toString()));
        reasons.forEach(rec::unsupported);
        return rec.finish(new ReplayFixture.Observation("NOT_RUN", Map.of(), List.of(), List.of(), List.of(), List.of()));
    }

    private static Instant run(OperatonEngineAdapter engine, String tenantId, EngineObserver observer, Step step, int n,
                               Instant clock, ZoneId zone, java.util.function.Consumer<String> unsupported) {
        switch (step) {
            case Advance a -> {
                if (a.to().isBefore(clock)) {
                    throw new IllegalStateException("step " + n + ": the clock never goes back (" + clock + " to "
                            + a.to() + ")");
                }
                engine.setClock(a.to());
                engine.executeDueJobs();
                return a.to();
            }
            case Complete c -> {
                LeasedWork w = take(engine, observer, c.activityId(), EngineObserver.RESULT_LEASE, n, zone, unsupported);
                engine.completeExternal(w.work().id(), EngineObserver.WORKER, w.work().lockVersion(),
                        EngineValues.toEngine(c.variables()));
            }
            case Fail f -> {
                LeasedWork w = take(engine, observer, f.activityId(), EngineObserver.RESULT_LEASE, n, zone, unsupported);
                engine.failExternal(w.work().id(), EngineObserver.WORKER, w.work().lockVersion(), f.errorMessage(),
                        f.retriesLeft(), Duration.ZERO);
            }
            case BpmnError b -> {
                LeasedWork w = take(engine, observer, b.activityId(), EngineObserver.RESULT_LEASE, n, zone, unsupported);
                engine.bpmnErrorExternal(w.work().id(), EngineObserver.WORKER, w.work().lockVersion(), b.errorCode(),
                        EngineValues.toEngine(b.variables()));
            }
            case Lease l -> take(engine, observer, l.activityId(), l.lock(), n, zone, unsupported);
            case Correlate m -> engine.correlateMessage(tenantId, m.messageName(), m.correlationKey(),
                    EngineValues.toEngine(m.variables()));
        }
        return clock;
    }

    private static LeasedWork take(OperatonEngineAdapter engine, EngineObserver observer, String activityId, Duration lock,
                                   int n, ZoneId zone, java.util.function.Consumer<String> unsupported) {
        LeasedWork w = observer.lease(engine, activityId, null, lock, zone, unsupported);
        if (w == null) {
            throw new IllegalStateException("step " + n + ": no external task waits at " + activityId);
        }
        return w;
    }

    /** One captured input into the recorder; an input the fixture cannot carry is named, not recorded. */
    private static void record(FixtureRecorder rec, CapturedInput in, ZoneId zone, List<String> unsupported) {
        String where = in.type() + (in.activityId() == null ? "" : " at " + in.activityId());
        if (in.executionId() == null) {
            unsupported.add(where + " has no execution (a message that starts an instance or a standalone decision); "
                    + "a fixture event is tied to the execution that took it");
            return;
        }
        Map<String, Value> payload = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : in.payload().entrySet()) {
            Value v = EngineValues.toValue(e.getValue(), zone);
            if (v == null) {
                unsupported.add(where + ": payload " + e.getKey() + " has no exact fixture form");
                return;
            }
            payload.put(e.getKey(), v);
        }
        rec.record(EventType.valueOf(in.type()), in.executionId(), in.activityId(), in.attempt(), in.at(),
                in.outcome() == null ? null : TaskOutcome.valueOf(in.outcome()), in.retriesLeft(), payload,
                in.activityInstanceId());
    }
}
