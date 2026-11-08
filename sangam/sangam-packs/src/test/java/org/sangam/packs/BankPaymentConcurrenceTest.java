// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.dmn.engine.DmnDecisionRuleResult;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.operaton.bpm.engine.task.IdentityLink;
import org.operaton.bpm.engine.task.Task;

import org.sangam.packs.port.PackAuditEvent;
import org.sangam.packs.port.PackRuntime;
import org.sangam.packs.sim.LoggingAuditSink;
import org.sangam.packs.sim.SimulatorConnectorGateway;
import org.sangam.packs.spi.PackDeployer;
import org.sangam.packs.spi.PackRegistry;

/**
 * SL-50, owner decision 2026-11-08 ("हाँ, रखो"): in {@code sec.bank.payment-dual-auth} a checker of an above-band payment
 * only concurs, and the concurrence is capped at the band of the authoriser that covers the payment. At the band
 * ceiling the concurrence is allowed; one minor unit above it the payment belongs to the next authoriser band (routing
 * and cap move together); above every authoriser band the concurrence is refused with {@code 422
 * POWER_LIMIT_EXCEEDED} and audited. Maker, checker and authoriser stay three persons on three seats. The bands are
 * data (packgen {@code sec_fin.bank_payment}); this test reads them only through the shipped DMN.
 */
class BankPaymentConcurrenceTest {

    static final String KEY = "sangam-sec-bank-payment";
    static final double AUTH_BAND = 100_000_000d;        // PAYAUTH band ceiling (10 crore)
    static final double TOP_BAND = 10_000_000_000d;      // TREASURYHEAD band ceiling (1,000 crore)
    static final double MINOR = 0.01;                    // one paisa

    static ProcessEngine engine;
    static PackDriver driver;
    static int seq;

    @BeforeAll
    static void setUp() {
        PackRuntime.reset();
        StandaloneInMemProcessEngineConfiguration cfg = new StandaloneInMemProcessEngineConfiguration();
        cfg.setJdbcUrl("jdbc:h2:mem:sangam-sl50;DB_CLOSE_DELAY=-1");
        cfg.setDatabaseSchemaUpdate("create-drop");
        cfg.setJobExecutorActivate(false);
        cfg.setHistory("full");
        engine = cfg.buildProcessEngine();
        new PackDeployer(engine, PackRegistry.discover()).deployAll(null);
        driver = new PackDriver(engine);
    }

    @AfterAll
    static void tearDown() {
        engine.close();
    }

    record Step(String task, String seat, String person) {
    }

    /** Starts a NEFT payment of {@code amount} raised by the seed maker; returns its business key. */
    static String start(double amount) {
        String bk = "SL50/" + (++seq);
        Map<String, Object> v = AllPacksSmokeTest.bag();
        v.put("amount", amount);
        v.put("paymentRail", "NEFT");
        v.put(org.sangam.packs.core.Vars.INITIATOR_PERSON, "bpy.pay-maker");
        v.put(org.sangam.packs.core.Vars.INITIATOR_SEAT, "PAY-MAKER");
        engine.getRuntimeService().createProcessInstanceByKey(KEY).businessKey(bk).setVariables(v)
                .processDefinitionWithoutTenantId().execute();
        return bk;
    }

    static String seatOf(Task t) {
        return engine.getTaskService().getIdentityLinksForTask(t.getId()).stream().map(IdentityLink::getGroupId)
                .filter(java.util.Objects::nonNull).findFirst().orElse("none");
    }

    /** Drives the case by configuration and records who completed each task on which seat. */
    static List<Step> drive(String bk) {
        List<Step> steps = new ArrayList<>();
        FormAnswers fa = new FormAnswers(engine);
        driver.run(bk, t -> {
            PackDriver.Answer a = fa.answer(t);
            steps.add(new Step(t.getTaskDefinitionKey(), a.seat(), a.person()));
            return a;
        }, 80);
        return steps;
    }

    static List<PackAuditEvent> audit(String bk) {
        return ((LoggingAuditSink) PackRuntime.audit()).timeline(bk);
    }

    static boolean transferred(String bk) {
        return ((SimulatorConnectorGateway) PackRuntime.gateway()).journal().stream()
                .anyMatch(r -> bk.equals(r.businessKey()) && "bank.transfer".equals(r.connector()));
    }

