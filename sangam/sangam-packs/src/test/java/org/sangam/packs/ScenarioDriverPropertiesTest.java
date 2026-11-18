// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.operaton.bpm.engine.impl.util.ClockUtil;
import org.operaton.bpm.model.bpmn.Bpmn;
import org.operaton.bpm.model.bpmn.BpmnModelInstance;
import org.operaton.bpm.model.bpmn.builder.AbstractFlowNodeBuilder;
import org.sangam.core.time.BusinessClock;

/**
 * M-095 property tests (plain JUnit 5, seeded generators; jqwik removed by owner decision 2026-11-18) over the M-116
 * scenario clock and steering driver ({@link PackDriver}, {@link AcceptanceSteps}):
 * <ul>
 *   <li>the scenario clock is the sum of its advances, for the engine, the pack and the process-wide business clock,
 *       never moves back, and a reset restores the business clock that was in place;</li>
 *   <li>a {@code run.steps} entry is accepted exactly when it follows the documented rules (an oracle written from the
 *       {@code acceptance_runs.py} contract), and a refusal names the first bad step;</li>
 *   <li>a model generator: any linear process of user tasks and timers is driven to its end by one {@code advance} per
 *       timer, completing the user tasks in model order; without the last advance it never ends.</li>
 * </ul>
 * Each property first runs its edge cases (empty, minimum, maximum, boundaries), then seeded random inputs up to the
 * try count. The seed is fixed ({@link #SEED}); a failure names the property, the try, the seed and the input.
 * The engine properties use few tries, each on a fresh process key.
 */
class ScenarioDriverPropertiesTest {

    /** Fixed base seed; each property derives its own stream from it and its name, so properties do not interfere. */
    static final long SEED = 0x5A4E_4741_4D09_5L;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AtomicInteger MODELS = new AtomicInteger();
    private static final long MAX_ADVANCE = 864_000_000L;
    private static ProcessEngine engine;
    private static PackDriver driver;

    @BeforeAll
    static void startEngine() {
        StandaloneInMemProcessEngineConfiguration cfg = new StandaloneInMemProcessEngineConfiguration();
        cfg.setJdbcUrl("jdbc:h2:mem:sangam-scenario-driver-properties;DB_CLOSE_DELAY=-1");
        cfg.setDatabaseSchemaUpdate("create-drop");
        cfg.setJobExecutorActivate(false);
        cfg.setHistory("full");
        PackTestEngines.configure(cfg); // decisions 138 and FEEL (sangam-feel): as SangamProcessEnginePlugin does
        engine = cfg.buildProcessEngine();
        driver = new PackDriver(engine);
    }

    @AfterAll
    static void stopEngine() {
        driver.resetClock();
        engine.close();
        // R8-heap2: a test class stays loaded for the whole run; its statics must not keep a closed engine
        engine = null;
        driver = null;
    }

    /** One checked try. */
    interface Check<T> {
        void accept(T input) throws Exception;
    }

    /**
     * Runs {@code check} on every edge case, then on random inputs from {@code gen} until {@code tries} inputs ran
     * (at least all edge cases). The driver clock is reset after every try (formerly jqwik's {@code @AfterTry}).
     */
    static <T> void forAll(String property, int tries, List<T> edges, Function<SplittableRandom, T> gen, Check<T> check) {
        long seed = SEED ^ property.hashCode();
        SplittableRandom rnd = new SplittableRandom(seed);
        int total = Math.max(tries, edges.size());
        for (int i = 0; i < total; i++) {
            T input = i < edges.size() ? edges.get(i) : gen.apply(rnd);
            try {
                check.accept(input);
            } catch (Throwable t) {
                throw new AssertionError(property + " failed at try " + (i + 1) + "/" + total + " (seed " + seed
                        + (i < edges.size() ? ", edge case" : "") + "), input: " + input, t);
            } finally {
                driver.resetClock();
            }
        }
    }

    static long longBetween(SplittableRandom r, long min, long max) {
        return r.nextLong(min, max + 1);
    }

