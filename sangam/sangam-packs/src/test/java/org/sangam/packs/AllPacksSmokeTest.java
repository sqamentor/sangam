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
        PackTestEngines.configure(cfg); // decisions 138 and FEEL (sangam-feel): as SangamProcessEnginePlugin does
        engine = cfg.buildProcessEngine();
        new PackDeployer(engine, PackRegistry.discover()).deployAll(PackTestTenant.ID);
    }

    @AfterAll
    static void tearDown() {
        engine.close();
        // R8-heap2: a test class stays loaded for the whole run; its statics must not keep a closed engine
        engine = null;
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
                "category", "subjectHead", "amendmentType", "deviceId", "entityId",
                // required start-form fields a decision reads (strict FEEL refuses an undefined variable, ADR-D-26)
                "planPeriod", "projectId", "phiScope",
                // registry party keys a money approval binds its payee by (owner decision E3, MoneyFieldsDelegate
                // payeeKeys); the test registry (VerifiedPayees.SIMULATOR) holds a verified record for each
                "payeePartyId", "slsCode", "remitterPartyId", "unpaidDividendPartyId", "sellerId",
                // the capture a refund to source returns to (owner decision M5, CaptureRefunds)
                "emdCaptureRef")) {
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
            v.put(n, new java.math.BigDecimal("1000")); // decision 138: an exact decimal, never a binary float
        }
        // money is integer minor units (SD-398, packgen money.py): 1,000.00 rupees = 100000 paise
        for (String n : List.of("amount", "estimateAmount", "income", "unitPrice", "lastPay", "bgAmount", "contractValue",
                "assetValue", "refundAmount", "poPrice", "invoicePrice", "amendmentAmount", "emdAmount", "debtAmount")) {
            v.put(n, 100_000L);
        }
        v.put("debitAmount", 100_000L); // a variable mandate's actual debit (owner item 3)
        for (String n : List.of("age", "durationYears", "leaveDays", "extensionDays", "delayDays", "retentionYears",
                "riskScore")) {
            v.put(n, 3);
        }
        v.put("age", 40);
        v.put("qualifyingService", new java.math.BigDecimal("20"));
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
        v.putAll(simulatedChecks());
        return v;
    }

    /**
     * SL-115: the answers of the simulated external checks for a generic run. A pack no longer pre-fills a check result
     * with a default (packgen check_results.py), and the bare simulator answers these connectors without the result
     * key ({@code ok=true}), which fails closed. The answers are the shipped demo data
     * ({@link org.sangam.packs.sim.DemoAnswersGateway#shippedAnswers()}, one source for demos and tests), equal to the
     * former pack defaults, so a generic run takes the same path as before; a test of the unconfirmed path removes or
     * overrides the directive. {@code ledger.available} is left out (SL-113: FundsCheckFailClosedTest relies on the bare
     * answer; a run that needs authority passes {@code sim_ledger_available}).
     */
    static Map<String, Object> simulatedChecks() {
        Map<String, Object> v = new HashMap<>();
        org.sangam.packs.sim.DemoAnswersGateway.shippedAnswers().forEach((connector, directive) -> {
            if (!"ledger.available".equals(connector)) {
                v.put("sim_" + connector.replace('.', '_').replace('-', '_'), directive);
            }
        });
        return v;
    }

    @Test
    void everyStartableProcessReachesAWaitState() {
        List<String> failures = new ArrayList<>();
        PackDriver driver = new PackDriver(engine);
        int started = 0;
        seedEmdCapture(PackTestTenant.ID);
        for (ProcessDefinition pd : engine.getRepositoryService().createProcessDefinitionQuery().latestVersion().list()) {
            var model = engine.getRepositoryService().getBpmnModelInstance(pd.getId());
            boolean noneStart = model.getModelElementsByType(StartEvent.class).stream()
                    .anyMatch(se -> se.getParentElement() instanceof org.operaton.bpm.model.bpmn.instance.Process
                            && se.getEventDefinitions().isEmpty());
            if (!noneStart || pd.getKey().startsWith("sangam-core-")) {
                continue; // core blocks are call activities with inputs from the calling pack
            }
            String bk = "SMOKE/" + pd.getKey();
            boolean batchItem = model.getModelElementsByType(org.operaton.bpm.model.bpmn.instance.ServiceTask.class)
                    .stream().anyMatch(t -> org.sangam.packs.core.BatchItemReleaseDelegate.class.getName()
                            .equals(t.getOperatonClass()));
            if (batchItem) {
                // owner decision E1: a batch item payment is started only by its parent's FAN_OUT under the batch
                // sanction (driven by the parent's own tests); started on its own it must be held, never paid
                try {
                    String piId = engine.getRuntimeService().startProcessInstanceByKey(pd.getKey(), bk, PackStartOffice.apply(pd.getKey(), bag()))
                            .getId();
                    driver.runJobs();
                    // no exception: the item waits before its release (e.g. a review); it must not have been released
                    long released = engine.getHistoryService().createHistoricActivityInstanceQuery()
                            .processInstanceId(piId).activityType("serviceTask").finished().list().stream()
                            .filter(a -> releaseTasks(model).contains(a.getActivityId())).count();
                    if (released > 0) {
                        failures.add(pd.getKey() + ": a batch item started without a batch sanction was released");
                    }
                } catch (RuntimeException e) {
                    if (!rootMessage(e).contains("carries no batch sanction")) {
                        failures.add(pd.getKey() + ": " + rootMessage(e));
                    }
                }
                continue;
            }
            try {
                Map<String, Object> vars = bag();
                vars.put("campaignId", "SMOKE/sangam-recruitment-campaign");
                engine.getRuntimeService().startProcessInstanceByKey(pd.getKey(), bk, PackStartOffice.apply(pd.getKey(), vars));
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

    private static java.util.Set<String> releaseTasks(org.operaton.bpm.model.bpmn.BpmnModelInstance model) {
        return model.getModelElementsByType(org.operaton.bpm.model.bpmn.instance.ServiceTask.class).stream()
                .filter(t -> org.sangam.packs.core.BatchItemReleaseDelegate.class.getName().equals(t.getOperatonClass()))
                .map(org.operaton.bpm.model.bpmn.instance.ServiceTask::getId)
                .collect(java.util.stream.Collectors.toSet());
    }

    /**
     * Owner decision M5: a refund to source needs the capture it returns to, recorded when the money came in. The EMD
     * of the bag (emdCaptureRef) was collected by e-procurement, outside these packs, so its capture record is the
     * test data a tender refund needs (amount: the bag's emdAmount, many times over for every tender run).
     */
    static void seedEmdCapture(String tenant) {
        PackRuntime.ledgers().post(tenant, org.sangam.packs.core.CaptureRefunds.LEDGER, "X-emdCaptureRef", "INR",
                org.sangam.packs.port.LedgerStore.amount(100_000L * 1000, "INR"), "capture", false);
    }

    static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getClass().getSimpleName() + ": " + t.getMessage();
    }
}