    /** Runs to the end and checks the two-level approval: checker seat, then {@code authSeat}, three persons. */
    static void assertConcurredAndAuthorisedBy(double amount, String authSeat, String authPerson) {
        String bk = start(amount);
        List<Step> steps = drive(bk);
        assertTrue(driver.ended(bk), amount + ": did not end; steps " + steps);
        assertEquals(0, engine.getRuntimeService().createIncidentQuery().count(), "incidents");
        List<Step> sanctions = steps.stream().filter(s -> "sn_sanction".equals(s.task())).toList();
        assertEquals(List.of(new Step("sn_sanction", "PAY-CHECKER", "bpy.pay-checker"),
                new Step("sn_sanction", authSeat, authPerson)), sanctions, amount + ": checker then authoriser band");
        assertEquals(3, java.util.stream.Stream.of("bpy.pay-maker", sanctions.get(0).person(), sanctions.get(1).person())
                .distinct().count(), "maker, checker and authoriser are three persons");
        assertEquals(3, java.util.stream.Stream.of("PAY-MAKER", sanctions.get(0).seat(), sanctions.get(1).seat())
                .distinct().count(), "maker, checker and authoriser are three seats");
        assertEquals(2, ((Number) driver.var(bk, "authLevels")).intValue());
        assertTrue(transferred(bk), "class (c) payment rail after both approvals");
        assertTrue(audit(bk).stream().noneMatch(e -> "POWER_LIMIT_EXCEEDED".equals(e.type())), "no refusal");
    }

    @Test
    void checkerConcurrenceAtTheAuthoriserBandCeilingIsAllowed() {
        assertConcurredAndAuthorisedBy(AUTH_BAND, "PAY-AUTH", "bpy.pay-auth");
    }

    @Test
    void oneMinorUnitAboveTheAuthoriserBandRoutesToTheCoveringBand() {
        // without SL-50 the concurrence was fixed at the PAYAUTH band and this payment was refused at the checker
        assertConcurredAndAuthorisedBy(AUTH_BAND + MINOR, "TREASURY-HEAD", "bpy.treasury-head");
    }

    @Test
    void checkerConcurrenceAtTheTopBandCeilingIsAllowed() {
        assertConcurredAndAuthorisedBy(TOP_BAND, "TREASURY-HEAD", "bpy.treasury-head");
    }

    @Test
    void concurrenceAboveEveryAuthoriserBandIsRefusedAndAudited() {
        String bk = start(TOP_BAND + MINOR);
        FormAnswers fa = new FormAnswers(engine);
        driver.run(bk, t -> "sn_sanction".equals(t.getTaskDefinitionKey()) ? null : fa.answer(t), 80);
        Task chk = engine.getTaskService().createTaskQuery().processInstanceBusinessKeyLike(bk + "%")
                .taskDefinitionKey("sn_sanction").singleResult();
        assertNotNull(chk, "checker task open");
        assertEquals("PAY-CHECKER", seatOf(chk));
        PackDriver.Answer a = fa.answer(chk);
        engine.getTaskService().setVariablesLocal(chk.getId(), Map.of("actingPerson", a.person(), "actingSeat", a.seat()));
        ProcessEngineException ex = assertThrows(ProcessEngineException.class,
                () -> engine.getTaskService().complete(chk.getId(), a.vars()));
        assertTrue(ex.getMessage().contains("422 POWER_LIMIT_EXCEEDED"), ex.getMessage());
        PackAuditEvent refusal = audit(bk).stream().filter(e -> "POWER_LIMIT_EXCEEDED".equals(e.type())).findFirst()
                .orElseThrow(() -> new AssertionError("refusal not audited: " + audit(bk)));
        assertEquals("bpy.pay-checker", refusal.actorPersonId());
        assertEquals("PAY-CHECKER", refusal.actorSeatId());
        assertEquals(0, new BigDecimal(refusal.details().get("powerLimit").toString()).signum(),
                "no authoriser band covers the amount: nothing to concur up to");
        assertEquals(0, new BigDecimal(refusal.details().get("amount").toString())
                .compareTo(BigDecimal.valueOf(TOP_BAND + MINOR)), "amount on the refusal");
        assertFalse(driver.ended(bk), "refused concurrence does not move the case");
        assertNotNull(engine.getTaskService().createTaskQuery().taskId(chk.getId()).singleResult(), "task still open");
        assertFalse(transferred(bk), "no payment after a refused concurrence");
    }

