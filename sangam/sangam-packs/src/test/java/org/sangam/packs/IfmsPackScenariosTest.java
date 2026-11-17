// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.operaton.bpm.engine.task.Task;
import org.sangam.packs.port.PackRuntime;
import org.sangam.packs.sim.InMemoryLedgerStore;
import org.sangam.packs.sim.LoggingAuditSink;
import org.sangam.packs.sim.SimulatorConnectorGateway;
import org.sangam.packs.spi.PackDeployer;
import org.sangam.packs.spi.PackRegistry;

/**
 * IFMS fit (owner decision 403, audit IFMS-FIT-AUDIT G9 / G12 / G14 and IFT-1..IFT-4), on H2 with the shipped packs
 * (PackDeployer over PackRegistry, the simulator connectors, the in-memory ledger and the pack audit sink): every case
 * starts from its process key, every task is answered from its form ({@link FormAnswers}) unless the scenario steers
 * it, and the outcome is checked as the case state, the external operations called and the pack audit trail.
 * <ul>
 *   <li>return and rework: budget distribution returned by the approver and reworked at scrutiny, clarification
 *       sought and answered (a new case version), a GPF return reworked at the preparer (IFT-4);</li>
 *   <li>cancellation of a case in flight (authorised cancellation, no ledger effect);</li>
 *   <li>year end: the earned-leave carry-forward cap, the year-end lapse, the year-end and monthly accounts
 *       (IFT-1 / IFT-2 / IFT-3: the approval is bound, the rendition runs, the case closes).</li>
 * </ul>
 */
class IfmsPackScenariosTest {

    static ProcessEngine engine;
    static PackDriver driver;
    static int seq;
    InMemoryLedgerStore ledger;

    @BeforeAll
    static void setUp() {
        PackRuntime.reset();
        StandaloneInMemProcessEngineConfiguration cfg = new StandaloneInMemProcessEngineConfiguration();
        cfg.setJdbcUrl("jdbc:h2:mem:sangam-ifms-scenarios;DB_CLOSE_DELAY=-1");
        cfg.setDatabaseSchemaUpdate("create-drop");
        cfg.setJobExecutorActivate(false);
        cfg.setHistory("full");
        PackTestEngines.configure(cfg); // decisions 138 and FEEL (sangam-feel): as SangamProcessEnginePlugin does
        engine = cfg.buildProcessEngine();
        new PackDeployer(engine, PackRegistry.discover()).deployAll(PackTestTenant.ID);
        driver = new PackDriver(engine).withWorkers();
    }

    @AfterAll
    static void tearDown() {
        engine.close();
    }

    /** Each scenario is its own given: no simulator, ledger or audit state from another one. */
    @BeforeEach
    void freshPorts() {
        driver.resetClock();
        ledger = new InMemoryLedgerStore();
        PackRuntime.install(PackRuntime.ports().withGateway(new SimulatorConnectorGateway())
                .withAudit(new LoggingAuditSink()).withLedgers(ledger)
                .withBatches(new org.sangam.packs.sim.InMemoryBatchLedger()).withClock(PackDriver.clock()));
    }

    // ------------------------------------------------------------------ driver

    /** Scenario answers: task key -> occurrence (1-based) -> variables over the form's own answer. */
    static final class Script {
        final Map<String, Map<Integer, Map<String, Object>>> steer = new HashMap<>();
        final Map<String, Integer> seen = new HashMap<>();
        String stopAt;
        int stopAtOccurrence;

        Script at(String task, int n, Object... kv) {
            Map<String, Object> m = new HashMap<>();
            for (int i = 0; i + 1 < kv.length; i += 2) {
                m.put(kv[i].toString(), kv[i + 1]);
            }
            steer.computeIfAbsent(task, k -> new HashMap<>()).put(n, m);
            return this;
        }

        /** Stop (leave the task open) when task {@code task} opens for the n-th time. */
        Script stopAt(String task, int n) {
            stopAt = task;
            stopAtOccurrence = n;
            return this;
        }
    }

