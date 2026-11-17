// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.sangam.packs.port.PackRuntime;
import org.sangam.packs.sim.SimulatorConnectorGateway;
import org.sangam.packs.spi.PackDeployer;
import org.sangam.packs.spi.PackRegistry;

/**
 * M-134, owner decision 175: the worldwide PFM packs in the owner's order (budget preparation and the budget-document
 * bundle, standard reports, cash plan, transfer-deadline monitor, budget-to-accounting reconciliation, replenishment,
 * Do-Not-Pay; then PFM-056, PFM-065, PFM-069, PFM-080). Each case reaches its first accountable person after its
 * fail-closed checks; nothing irreversible (legislature submission, withdrawal application, publication, suspension,
 * debt recording) happens before a person's step; country values are declared SYNTHETIC placeholders (decision 108).
 */
class IntlPfmOrderPacksTest {

    static ProcessEngine engine;
    static PackDriver driver;
    static int seq;

    /** pack directory -> its SYNTHETIC country decisions. */
    static final Map<String, List<String>> PLACEHOLDERS = Map.of(
            "gov-intl-pfm-budget-preparation", List.of("intl-pfm-budget-calendar"),
            "gov-intl-pfm-standard-reports", List.of("intl-pfm-report-calendar"),
            "gov-intl-pfm-cash-plan", List.of("intl-pfm-cash-plan"),
            "gov-intl-pfm-transfer-deadline", List.of("intl-pfm-transfer-deadline"),
            "gov-intl-pfm-replenishment", List.of("intl-pfm-replenishment"),
            "gov-intl-pfm-fiscal-transparency", List.of("intl-pfm-publication-calendar"),
            "gov-intl-pfm-procurement-complaint", List.of("intl-pfm-complaint-rules"),
            "gov-intl-pfm-debt-operation", List.of("intl-pfm-debt-approval"));

