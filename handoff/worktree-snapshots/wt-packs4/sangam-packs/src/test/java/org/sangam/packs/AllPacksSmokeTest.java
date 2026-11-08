// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.operaton.bpm.engine.repository.ProcessDefinition;
import org.operaton.bpm.model.bpmn.instance.StartEvent;

import org.sangam.packs.port.PackRuntime;
import org.sangam.packs.spi.PackDeployer;
import org.sangam.packs.spi.PackRegistry;

/**
 * Every process with a none start event starts with a generic variable bag and runs to its first wait state without an
 * exception or incident (fast, in-memory). Catches expression and variable-contract errors across all packs.
 */
class AllPacksSmokeTest {

    static ProcessEngine engine;

    @BeforeAll
    static void setUp() {
        PackRuntime.reset();
        StandaloneInMemProcessEngineConfiguration cfg = new StandaloneInMemProcessEngineConfiguration();
        cfg.setJdbcUrl("jdbc:h2:mem:sangam-smoke;DB_CLOSE_DELAY=-1");
        cfg.setDatabaseSchemaUpdate("create-drop");
        cfg.setJobExecutorActivate(false);
        cfg.setHistory("full");
        engine = cfg.buildProcessEngine();
        new PackDeployer(engine, PackRegistry.discover()).deployAll(null);
    }

    @AfterAll
    static void tearDown() {
        engine.close();
    }

    static Map<String, Object> bag() {
        Date later = new Date(System.currentTimeMillis() + 7L * 24 * 3600 * 1000);
        Map<String, Object> v = new HashMap<>();
        for (String s : List.of("payee", "account", "ifsc", "invoiceNo", "budgetHead", "sanctionRef", "subject",
                "noting", "draftLetter", "fileNo", "applicantName", "beneficiaryId", "bankAccount", "tradeName",
                "premisesAddress", "workName", "itemDescription", "tenderId", "nitText", "employeeId", "employeeName",
                "fromHead", "toHead", "justification", "postName", "information", "grievanceText", "caseNo", "court",
                "projectName", "assetId", "premisesId", "orderId", "customerId", "description", "policyNo",
                "incidentSummary", "rollbackPlan", "supplierName", "counterpartyName", "bgNo", "bgBank", "contractorId",
                "qualification", "socialCategory", "district", "schemeId", "serviceCode", "licenceType", "leaveType",
                "category", "subjectHead", "amendmentType", "deviceId", "entityId")) {
            v.put(s, "X-" + s);
        }
        v.put("schemeId", "HOUSING-GRANT");
        v.put("licenceType", "TRADE");
        v.put("leaveType", "EL");
        v.put("subjectHead", "ESTABLISHMENT");
        v.put("serviceCode", "INCOME-CERT");
        v.put("category", "SERVICE");
        v.put("socialCategory", "OBC");
        v.put("qualification", "GRADUATE");
        v.put("amendmentType", "VARIATION");
        v.put("applicantRef", "mobile:9XXXXXXXX0");
        v.put("vacancies", "{\"UR\":2,\"OBC\":1}");
        v.put("auditParas", "[{\"key\":\"P1\",\"paraText\":\"Excess payment\"}]");
        v.put("priority", "P2");
        v.put("key", "ITEM-1");
        for (String n : List.of("premisesArea", "quantity", "qualifyingService", "areaHectares", "returnQty", "poQty",
                "grnQty", "invoiceQty", "escalationIndex")) {
            v.put(n, 1000.0);
        }
        // money is integer minor units (SD-398, packgen money.py): 1,000.00 rupees = 100000 paise
        for (String n : List.of("amount", "estimateAmount", "income", "unitPrice", "lastPay", "bgAmount", "contractValue",
                "assetValue", "refundAmount", "poPrice", "invoicePrice", "amendmentAmount", "emdAmount")) {
            v.put(n, 100_000L);
        }
        for (String n : List.of("age", "durationYears", "leaveDays", "extensionDays", "delayDays", "retentionYears",
                "riskScore")) {
            v.put(n, 3);
        }
        v.put("age", 40);
        v.put("qualifyingService", 20.0);
        v.put("gemAvailable", true);
        v.put("consentGiven", true);
        v.put("lifeLiberty", false);
        v.put("bplApplicant", false);
        v.put("retentionCategory", "C");
        for (String d : List.of("leaveFrom", "leaveTo", "bidDueAt", "openingAt", "bgExpiryAt", "retirementDate",
                "changeWindowStart", "joiningDate", "completionDueAt", "dob")) {
            v.put(d, later);
        }
        v.put("dob", new Date(0));
        v.put("initiator", "smoke.user");
        v.put("actingSeat", "SMOKE-SEAT");
        v.put("instalmentInterval", "PT1S");
        return v;
    }

    @Test
    void everyStartableProcessReachesAWaitState() {
        List<String> failures = new ArrayList<>();
        PackDriver driver = new PackDriver(engine);
        int started = 0;
        for (ProcessDefinition pd : engine.getRepositoryService().createProcessDefinitionQuery().latestVersion().list()) {
            var model = engine.getRepositoryService().getBpmnModelInstance(pd.getId());
            boolean noneStart = model.getModelElementsByType(StartEvent.class).stream()
                    .anyMatch(se -> se.getParentElement() instanceof org.operaton.bpm.model.bpmn.instance.Process
                            && se.getEventDefinitions().isEmpty());
            if (!noneStart || pd.getKey().startsWith("sangam-core-")) {
                continue; // core blocks are call activities with inputs from the calling pack
            }
            String bk = "SMOKE/" + pd.getKey();
            try {
                Map<String, Object> vars = bag();
                vars.put("campaignId", "SMOKE/sangam-recruitment-campaign");
                engine.getRuntimeService().startProcessInstanceByKey(pd.getKey(), bk, vars);
                started++;
                driver.runJobs();
            } catch (RuntimeException e) {
                failures.add(pd.getKey() + ": " + rootMessage(e));
            }
        }
        long incidents = engine.getRuntimeService().createIncidentQuery().count();
        engine.getRuntimeService().createIncidentQuery().list()
                .forEach(i -> failures.add("incident " + i.getActivityId() + ": " + i.getIncidentMessage()));
        assertTrue(failures.isEmpty(), started + " started; failures:\n" + String.join("\n", failures));
        assertTrue(incidents == 0);
    }

    static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getClass().getSimpleName() + ": " + t.getMessage();
    }
}