    static String start(String key, String prefix, Map<String, Object> extra) {
        String bk = "IFMS/" + prefix + "/" + (++seq);
        Map<String, Object> v = AllPacksSmokeTest.bag();
        v.putAll(extra);
        engine.getRuntimeService().createProcessInstanceByKey(key).businessKey(bk).setVariables(v)
                .processDefinitionTenantId(PackTestTenant.ID).execute();
        return bk;
    }

    static List<String> run(String bk, Script s) {
        FormAnswers base = new FormAnswers(engine);
        return driver.run(bk, (Task t) -> {
            String tk = t.getTaskDefinitionKey();
            int n = s.seen.getOrDefault(tk, 0) + 1;
            if (tk.equals(s.stopAt) && n == s.stopAtOccurrence) {
                return null;
            }
            s.seen.put(tk, n);
            PackDriver.Answer a = base.answer(t);
            a.vars().putAll(s.steer.getOrDefault(tk, Map.of()).getOrDefault(n, Map.of()));
            return a;
        }, 150);
    }

    static Set<String> audit(String bk) {
        Set<String> types = new LinkedHashSet<>();
        ((LoggingAuditSink) PackRuntime.audit()).timeline(bk).forEach(e -> types.add(e.type()));
        return types;
    }

    static List<String> calls(String bk) {
        List<String> out = new ArrayList<>();
        ((SimulatorConnectorGateway) PackRuntime.gateway()).journal().stream()
                .filter(r -> bk.equals(r.businessKey())).forEach(r -> out.add(r.connector() + "." + r.operation()));
        return out;
    }

    static long count(List<String> calls, String prefix) {
        return calls.stream().filter(c -> c.startsWith(prefix)).count();
    }

    static List<String> open(String bk) {
        return engine.getTaskService().createTaskQuery().processInstanceBusinessKeyLike(bk + "%").list().stream()
                .map(Task::getTaskDefinitionKey).toList();
    }

    static void correlate(String bk, String message, Map<String, Object> vars) {
        var sub = engine.getRuntimeService().createEventSubscriptionQuery().eventType("message").eventName(message).list()
                .stream().filter(x -> x.getProcessInstanceId() != null && engine.getRuntimeService()
                        .createProcessInstanceQuery().processInstanceId(x.getProcessInstanceId())
                        .processInstanceBusinessKey(bk).count() > 0).findFirst()
                .orElseThrow(() -> new AssertionError(bk + " does not wait for " + message));
        engine.getRuntimeService().createMessageCorrelation(message).processInstanceId(sub.getProcessInstanceId())
                .setVariables(vars).correlate();
    }

    static String report(String bk, List<String> done) {
        return bk + ": done " + done + ", open " + open(bk) + ", state " + driver.var(bk, "caseState") + ", audit "
                + audit(bk) + ", calls " + calls(bk);
    }

    static void noIncidents(String bk) {
        Set<String> mine = new java.util.HashSet<>();
        engine.getHistoryService().createHistoricProcessInstanceQuery().processInstanceBusinessKeyLike(bk + "%").list()
                .forEach(p -> mine.add(p.getId()));
        assertEquals(List.of(), engine.getRuntimeService().createIncidentQuery().list().stream()
                .filter(i -> mine.contains(i.getProcessInstanceId()))
                .map(i -> i.getActivityId() + ": " + i.getIncidentMessage()).toList(), bk + ": incidents");
    }

    static void closed(String bk, List<String> done) {
        String r = report(bk, done);
        assertTrue(driver.ended(bk), r + ": ended");
        assertEquals("CLOSED", String.valueOf(driver.var(bk, "caseState")), r);
        assertTrue(audit(bk).contains("CASE_CLOSED"), r + ": CASE_CLOSED audited");
        noIncidents(bk);
    }

    /** The order of {@code expected} is found in {@code done}, other tasks may come between. */
    static void inOrder(List<String> done, List<String> expected, String r) {
        int at = 0;
        for (String t : expected) {
            int i = done.subList(at, done.size()).indexOf(t);
            assertTrue(i >= 0, r + ": " + t + " not completed in order " + expected);
            at += i + 1;
        }
    }

