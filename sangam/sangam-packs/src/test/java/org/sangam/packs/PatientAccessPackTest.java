// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.DocumentBuilderFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.dmn.engine.DmnDecisionResultEntries;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.operaton.bpm.engine.variable.Variables;
import org.sangam.packs.port.ConnectorRequest;
import org.sangam.packs.port.PackRuntime;
import org.sangam.packs.sim.SimulatorConnectorGateway;
import org.sangam.packs.spi.PackDeployer;
import org.sangam.packs.spi.PackRegistry;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * M-133, owner decision 174 (US-only, decision 107): the US-HLT-061 pack {@code sec.ushealth.patient-access}. A prior
 * authorization change of an item or service is made available through the Patient Access API, a drug never is, and an
 * item category or status the rules do not know is held for a person instead of being published or excluded; the
 * usage-metrics cycle submits only a compiled, reviewed report and holds an unconfirmed compilation or receipt (SL-115).
 * Every rule row carries its citation and is UNVERIFIED (decision 288), so both tables are readiness placeholders.
 */
class PatientAccessPackTest {

    static ProcessEngine engine;
    static PackDriver driver;
    static int seq;

    static final String PACK_DIR = "packs/sec-ushealth-patient-access/";
    static final String PA = "sangam-sec-ushealth-patient-access-pa";
    static final String METRICS = "sangam-sec-ushealth-patient-access-metrics";
    static final String PA_RULES = "sec-ushealth-patient-access-config";
    static final String METRICS_RULES = "sec-ushealth-patient-access-metrics-config";

