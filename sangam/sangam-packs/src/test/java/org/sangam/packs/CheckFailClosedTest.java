// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.sangam.packs.port.PackAuditEvent;
import org.sangam.packs.port.PackRuntime;
import org.sangam.packs.sim.LoggingAuditSink;
import org.sangam.packs.sim.SimulatorConnectorGateway;
import org.sangam.packs.spi.PackDeployer;
import org.sangam.packs.spi.PackRegistry;

/**
 * SL-115: every check whose result a pack default used to pre-fill fails CLOSED (packgen
 * {@code common.checked_query} / {@code check_conditions}). The result is cleared before the call, so neither a default
 * nor a start variable stands in; a call that did not complete, or completed without the result, takes an explicit
 * "not confirmed" branch: a rejection with the check family's coded reason (SL-55 catalogue), or the pack's human
 * exception path where the pack has no rejection.
 *
 * <p>Each case starts the generic bag ({@link AllPacksSmokeTest#bag()}, whose simulated checks answer like the former
 * defaults), spoofs the check's result as a start variable, and makes the simulator fail, time out or answer without
 * the result (the directive removed: the bare simulator answers {@code ok=true} only).
 */
class CheckFailClosedTest {

    static ProcessEngine engine;
    static PackDriver driver;
    static int seq;

    @BeforeAll
    static void setUp() {
        PackRuntime.reset();
        StandaloneInMemProcessEngineConfiguration cfg = new StandaloneInMemProcessEngineConfiguration();
        cfg.setJdbcUrl("jdbc:h2:mem:sangam-sl115;DB_CLOSE_DELAY=-1");
        cfg.setDatabaseSchemaUpdate("create-drop");
        cfg.setJobExecutorActivate(false);
        cfg.setHistory("full");
        PackTestEngines.configure(cfg); // decisions 138 and FEEL (sangam-feel): as SangamProcessEnginePlugin does
        engine = cfg.buildProcessEngine();
        new PackDeployer(engine, PackRegistry.discover()).deployAll(PackTestTenant.ID);
        driver = new PackDriver(engine);
    }

    @AfterAll
    static void tearDown() {
        engine.close();
        // R8-heap2: a test class stays loaded for the whole run; its statics must not keep a closed engine
        engine = null;
        driver = null;
    }