    // ------------------------------------------------------------------ G9 return and rework

    /** Budget distribution: the PAO returns the order; it goes back to scrutiny, is approved and posted once. */
    @Test
    void budgetDistributionReturnedByTheApproverIsReworkedAndPostedOnce() {
        String bk = start("sangam-ifms-budget-allocation", "BA-RET", Map.of());
        List<String> done = run(bk, new Script().at("sn_sanction", 1, "sanctionDecision", "return",
                "returnReason", "Head of account to be corrected"));
        String r = report(bk, done);
        inOrder(done, List.of("sc_scrutiny", "sn_sanction", "sc_scrutiny", "sn_sanction"), r);
        closed(bk, done);
        assertEquals(1, count(calls(bk), "ledger.distribute."), r + ": the limit is posted exactly once");
    }

    /** Scrutiny seeks a clarification; the answer makes a new case version and scrutiny accepts it. */
    @Test
    void budgetDistributionClarificationIsAnsweredAsANewVersion() {
        String bk = start("sangam-ifms-budget-allocation", "BA-CLR", Map.of());
        List<String> done = new ArrayList<>(run(bk, new Script().at("sc_scrutiny", 1, "scrutinyDecision", "clarify")));
        assertEquals("CLARIFICATION_SOUGHT", String.valueOf(driver.var(bk, "caseState")), report(bk, done));
        assertEquals(0, count(calls(bk), "ledger.distribute."), report(bk, done) + ": nothing posted while waiting");
        correlate(bk, "sangam.clarificationReceived", Map.of("remarks", "Order number corrected"));
        done.addAll(run(bk, new Script()));
        String r = report(bk, done);
        inOrder(done, List.of("sc_scrutiny", "sc_scrutiny", "sn_sanction"), r);
        assertEquals("2", String.valueOf(driver.var(bk, "caseVersion")), r + ": new case version");
        closed(bk, done);
        assertEquals(1, count(calls(bk), "ledger.distribute."), r);
    }

    static Map<String, Object> gpf() {
        return Map.of("gpfAccount", "GPF-123", "gpfPurpose", "ADVANCE", "amount", 5_000_000L,
                "gpfBalance", 40_000_000L, "qualifyingService", new BigDecimal("12.0"), "employeeId", "E-1001");
    }

    /**
     * IFT-4 (IFMS answer: Odisha FD 19582 of 11-07-2023 p.15): a GPF return is not a rejection. It goes to the
     * preparer (DDO-1) for rework with its reason, the resubmission is a new version and is sanctioned again; a later
     * rejection is terminal.
     */
    @Test
    void gpfReturnGoesToReworkAtThePreparerAndRejectionStaysTerminal() {
        String bk = start("sangam-ifms-gpf", "GPF-RET", gpf());
        List<String> done = run(bk, new Script()
                .at("sn_sanction", 1, "sanctionDecision", "return", "returnReason", "Attach the purpose document")
                .at("sn_sanction", 2, "sanctionDecision", "reject", "rejectionReasonCodes", "GPF_NOT_ELIGIBLE")
                .at("gp_rework", 1, "reworkDecision", "resubmit", "remarks", "Document attached"));
        String r = report(bk, done);
        inOrder(done, List.of("sn_sanction", "gp_rework", "sn_sanction"), r);
        assertEquals("2", String.valueOf(driver.var(bk, "caseVersion")), r + ": resubmission is a new version");
        assertEquals("DDO-1", String.valueOf(driver.var(bk, "gpfReturnSeat")), r + ": the Head of Office returns to the Operator");
        assertTrue(driver.ended(bk), r);
        assertEquals("REJECTED", String.valueOf(driver.var(bk, "caseState")), r + ": rejection is terminal");
        assertEquals(0, count(calls(bk), "ledger."), r + ": nothing encumbered or paid");
        noIncidents(bk);
    }

    static String returnTarget(String level) {
        Map<String, Object> v = new HashMap<>();
        v.put("competentRole", level);
        return String.valueOf(engine.getDecisionService().evaluateDecisionTableByKey("ifms-gpf-return-target")
                .decisionDefinitionTenantId(PackTestTenant.ID).variables(v).evaluate().getSingleResult()
                .<Object>getEntry("gpfReturnSeat"));
    }

