// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.operaton.bpm.engine.task.Task;

import org.sangam.packs.port.PackAuditEvent;
import org.sangam.packs.port.PackRuntime;
import org.sangam.packs.port.SeatHolding;
import org.sangam.packs.sim.InMemorySeatDirectory;
import org.sangam.packs.sim.LoggingAuditSink;
import org.sangam.packs.sim.SimulatorConnectorGateway;
import org.sangam.packs.spi.PackDeployer;
import org.sangam.packs.spi.PackRegistry;

/**
 * SL-32 residual, J-P1-E09 (IFMS R-102 v0.1) on the shipped p1 pack: a bill strictly above
 * {@code jointApprovalThreshold} (Rs 1 crore = 1,000,000,000 paise) needs joint approval by {@code jointApprovalSeats}
 * (SE-CIRCLE, CE-ZONE); above 25,00,000 the sanctioning seat is CE-ZONE. The joint step leaves out every earlier
 * actor of the case (SeatResolutionDelegate op {@code EXCLUDE_ACTORS}), so it never lands on the sanctioning seat, and
 * segregation of duties still refuses a second act of the same seat or person. Amounts are integer minor units.
 */
class JointApprovalSeatsTest {

    static final String KEY = "sangam-p1-bill-payment";
    static final long THRESHOLD = 1_000_000_000L;   // jointApprovalThreshold: 1,00,00,000 rupees in paise
    static final long BILL = 1_250_000_000L;        // J-P1-E09: 1,25,00,000 rupees in paise

    static ProcessEngine engine;
    static PackDriver driver;
    static int seq;

    @BeforeAll
    static void setUp() {
        PackRuntime.reset();
        StandaloneInMemProcessEngineConfiguration cfg = new StandaloneInMemProcessEngineConfiguration();
        cfg.setJdbcUrl("jdbc:h2:mem:sangam-sl32-joint;DB_CLOSE_DELAY=-1");
        cfg.setDatabaseSchemaUpdate("create-drop");
        cfg.setJobExecutorActivate(false);
        cfg.setHistory("full");
        PackTestEngines.configure(cfg); // decisions 138 and FEEL (sangam-feel): as SangamProcessEnginePlugin does
        engine = cfg.buildProcessEngine();
        new PackDeployer(engine, PackRegistry.discover()).deployAll(PackTestTenant.ID);
        driver = new PackDriver(engine);
        // two test-only joint seats beside the p1 seed: JOINT-B9-X is held by p1.se (who holds SE-CIRCLE too);
        // JOINT-B9-Y is held by p1.b9y, with p1.se on additional charge
        InMemorySeatDirectory dir = (InMemorySeatDirectory) PackRuntime.seats();
        LocalDate from = LocalDate.of(2026, 1, 1);
        dir.addSeat(PackTestTenant.ID, "JOINT-B9-X", "SE", "DPW-CIRCLE", "CE-ZONE");
        dir.addSeat(PackTestTenant.ID, "JOINT-B9-Y", "SE", "DPW-CIRCLE", "CE-ZONE");
        dir.addHolding(PackTestTenant.ID, new SeatHolding("JOINT-B9-X", "p1.se", SeatHolding.Basis.SUBSTANTIVE, null, from, null));
        dir.addHolding(PackTestTenant.ID, new SeatHolding("JOINT-B9-Y", "p1.b9y", SeatHolding.Basis.SUBSTANTIVE, null, from, null));
        dir.addHolding(PackTestTenant.ID, new SeatHolding("JOINT-B9-Y", "p1.se", SeatHolding.Basis.ADDITIONAL_CHARGE, "AC-B9",
                from, null));
    }

    @AfterAll
    static void tearDown() {
        engine.close();
        // R8-heap2: a test class stays loaded for the whole run; its statics must not keep a closed engine
        engine = null;
        driver = null;
    }

    record Step(String task, String seat, String person) {
    }

    /** Starts a p1 bill of {@code amount} paise with a budget of 2 crore; {@code jointSeats} overrides the pack's. */
    static String start(long amount, String jointSeats) {
        String bk = "SL32-JOINT/" + (++seq);
        Map<String, Object> v = AllPacksSmokeTest.bag();
        v.putAll(PilotScenarioTest.bill());
        v.put("amount", amount);
        v.put("invoiceNo", "INV-SL32-JOINT-" + seq);
        v.put("sim_ifms_budget", "available=2000000000"); // the simulator's default budget is only 1 crore
        if (jointSeats != null) {
            v.put("jointApprovalSeats", jointSeats);
        }
        engine.getRuntimeService().createProcessInstanceByKey(KEY).businessKey(bk).setVariables(v)
                .processDefinitionTenantId(PackTestTenant.ID).execute();
        return bk;
    }

