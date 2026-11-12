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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.From;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.LongRange;
import net.jqwik.api.constraints.Size;
import net.jqwik.api.lifecycle.AfterContainer;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeContainer;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.operaton.bpm.engine.impl.util.ClockUtil;
import org.operaton.bpm.model.bpmn.Bpmn;
import org.operaton.bpm.model.bpmn.BpmnModelInstance;
import org.operaton.bpm.model.bpmn.builder.AbstractFlowNodeBuilder;
import org.sangam.core.time.BusinessClock;

/**
 * M-095 (jqwik) over the M-116 scenario clock and steering driver ({@link PackDriver}, {@link AcceptanceSteps}):
 * <ul>
 *   <li>the scenario clock is the sum of its advances, for the engine, the pack and the process-wide business clock,
 *       never moves back, and a reset restores the business clock that was in place;</li>
 *   <li>a {@code run.steps} entry is accepted exactly when it follows the documented rules (an oracle written from the
 *       {@code acceptance_runs.py} contract), and a refusal names the first bad step;</li>
 *   <li>a model generator: any linear process of user tasks and timers is driven to its end by one {@code advance} per
 *       timer, completing the user tasks in model order; without the last advance it never ends.</li>
 * </ul>
 * Runs in the test phase only (no run here); the engine properties use few tries, each on a fresh process key.
 */
class ScenarioDriverPropertiesTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AtomicInteger MODELS = new AtomicInteger();
    private static ProcessEngine engine;
    private static PackDriver driver;

    @BeforeContainer
    static void startEngine() {
        StandaloneInMemProcessEngineConfiguration cfg = new StandaloneInMemProcessEngineConfiguration();
        cfg.setJdbcUrl("jdbc:h2:mem:sangam-scenario-driver-properties;DB_CLOSE_DELAY=-1");
        cfg.setDatabaseSchemaUpdate("create-drop");
        cfg.setJobExecutorActivate(false);
        cfg.setHistory("full");
        engine = cfg.buildProcessEngine();
        driver = new PackDriver(engine);
    }

    @AfterContainer
    static void stopEngine() {
        driver.resetClock();
        engine.close();
    }

    @AfterTry
    void resetClock() {
        driver.resetClock();
    }

    // ---- the scenario clock ------------------------------------------------------------------------------------------

    @Property(tries = 200)
    void theScenarioClockIsTheSumOfItsAdvances(
            @ForAll @Size(max = 12) List<@LongRange(min = 0, max = 864_000_000L) Long> advances) {
        BusinessClock outer = BusinessClock.system();
        PackDriver d = new PackDriver(engine);
        Instant start = Instant.now();
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
    }

    @Property(tries = 100)
    void theScenarioClockNeverMovesBack(@ForAll @LongRange(min = -864_000_000_000L, max = -1) long backwards,
                                        @ForAll @LongRange(min = 0, max = 864_000_000L) long before) {
        PackDriver d = new PackDriver(engine);
        try {
            d.advance(Duration.ofMillis(before));
            assertThrows(IllegalArgumentException.class, () -> d.advance(Duration.ofMillis(backwards)));
            assertEquals(before, d.offsetMillis(), "a refused move leaves the clock where it was");
        } finally {
            d.resetClock();
        }
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

    @Provide
    Arbitrary<StepSpec> stepSpecs() {
        Arbitrary<String> at = Arbitraries.of("", " ", "task_a", "timer_b").injectNull(0.15);
        Arbitrary<String> advance = Arbitraries.of("PT1H", "P2D", "PT0S", "-PT1H", "PT30M").injectNull(0.5);
        Arbitrary<String> action = Arbitraries.of("complete", "correlate", "set", "start", "return", "decline")
                .injectNull(0.5);
        Arbitrary<String> refused = Arbitraries.of("RETRY_NOT_ALLOWED").injectNull(0.7);
        Arbitrary<Integer> n = Arbitraries.integers().between(-1, 3).injectNull(0.6);
        Arbitrary<String> key = Arbitraries.of("app-1").injectNull(0.4);
        Arbitrary<String> pack = Arbitraries.of("test.pack").injectNull(0.5);
        Arbitrary<String> process = Arbitraries.of("steps-loop").injectNull(0.5);
        return Combinators.combine(at, advance, action, refused, n, key, pack, process).as(StepSpec::new);
    }

    @Property(tries = 500)
    void aStepIsAcceptedExactlyWhenItFollowsTheContract(@ForAll("stepSpecs") StepSpec spec) {
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
    }

    @Property(tries = 200)
    void aRefusalNamesTheFirstBadStep(@ForAll @Size(min = 1, max = 6) List<@From("stepSpecs") StepSpec> specs) {
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
    }

    // ---- the model generator ----------------------------------------------------------------------------------------

    /** One segment of a generated linear process: a user task, or a timer of {@code hours}. */
    record Segment(boolean timer, int hours) {
    }

    @Provide
    Arbitrary<List<Segment>> linearModels() {
        return Combinators.combine(Arbitraries.of(Boolean.TRUE, Boolean.FALSE), Arbitraries.integers().between(1, 72))
                .as(Segment::new).list().ofMinSize(1).ofMaxSize(6);
    }

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
        engine.getRepositoryService().createDeployment().addModelInstance(key + ".bpmn", m).deploy();
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

    @Property(tries = 25)
    void everyLinearModelEndsWithOneAdvancePerTimer(@ForAll("linearModels") List<Segment> model) {
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
    }

    @Property(tries = 15)
    void withoutTheLastAdvanceTheCaseWaitsAtTheLastTimer(@ForAll("linearModels") List<Segment> model) {
        Assume.that(model.stream().anyMatch(Segment::timer));
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
    }
}