    /** IFT-4 follow-up: the return target by returning level (Odisha FD 19582 p.15; the fallback is inferred). */
    @Test
    void gpfReturnTargetFollowsTheReturningLevel() {
        assertEquals("DDO-1", returnTarget("HOO"), "Head of Office returns to the Operator");
        assertEquals("HOO-1", returnTarget("AG"), "the A.G. returns to the HoO approver (unused in 0.1.0)");
        assertEquals("DDO-1", returnTarget("FA"), "any other internal level returns to the Operator (inferred)");
    }

    /** A return without a reason is not taken: the sanctioning authority is asked again. */
    @Test
    void gpfReturnWithoutAReasonGoesBackToTheAuthority() {
        String bk = start("sangam-ifms-gpf", "GPF-NORSN", gpf());
        List<String> done = run(bk, new Script().at("sn_sanction", 1, "sanctionDecision", "return")
                .stopAt("sn_sanction", 2));
        String r = report(bk, done);
        assertFalse(done.contains("gp_rework"), r + ": no rework without a reason");
        assertEquals(List.of("sn_sanction"), open(bk), r + ": the authority is asked again");
        assertFalse("REJECTED".equals(String.valueOf(driver.var(bk, "caseState"))), r);
    }

    // ------------------------------------------------------------------ G14 cancellation

    /** An authorised cancellation of a distribution waiting for approval ends it CANCELLED with nothing posted. */
    @Test
    void budgetDistributionCancelledInFlightPostsNothing() {
        String bk = start("sangam-ifms-budget-allocation", "BA-CAN", Map.of());
        List<String> done = run(bk, new Script().stopAt("sn_sanction", 1));
        assertEquals(List.of("sn_sanction"), open(bk), report(bk, done));
        correlate(bk, "sangam.cancelCase", Map.of("cancelReason", "Order withdrawn by the Ministry"));
        done = new ArrayList<>(done);
        done.addAll(run(bk, new Script()));
        String r = report(bk, done);
        assertTrue(driver.ended(bk), r);
        assertEquals("CANCELLED", String.valueOf(driver.var(bk, "caseState")), r);
        assertEquals(0, count(calls(bk), "ledger.distribute."), r + ": nothing posted");
        assertEquals(List.of(), open(bk), r);
        noIncidents(bk);
    }

    /** Withdrawal by the applicant of a GPF application waiting for sanction. */
    @Test
    void gpfWithdrawnByTheApplicantBeforeSanction() {
        String bk = start("sangam-ifms-gpf", "GPF-WD", gpf());
        List<String> done = run(bk, new Script().stopAt("sn_sanction", 1));
        correlate(bk, "sangam.withdrawCase", Map.of("cancelReason", "No longer needed"));
        done = new ArrayList<>(done);
        done.addAll(run(bk, new Script()));
        String r = report(bk, done);
        assertTrue(driver.ended(bk), r);
        assertEquals("WITHDRAWN", String.valueOf(driver.var(bk, "caseState")), r);
        assertEquals(0, count(calls(bk), "ledger."), r);
        noIncidents(bk);
    }

    // ------------------------------------------------------------------ G12 year end

    /** Earned leave carries forward up to the 300-day cap (leave-yearend DMN); the credit run closes (IFT-3). */
    @Test
    void earnedLeaveCarriesForwardUpToTheCap() {
        Map<String, BigDecimal> want = Map.of("E-0", new BigDecimal("30"), "E-285", new BigDecimal("300"),
                "E-300", new BigDecimal("300"));
        Map<String, BigDecimal> before = Map.of("E-0", BigDecimal.ZERO, "E-285", new BigDecimal("285"),
                "E-300", new BigDecimal("300"));
        for (var e : before.entrySet()) {
            ledger.open(PackTestTenant.ID, "leave", e.getKey() + ":EL", "DAYS", e.getValue());
            String bk = start("sangam-leave-credit", "LC-" + e.getKey(), Map.of("key", e.getKey()));
            List<String> done = run(bk, new Script());
            String r = report(bk, done);
            assertEquals(0, want.get(e.getKey()).compareTo(ledger.balance(PackTestTenant.ID, "leave", e.getKey() + ":EL")),
                    r + ": balance after the credit " + ledger.balance(PackTestTenant.ID, "leave", e.getKey() + ":EL"));
            assertTrue(driver.ended(bk), r);
            assertEquals("CLOSED", String.valueOf(driver.var(bk, "caseState")), r + ": IFT-3");
            noIncidents(bk);
        }
    }