    @Test
    void checkerCannotTakeTheAuthoriserStep() {
        String bk = start(AUTH_BAND + MINOR);
        FormAnswers fa = new FormAnswers(engine);
        int[] sanctions = {0};
        driver.run(bk, t -> "sn_sanction".equals(t.getTaskDefinitionKey()) && ++sanctions[0] > 1 ? null : fa.answer(t),
                80);
        Task auth = engine.getTaskService().createTaskQuery().processInstanceBusinessKeyLike(bk + "%")
                .taskDefinitionKey("sn_sanction").singleResult();
        assertNotNull(auth, "authoriser task open");
        assertEquals("TREASURY-HEAD", seatOf(auth));
        engine.getTaskService().setVariablesLocal(auth.getId(),
                Map.of("actingPerson", "bpy.pay-checker", "actingSeat", "PAY-CHECKER"));
        ProcessEngineException ex = assertThrows(ProcessEngineException.class,
                () -> engine.getTaskService().complete(auth.getId(), fa.answer(auth).vars()));
        assertTrue(ex.getMessage().contains("422 SOD_VIOLATION"), ex.getMessage());
        assertTrue(audit(bk).stream().anyMatch(e -> "SOD_VIOLATION_BLOCKED".equals(e.type())), "SoD refusal audited");
    }

    static DmnDecisionRuleResult decide(String key, Map<String, Object> in) {
        return engine.getDecisionService().evaluateDecisionTableByKey(key).variables(in).evaluate().getSingleResult();
    }

    /** The concurrence cap is the power of the authoriser the routing picks for that amount, at every band edge. */
    @Test
    void concurrenceCapEqualsThePowerOfTheRoutedAuthoriser() {
        for (double amount : new double[] {2_500_000d + MINOR, AUTH_BAND, AUTH_BAND + MINOR, TOP_BAND}) {
            DmnDecisionRuleResult lim = decide("sec-bank-pay-limits", Map.of("paymentRail", "NEFT", "amount", amount));
            assertEquals(2, ((Number) lim.get("authLevels")).intValue(), amount + " two levels");
            Object bandMax = lim.get("authBandMax");
            String role = (String) decide("sec-bank-pay-authoriser", Map.of("amount", amount)).get("competentRole");
            Map<String, Object> chk = new HashMap<>(Map.of("seatRole", "PAYCHECKER", "caseType", "PAYMENT",
                    "authLevels", 2, "authBandMax", bandMax, "amount", amount));
            Map<String, Object> au = new HashMap<>(Map.of("seatRole", role, "caseType", "PAYMENT", "authLevels", 2,
                    "authBandMax", bandMax, "amount", amount));
            BigDecimal cap = new BigDecimal(decide("sec-bank-pay-powers", chk).get("powerLimit").toString());
            BigDecimal authPower = new BigDecimal(decide("sec-bank-pay-powers", au).get("powerLimit").toString());
            assertEquals(0, cap.compareTo(authPower), amount + ": checker cap " + cap + " = power of " + role);
            assertTrue(BigDecimal.valueOf(amount).compareTo(cap) <= 0, amount + " within the cap");
        }
        DmnDecisionRuleResult over = decide("sec-bank-pay-limits",
                Map.of("paymentRail", "NEFT", "amount", TOP_BAND + MINOR));
        assertEquals(0, new BigDecimal(over.get("authBandMax").toString()).signum(), "no band above the top band");
        // a single-level payment keeps the checker's own band
        Map<String, Object> alone = new HashMap<>(Map.of("seatRole", "PAYCHECKER", "caseType", "PAYMENT",
                "authLevels", 1, "authBandMax", 0d, "amount", 2_500_000d));
        assertEquals(0, new BigDecimal("2500000").compareTo(
                new BigDecimal(decide("sec-bank-pay-powers", alone).get("powerLimit").toString())));
    }
}