    static <T> T nullOr(SplittableRandom r, double nullProbability, List<T> values) {
        return r.nextDouble() < nullProbability ? null : values.get(r.nextInt(values.size()));
    }

    // ---- the scenario clock ------------------------------------------------------------------------------------------

    @Test
    void theScenarioClockIsTheSumOfItsAdvances() {
        List<List<Long>> edges = List.of(List.of(), List.of(0L), List.of(1L), List.of(MAX_ADVANCE),
                List.of(0L, MAX_ADVANCE), List.of(MAX_ADVANCE, 0L, 1L), Collections.nCopies(12, 0L),
                Collections.nCopies(12, MAX_ADVANCE), Collections.nCopies(12, 1L));
        forAll("theScenarioClockIsTheSumOfItsAdvances", 200, edges, r -> {
            int size = r.nextInt(13);
            List<Long> l = new ArrayList<>();
            for (int i = 0; i < size; i++) {
                l.add(longBetween(r, 0, MAX_ADVANCE));
            }
            return l;
        }, advances -> {
            BusinessClock outer = BusinessClock.system();
            PackDriver d = new PackDriver(engine);
            // the engine clock counts whole milliseconds (java.util.Date): compare from the same resolution
            Instant start = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
            long sum = 0;
            try {
                for (long ms : advances) {
                    d.advance(Duration.ofMillis(ms));
                    sum += ms;
                    assertEquals(sum, d.offsetMillis(), "cumulative");
                }
                Instant reached = start.plusMillis(sum);
                assertFalse(PackDriver.clock().instant().isBefore(reached), "the pack clock reads the engine clock");
                assertFalse(ClockUtil.getCurrentTime().toInstant().isBefore(reached), "the engine clock moved");
                if (!advances.isEmpty()) {
                    assertFalse(BusinessClock.system().clock().instant().isBefore(reached),
                            "the process-wide business clock moved with it");
                }
            } finally {
                d.resetClock();
            }
            assertEquals(0, d.offsetMillis());
            assertSame(outer, BusinessClock.system(), "the reset restores the business clock in place before");
        });
    }

    @Test
    void theScenarioClockNeverMovesBack() {
        long minBack = -864_000_000_000L;
        List<List<Long>> edges = new ArrayList<>();
        for (long backwards : new long[] {minBack, minBack + 1, -2, -1}) {
            for (long before : new long[] {0, 1, MAX_ADVANCE - 1, MAX_ADVANCE}) {
                edges.add(List.of(backwards, before));
            }
        }
        forAll("theScenarioClockNeverMovesBack", 100, edges,
                r -> List.of(longBetween(r, minBack, -1), longBetween(r, 0, MAX_ADVANCE)), in -> {
                    long backwards = in.get(0);
                    long before = in.get(1);
                    PackDriver d = new PackDriver(engine);
                    try {
                        d.advance(Duration.ofMillis(before));
                        assertThrows(IllegalArgumentException.class, () -> d.advance(Duration.ofMillis(backwards)));
                        assertEquals(before, d.offsetMillis(), "a refused move leaves the clock where it was");
                    } finally {
                        d.resetClock();
                    }
                });
    }

    // ---- run.steps validation ----------------------------------------------------------------------------------------

