// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.operaton.bpm.model.bpmn.Bpmn;
import org.operaton.bpm.model.bpmn.BpmnModelInstance;

/**
 * SL-32: the generic {@code run.steps} driver ({@link AcceptanceSteps}, {@link PackDriver} scenario clock) on small
 * models, independent of any pack: advance moves the engine and the pack clock and fires the due timer; correlate
 * finds the waiting message; set reaches the process instance holding the activity; complete hits exactly the n-th
 * occurrence; an expected refusal is consumed and a missing one fails; malformed steps are refused.
 */
class AcceptanceStepsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    static ProcessEngine engine;
    static PackDriver driver;

    @BeforeAll
    static void setUp() {
        StandaloneInMemProcessEngineConfiguration cfg = new StandaloneInMemProcessEngineConfiguration();
        cfg.setJdbcUrl("jdbc:h2:mem:sangam-acceptance-steps;DB_CLOSE_DELAY=-1");
        cfg.setDatabaseSchemaUpdate("create-drop");
        cfg.setJobExecutorActivate(false);
        cfg.setHistory("full");
        PackTestEngines.configure(cfg); // decisions 138 and FEEL (sangam-feel): as SangamProcessEnginePlugin does
        engine = cfg.buildProcessEngine();
        driver = new PackDriver(engine);
        deploy(Bpmn.createExecutableProcess("steps-timer").startEvent().intermediateCatchEvent("wait")
                .timerWithDuration("PT1H").userTask("after").endEvent().done());
        deploy(Bpmn.createExecutableProcess("steps-message").startEvent().intermediateCatchEvent("inbox")
                .message("steps.test.arrived").userTask("after").endEvent().done());
        deploy(Bpmn.createExecutableProcess("steps-loop").startEvent().exclusiveGateway("join")
                .userTask("level").exclusiveGateway("again")
                .condition("loop", "${decision == 'again'}").connectTo("join")
                .moveToNode("again").condition("done", "${decision != 'again'}").endEvent().done());
        deploy(Bpmn.createExecutableProcess("steps-async").startEvent().serviceTask("pay").operatonAsyncBefore()
                .operatonExpression("${execution.setVariable('paid', amount)}").userTask("after").endEvent().done());
    }

    @AfterAll
    static void tearDown() {
        driver.resetClock();
        engine.close();
    }

    @AfterEach
    void clock() {
        driver.resetClock();
    }

    private static void deploy(BpmnModelInstance m) {
        engine.getRepositoryService().createDeployment().tenantId(PackTestTenant.ID).addModelInstance(m.getModelElementsByType(
                org.operaton.bpm.model.bpmn.instance.Process.class).iterator().next().getId() + ".bpmn", m).deploy();
    }

    private static JsonNode steps(String json) throws Exception {
        return JSON.readTree(json);
    }

    private static void start(String key, String bk, Object... kv) {
        java.util.Map<String, Object> v = new java.util.HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            v.put(kv[i].toString(), kv[i + 1]);
        }
        engine.getRuntimeService().startProcessInstanceByKey(key, bk, v);
    }

    private static List<String> drive(String bk, AcceptanceSteps s, String decision) {
        return drive(bk, s, occurrence -> decision);
    }

    /**
     * As {@link #drive(String, AcceptanceSteps, String)}, with a configuration answer that depends on the occurrence
     * of the task (1 for the first time it is open), as a configuration whose answer changes on a later round.
     */
    private static List<String> drive(String bk, AcceptanceSteps s, java.util.function.IntFunction<String> decisionAt) {
        List<String> progress = new ArrayList<>();
        return driver.run(bk, t -> s.answer(t, PackDriver.Answer.of("p", "S", "decision",
                        decisionAt.apply(java.util.Collections.frequency(progress, t.getTaskDefinitionKey()) + 1)),
                        progress), 20,
                d -> {
                    progress.clear();
                    progress.addAll(d);
                    return s.beforeTurn(d);
                });
    }

    @Test
    void advanceMovesBothClocksAndFiresTheDueTimer() throws Exception {
        start("steps-timer", "ST/1");
        Instant before = PackDriver.clock().instant();
        AcceptanceSteps s = new AcceptanceSteps(engine, driver, "ST/1", steps("[{\"at\":\"wait\",\"advance\":\"PT2H\"}]"));
        List<String> done = drive("ST/1", s, "ok");
        assertEquals(List.of("after"), done, "the timer fired after the advance and the next task ran");
        assertTrue(s.pending().isEmpty());
        assertEquals(Duration.ofHours(2).toMillis(), driver.offsetMillis());
        assertTrue(!PackDriver.clock().instant().isBefore(before.plus(Duration.ofHours(2))), "pack clock moved");
        assertTrue(driver.ended("ST/1"));
    }

    @Test
    void withoutTheAdvanceTheTimerDoesNotFire() throws Exception {
        start("steps-timer", "ST/2");
        AcceptanceSteps s = new AcceptanceSteps(engine, driver, "ST/2", steps("[]"));
        assertEquals(List.of(), drive("ST/2", s, "ok"));
        assertFalse(driver.ended("ST/2"));
    }

    @Test
    void correlateUsesTheSubscriptionNameAndVariables() throws Exception {
        start("steps-message", "SM/1");
        AcceptanceSteps s = new AcceptanceSteps(engine, driver, "SM/1",
                steps("[{\"at\":\"inbox\",\"action\":\"correlate\",\"vars\":{\"resolution\":\"OK\"}}]"));
        assertEquals(List.of("after"), drive("SM/1", s, "ok"));
        assertEquals("OK", driver.var("SM/1", "resolution"));
    }

    @Test
    void anExpectedRefusalIsConsumedAndAMissingOneFails() throws Exception {
        start("steps-message", "SM/2");
        AcceptanceSteps wrongName = new AcceptanceSteps(engine, driver, "SM/2", steps(
                "[{\"at\":\"inbox\",\"action\":\"correlate\",\"message\":\"no.such.message\",\"refused\":\"no.such\"},"
                        + "{\"at\":\"inbox\",\"action\":\"correlate\"}]"));
        assertEquals(List.of("after"), drive("SM/2", wrongName, "ok"));
        assertEquals(1, wrongName.refusals().size());
        start("steps-message", "SM/3");
        AcceptanceSteps notRefused = new AcceptanceSteps(engine, driver, "SM/3",
                steps("[{\"at\":\"inbox\",\"action\":\"correlate\",\"refused\":\"RETRY_NOT_ALLOWED\"}]"));
        assertThrows(AssertionError.class, () -> drive("SM/3", notRefused, "ok"));
        start("steps-message", "SM/4");
        AcceptanceSteps unexpected = new AcceptanceSteps(engine, driver, "SM/4",
                steps("[{\"at\":\"inbox\",\"action\":\"correlate\",\"message\":\"no.such.message\"}]"));
        assertThrows(RuntimeException.class, () -> drive("SM/4", unexpected, "ok"));
    }

    @Test
    void setReachesTheInstanceWaitingAtAnAsynchronousJob() throws Exception {
        start("steps-async", "SA/1", "amount", 100L);
        AcceptanceSteps s = new AcceptanceSteps(engine, driver, "SA/1",
                steps("[{\"at\":\"pay\",\"action\":\"set\",\"vars\":{\"amount\":480}}]"));
        assertEquals(List.of("after"), drive("SA/1", s, "ok"));
        assertEquals(480, ((Number) driver.var("SA/1", "paid")).intValue(), "the change was made before the job ran");
    }

    @Test
    void completeHitsOnlyTheNthOccurrence() throws Exception {
        start("steps-loop", "SL/1");
        AcceptanceSteps s = new AcceptanceSteps(engine, driver, "SL/1", steps(
                "[{\"at\":\"level\",\"action\":\"complete\",\"n\":2,\"vars\":{\"decision\":\"again\"},\"person\":\"q\"}]"));
        // The configuration answer loops on the first occurrence only ("again", then "ok"), so a second occurrence
        // exists and would end the case; the step's "again" on exactly that second occurrence is the only reason for a
        // third. The step on any other occurrence gives two completions (on the first: the second then takes "ok"),
        // on more than one gives four or more. With the constant answer "ok" used before (written, never run) the case
        // ended at the first completion and the second occurrence the step names could not happen, as
        // aStepThatIsNeverReachedStaysPending shows for the same model and answer.
        assertEquals(List.of("level", "level", "level"), drive("SL/1", s, occurrence -> occurrence == 1 ? "again" : "ok"),
                "the second completion loops once more; the first and third take the configuration answer");
        assertTrue(s.pending().isEmpty());
        assertTrue(driver.ended("SL/1"));
    }

    @Test
    void aStepThatIsNeverReachedStaysPending() throws Exception {
        start("steps-loop", "SL/2");
        AcceptanceSteps s = new AcceptanceSteps(engine, driver, "SL/2",
                steps("[{\"at\":\"level\",\"action\":\"complete\",\"n\":5,\"vars\":{\"decision\":\"again\"}}]"));
        assertEquals(List.of("level"), drive("SL/2", s, "ok"));
        assertEquals(1, s.pending().size(), "PackAcceptanceTest fails a scenario whose when never happened");
    }

    @Test
    void malformedStepsAreRefused() {
        for (String bad : List.of("[{\"advance\":\"PT1H\"}]", "[{\"at\":\"x\"}]",
                "[{\"at\":\"x\",\"advance\":\"PT1H\",\"action\":\"set\"}]", "[{\"at\":\"x\",\"action\":\"return\"}]",
                "[{\"at\":\"x\",\"advance\":\"-PT1H\"}]", "[{\"at\":\"x\",\"advance\":\"P1Y\"}]",
                "[{\"at\":\"x\",\"action\":\"complete\",\"refused\":\"X\"}]",
                "[{\"at\":\"x\",\"action\":\"complete\",\"n\":0}]",
                "[{\"at\":\"x\",\"action\":\"start\",\"pack\":\"p\"}]",
                "[{\"at\":\"x\",\"action\":\"start\",\"key\":\"k\",\"pack\":\"p\",\"process\":\"q\"}]")) {
            assertThrows(RuntimeException.class, () -> new AcceptanceSteps(engine, driver, "BAD", steps(bad)), bad);
        }
    }

    @Test
    void startOpensAnotherCaseOfTheScenarioNamingIt() throws Exception {
        start("steps-message", "SM/5");
        AcceptanceSteps s = new AcceptanceSteps(engine, driver, "SM/5", steps("[{\"at\":\"inbox\",\"action\":\"start\","
                + "\"pack\":\"test.pack\",\"key\":\"app-1\",\"vars\":{\"campaignId\":\"@case\",\"n\":1}}]"),
                pack -> "test.pack".equals(pack) ? "steps-loop" : null);
        assertEquals(List.of("level"), drive("SM/5", s, "ok"), "the started case is driven with the scenario");
        assertTrue(s.pending().isEmpty());
        assertEquals("SM/5", driver.var("SM/5/app-1", "campaignId"), "@case is the scenario's business key");
        assertTrue(driver.ended("SM/5/app-1"));
    }

    @Test
    void aStepNamingACaseActsOnThatCaseOnly() throws Exception {
        start("steps-message", "SM/6");
        AcceptanceSteps s = new AcceptanceSteps(engine, driver, "SM/6", steps("["
                + "{\"at\":\"inbox\",\"action\":\"start\",\"process\":\"steps-message\",\"key\":\"a\"},"
                + "{\"at\":\"inbox\",\"action\":\"start\",\"process\":\"steps-message\",\"key\":\"b\"},"
                + "{\"at\":\"inbox\",\"case\":\"b\",\"action\":\"correlate\",\"vars\":{\"who\":\"b\"}}]"));
        assertEquals(List.of("after"), drive("SM/6", s, "ok"), "only case b got the message");
        assertTrue(s.pending().isEmpty());
        assertEquals("b", driver.var("SM/6/b", "who"));
        assertTrue(driver.ended("SM/6/b"));
        assertFalse(driver.ended("SM/6/a"), "case a still waits");
        assertFalse(driver.ended("SM/6"), "the scenario's own case still waits");
    }

    @Test
    void advanceWaitsUntilWhatIsDueNowHasRun() throws Exception {
        start("steps-async", "SA/2", "amount", 1L);
        AcceptanceSteps s = new AcceptanceSteps(engine, driver, "SA/2", steps("[{\"at\":\"pay\",\"advance\":\"PT1H\"}]"));
        assertEquals(List.of("after"), drive("SA/2", s, "ok"));
        assertEquals(0, driver.offsetMillis(), "the due job ran first, so time never moved");
        assertEquals(1, s.pending().size());
    }

    @Test
    void aWrappedRefusalIsMatchedThroughItsCauses() {
        RuntimeException wrapped = new RuntimeException("command failed",
                new IllegalStateException("422 RETRY_NOT_ALLOWED: class (c) operations are never repeated"));
        assertTrue(AcceptanceSteps.refusalText(wrapped).contains("RETRY_NOT_ALLOWED"));
        assertFalse(AcceptanceSteps.refusalText(new RuntimeException("other")).contains("RETRY_NOT_ALLOWED"));
    }

    @Test
    void theScenarioClockOnlyMovesForwardAndResets() {
        driver.advance(Duration.ofMinutes(5));
        driver.advance(Duration.ofMinutes(5));
        assertEquals(Duration.ofMinutes(10).toMillis(), driver.offsetMillis(), "cumulative");
        assertThrows(IllegalArgumentException.class, () -> driver.advance(Duration.ofMinutes(-1)));
        driver.resetClock();
        assertEquals(0, driver.offsetMillis());
    }

    /**
     * M-116: the scenario clock is also the process-wide business clock ({@code BusinessClock.system()}), so a business
     * date read through it moves with the timers; the tenant zones stay those of the clock in place before the
     * scenario, and the reset puts that very clock back.
     */
    @Test
    void advanceAlsoMovesTheProcessWideBusinessClockAndResetRestoresIt() {
        java.time.ZoneId tenantZone = java.time.ZoneId.of("America/New_York");
        org.sangam.core.time.BusinessClock outer = org.sangam.core.time.BusinessClock.of(java.time.Clock.systemUTC(),
                t -> "t-zone".equals(t) ? tenantZone : null);
        org.sangam.core.time.BusinessClock before = org.sangam.core.time.BusinessClock.install(outer);
        try {
            Instant start = PackDriver.clock().instant();
            driver.advance(Duration.ofDays(3));
            org.sangam.core.time.BusinessClock during = org.sangam.core.time.BusinessClock.system();
            assertTrue(during != outer, "the scenario installs its own business clock");
            assertTrue(!during.clock().instant().isBefore(start.plus(Duration.ofDays(3))),
                    "business time moved with the engine clock");
            assertEquals(tenantZone, during.zone("t-zone"), "the tenant zone of the outer clock is kept");
            assertEquals(java.time.ZoneOffset.UTC, during.zone("other"), "and so is its fallback");
            driver.advance(Duration.ofDays(1));
            assertTrue(during == org.sangam.core.time.BusinessClock.system(), "installed once per scenario");
            assertTrue(!during.clock().instant().isBefore(start.plus(Duration.ofDays(4))), "cumulative");
            driver.resetClock();
            assertTrue(outer == org.sangam.core.time.BusinessClock.system(), "the reset puts the outer clock back");
            driver.resetClock();
            assertTrue(outer == org.sangam.core.time.BusinessClock.system(), "a second reset changes nothing");
        } finally {
            driver.resetClock();
            org.sangam.core.time.BusinessClock.install(before);
        }
    }
}