    @BeforeAll
    static void setUp() {
        PackRuntime.reset();
        StandaloneInMemProcessEngineConfiguration cfg = new StandaloneInMemProcessEngineConfiguration();
        cfg.setJdbcUrl("jdbc:h2:mem:sangam-m133-061;DB_CLOSE_DELAY=-1");
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

    /** Starts {@code key} with the generic bag plus {@code kv} (a null value removes the key) and runs to a person. */
    static String run(String key, Object... kv) {
        String bk = "M133-061/" + key + "/" + (++seq);
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
        driver.run(bk, t -> null, 40);
        return bk;
    }

    static List<ConnectorRequest> calls(String bk, String connector) {
        return ((SimulatorConnectorGateway) PackRuntime.gateway()).journal().stream()
                .filter(r -> bk.equals(r.businessKey()) && connector.equals(r.connector())).toList();
    }

    static boolean reached(String bk, String activityId) {
        String root = driver.rootId(bk);
        return root != null && engine.getHistoryService().createHistoricActivityInstanceQuery().processInstanceId(root)
                .activityId(activityId).count() > 0;
    }

    static List<String> openTasks(String bk) {
        return engine.getTaskService().createTaskQuery().processInstanceBusinessKeyLike(bk + "%").list().stream()
                .map(t -> t.getTaskDefinitionKey()).toList();
    }

    static void noIncidents() {
        assertEquals(0, engine.getRuntimeService().createIncidentQuery().count(), "incidents");
    }

    // ----------------------------------------------------------------------- prior authorization information (PA)

    @Test
    void anItemOrServiceIsMadeAvailableWithinTheClockAndWithTheRetention() {
        String bk = run(PA, "itemCategory", "ITEM_OR_SERVICE", "paStatus", "APPROVED");
        assertTrue(reached(bk, "pt_end"), "published with the demo answer");
        List<ConnectorRequest> published = calls(bk, "payer.patientAccess");
        assertEquals(1, published.size(), "published once");
        assertEquals("APPROVED", published.get(0).payload().get("paStatus"));
        assertEquals("P1Y", published.get(0).payload().get("paRetentionSpec"), "the retention from the rules");
        assertNotNull(driver.var(bk, "paPublishDueAt"), "the availability clock is set");
        assertEquals("UNVERIFIED", driver.var(bk, "evidenceStatus"), "the case records the evidence of its rule");
        noIncidents();
    }

    @Test
    void aDrugIsNeverPublished() {
        String bk = run(PA, "itemCategory", "DRUG", "paStatus", "APPROVED");
        assertTrue(reached(bk, "pt_endExcluded"), "a drug is not part of the PA information (US-HLT-061)");
        assertTrue(calls(bk, "payer.patientAccess").isEmpty(), "a drug was published");
        noIncidents();
    }

    @Test
    void anUnknownItemCategoryIsHeldNeitherPublishedNorExcluded() {
        for (Object category : new Object[] {null, "SUPPLY"}) {
            String bk = run(PA, "itemCategory", category, "paStatus", "APPROVED");
            assertEquals(List.of("pt_classifyHold"), openTasks(bk), "member services classify the item: " + category);
            assertFalse(reached(bk, "pt_endExcluded"), "closed as excluded without a classification");
            assertTrue(calls(bk, "payer.patientAccess").isEmpty(), "published without a classification");
        }
        noIncidents();
    }

    @Test
    void aMissingStatusIsHeldBeforeAnythingIsPublished() {
        String bk = run(PA, "itemCategory", "ITEM_OR_SERVICE", "paStatus", null);
        assertEquals(List.of("pt_classifyHold"), openTasks(bk));
        assertTrue(calls(bk, "payer.patientAccess").isEmpty(), "published without a status");
    }

    @Test
    void anUnconfirmedPublicationIsHeldAndNeverClosedAsDone() {
        String bk = run(PA, "itemCategory", "ITEM_OR_SERVICE", "paStatus", "DENIED",
                "sim_payer_patientAccess", "FAILED:FHIR_DOWN");
        assertEquals(List.of("pt_publishHold"), openTasks(bk), "the interoperability analyst checks the API");
        assertFalse(reached(bk, "pt_end"), "closed as published without a confirmed record");
        noIncidents();
    }

    // -------------------------------------------------------------------------------------------- usage metrics

    @Test
    void aMissingReportingPeriodHoldsBeforeAnythingIsCompiled() {
        String bk = run(METRICS, "reportingPeriod", null);
        assertEquals(List.of("pm_periodHold"), openTasks(bk));
        assertTrue(calls(bk, "payer.apiMetrics").isEmpty(), "compiled without a reporting period");
    }

    @Test
    void anUnconfirmedCompilationHoldsAndNothingIsReviewedOrSubmitted() {
        String bk = run(METRICS, "reportingPeriod", "2026", "sim_payer_apiMetrics", "FAILED:METRICS_DOWN");
        assertEquals(List.of("pm_collectHold"), openTasks(bk));
        assertTrue(calls(bk, "payer.metricsReport").isEmpty(), "submitted without compiled metrics");
    }

    @Test
    void compiledMetricsWaitForTheComplianceReviewThenTheReceiptClosesTheCycle() {
        String bk = run(METRICS, "reportingPeriod", "2026");
        assertEquals(List.of("pm_review"), openTasks(bk), "nothing is submitted before the review");
        assertTrue(calls(bk, "payer.metricsReport").isEmpty());
        assertNotNull(driver.var(bk, "metricsReportDueAt"), "the report clock is set");
        driver.run(bk, t -> PackDriver.Answer.of("upa.compliance-officer", "COMPLIANCE-OFFICER",
                "metricsDecision", "submitReport", "metricsReview", "checked against the API usage log"), 40);
        assertTrue(reached(bk, "pm_end"), "submitted with a confirmed receipt");
        assertEquals(1, calls(bk, "payer.metricsReport").size());
        noIncidents();
    }

    @Test
    void anUnconfirmedReceiptHoldsTheCycle() {
        String bk = run(METRICS, "reportingPeriod", "2026", "sim_payer_metricsReport", "FAILED:RECIPIENT_DOWN");
        driver.run(bk, t -> "pm_review".equals(t.getTaskDefinitionKey())
                ? PackDriver.Answer.of("upa.compliance-officer", "COMPLIANCE-OFFICER", "metricsDecision", "submitReport",
                        "metricsReview", "checked") : null, 40);
        assertEquals(List.of("pm_submitHold"), openTasks(bk));
        assertFalse(reached(bk, "pm_end"), "closed as reported without a receipt");
    }

    // ------------------------------------------------------------------------------------ contract and citations

    @Test
    void theRulesRouteTheItemCategoryAndAnUnknownOneFailsClosed() {
        assertEquals("IN_SCOPE", scope("ITEM_OR_SERVICE"));
        assertEquals("EXCLUDED_DRUG", scope("DRUG"));
        assertEquals("UNCLASSIFIED", scope("SUPPLY"));
        // ADR-D-26 strict null input: a null category is refused, never matched by the catch-all; the process sets the
        // sentinel UNCLASSIFIED first (pt_defaults), so it reaches the hold instead
        Exception refused = org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () -> scope(null));
        assertTrue(String.valueOf(rootMessage(refused)).contains("SANGAM-DMN-NULL-INPUT"), rootMessage(refused));
    }