    /** One generated step: each key present or absent, with good and bad values. */
    record StepSpec(String at, String advance, String action, String refused, Integer n, String key, String pack,
                    String process) {

        ObjectNode json() {
            ObjectNode o = JSON.createObjectNode();
            if (at != null) {
                o.put("at", at);
            }
            if (advance != null) {
                o.put("advance", advance);
            }
            if (action != null) {
                o.put("action", action);
            }
            if (refused != null) {
                o.put("refused", refused);
            }
            if (n != null) {
                o.put("n", n);
            }
            if (key != null) {
                o.put("key", key);
            }
            if (pack != null) {
                o.put("pack", pack);
            }
            if (process != null) {
                o.put("process", process);
            }
            return o;
        }

        /** The documented contract of a step (acceptance_runs.py docstring; AcceptanceSteps.Step.of). */
        boolean valid() {
            if (at == null || at.isBlank()) {
                return false;
            }
            if ((advance != null) == (action != null)) {
                return false;
            }
            if (advance != null && (Duration.parse(advance).isNegative() || Duration.parse(advance).isZero())) {
                return false;
            }
            if (action != null && !AcceptanceSteps.ACTIONS.contains(action)) {
                return false;
            }
            if (refused != null && !"correlate".equals(action) && !"set".equals(action)) {
                return false;
            }
            if (n != null && n < 1) {
                return false;
            }
            return !"start".equals(action) || key != null && (pack != null) != (process != null);
        }
    }

    static final List<String> ATS = List.of("", " ", "task_a", "timer_b");
    static final List<String> ADVANCES = List.of("PT1H", "P2D", "PT0S", "-PT1H", "PT30M");
    static final List<String> ACTIONS = List.of("complete", "correlate", "set", "start", "return", "decline");
    static final List<Integer> NS = List.of(-1, 0, 1, 2, 3);

    /** Same value sets and null probabilities as the former jqwik generator. */
    static StepSpec stepSpec(SplittableRandom r) {
        return new StepSpec(nullOr(r, 0.15, ATS), nullOr(r, 0.5, ADVANCES), nullOr(r, 0.5, ACTIONS),
                nullOr(r, 0.7, List.of("RETRY_NOT_ALLOWED")), nullOr(r, 0.6, NS), nullOr(r, 0.4, List.of("app-1")),
                nullOr(r, 0.5, List.of("test.pack")), nullOr(r, 0.5, List.of("steps-loop")));
    }

    /** Edge steps: all keys absent, every boundary of n, zero/negative advance, each start shape, refused shapes. */
    static List<StepSpec> stepEdges() {
        List<StepSpec> e = new ArrayList<>();
        e.add(new StepSpec(null, null, null, null, null, null, null, null));
        e.add(new StepSpec("", "PT1H", null, null, null, null, null, null));
        e.add(new StepSpec(" ", "PT1H", null, null, null, null, null, null));
        e.add(new StepSpec("task_a", null, null, null, null, null, null, null));
        e.add(new StepSpec("task_a", "PT1H", "complete", null, null, null, null, null));
        for (String adv : ADVANCES) {
            e.add(new StepSpec("timer_b", adv, null, null, null, null, null, null));
        }
        for (String act : ACTIONS) {
            e.add(new StepSpec("task_a", null, act, null, null, null, null, null));
            e.add(new StepSpec("task_a", null, act, "RETRY_NOT_ALLOWED", null, null, null, null));
        }
        for (Integer n : NS) {
            e.add(new StepSpec("task_a", null, "complete", null, n, null, null, null));
        }
        for (String key : new String[] {null, "app-1"}) {
            for (String pack : new String[] {null, "test.pack"}) {
                for (String process : new String[] {null, "steps-loop"}) {
                    e.add(new StepSpec("task_a", null, "start", null, null, key, pack, process));
                }
            }
        }
        e.add(new StepSpec("task_a", "PT1H", null, "RETRY_NOT_ALLOWED", 3, "app-1", "test.pack", "steps-loop"));
        return e;
    }