    /** The year-end lapse closes the period in the ledger, issues the lapse statement and closes (IFT-3). */
    @Test
    void yearEndLapseClosesThePeriodAndTheCase() {
        String bk = start("sangam-ifms-year-end-lapse", "YL", Map.of());
        List<String> done = run(bk, new Script());
        String r = report(bk, done);
        assertEquals(1, count(calls(bk), "ledger.closePeriod."), r);
        assertTrue(audit(bk).contains("DOCUMENT_ISSUED"), r + ": lapse statement");
        assertTrue(driver.ended(bk), r);
        assertEquals("CLOSED", String.valueOf(driver.var(bk, "caseState")), r + ": IFT-3");
        noIncidents(bk);
    }

    /** IFT-1 / IFT-2: the CAA's signature is the bound approval, the annual accounts are rendered, the case closes. */
    @Test
    void yearEndClosingRendersTheSignedAccountsAndCloses() {
        String bk = start("sangam-ifms-year-end-closing", "YC", Map.of("fiscalYear", "2025-26"));
        List<String> done = run(bk, new Script());
        String r = report(bk, done);
        inOrder(done, List.of("yc_compile", "yc_sign"), r);
        assertFalse(audit(bk).contains("APPROVAL_INVALIDATED"), r + ": IFT-1");
        assertEquals(1, count(calls(bk), "accounts.render."), r + ": IFT-1 the accounts are rendered");
        closed(bk, done);
    }

    /** IFT-1 / IFT-2: the Pr.AO's approval is bound, the monthly account is rendered, the case closes. */
    @Test
    void monthlyAccountsAreRenderedAfterTheApprovalAndClose() {
        String bk = start("sangam-ifms-monthly-accounts", "MA", Map.of());
        List<String> done = run(bk, new Script());
        String r = report(bk, done);
        inOrder(done, List.of("ma_compile", "ma_consolidate"), r);
        assertFalse(audit(bk).contains("APPROVAL_INVALIDATED"), r + ": IFT-1");
        assertEquals(1, count(calls(bk), "accounts.render."), r + ": IFT-1 the account is rendered");
        closed(bk, done);
    }

    /** IFT-1: the FA's concurrence is bound, the ATN is submitted, the audit decision settles the para. */
    @Test
    void auditParaAtnIsSubmittedAndTheParaSettled() {
        String bk = start("sangam-ifms-audit-para", "AP", Map.of("paraNo", "PARA-7"));
        List<String> done = new ArrayList<>(run(bk, new Script()));
        String r = report(bk, done);
        assertFalse(audit(bk).contains("APPROVAL_INVALIDATED"), r + ": IFT-1");
        assertEquals(1, count(calls(bk), "audit.apms."), r + ": IFT-1 the ATN is submitted");
        correlate(bk, "sangam.ifms.paraDecision", Map.of());
        done.addAll(run(bk, new Script().at("ap_close", 1, "paraDecision", "settlePara")));
        r = report(bk, done);
        assertTrue(done.contains("ap_close"), r);
        assertTrue(driver.ended(bk), r);
        noIncidents(bk);
    }

    // ------------------------------------------------------------------ uncovered packs: happy paths

    @Test
    void surrenderOfSavingsIsWithdrawnUpTheStagesAndCloses() {
        String bk = start("sangam-ifms-surrender", "SU", Map.of("surrenderAmount", 100_000L));
        List<String> done = run(bk, new Script());
        String r = report(bk, done);
        inOrder(done, List.of("su_fa", "sn_sanction"), r);
        assertEquals(1, count(calls(bk), "ledger.withdraw."), r);
        closed(bk, done);
    }