    static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getMessage();
    }

    static String scope(String category) {
        DmnDecisionResultEntries row = engine.getDecisionService().evaluateDecisionByKey(PA_RULES)
                .decisionDefinitionTenantId(PackTestTenant.ID)
                .variables(Variables.createVariables().putValue("itemCategory", category)).evaluate().getSingleResult();
        return (String) row.get("paScope");
    }

    @Test
    void theManifestCoversUsHlt061AndDeclaresBothTablesAsPlaceholders() throws Exception {
        JsonNode m;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(PACK_DIR + "pack.json")) {
            assertNotNull(in, "the US-HLT-061 pack is generated");
            m = new ObjectMapper().readTree(in);
        }
        assertEquals("sec.ushealth.patient-access", m.path("id").asText());
        List<String> covers = new ArrayList<>();
        m.path("covers").forEach(c -> covers.add(c.asText()));
        assertTrue(covers.contains("US-HLT-061"), "covers " + covers);
        List<String> placeholders = new ArrayList<>();
        m.path("placeholders").forEach(p -> {
            placeholders.add(p.path("decision").asText());
            assertFalse(p.path("reason").asText().isBlank(), "a placeholder records why");
        });
        assertTrue(placeholders.containsAll(List.of(PA_RULES, METRICS_RULES)), "placeholders " + placeholders);
        List<String> connectors = new ArrayList<>();
        m.path("connectors").forEach(c -> connectors.add(c.path("id").asText()));
        assertEquals(List.of("payer.patientAccess", "payer.apiMetrics", "payer.metricsReport"), connectors);
    }

    @Test
    void everyRuleRowCarriesItsCitationAndNoneClaimsVerifiedOrProductionUse() throws Exception {
        for (String table : List.of(PA_RULES, METRICS_RULES)) {
            Document d;
            try (InputStream in = getClass().getClassLoader().getResourceAsStream(PACK_DIR + table + ".dmn")) {
                assertNotNull(in, table);
                DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
                f.setNamespaceAware(true);
                f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                d = f.newDocumentBuilder().parse(in);
            }
            NodeList outs = d.getElementsByTagNameNS("*", "output");
            List<String> names = new ArrayList<>();
            for (int i = 0; i < outs.getLength(); i++) {
                names.add(((Element) outs.item(i)).getAttribute("name"));
            }
            int evidence = names.indexOf("evidenceStatus");
            int use = names.indexOf("permittedUse");
            int note = names.indexOf("evidenceNote");
            int source = names.indexOf("source");
            assertTrue(evidence >= 0 && use >= 0 && note >= 0 && source >= 0, table + " columns " + names);
            NodeList rules = d.getElementsByTagNameNS("*", "rule");
            assertTrue(rules.getLength() > 0, table);
            for (int r = 0; r < rules.getLength(); r++) {
                NodeList entries = ((Element) rules.item(r)).getElementsByTagNameNS("*", "outputEntry");
                String where = table + " rule " + r;
                assertEquals("\"UNVERIFIED\"", text(entries, evidence), where + ": the rule texts were not read");
                assertEquals("\"SYNTHETIC_DEMO_ONLY\"", text(entries, use), where);
                assertTrue(text(entries, note).length() > 2, where + ": the reason is recorded");
                assertTrue(text(entries, source).length() > 2, where + ": every row cites its source");
            }
        }
        List<String> cited = List.of(scopeSource("DRUG"), scopeSource("ITEM_OR_SERVICE"));
        assertTrue(cited.stream().allMatch(s -> s.startsWith("cited, not read: ") && s.contains("US-HLT-061")),
                "the scope rows name the requirement and say the rule text was not read: " + cited);
    }

    static String text(NodeList entries, int column) {
        return entries.item(column).getTextContent().trim();
    }

    static String scopeSource(String category) {
        return (String) engine.getDecisionService().evaluateDecisionByKey(PA_RULES)
                .decisionDefinitionTenantId(PackTestTenant.ID)
                .variables(Variables.createVariables().putValue("itemCategory", category)).evaluate().getSingleResult()
                .get("source");
    }
}