    @Test
    void aStepIsAcceptedExactlyWhenItFollowsTheContract() {
        forAll("aStepIsAcceptedExactlyWhenItFollowsTheContract", 500, stepEdges(),
                ScenarioDriverPropertiesTest::stepSpec, spec -> {
                    ArrayNode steps = JSON.createArrayNode().add(spec.json());
                    if (spec.valid()) {
                        AcceptanceSteps s = new AcceptanceSteps(engine, driver, "PROP", steps);
                        assertEquals(1, s.steps().size());
                        assertEquals(spec.at(), s.steps().get(0).at());
                        assertEquals(spec.n() == null ? 1 : spec.n(), s.steps().get(0).n());
                    } else {
                        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                                () -> new AcceptanceSteps(engine, driver, "PROP", steps), spec::toString);
                        assertTrue(e.getMessage().startsWith("run.steps[0]: "), e.getMessage());
                    }
                });
    }

    @Test
    void aRefusalNamesTheFirstBadStep() {
        StepSpec good = new StepSpec("task_a", null, "complete", null, null, null, null, null);
        StepSpec bad = new StepSpec(null, null, null, null, null, null, null, null);
        List<List<StepSpec>> edges = List.of(List.of(good), List.of(bad), Collections.nCopies(6, good),
                Collections.nCopies(6, bad), List.of(good, good, good, good, good, bad),
                List.of(bad, good, good, good, good, good), List.of(good, bad, good, bad, good, bad));
        forAll("aRefusalNamesTheFirstBadStep", 200, edges, r -> {
            int size = 1 + r.nextInt(6);
            List<StepSpec> l = new ArrayList<>();
            for (int i = 0; i < size; i++) {
                l.add(stepSpec(r));
            }
            return l;
        }, specs -> {
            ArrayNode steps = JSON.createArrayNode();
            int firstBad = -1;
            for (int i = 0; i < specs.size(); i++) {
                steps.add(specs.get(i).json());
                if (firstBad < 0 && !specs.get(i).valid()) {
                    firstBad = i;
                }
            }
            if (firstBad < 0) {
                assertEquals(specs.size(), new AcceptanceSteps(engine, driver, "PROP", steps).steps().size());
            } else {
                IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                        () -> new AcceptanceSteps(engine, driver, "PROP", steps));
                assertTrue(e.getMessage().startsWith("run.steps[" + firstBad + "]: "), e.getMessage());
            }
        });
    }

    // ---- the model generator ----------------------------------------------------------------------------------------

    /** One segment of a generated linear process: a user task, or a timer of {@code hours}. */
    record Segment(boolean timer, int hours) {
    }

    /** A linear model of 1..6 segments, each a user task or a timer of 1..72 hours. */
    static List<Segment> linearModel(SplittableRandom r) {
        int size = 1 + r.nextInt(6);
        List<Segment> l = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            l.add(new Segment(r.nextBoolean(), 1 + r.nextInt(72)));
        }
        return l;
    }

    static final Segment USER = new Segment(false, 1);
    static final Segment T1 = new Segment(true, 1);
    static final Segment T72 = new Segment(true, 72);

    /** Deploys the model as a fresh process key; ids are u&lt;i&gt; for user tasks and t&lt;i&gt; for timers. */
    private static String deploy(List<Segment> model) {
        String key = "prop-linear-" + MODELS.incrementAndGet();
        AbstractFlowNodeBuilder<?, ?> b = Bpmn.createExecutableProcess(key).startEvent();
        for (int i = 0; i < model.size(); i++) {
            Segment s = model.get(i);
            b = s.timer() ? b.intermediateCatchEvent("t" + i).timerWithDuration("PT" + s.hours() + "H")
                    : b.userTask("u" + i);
        }
        BpmnModelInstance m = b.endEvent().done();
        engine.getRepositoryService().createDeployment().tenantId(PackTestTenant.ID).addModelInstance(key + ".bpmn", m).deploy();
        return key;
    }

    /** One advance per timer, a minute past its due time; with {@code skipLast}, the last timer gets none. */
    private static ArrayNode advances(List<Segment> model, boolean skipLast) {
        ArrayNode steps = JSON.createArrayNode();
        int lastTimer = -1;
        for (int i = 0; i < model.size(); i++) {
            if (model.get(i).timer()) {
                lastTimer = i;
            }
        }
        for (int i = 0; i < model.size(); i++) {
            if (model.get(i).timer() && !(skipLast && i == lastTimer)) {
                steps.add(JSON.createObjectNode().put("at", "t" + i).put("advance", "PT" + model.get(i).hours() + "H1M"));
            }
        }
        return steps;
    }

    private static List<String> drive(String bk, AcceptanceSteps s, int segments) {
        List<String> progress = new ArrayList<>();
        return driver.run(bk, t -> s.answer(t, PackDriver.Answer.of("p", "S"), progress), 4 * segments + 10, d -> {
            progress.clear();
            progress.addAll(d);
            return s.beforeTurn(d);
        });
    }

    private static void cleanUp(String bk) {
        engine.getRuntimeService().createProcessInstanceQuery().processInstanceBusinessKeyLike(bk + "%").list()
                .forEach(p -> engine.getRuntimeService().deleteProcessInstance(p.getId(), "property cleanup"));
    }

    @Test
    void everyLinearModelEndsWithOneAdvancePerTimer() {
        List<List<Segment>> edges = List.of(List.of(USER), List.of(T1), List.of(T72), Collections.nCopies(6, USER),
                Collections.nCopies(6, T72), List.of(USER, T1, USER, T72, USER, T1), List.of(T1, T1, USER));
        forAll("everyLinearModelEndsWithOneAdvancePerTimer", 25, edges, ScenarioDriverPropertiesTest::linearModel,
                model -> {
                    String key = deploy(model);
                    String bk = "PROP/" + key + "/c";
                    engine.getRuntimeService().startProcessInstanceByKey(key, bk, Map.of());
                    try {
                        AcceptanceSteps s = new AcceptanceSteps(engine, driver, bk, advances(model, false));
                        List<String> done = drive(bk, s, model.size());
                        List<String> userTasks = new ArrayList<>();
                        long advanced = 0;
                        for (int i = 0; i < model.size(); i++) {
                            if (model.get(i).timer()) {
                                advanced += Duration.ofHours(model.get(i).hours()).plusMinutes(1).toMillis();
                            } else {
                                userTasks.add("u" + i);
                            }
                        }
                        assertEquals(userTasks, done, "the user tasks, in model order");
                        assertTrue(s.pending().isEmpty(), "every advance was applied at its timer");
                        assertEquals(advanced, driver.offsetMillis(), "the clock moved exactly by the advances");
                        assertTrue(driver.ended(bk), "the case ended");
                    } finally {
                        cleanUp(bk);
                    }
                });
    }

    @Test
    void withoutTheLastAdvanceTheCaseWaitsAtTheLastTimer() {
        List<List<Segment>> edges = List.of(List.of(T1), List.of(T72), List.of(USER, T1), List.of(T1, USER),
                Collections.nCopies(6, T72), List.of(USER, USER, USER, USER, USER, T1));
        // only models with a timer (formerly Assume.that(...)): every try checks the property, none is discarded
        forAll("withoutTheLastAdvanceTheCaseWaitsAtTheLastTimer", 15, edges, r -> {
            List<Segment> m;
            do {
                m = linearModel(r);
            } while (m.stream().noneMatch(Segment::timer));
            return m;
        }, model -> {
            assertTrue(model.stream().anyMatch(Segment::timer), "generator invariant: a timer");
            String key = deploy(model);
            String bk = "PROP/" + key + "/w";
            engine.getRuntimeService().startProcessInstanceByKey(key, bk, Map.of());
            try {
                AcceptanceSteps s = new AcceptanceSteps(engine, driver, bk, advances(model, true));
                drive(bk, s, model.size());
                int lastTimer = -1;
                for (int i = 0; i < model.size(); i++) {
                    if (model.get(i).timer()) {
                        lastTimer = i;
                    }
                }
                assertFalse(driver.ended(bk), "a timer whose time never came does not fire");
                assertTrue(s.pending().isEmpty(), "the advances that were given were all applied");
                assertEquals(1, engine.getManagementService().createJobQuery().timers().activityId("t" + lastTimer)
                        .processInstanceId(engine.getRuntimeService().createProcessInstanceQuery()
                                .processInstanceBusinessKey(bk).singleResult().getId()).count(),
                        "the case waits at the last timer");
            } finally {
                cleanUp(bk);
            }
        });
    }
}
