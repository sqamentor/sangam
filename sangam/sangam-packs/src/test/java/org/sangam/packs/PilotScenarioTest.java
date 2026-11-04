// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;

import org.sangam.packs.PackDriver.Answer;
import org.operaton.bpm.engine.task.IdentityLink;
import org.operaton.bpm.engine.task.Task;
import org.sangam.packs.port.PackRuntime;
import org.sangam.packs.spi.PackDeployer;
import org.sangam.packs.spi.PackRegistry;

/** Pilot happy paths of the four packs on the in-memory engine with the simulator (fast, no Docker). */
class PilotScenarioTest {

    static ProcessEngine engine;
    static PackDriver driver;

    @BeforeAll
    static void setUp() {
        PackRuntime.reset();
        StandaloneInMemProcessEngineConfiguration cfg = new StandaloneInMemProcessEngineConfiguration();
        cfg.setJdbcUrl("jdbc:h2:mem:sangam-pilot;DB_CLOSE_DELAY=-1");
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

    /** Acting as the holder of the task's candidate seat today. */
    static Answer asSeat(Task t, Object... kv) {
        String seat = engine.getTaskService().getIdentityLinksForTask(t.getId()).stream()
                .map(IdentityLink::getGroupId).filter(java.util.Objects::nonNull).findFirst().orElse("none");
        String person = PackRuntime.seats().holderOf(null, seat, java.time.LocalDate.now()).orElse("user." + seat);
        return Answer.of(person, seat, kv);
    }

    /** Starts the shared (tenant-less) definition; tenant deployments of the same key exist in this engine. */
    static void start(String key, String bk, Map<String, Object> v) {
        engine.getRuntimeService().createProcessInstanceByKey(key).businessKey(bk).setVariables(v)
                .processDefinitionWithoutTenantId().execute();
    }

    static Map<String, Object> vars(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i].toString(), kv[i + 1]);
        }
        return m;
    }

    static Map<String, Object> bill() {
        Map<String, Object> v = new HashMap<>();
        v.put("payee", "M/s Shree Constructions");
        v.put("account", "50100012345678");
        v.put("ifsc", "SBIN0001234");
        v.put("amount", 475000.0);
        v.put("invoiceNo", "INV-2026-0412");
        v.put("budgetHead", "4059-01-051");
        v.put("sanctionRef", "AA/2026/77");
        v.put("actingSeat", "CLERK-DDO");
        v.put("initiator", "p1.clerk");
        v.put("officeId", "DPW-X");
        v.put("selectedSeat", "AO-2");
        return v;
    }

    @Test
    void p1HappyPathClosesReconciled() {
        String bk = "BILL/2026/00412";
        start("sangam-p1-bill-payment", bk, bill());
        List<String> done = driver.run(bk, t -> switch (t.getTaskDefinitionKey()) {
            case "sc_scrutiny" -> Answer.of("p1.ao2", "AO-2", "scrutinyDecision", "accept");
            case "sn_sanction" -> Answer.of("p1.ee", "EE-DIV1", "sanctionDecision", "approve");
            default -> null;
        }, 50);
        assertEquals(List.of("sc_scrutiny", "sn_sanction"), done);
        assertTrue(driver.ended(bk), "case still running");
        assertEquals("CLOSED", driver.var(bk, "caseState"));
        assertEquals("MATCHED", driver.var(bk, "reconStatus"));
    }

    @Test
    void p2FileMovesUpAndIsDisposed() {
        String bk = "B-2/14/2026-Estt";
        start("sangam-p2-file-movement", bk, vars(
                "fileNo", bk, "subject", "Sanction of honorarium", "subjectHead", "ESTABLISHMENT",
                "noting", "Proposal for approval.", "draftLetter", "Draft order.", "actingSeat", "DA-B2",
                "initiator", "p2.da", "officeId", "SEC-B2"));
        List<String> done = driver.run(bk, t -> switch (t.getTaskDefinitionKey()) {
            case "rc_level" -> asSeat(t, "recDecision", "recommend");
            case "sn_sanction" -> asSeat(t, "sanctionDecision", "approve");
            case "p2_dispatch" -> Answer.of("p2.da", "DA-B2", "letterNo", "L-1", "dispatchRecipients", "all");
            case "p2_close" -> Answer.of("p2.da", "DA-B2", "retentionCategory", "C", "retentionYears", 10);
            default -> null;
        }, 80);
        assertEquals(List.of("rc_level", "rc_level", "sn_sanction", "p2_dispatch", "p2_close"), done);
        assertTrue(driver.ended(bk));
        assertEquals("CLOSED", driver.var(bk, "caseState"));
    }

    @Test
    void p3GrantSanctionedAndDisbursedInInstalments() {
        String bk = "GRANT/2026/0001";
        start("sangam-p3-grant-application", bk, vars(
                "applicantName", "Sita Devi", "applicantRef", "mobile:98XXXXXX10", "schemeId", "HOUSING-GRANT",
                "beneficiaryId", "VAULT-7f3a", "bankAccount", "3210987654", "ifsc", "PUNB0123400",
                "income", 120000.0, "age", 41, "socialCategory", "SC", "landHolding", 0.5, "district", "A",
                "officeId", "BLOCK-A1", "actingSeat", "DEO-A1", "initiator", "p3.deo-a1", "instalmentInterval", "PT1S"));
        List<String> done = driver.run(bk, t -> switch (t.getTaskDefinitionKey()) {
            case "sc_scrutiny" -> asSeat(t, "scrutinyDecision", "accept");
            case "p3_recommend" -> asSeat(t, "p3RecDecision", "recommend");
            case "sn_sanction" -> asSeat(t, "sanctionDecision", "approve");
            default -> null;
        }, 80);
        assertEquals(List.of("sc_scrutiny", "p3_recommend", "sn_sanction"), done);
        assertTrue(driver.ended(bk), "still running");
        assertEquals("CLOSED", driver.var(bk, "caseState"));
        assertEquals(3L, ((Number) driver.var(bk, "instalmentNo")).longValue());
    }

    @Test
    void p4LicenceIssuedWithCertificateAndPush() {
        String bk = "LIC/2026/0007";
        start("sangam-p4-licence", bk, vars(
                "applicantName", "Ramesh Traders", "applicantRef", "mobile:97XXXXXX21", "licenceType", "TRADE",
                "tradeName", "Ramesh General Store", "premisesAddress", "Shop 4, Ward 7", "premisesArea", 35.0,
                "durationYears", 1, "officeId", "WARD-7", "initiator", "citizen.ramesh"));
        List<String> done = driver.run(bk, t -> switch (t.getTaskDefinitionKey()) {
            case "sc_scrutiny" -> asSeat(t, "scrutinyDecision", "accept");
            case "sn_sanction" -> asSeat(t, "sanctionDecision", "approve");
            default -> null;
        }, 80);
        assertEquals(List.of("sc_scrutiny", "sn_sanction"), done);
        assertTrue(driver.ended(bk));
        assertEquals("CLOSED", driver.var(bk, "caseState"));
        assertTrue(driver.var(bk, "licenceNo") != null);
    }

    /** Starts a works case, passes estimate scrutiny, returns the call activity of the first sanction. */
    static String firstWorksSanction(String bk, String tenant, String office) {
        Map<String, Object> v = vars("workName", "Road", "estimateAmount", 900000.0, "budgetHead", "5054",
                "officeId", office, "actingSeat", "AE-WK", "initiator", "wk.ae-wk");
        var b = engine.getRuntimeService().createProcessInstanceByKey("sangam-works-project").businessKey(bk)
                .setVariables(v);
        b = tenant != null ? b.processDefinitionTenantId(tenant) : b.processDefinitionWithoutTenantId();
        String root = b.execute().getId();
        driver.run(bk, t -> "sc_scrutiny".equals(t.getTaskDefinitionKey())
                ? Answer.of("wk.da-wk", "DA-WK", "scrutinyDecision", "accept") : null, 20);
        List<String> active = engine.getRuntimeService().getActiveActivityIds(root);
        return active.contains("wk_aaFirst") ? "AA_FIRST" : active.contains("wk_ts") ? "TS_FIRST" : active.toString();
    }

    @Test
    void worksOrderOfSanctionsIsPackConfiguration() {
        assertEquals("TS_FIRST", firstWorksSanction("WORKS/1", null, "DIV-WK"), "default order");
        assertEquals("AA_FIRST", firstWorksSanction("WORKS/2", null, "DIV-WK-AAFIRST"), "department row");
        PackRegistry reg = PackRegistry.discover();
        new PackDeployer(engine, reg).deploy(List.of("gov.works"), "state-b"); // with its dependencies
        assertEquals("AA_FIRST", firstWorksSanction("WORKS/3", "state-b", "DIV-WK"), "tenant override");
    }

    @Test
    void p1UnknownPaymentOutcomeIsReconciledWithoutRepeat() {
        String bk = "BILL/2026/00999";
        Map<String, Object> v = bill();
        v.put("invoiceNo", "INV-UNK-1");
        v.put("sim_pfms_payment", "UNKNOWN_THEN_COMPLETED");
        start("sangam-p1-bill-payment", bk, v);
        driver.run(bk, t -> switch (t.getTaskDefinitionKey()) {
            case "sc_scrutiny" -> Answer.of("p1.ao2", "AO-2", "scrutinyDecision", "accept");
            case "sn_sanction" -> Answer.of("p1.ee", "EE-DIV1", "sanctionDecision", "approve");
            default -> null;
        }, 50);
        assertTrue(engine.getRuntimeService().createVariableInstanceQuery().variableName("caseState").list().stream()
                .anyMatch(x -> "UNKNOWN_OUTCOME".equals(x.getValue())), "no UNKNOWN_OUTCOME");
        // class (c): a retry with the same operationId is refused; success with evidence closes the item
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () -> engine.getRuntimeService()
                .createMessageCorrelation("sangam.reconciliationResolved").processInstanceBusinessKey(bk)
                .setVariable("resolution", "RETRY_SAME_OPERATION").correlate());
        engine.getRuntimeService().createMessageCorrelation("sangam.reconciliationResolved")
                .processInstanceBusinessKey(bk).setVariable("resolution", "CONFIRMED_SUCCESS")
                .setVariable("resolutionEvidence", "UTR123456").correlate();
        driver.run(bk, t -> null, 50);
        assertTrue(driver.ended(bk), "still running");
        assertEquals("CLOSED", driver.var(bk, "caseState"));
    }
}