    @Test
    void schemeAppraisalIsApprovedByTheLadderAuthorityAndCloses() {
        String bk = start("sangam-ifms-scheme-appraisal", "SA", Map.of("schemeName", "Rural roads",
                "totalOutlay", 100_000L));
        List<String> done = run(bk, new Script());
        String r = report(bk, done);
        inOrder(done, List.of("sa_comments", "sn_sanction"), r);
        assertTrue(audit(bk).contains("APPROVAL_RECORDED"), r);
        closed(bk, done);
    }

    // ------------------------------------------------------------------ bills and the remaining IFMS packs

    /**
     * The answers of the government systems a bill consults, as the simulator's directives (one per connector,
     * SimulatorConnectorGateway): bill type and head valid with a one-signature chain, no duplicate, one payee, funds.
     */
    static Map<String, Object> systemsAnswer() {
        Map<String, Object> v = new HashMap<>();
        v.put("sim_config_documentType", "valid=true;signatures=1");
        v.put("sim_config_classification", "valid=true");
        v.put("sim_ifms_duplicateBill", "duplicate=false");
        v.put("sim_registry_party", "multiple=false");
        v.put("sim_ledger_available", "available=100000000");
        return v;
    }

    static Map<String, Object> bill() {
        Map<String, Object> v = systemsAnswer();
        v.put("billType", "CONTINGENT");
        v.put("headOfAccount", "2054-00-001-01-00-13");
        v.put("amount", 1_500_000L);
        v.put("payee", "A Supplier");
        v.put("payeeAccount", "XXXX2002");
        v.put("ifsc", "SBIN0000001");
        v.put("ddoCode", "DDO-1");
        return v;
    }

    @Test
    void contingentBillIsEncumberedPassedPaidAndClosed() {
        String bk = start("sangam-ifms-bill", "BL", bill());
        List<String> done = run(bk, new Script());
        String r = report(bk, done);
        List<String> c = calls(bk);
        assertEquals(1, count(c, "ledger.encumber."), r);
        assertEquals(1, count(c, "ledger.consume."), r + ": paid once");
        closed(bk, done);
    }

    /** G9 on the bill: the PAO returns it, the encumbrance is released, the DDO corrects and resubmits, it is paid once. */
    @Test
    void billReturnedByThePaoIsRevisedResubmittedAndPaidOnce() {
        String bk = start("sangam-ifms-bill", "BL-RET", bill());
        List<String> done = run(bk, new Script().at("pc_pao", 1, "paoDecision", "returnBill", "remarks", "Sanction copy missing")
                .at("bl_revise", 1, "reviseDecision", "resubmit", "remarks", "Sanction copy attached"));
        String r = report(bk, done);
        inOrder(done, List.of("pc_pao", "bl_revise", "pc_pao"), r);
        List<String> c = calls(bk);
        assertEquals(2, count(c, "ledger.encumber."), r + ": encumbered for each version");
        assertEquals(1, count(c, "ledger.release."), r + ": the returned version's encumbrance is released");
        assertEquals(1, count(c, "ledger.consume."), r + ": paid once");
        closed(bk, done);
    }

    /** G14 on the bill: cancelled at the PAO after encumbrance; the encumbrance is released and nothing is paid. */
    @Test
    void billCancelledAtThePaoReleasesItsEncumbranceAndPaysNothing() {
        String bk = start("sangam-ifms-bill", "BL-CAN", bill());
        List<String> done = new ArrayList<>(run(bk, new Script().stopAt("pc_pao", 1)));
        assertEquals(1, count(calls(bk), "ledger.encumber."), report(bk, done));
        correlate(bk, "sangam.cancelCase", Map.of("cancelReason", "Supply order cancelled"));
        done.addAll(run(bk, new Script()));
        String r = report(bk, done);
        assertTrue(driver.ended(bk), r);
        assertEquals("CANCELLED", String.valueOf(driver.var(bk, "caseState")), r);
        assertEquals(1, count(calls(bk), "ledger.release"), r + ": encumbrance released");
        assertEquals(0, count(calls(bk), "ledger.consume."), r + ": nothing paid");
        noIncidents(bk);
    }