    /** Drives by configuration; {@code stopAt} is left open. Records who completed each task on which seat. */
    static List<Step> drive(String bk, String stopAt) {
        List<Step> steps = new ArrayList<>();
        FormAnswers fa = new FormAnswers(engine);
        driver.run(bk, t -> {
            if (t.getTaskDefinitionKey().equals(stopAt)) {
                return null;
            }
            PackDriver.Answer a = fa.answer(t);
            steps.add(new Step(t.getTaskDefinitionKey(), a.seat(), a.person()));
            return a;
        }, 80);
        return steps;
    }

    static List<PackAuditEvent> audit(String bk) {
        return ((LoggingAuditSink) PackRuntime.audit()).timeline(bk);
    }

    static boolean paid(String bk) {
        return ((SimulatorConnectorGateway) PackRuntime.gateway()).journal().stream()
                .anyMatch(r -> bk.equals(r.businessKey()) && "pfms.payment".equals(r.connector()));
    }

    static List<Step> of(List<Step> steps, String task) {
        return steps.stream().filter(s -> task.equals(s.task())).toList();
    }

    @Test
    void theJointStepNeverLandsOnTheSanctioningSeat() {
        String bk = start(BILL, null);
        List<Step> steps = drive(bk, null);
        assertTrue(driver.ended(bk), "did not end; steps " + steps);
        assertEquals(List.of(new Step("sn_sanction", "CE-ZONE", "p1.ce")), of(steps, "sn_sanction"),
                "above 25,00,000 the competent seat is CE-ZONE");
        assertEquals(List.of(new Step("p1_joint", "SE-CIRCLE", "p1.se")), of(steps, "p1_joint"),
                "one joint instance, on the joint seat that has not acted; CE-ZONE is not asked twice");
        assertEquals("SE-CIRCLE", driver.var(bk, "jointSeats"));
        assertEquals(1, ((Number) driver.var(bk, "jointSeatsCount")).intValue());
        assertTrue(audit(bk).stream().noneMatch(e -> "SOD_VIOLATION_BLOCKED".equals(e.type())), "nothing refused");
        assertEquals(2, audit(bk).stream().filter(e -> "APPROVAL_RECORDED".equals(e.type()))
                .map(PackAuditEvent::actorSeatId).distinct().count(), "two approvals by two seats");
        assertEquals(1, audit(bk).stream().filter(e -> "APPROVAL_RECORDED".equals(e.type()))
                .map(e -> e.details().get("payloadHash")).distinct().count(), "both bound to the same payload hash");
        assertEquals("CLOSED", driver.var(bk, "caseState"));
        assertTrue(paid(bk), "payment after both approvals");
    }

    @Test
    void theJointStepStartsStrictlyAboveOneCrore() {
        String at = start(THRESHOLD, null);
        List<Step> steps = drive(at, null);
        assertTrue(driver.ended(at), "did not end; steps " + steps);
        assertTrue(of(steps, "p1_joint").isEmpty(), "Rs 1 crore exactly: no joint approval");
        String above = start(THRESHOLD + 1, null);
        List<Step> more = drive(above, null);
        assertTrue(driver.ended(above), "did not end; steps " + more);
        assertEquals(List.of(new Step("p1_joint", "SE-CIRCLE", "p1.se")), of(more, "p1_joint"),
                "one paisa above Rs 1 crore: joint approval");
    }

    static String seatOf(Task t) {
        return engine.getTaskService().getIdentityLinksForTask(t.getId()).stream()
                .map(org.operaton.bpm.engine.task.IdentityLink::getGroupId).filter(java.util.Objects::nonNull)
                .findFirst().orElse("none");
    }

    static Task openJoint(String bk) {
        Task t = engine.getTaskService().createTaskQuery().processInstanceBusinessKeyLike(bk + "%")
                .taskDefinitionKey("p1_joint").singleResult();
        assertNotNull(t, "joint approval open");
        return t;
    }