    @BeforeAll
    static void setUp() {
        PackRuntime.reset();
        StandaloneInMemProcessEngineConfiguration cfg = new StandaloneInMemProcessEngineConfiguration();
        cfg.setJdbcUrl("jdbc:h2:mem:sangam-d175;DB_CLOSE_DELAY=-1");
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

    static String run(String key, Map<String, Object> extra) {
        String bk = "D175/" + key + "/" + (++seq);
        Map<String, Object> v = AllPacksSmokeTest.bag();
        v.putAll(extra);
        engine.getRuntimeService().createProcessInstanceByKey(key).businessKey(bk).setVariables(v)
                .processDefinitionTenantId(PackTestTenant.ID).execute();
        driver.run(bk, t -> null, 40);
        return bk;
    }

    static String run(String key) {
        return run(key, Map.of());
    }

    static List<String> openTasks(String bk) {
        return engine.getTaskService().createTaskQuery().processInstanceBusinessKeyLike(bk + "%").list().stream()
                .map(t -> t.getTaskDefinitionKey()).toList();
    }

    static boolean called(String bk, String connector) {
        return ((SimulatorConnectorGateway) PackRuntime.gateway()).journal().stream()
                .anyMatch(r -> r.businessKey() != null && r.businessKey().startsWith(bk) && connector.equals(r.connector()));
    }

    static void noIncidents() {
        assertEquals(0, engine.getRuntimeService().createIncidentQuery().count(), "incidents");
    }

    @Test
    void countryValuesAreDeclaredPlaceholders() throws Exception {
        ObjectMapper json = new ObjectMapper();
        for (Map.Entry<String, List<String>> e : PLACEHOLDERS.entrySet()) {
            JsonNode pack = json.readTree(Files.readString(Path.of("src", "main", "resources", "packs", e.getKey(),
                    "pack.json"), StandardCharsets.UTF_8));
            Set<String> declared = new HashSet<>();
            for (JsonNode p : pack.path("placeholders")) {
                declared.add(p.path("decision").asText());
                assertTrue(p.path("rule").asText().startsWith("SYNTHETIC"), e.getKey() + " " + p);
            }
            assertEquals(Set.copyOf(e.getValue()), declared, e.getKey());
        }
    }

    @Test
    void budgetPreparationWaitsForTheSubmissionAndSubmitsNothingToTheLegislature() {
        String bk = run("sangam-intl-pfm-budget-preparation");
        assertEquals(List.of("bp_submit"), openTasks(bk), "the spending unit submits first");
        assertTrue(called(bk, "ledger.ceilings"), "the ceiling comes from the ledger");
        assertFalse(called(bk, "legislature.submit"), "submitted before review and approval");
        assertNotNull(driver.var(bk, "bp_submitDueAt"), "the submission deadline is set from DMN");
        noIncidents();
    }

    @Test
    void theBudgetDocumentBundleIsCheckedByAPerson() {
        String bk = run("sangam-intl-pfm-budget-document-bundle");
        assertEquals(List.of("bb_check"), openTasks(bk), "the budget office checks the bundle");
        noIncidents();
    }

    @Test
    void aStandardReportIsPreparedFromTheLedgerExtractByItsDueDate() {
        String bk = run("sangam-intl-pfm-standard-reports");
        assertEquals(List.of("sr_prepare"), openTasks(bk));
        assertTrue(called(bk, "ledger.report"));
        assertNotNull(driver.var(bk, "reportDueAt"), "the report due date is set from DMN");
        noIncidents();
    }

    @Test
    void theCashPlanStartsFromTheForecast() {
        String bk = run("sangam-intl-pfm-cash-plan");
        assertEquals(List.of("cp_prepare"), openTasks(bk));
        assertTrue(called(bk, "ledger.cashForecast"));
        noIncidents();
    }

    @Test
    void aCollectionWaitsForItsTsaCreditUnderTheDeadline() {
        String bk = run("sangam-intl-pfm-transfer-deadline");
        assertNotNull(driver.var(bk, "transferDueAt"), "the transfer deadline is set from DMN");
        assertFalse(called(bk, "tsa.credit"), "the credit is looked up only when it is reported");
        assertTrue(openTasks(bk).isEmpty() || openTasks(bk).equals(List.of("td_overdue")), openTasks(bk).toString());
        noIncidents();
    }

    @Test
    void equalBudgetAndAccountingAmountsGoStraightToReview() {
        String bk = run("sangam-intl-pfm-budget-accounting-reconciliation",
                Map.of("sim_ledger_outturn", "amount=250000", "sim_accounts_statement", "amount=250000"));
        assertEquals(List.of("br_review"), openTasks(bk), "no difference to explain");
        assertEquals(0, new BigDecimal(String.valueOf(driver.var(bk, "differenceAmount"))).signum());
        noIncidents();
    }

    @Test
    void aBudgetAccountingDifferenceIsComputedExactlyAndExplained() {
        String bk = run("sangam-intl-pfm-budget-accounting-reconciliation",
                Map.of("sim_ledger_outturn", "amount=250001", "sim_accounts_statement", "amount=250000"));
        assertEquals(List.of("br_explain"), openTasks(bk), "the accountant explains the difference");
        assertEquals(0, new BigDecimal("1").compareTo(new BigDecimal(String.valueOf(driver.var(bk,
                "differenceAmount")))), "exact difference in minor units");
        noIncidents();
    }

    @Test
    void anUnconfirmedReconciliationAmountHoldsTheCaseForAPerson() {
        String bk = run("sangam-intl-pfm-budget-accounting-reconciliation",
                Map.of("sim_ledger_outturn", "FAILED:SIM_UNAVAILABLE"));
        assertEquals(List.of("br_hold"), openTasks(bk), "no default amount stands in");
        noIncidents();
    }

    @Test
    void aReplenishmentIsReviewedBeforeTheApplicationIsSent() {
        String bk = run("sangam-intl-pfm-replenishment");
        assertEquals(List.of("rp_review"), openTasks(bk));
        assertFalse(called(bk, "donor.withdrawal"), "sent before the treasury review");
        noIncidents();
    }

    @Test
    void aPayeeWithoutAListMatchIsCleared() {
        String bk = run("sangam-intl-pfm-do-not-pay", Map.of("sim_payee_exclusion", "match=false"));
        assertTrue(called(bk, "payee.exclusion"));
        assertTrue(driver.ended(bk), "cleared without a person");
        noIncidents();
    }

    @Test
    void aListMatchIsAdjudicatedByAPersonAndNeverClearedAutomatically() {
        String bk = run("sangam-intl-pfm-do-not-pay", Map.of("sim_payee_exclusion", "match=true"));
        assertEquals(List.of("dn_adjudicate"), openTasks(bk));
        noIncidents();
    }

    @Test
    void anUnansweredScreeningHoldsThePayment() {
        String bk = run("sangam-intl-pfm-do-not-pay", Map.of("sim_payee_exclusion", "FAILED:SIM_UNAVAILABLE"));
        assertEquals(List.of("dn_screenHold"), openTasks(bk), "a failed screening never clears the payee");
        noIncidents();
    }

    @Test
    void aPublicationIsPreparedAndApprovedBeforeItIsPublished() {
        String bk = run("sangam-intl-pfm-fiscal-transparency");
        assertEquals(List.of("ft_prepare"), openTasks(bk));
        assertFalse(called(bk, "opendata.publish"), "published before approval");
        noIncidents();
    }

    @Test
    void aComplaintIsFirstJudgedForAdmissibilityWithoutSuspendingTheAward() {
        String bk = run("sangam-intl-pfm-procurement-complaint");
        assertEquals(List.of("pc_admissibility"), openTasks(bk));
        assertFalse(called(bk, "procurement.suspend"), "suspended before admissibility");
        assertNotNull(driver.var(bk, "complaintDecisionDueAt"), "the decision deadline is set from DMN");
        noIncidents();
    }

    @Test
    void aDebtOperationWithinTheAuthorityIsAnalysedAndNotRecordedUnapproved() {
        String bk = run("sangam-intl-pfm-debt-operation",
                Map.of("debtAmount", 100000L, "sim_debt_limit", "remainingLimit=500000"));
        assertEquals(List.of("do_analysis"), openTasks(bk));
        assertFalse(called(bk, "debt.record"), "recorded before approval");
        noIncidents();
    }

    @Test
    void aDebtOperationAboveTheAuthorityGoesBackToTheDebtOffice() {
        String bk = run("sangam-intl-pfm-debt-operation",
                Map.of("debtAmount", 500001L, "sim_debt_limit", "remainingLimit=500000"));
        assertEquals(List.of("do_aboveLimit"), openTasks(bk));
        assertFalse(called(bk, "debt.record"));
        noIncidents();
    }

    @Test
    void thePefaEvidenceIsCompiledFromTheLedgerReportSet() {
        String bk = run("sangam-intl-pfm-pefa-evidence");
        assertEquals(List.of("pf_compile"), openTasks(bk));
        assertTrue(called(bk, "ledger.report"));
        noIncidents();
    }
}