    @Test
    void grantInAidReleaseIsSanctionedPaidAndClosed() {
        Map<String, Object> v = systemsAnswer();
        v.putAll(Map.of("grantee", "District Health Society", "granteeType", "INSTITUTION", "amount", 2_000_000L,
                "headOfAccount", "2210-00-101-01-00-31", "payee", "District Health Society", "payeeAccount", "XXXX3003",
                "ifsc", "SBIN0000001"));
        String bk = start("sangam-ifms-gia-release", "GIA", v);
        List<String> done = run(bk, new Script());
        String r = report(bk, done);
        assertEquals(1, count(calls(bk), "ledger.consume."), r);
        closed(bk, done);
    }

    @Test
    void employeeAdvanceIsSanctionedPaidAndClosed() {
        Map<String, Object> v = systemsAnswer();
        v.putAll(Map.of("advanceKind", "COMPUTER", "amount", 5_000_000L, "instalments", 10, "basicPay", 5_600_000L,
                "headOfAccount", "7610-00-202-01-00-55", "payee", "A Subscriber", "payeeAccount", "XXXX1001",
                "ifsc", "SBIN0000001"));
        String bk = start("sangam-ifms-advance", "ADV", v);
        List<String> done = run(bk, new Script());
        String r = report(bk, done);
        assertEquals(1, count(calls(bk), "ledger.consume."), r);
        closed(bk, done);
    }

    @Test
    void snaSparshReleaseIsSentToTheRbiAndClosed() {
        Map<String, Object> v = systemsAnswer();
        v.putAll(Map.of("slsCode", "SLS-0101", "motherSanctionNo", "MS-2026-17", "amount", 1_000_000L,
                "centreShare", 600_000L, "stateShare", 400_000L));
        String bk = start("sangam-ifms-sna-sparsh-release", "SNA", v);
        List<String> done = run(bk, new Script());
        String r = report(bk, done);
        assertEquals(1, count(calls(bk), "rail.ekuber."), r);
        closed(bk, done);
    }

    @Test
    void receiptChallanIsPaidAndAccounted() {
        String bk = start("sangam-ifms-receipt", "RC", Map.of("receiptHead", "0070-01-800-00", "amount", 250_000L,
                "payee", "A Depositor"));
        List<String> done = new ArrayList<>(run(bk, new Script()));
        correlate(bk, "sangam.ifms.challanPaid", Map.of("bankRef", "BRN-778812"));
        done.addAll(run(bk, new Script()));
        String r = report(bk, done);
        assertTrue(driver.ended(bk), r);
        noIncidents(bk);
    }

    @Test
    void supplementaryDemandIsConcurredByFinanceAndClosed() {
        Map<String, Object> v = systemsAnswer();
        v.putAll(Map.of("fiscalYear", "2026-27", "headOfAccount", "2052-00-090-01", "amount", 1_000_000L,
                "supplementaryKind", "CASH", "justification", "Unforeseen election expenditure"));
        String bk = start("sangam-ifms-supplementary", "SD", v);
        List<String> done = new ArrayList<>(run(bk, new Script()));
        correlate(bk, "sangam.referralReply", Map.of("referralDecision", "faConcur", "remarks", "Concurred"));
        done.addAll(run(bk, new Script()));
        String r = report(bk, done);
        closed(bk, done);
    }

    @Test
    void utilisationCertificateIsReceivedAndClosed() {
        String bk = start("sangam-ifms-uc", "UC", Map.of("grantee", "District Health Society"));
        List<String> done = new ArrayList<>(run(bk, new Script()));
        correlate(bk, "sangam.ifms.ucSubmitted", Map.of("ucAmount", 2_000_000L));
        done.addAll(run(bk, new Script()));
        String r = report(bk, done);
        assertTrue(driver.ended(bk), r);
        noIncidents(bk);
    }
}