    /** Starts {@code key} with the bag plus {@code kv} (a null value removes the key); runs it; returns the business key. */
    static String run(String key, Object... kv) {
        String bk = "SL115/" + key + "/" + (++seq);
        Map<String, Object> v = AllPacksSmokeTest.bag();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (kv[i + 1] == null) {
                v.remove(kv[i].toString());
            } else {
                v.put(kv[i].toString(), kv[i + 1]);
            }
        }
        engine.getRuntimeService().createProcessInstanceByKey(key).businessKey(bk).setVariables(v)
                .processDefinitionTenantId(PackTestTenant.ID).execute();
        driver.run(bk, new FormAnswers(engine)::answer, 80);
        return bk;
    }

    static List<PackAuditEvent> audit(String bk) {
        return ((LoggingAuditSink) PackRuntime.audit()).timeline(bk);
    }

    static boolean called(String bk, String connector) {
        return ((SimulatorConnectorGateway) PackRuntime.gateway()).journal().stream()
                .anyMatch(r -> bk.equals(r.businessKey()) && connector.equals(r.connector()));
    }

    static boolean taskDone(String bk, String taskKey) {
        return audit(bk).stream().anyMatch(e -> "TASK_COMPLETED".equals(e.type())
                && taskKey.equals(e.details().get("task")));
    }

    static boolean reached(String bk, String activityId) {
        String root = driver.rootId(bk);
        return root != null && engine.getHistoryService().createHistoricActivityInstanceQuery().processInstanceId(root)
                .activityId(activityId).count() > 0;
    }

    /** Rejected with {@code code}, never approved, no incident, and the step after the check was never called. */
    static void assertRejected(String bk, String code, String notCalled) {
        assertEquals(0, engine.getRuntimeService().createIncidentQuery().count(), "incidents");
        assertTrue(String.valueOf(driver.var(bk, "rejectionReasonCodes")).contains(code),
                bk + ": coded reason " + driver.var(bk, "rejectionReasonCodes") + ", expected " + code);
        assertTrue(audit(bk).stream().anyMatch(e -> "CASE_REJECTED".equals(e.type())), bk + ": no CASE_REJECTED");
        assertTrue(audit(bk).stream().noneMatch(e -> "CASE_APPROVED".equals(e.type())), bk + ": approved");
        assertFalse(taskDone(bk, "sn_sanction"), bk + ": reached sanction");
        assertFalse(called(bk, notCalled), bk + ": reached " + notCalled);
    }

    // ------------------------------------------------------------------ banking: account opening, payment, loans

    static final String ACCOUNT = "sangam-sec-bank-account-opening";

    @Test
    void accountOpeningPanCheckFailedIsRejectedEvenWithASpoofedStartValue() {
        String bk = run(ACCOUNT, "panValid", true, "sim_pan_verify", "FAILED:ITD_DOWN");
        assertRejected(bk, "PAN_NOT_CONFIRMED", "sanctions.screen");
        assertFalse(called(bk, "cbs.accountOpen"), "account opened");
    }

    @Test
    void accountOpeningScreeningWithoutAResultIsRejected() {
        String bk = run(ACCOUNT, "screenHit", false, "sim_sanctions_screen", null);
        assertRejected(bk, "SCREENING_NOT_CONFIRMED", "cbs.accountOpen");
        assertFalse(taskDone(bk, "ao_compliance"), "an unconfirmed screen is not a reviewable hit");
    }

    @Test
    void accountOpeningWithConfirmedChecksPassesTheGates() {
        String bk = run(ACCOUNT);
        assertTrue(called(bk, "sanctions.screen"), bk + ": screening not reached");
        assertFalse(String.valueOf(driver.var(bk, "rejectionReasonCodes")).contains("_NOT_CONFIRMED"),
                bk + ": " + driver.var(bk, "rejectionReasonCodes"));
    }

    @Test
    void paymentBeneficiaryValidationTimedOutIsRejected() {
        String bk = run("sangam-sec-bank-payment", "beneNameMatch", true, "sim_bene_validate", "UNKNOWN");
        assertRejected(bk, "BENEFICIARY_NAME_NOT_CONFIRMED", "sanctions.screen");
        assertFalse(called(bk, "bank.transfer"), "money moved");
    }

    @Test
    void loanOriginationBureauFailedIsRejectedBeforeTheCreditPolicy() {
        String bk = run("sangam-sec-bank-loan-origination", "bureauScore", 900, "sim_bureau_pull", "FAILED:BUREAU_DOWN");
        assertRejected(bk, "CREDIT_BUREAU_NOT_CONFIRMED", "bank.transfer");
        assertFalse(reached(bk, "lo_policy"), "credit policy evaluated on a score nobody confirmed");
    }

    @Test
    void loanClosureDuesNotConfirmedReleasesNothing() {
        String bk = run("sangam-sec-bank-loan-closure", "closureDuesNil", true, "sim_lms_closureCheck", "UNKNOWN");
        assertEquals(0, engine.getRuntimeService().createIncidentQuery().count(), "incidents");
        assertTrue(reached(bk, "lc_endDuesUnconfirmed"), bk + ": not the not-confirmed end");
        assertFalse(reached(bk, "lc_release"), "documents released on unconfirmed dues");
    }

    // ------------------------------------------------------------------ insurance, government money

    @Test
    void motorClaimCoverWithoutAResultIsRejected() {
        String bk = run("sangam-sec-ins-motor-claim", "coverActive", true, "sim_policy_coverCheck", null);
        assertRejected(bk, "COVER_NOT_CONFIRMED", "fraud.score");
    }

    @Test
    void billBudgetCheckFailedIsRejectedAndNothingIsEncumbered() {
        // an invoice of its own: the simulated duplicate register remembers every bill of this engine
        String bk = run("sangam-p1-bill-payment", "invoiceNo", "INV-SL115-FUNDS", "budgetAvailable",
                1_000_000_000_000_000L, "sim_ifms_budget", "FAILED:IFMS_DOWN");
        assertRejected(bk, "FUNDS_NOT_CONFIRMED", "ifms.encumber");
    }

    @Test
    void billDuplicateCheckTimedOutIsRejectedBeforeTheBudget() {
        String bk = run("sangam-p1-bill-payment", "invoiceNo", "INV-SL115-DUP", "dupDuplicate", false,
                "sim_ifms_duplicateBill", "UNKNOWN");
        assertRejected(bk, "DUPLICATE_CHECK_NOT_CONFIRMED", "ifms.budget");
    }

    @Test
    void grantDuplicateBeneficiaryCheckFailedIsRejected() {
        String bk = run("sangam-p3-grant-application", "regDuplicate", false,
                "sim_registry_beneficiary", "FAILED:REGISTRY_DOWN");
        assertRejected(bk, "DUPLICATE_CHECK_NOT_CONFIRMED", "pfms.dbt");
    }

    @Test
    void pfmAuthorityCheckFailedIsRejectedWithoutACommitment() {
        String bk = run("sangam-intl-pfm-execution", "amount", 5_000_000L, "fundsAvailable", 1_000_000_000_000L,
                "sim_ledger_available", "FAILED:LEDGER_DOWN");
        assertRejected(bk, "FUNDS_NOT_CONFIRMED", "ledger.encumber");
        assertFalse(taskDone(bk, "pe_breach"), "an unconfirmed check is not a shortfall");
    }

    @Test
    void sellerPanCheckFailedIsRejected() {
        String bk = run("sangam-sec-ecom-seller-onboarding", "panValid", true, "sim_pan_verify", "FAILED:ITD_DOWN");
        assertRejected(bk, "PAN_NOT_CONFIRMED", "marketplace.createSeller");
    }

    // ------------------------------------------------------------------ integrator decision: an unconfirmed result
    // never triggers an irreversible or adverse automatic action; it goes to the accountable human step

    /** Starts {@code key} with the bag plus {@code kv}, runs it until a person must act; returns the business key. */
    static String runToPerson(String key, Object... kv) {
        String bk = "SL115/" + key + "/" + (++seq);
        Map<String, Object> v = AllPacksSmokeTest.bag();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            v.put(kv[i].toString(), kv[i + 1]);
        }
        engine.getRuntimeService().createProcessInstanceByKey(key).businessKey(bk).setVariables(v)
                .processDefinitionTenantId(PackTestTenant.ID).execute();
        driver.run(bk, t -> null, 30);
        return bk;
    }

    static List<String> openTasks(String bk) {
        return engine.getTaskService().createTaskQuery().processInstanceBusinessKeyLike(bk + "%").list().stream()
                .map(t -> t.getTaskDefinitionKey()).toList();
    }

    @Test
    void disputeStatusUnknownGoesToTheDeskAndNothingIsReversed() {
        String bk = runToPerson("sangam-sec-bank-txn-dispute", "txnFailed", true, "sim_switch_status", "UNKNOWN");
        assertEquals(List.of("dp_statusUnconfirmed"), openTasks(bk), "the dispute desk establishes the status");
        assertFalse(called(bk, "cbs.reversal"), "reversed on an unknown status");
        assertFalse(reached(bk, "dp_reverse"), "reversal task on an unknown status");
        assertFalse(reached(bk, "dp_endSuccess"), "closed as successful on an unknown status");
    }

    @Test
    void netSuppliesNotConfirmedGoToTheTaxTeamAndNothingIsComputedOrFiled() {
        String bk = runToPerson("sangam-sec-ecom-gst-tcs", "netSupplies", 0, "sim_erp_netSupplies", "FAILED:ERP_DOWN");
        assertEquals(List.of("gt_suppliesUnconfirmed"), openTasks(bk), "the tax team establishes the supplies");
        assertFalse(reached(bk, "gt_compute"), "TCS computed on unconfirmed supplies");
        assertFalse(called(bk, "gstn.payment") || called(bk, "gstn.gstr8"), "deposited or filed");
    }

    @Test
    void fraudScoreNotConfirmedIsReferredToTheSiuNeverAutoRejected() {
        String bk = "SL115/motor/" + (++seq);
        Map<String, Object> v = AllPacksSmokeTest.bag();
        v.put("fraudScore", 10); // spoofed
        v.put("sim_fraud_score", "UNKNOWN");
        engine.getRuntimeService().createProcessInstanceByKey("sangam-sec-ins-motor-claim").businessKey(bk)
                .setVariables(v).processDefinitionTenantId(PackTestTenant.ID).execute();
        FormAnswers answers = new FormAnswers(engine);
        driver.run(bk, t -> "mc_siu".equals(t.getTaskDefinitionKey()) ? null : answers.answer(t), 60);
        assertTrue(openTasks(bk).contains("mc_siu"), bk + ": SIU not referred; open " + openTasks(bk));
        assertFalse(taskDone(bk, "mc_assess"), "assessed at the desk on an unconfirmed score");
        assertTrue(audit(bk).stream().noneMatch(e -> "CASE_REJECTED".equals(e.type())), "auto-rejected");
    }

    @Test
    void arrearsNotConfirmedGoToTheOfficerNeverSettled() {
        String bk = runToPerson("sangam-billing-account", "key", "CONS-SL115", "arrInArrears", false,
                "sim_billing_arrears", "FAILED:BILLING_DOWN");
        engine.getRuntimeService().createMessageCorrelation("sangam.billing.paymentReceived").processInstanceBusinessKey(bk)
                .correlate();
        driver.run(bk, t -> null, 30);
        assertEquals(List.of("ba_decide"), openTasks(bk), "the officer decides");
        assertFalse(reached(bk, "ba_end"), "settled on an unconfirmed arrears answer");
        assertFalse(called(bk, "billing.penalty"), "penalty on an unconfirmed arrears answer");
    }

    @Test
    void providerNpiNotConfirmedHoldsTheEligibilityInquiry() {
        String bk = runToPerson("sangam-sec-ushealth-eligibility", "sim_npi_registry", "FAILED:NPPES_DOWN");
        assertEquals(List.of("el_npiUnconfirmed"), openTasks(bk), "patient access corrects the NPI");
        assertFalse(called(bk, "x12.eligibility"), "270 sent on an unconfirmed provider");
    }

    /** The BPMN of {@code process} under the shipped packs. */
    static String model(String dir, String process) throws java.io.IOException {
        return java.nio.file.Files.readString(java.nio.file.Path.of("src", "main", "resources", "packs", dir,
                process + ".bpmn"), java.nio.charset.StandardCharsets.UTF_8);
    }

    static boolean flow(String xml, String source, String target, String conditionPart) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("<sequenceFlow\\b[^>]*sourceRef=\"" + source
                + "\" targetRef=\"" + target + "\"[^>]*>(.*?)</sequenceFlow>", java.util.regex.Pattern.DOTALL).matcher(xml);
        while (m.find()) {
            if (m.group(1).contains(conditionPart)) {
                return true;
            }
        }
        return false;
    }

    @Test
    void unconfirmedBidsCountHoldsTheTenderAndUnconfirmedSignatureCountNeedsTheSecondSignature() throws Exception {
        String tender = model("tender", "sangam-tender");
        assertTrue(flow(tender, "td_bidsGw", "td_bidsUnconfirmed", "eprocBidsCount == null"), "tender hold");
        assertTrue(flow(tender, "td_bidsUnconfirmed", "td_bids_clear", ""), "the officer's hold re-checks the count");
        assertFalse(tender.contains("eprocBidsCount=1"), "no default bid count");
        String chain = model("ifms-pao-chain", "sangam-ifms-pao-chain");
        assertTrue(flow(chain, "pc_sigGw", "pc_second_slaPlan", "chainSignatures == null"),
                "an unconfirmed signature count requires the second signature, never one");
        assertTrue(chain.contains("<operaton:string>chainSignatures=null</operaton:string>"), "count cleared first");
    }

    // ------------------------------------------------------------------ a human exception path, no rejection

    @Test
    void meterReadFailedGoesToTheReadExceptionAndIsNotBilled() {
        String bk = "SL115/util-bill/" + (++seq);
        Map<String, Object> v = AllPacksSmokeTest.bag();
        v.put("key", "CONS-SL115");
        v.put("batchId", "CYCLE-SL115");
        v.put("mdmReadStatus", "NORMAL"); // spoofed
        v.put("sim_mdm_read", "FAILED:MDM_DOWN");
        engine.getRuntimeService().createProcessInstanceByKey("sangam-sec-util-bill").businessKey(bk).setVariables(v)
                .processDefinitionTenantId(PackTestTenant.ID).execute();
        driver.run(bk, t -> null, 20);
        assertEquals(List.of("bl_exception"), engine.getTaskService().createTaskQuery().processInstanceBusinessKey(bk)
                .list().stream().map(t -> t.getTaskDefinitionKey()).toList(), "the billing clerk resolves the read");
        assertFalse(called(bk, "billing.generate"), "billed on an unconfirmed read");
    }
}