    static void assertRefused(String bk, Task joint, String person, String seat) {
        engine.getTaskService().setVariablesLocal(joint.getId(), Map.of("actingPerson", person, "actingSeat", seat));
        ProcessEngineException ex = assertThrows(ProcessEngineException.class,
                () -> engine.getTaskService().complete(joint.getId(), Map.of("jointDecision", "approve")));
        assertTrue(ex.getMessage().contains("422 SOD_VIOLATION"), ex.getMessage());
        PackAuditEvent refusal = audit(bk).stream().filter(e -> "SOD_VIOLATION_BLOCKED".equals(e.type()))
                .reduce((a, b) -> b).orElseThrow(() -> new AssertionError("refusal not audited: " + audit(bk)));
        assertEquals(person, refusal.actorPersonId());
        assertEquals(seat, refusal.actorSeatId());
        assertNotNull(engine.getTaskService().createTaskQuery().taskId(joint.getId()).singleResult(), "still open");
    }

    @Test
    void theSodGuardStillRefusesASecondActOfTheSanctioningSeatOrPerson() {
        String bk = start(BILL, null);
        drive(bk, "p1_joint");
        Task joint = openJoint(bk);
        assertRefused(bk, joint, "p1.ce", "CE-ZONE");     // the same seat acting a second time
        assertRefused(bk, joint, "p1.ce", "SE-CIRCLE");   // the same person through another seat context
        assertFalse(driver.ended(bk), "a refused joint approval does not move the case");
        assertFalse(paid(bk), "no payment after a refused joint approval");
    }

    @Test
    void onePersonHoldingTwoJointSeatsGetsOneTask() {
        String bk = start(BILL, "SE-CIRCLE,JOINT-B9-X");
        List<Step> steps = drive(bk, null);
        assertTrue(driver.ended(bk), "did not end; steps " + steps);
        assertEquals(List.of(new Step("p1_joint", "SE-CIRCLE", "p1.se")), of(steps, "p1_joint"),
                "p1.se holds both joint seats: one instance, on the first seat");
        assertEquals("SE-CIRCLE", driver.var(bk, "jointSeats"));
        assertEquals(1, ((Number) driver.var(bk, "jointSeatsCount")).intValue());
    }

    @Test
    void aSecondCompletionBySamePersonInOneJointRoundIsRefused() {
        String bk = start(BILL, "SE-CIRCLE,JOINT-B9-Y");
        drive(bk, "p1_joint");
        List<Task> joint = engine.getTaskService().createTaskQuery().processInstanceBusinessKeyLike(bk + "%")
                .taskDefinitionKey("p1_joint").list();
        assertEquals(2, joint.size(), "two joint seats with two different holders: two instances");
        Task se = joint.stream().filter(t -> "SE-CIRCLE".equals(seatOf(t))).findFirst().orElseThrow();
        Task y = joint.stream().filter(t -> "JOINT-B9-Y".equals(seatOf(t))).findFirst().orElseThrow();
        engine.getTaskService().setVariablesLocal(se.getId(), Map.of("actingPerson", "p1.se", "actingSeat", "SE-CIRCLE"));
        engine.getTaskService().complete(se.getId(), Map.of("jointDecision", "approve"));
        // p1.se also holds JOINT-B9-Y (additional charge) but has already approved in this round
        assertRefused(bk, y, "p1.se", "JOINT-B9-Y");
        // a different person on the other seat stays allowed, and the case goes on to payment
        engine.getTaskService().setVariablesLocal(y.getId(), Map.of("actingPerson", "p1.b9y", "actingSeat", "JOINT-B9-Y"));
        engine.getTaskService().complete(y.getId(), Map.of("jointDecision", "approve"));
        drive(bk, null);
        assertTrue(driver.ended(bk), "case ends after two joint approvals by two persons");
        assertTrue(paid(bk), "payment after the joint approvals");
    }

    @Test
    void aJointPoolLeftEmptyByTheSanctionerIsRefusedNotSkipped() {
        // only the sanctioning seat is configured: one seat must not count as two approvals, so the case stops
        String bk = start(BILL, "CE-ZONE");
        RuntimeException ex = assertThrows(RuntimeException.class, () -> drive(bk, null));
        assertTrue(String.valueOf(ex.getMessage()).contains("422 SOD_VIOLATION"), ex.getMessage());
        assertTrue(engine.getTaskService().createTaskQuery().processInstanceBusinessKeyLike(bk + "%")
                .taskDefinitionKey("p1_joint").list().isEmpty(), "no joint task on the sanctioning seat");
        assertFalse(paid(bk), "no payment with a single approval");
    }
}
