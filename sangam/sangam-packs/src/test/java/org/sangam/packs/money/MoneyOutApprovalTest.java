// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.money;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;

import org.sangam.packs.PackTestEngines;
import org.sangam.packs.port.PackRuntime;
import org.sangam.packs.sim.LoggingAuditSink;
import org.sangam.packs.sim.SimulatorConnectorGateway;
import org.sangam.packs.spi.ConnectorMoneyMovements;
import org.sangam.packs.spi.MoneyMovement;
import org.sangam.packs.spi.MoneyPathGuard;
import org.sangam.packs.spi.PackDescriptor;
import org.sangam.packs.spi.PackRegistry;
import org.sangam.packs.spi.SangamPack;

/**
 * ARCH-JURIS I1/I2, owner decision A1: every money movement out needs a recorded approval; a DMN result only
 * recommends; no process variable switches the check off; deploy refuses a money-out path without an approval.
 * Before the fix {@code verifyApproval=false} let up14, DBT, land compensation, refunds and debits pay without an
 * approval (ExternalOperationDelegate.java:100), and nothing checked the models.
 */
class MoneyOutApprovalTest {

    static ProcessEngine engine;

    @BeforeAll
    static void setUp() {
        PackRuntime.reset();
        PackRegistry.discover(); // registers the declared money movements
        StandaloneInMemProcessEngineConfiguration cfg = new StandaloneInMemProcessEngineConfiguration();
        cfg.setJdbcUrl("jdbc:h2:mem:sangam-money-out;DB_CLOSE_DELAY=-1");
        cfg.setDatabaseSchemaUpdate("create-drop");
        cfg.setJobExecutorActivate(false);
        cfg.setHistory("full");
        PackTestEngines.configure(cfg);
        engine = cfg.buildProcessEngine();
        PackRuntime.install(PackRuntime.ports().withGateway(new SimulatorConnectorGateway())
                .withAudit(new LoggingAuditSink()));
    }

    @AfterAll
    static void tearDown() {
        engine.close();
        engine = null;
        PackRuntime.reset();
    }

    // ---- contract: moneyMovement on every connector ----

    @Test
    void aMissingOrUnknownMoneyMovementIsRefusedNeverDefaulted() {
        assertThrows(IllegalArgumentException.class, () -> MoneyMovement.parse("x.pay", null));
        assertThrows(IllegalArgumentException.class, () -> MoneyMovement.parse("x.pay", " "));
        assertThrows(IllegalArgumentException.class, () -> MoneyMovement.parse("x.pay", "OUTGOING"));
        assertEquals(MoneyMovement.IN, MoneyMovement.parse("x.fee", "IN"));
    }

    @Test
    void everyShippedConnectorDeclaresItsMoneyMovement() {
        List<String> missing = new ArrayList<>();
        for (SangamPack p : PackRegistry.discover().all()) {
            for (PackDescriptor.ConnectorUse u : p.descriptor().connectors()) {
                if (u.moneyMovement() == null) {
                    missing.add(p.descriptor().id() + ":" + u.id());
                }
            }
        }
        assertEquals(List.of(), missing);
        assertEquals(MoneyMovement.OUT, ConnectorMoneyMovements.enforced("pfms.payment"));
        assertEquals(MoneyMovement.OUT, ConnectorMoneyMovements.enforced("pg.refund"), "a refund is OUT");
        assertEquals(MoneyMovement.IN, ConnectorMoneyMovements.enforced("pg.collect"));
        assertEquals(MoneyMovement.IN, ConnectorMoneyMovements.enforced("paygov.fee"));
        assertEquals(MoneyMovement.OUT, ConnectorMoneyMovements.enforced("never.declared"), "undeclared is stricter");
    }

    @Test
    void oneConnectorWithTwoDirectionsIsAConflictNotALastWins() {
        PackDescriptor a = descriptor("t.conflict-a", new PackDescriptor.ConnectorUse("t.conflict.pay", "c", "", "",
                null, MoneyMovement.OUT));
        PackDescriptor b = descriptor("t.conflict-b", new PackDescriptor.ConnectorUse("t.conflict.pay", "c", "", "",
                null, MoneyMovement.NONE));
        ConnectorMoneyMovements.registerDeclared(a);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> ConnectorMoneyMovements.registerDeclared(b));
        assertTrue(e.getMessage().contains("t.conflict-a"), e.getMessage());
    }

    // ---- I2: deploy guard ----

    private static final String HEAD = "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" "
            + "xmlns:operaton=\"http://operaton.org/schema/1.0/bpmn\" targetNamespace=\"t\">"
            + "<process id=\"p\" isExecutable=\"true\" operaton:historyTimeToLive=\"P1D\">" + MoneyOutApprovalTest.MONEY_FIELDS;

    /** E3: a money-out process declares amount, payee account, currency and purpose as material fields. */
    private static final String MONEY_FIELDS = "<extensionElements><operaton:executionListener event=\"start\" "
            + "class=\"org.sangam.packs.core.PackStartListener\"><operaton:field name=\"materialFields\">"
            + "<operaton:string>amount,payeeAccount,currency,purpose</operaton:string></operaton:field>"
            + "</operaton:executionListener></extensionElements>";

    @Test
    void aMoneyOutProcessWithoutTheMoneyFieldsIsRefusedAtDeploy() {
        String body = "<startEvent id=\"s\"/>" + APPROVE + pay("pfms.payment") + "<endEvent id=\"e\"/>" + flow("f1", "s", "approve")
                + flow("f2", "approve", "pay") + flow("f3", "pay", "e");
        List<String> v = MoneyPathGuard.check("t.bpmn", (HEAD.replace(MONEY_FIELDS, "") + body + "</process></definitions>")
                .getBytes(StandardCharsets.UTF_8), ConnectorMoneyMovements::enforced, Map.of());
        assertEquals(1, v.size(), v.toString());
        assertTrue(v.get(0).contains("E3") && v.get(0).contains("payeeAccount"), v.toString());
    }

    private static String pay(String connector) {
        return "<serviceTask id=\"pay\" operaton:class=\"org.sangam.packs.core.ConnectorCallDelegate\"><extensionElements>"
                + "<operaton:field name=\"connector\"><operaton:string>" + connector + "</operaton:string></operaton:field>"
                + "<operaton:field name=\"mode\"><operaton:string>submit</operaton:string></operaton:field>"
                + "</extensionElements></serviceTask>";
    }

    private static final String DMN =
            "<businessRuleTask id=\"dmn\" operaton:class=\"org.sangam.packs.core.DecisionDelegate\"/>";
    private static final String APPROVE = "<userTask id=\"approve\"><extensionElements><operaton:taskListener "
            + "event=\"complete\" class=\"org.sangam.packs.core.ApprovalBindingListener\"/></extensionElements></userTask>";

    private static String flow(String id, String from, String to) {
        return "<sequenceFlow id=\"" + id + "\" sourceRef=\"" + from + "\" targetRef=\"" + to + "\"/>";
    }

    private static List<String> guard(String body) {
        return MoneyPathGuard.check("t.bpmn", (HEAD + body + "</process></definitions>")
                .getBytes(StandardCharsets.UTF_8), ConnectorMoneyMovements::enforced, Map.of());
    }

    @Test
    void aDmnStraightToAPaymentIsRefusedAtDeploy() {
        List<String> v = guard("<startEvent id=\"s\"/>" + DMN + pay("pfms.payment") + "<endEvent id=\"e\"/>"
                + flow("f1", "s", "dmn") + flow("f2", "dmn", "pay") + flow("f3", "pay", "e"));
        assertEquals(1, v.size(), v.toString());
        assertTrue(v.get(0).contains("pfms.payment"), v.toString());
    }

    @Test
    void anApprovalBetweenTheDmnAndThePaymentPasses() {
        assertEquals(List.of(), guard("<startEvent id=\"s\"/>" + DMN + APPROVE + pay("pfms.payment")
                + "<endEvent id=\"e\"/>" + flow("f1", "s", "dmn") + flow("f2", "dmn", "approve")
                + flow("f3", "approve", "pay") + flow("f4", "pay", "e")));
    }

    @Test
    void aFeeCollectionNeedsNoApproval() {
        assertEquals(List.of(), guard("<startEvent id=\"s\"/>" + pay("pg.collect") + "<endEvent id=\"e\"/>"
                + flow("f1", "s", "pay") + flow("f2", "pay", "e")));
    }

    @Test
    void aGatewayBypassAroundTheApprovalIsRefused() {
        List<String> v = guard("<startEvent id=\"s\"/>" + DMN + "<exclusiveGateway id=\"g\"/>" + APPROVE
                + pay("pg.refund") + "<endEvent id=\"e\"/>" + flow("f1", "s", "dmn") + flow("f2", "dmn", "g")
                + flow("f3", "g", "approve") + flow("f4", "g", "pay") + flow("f5", "approve", "pay")
                + flow("f6", "pay", "e"));
        assertEquals(1, v.size(), v.toString());
    }

    @Test
    void aSecondPaymentOnOneSanctionIsRefused() {
        // instalment loop / reissue: approve once, pay, loop back to the same payment
        List<String> v = guard("<startEvent id=\"s\"/>" + APPROVE + pay("pfms.dbt") + "<exclusiveGateway id=\"g\"/>"
                + "<endEvent id=\"e\"/>" + flow("f1", "s", "approve") + flow("f2", "approve", "pay")
                + flow("f3", "pay", "g") + flow("f4", "g", "pay") + flow("f5", "g", "e"));
        assertEquals(1, v.size(), v.toString());
        assertTrue(v.get(0).contains("reaches pay"), v.toString());
    }

    @Test
    void aFreshSanctionBeforeEveryPaymentInALoopPasses() {
        assertEquals(List.of(), guard("<startEvent id=\"s\"/>" + APPROVE + pay("pfms.dbt")
                + "<exclusiveGateway id=\"g\"/><endEvent id=\"e\"/>" + flow("f1", "s", "approve")
                + flow("f2", "approve", "pay") + flow("f3", "pay", "g") + flow("f4", "g", "approve")
                + flow("f5", "g", "e")));
    }

    @Test
    void aPaymentInsideASubProcessIsSeen() {
        List<String> v = guard("<startEvent id=\"s\"/><subProcess id=\"sp\"><startEvent id=\"ss\"/>" + pay("bank.transfer")
                + "<endEvent id=\"se\"/>" + flow("g1", "ss", "pay") + flow("g2", "pay", "se") + "</subProcess>"
                + "<endEvent id=\"e\"/>" + flow("f1", "s", "sp") + flow("f2", "sp", "e"));
        assertEquals(1, v.size(), v.toString());
    }

    @Test
    void everyShippedPackPassesTheMoneyPathGuard() {
        List<String> all = new ArrayList<>();
        for (SangamPack p : PackRegistry.discover().all()) {
            Map<String, String> topics = new HashMap<>();
            p.descriptor().workers().forEach(w -> topics.put(w.topic(), w.connector()));
            for (PackDescriptor.PackResource r : p.descriptor().resources()) {
                if (r.kind() == PackDescriptor.PackResource.Kind.BPMN) {
                    all.addAll(MoneyPathGuard.check(r.path(), read(p, r.path()), ConnectorMoneyMovements::enforced,
                            topics));
                }
            }
        }
        assertEquals(List.of(), all, all.size() + " money-out paths without approval");
    }

    // ---- I1: runtime, no variable switches the approval check off for OUT ----

    @Test
    void verifyApprovalFalseNoLongerLetsAPaymentThroughWithoutApproval() {
        String bpmn = HEAD.replace("id=\"p\"", "id=\"t-money-out\"")
                + "<startEvent id=\"s\"/><serviceTask id=\"prep\" "
                + "operaton:class=\"org.sangam.packs.core.ExternalOperationDelegate\"><extensionElements>"
                + "<operaton:field name=\"op\"><operaton:string>PREPARE</operaton:string></operaton:field>"
                + "<operaton:field name=\"connector\"><operaton:string>pfms.payment</operaton:string></operaton:field>"
                + "<operaton:field name=\"verifyApproval\"><operaton:expression>${verifyApproval}</operaton:expression>"
                + "</operaton:field></extensionElements></serviceTask>"
                + "<boundaryEvent id=\"refused\" attachedToRef=\"prep\"><errorEventDefinition errorRef=\"err\"/>"
                + "</boundaryEvent><userTask id=\"held\"/><endEvent id=\"paid\"/><endEvent id=\"e2\"/>"
                + flow("f1", "s", "prep") + flow("f2", "prep", "paid") + flow("f3", "refused", "held")
                + flow("f4", "held", "e2")
                + "</process><error id=\"err\" errorCode=\"APPROVAL_INVALIDATED\"/></definitions>";
        engine.getRepositoryService().createDeployment().addString("t-money-out.bpmn", bpmn)
                .tenantId(org.sangam.packs.PackTestTenant.ID).deploy();
        Map<String, Object> v = new HashMap<>();
        v.put("verifyApproval", false);
        v.put("amount", 100);
        String id = engine.getRuntimeService().createProcessInstanceByKey("t-money-out").businessKey("MO-1")
                .setVariables(v).processDefinitionTenantId(org.sangam.packs.PackTestTenant.ID).execute().getId();
        assertNotNull(engine.getTaskService().createTaskQuery().processInstanceId(id).taskDefinitionKey("held")
                .singleResult(), "an OUT connector without a recorded approval is held with APPROVAL_INVALIDATED");
    }

    @Test
    void aRefundInACompensationHandlerIsNotABypass() {
        // before the fix the guard walked sequence flows only, so a handler reached by an association was never seen
        String handler = pay("pg.refund").replace("<serviceTask id=\"pay\"", "<serviceTask id=\"pay\" isForCompensation=\"true\"");
        List<String> v = guard("<startEvent id=\"s\"/><serviceTask id=\"charge\"/>"
                + "<boundaryEvent id=\"cb\" attachedToRef=\"charge\"><compensateEventDefinition/></boundaryEvent>" + handler
                + "<endEvent id=\"e\"/>" + flow("f1", "s", "charge") + flow("f2", "charge", "e")
                + "<association id=\"a1\" sourceRef=\"cb\" targetRef=\"pay\"/>");
        assertEquals(1, v.size(), v.toString());
    }

    // ---- a held payment stops as an incident, never a quiet end ----

    /** Stands for the earlier form: a refused payment raised as a BPMN error that nothing catches. */
    public static class ThrowsBpmnError implements org.operaton.bpm.engine.delegate.JavaDelegate {
        @Override
        public void execute(org.operaton.bpm.engine.delegate.DelegateExecution ex) {
            throw new org.operaton.bpm.engine.delegate.BpmnError("APPROVAL_INVALIDATED", "no approval");
        }
    }

    private static String asyncPay(String processId, String cls, String connectorField) {
        return HEAD.replace("id=\"p\"", "id=\"" + processId + "\"") + "<startEvent id=\"s\"/>"
                + "<serviceTask id=\"pay\" operaton:asyncBefore=\"true\" operaton:class=\"" + cls + "\">"
                + connectorField + "</serviceTask><endEvent id=\"e\"/>" + flow("f1", "s", "pay") + flow("f2", "pay", "e")
                + "</process></definitions>";
    }

    private static String runPayJob(String processId, String bpmn) {
        engine.getRepositoryService().createDeployment().addString(processId + ".bpmn", bpmn)
                .tenantId(org.sangam.packs.PackTestTenant.ID).deploy();
        Map<String, Object> v = new HashMap<>();
        v.put("amount", 100);
        String id = engine.getRuntimeService().createProcessInstanceByKey(processId).businessKey(processId + "-1")
                .setVariables(v).processDefinitionTenantId(org.sangam.packs.PackTestTenant.ID).execute().getId();
        var job = engine.getManagementService().createJobQuery().processInstanceId(id).singleResult();
        engine.getManagementService().setJobRetries(job.getId(), 1);
        try {
            engine.getManagementService().executeJob(job.getId());
        } catch (RuntimeException expected) {
            // the held payment fails its job
        }
        return id;
    }

    @Test
    void anUncaughtBpmnErrorEndsTheCaseQuietlyWhichIsWhyAHeldPaymentIsNoLongerOne() {
        String id = runPayJob("t-old-bpmn-error", asyncPay("t-old-bpmn-error", ThrowsBpmnError.class.getName(), ""));
        assertEquals(null, engine.getRuntimeService().createProcessInstanceQuery().processInstanceId(id).singleResult(),
                "the old form: the case is gone");
        assertEquals(0, engine.getRuntimeService().createIncidentQuery().processInstanceId(id).count(),
                "and nothing records why");
    }

    @Test
    void aHeldPaymentStaysOpenAsAnIncidentWithItsReason() {
        String field = "<extensionElements><operaton:field name=\"connector\"><operaton:string>pfms.payment</operaton:string>"
                + "</operaton:field><operaton:field name=\"mode\"><operaton:string>submit</operaton:string></operaton:field>"
                + "</extensionElements>";
        String id = runPayJob("t-held", asyncPay("t-held", "org.sangam.packs.core.ConnectorCallDelegate", field));
        assertNotNull(engine.getRuntimeService().createProcessInstanceQuery().processInstanceId(id).singleResult(),
                "the case stays open");
        var incident = engine.getRuntimeService().createIncidentQuery().processInstanceId(id).singleResult();
        assertNotNull(incident, "an incident holds the payment");
        assertTrue(incident.getIncidentMessage().contains("MONEY_OUT_HELD"), incident.getIncidentMessage());
    }

    @Test
    void anE3RefusalInCoreExecutionPrepareIsAnIncidentNotAQuietEnd() {
        // before the fix ExternalOperationDelegate raised the E2/E3 refusal as a BPMN error; core execution has no
        // catch for it, so a caller without a boundary (ifms-bill bl_pay) went on without executionOutcome
        String field = "<extensionElements><operaton:field name=\"op\"><operaton:string>PREPARE</operaton:string>"
                + "</operaton:field><operaton:field name=\"connector\"><operaton:string>pfms.payment</operaton:string>"
                + "</operaton:field></extensionElements>";
        String bpmn = asyncPay("t-e3-prepare", "org.sangam.packs.core.ExternalOperationDelegate", field);
        engine.getRepositoryService().createDeployment().addString("t-e3-prepare.bpmn", bpmn)
                .tenantId(org.sangam.packs.PackTestTenant.ID).deploy();
        Map<String, Object> v = new HashMap<>();
        v.put("amount", 100);
        v.put("materialFields", "amount"); // approved, but without payeeAccount, currency, purpose (E3)
        v.put("approvedPayloadHash", org.sangam.packs.core.Vars.hash(
                new java.util.LinkedHashMap<>(Map.of("amount", org.sangam.packs.core.Vars.canonical(100)))));
        v.put("approvedBy", "pao-1");
        String id = engine.getRuntimeService().createProcessInstanceByKey("t-e3-prepare").businessKey("E3-1")
                .setVariables(v).processDefinitionTenantId(org.sangam.packs.PackTestTenant.ID).execute().getId();
        var job = engine.getManagementService().createJobQuery().processInstanceId(id).singleResult();
        engine.getManagementService().setJobRetries(job.getId(), 1);
        try {
            engine.getManagementService().executeJob(job.getId());
        } catch (RuntimeException expected) {
            // the held payment fails its job
        }
        assertNotNull(engine.getRuntimeService().createProcessInstanceQuery().processInstanceId(id).singleResult(),
                "the case stays open");
        var incident = engine.getRuntimeService().createIncidentQuery().processInstanceId(id).singleResult();
        assertNotNull(incident, "an incident holds the payment");
        assertTrue(incident.getIncidentMessage().contains("MONEY_OUT_HELD"), incident.getIncidentMessage());
    }

    // ---- E2 / E3 on the shared rule ----

    private static Map<String, Object> approvedCase(String approvedBy) {
        Map<String, Object> v = new HashMap<>();
        v.put("materialFields", "amount,payeeAccount,currency,purpose");
        v.put("amount", 100);
        v.put("payeeAccount", "ACC-1");
        v.put("currency", "INR");
        v.put("purpose", "Refund");
        Map<String, Object> material = new java.util.LinkedHashMap<>();
        for (String f : List.of("amount", "payeeAccount", "currency", "purpose")) {
            material.put(f, org.sangam.packs.core.Vars.canonical(v.get(f)));
        }
        v.put("approvedPayloadHash", org.sangam.packs.core.Vars.hash(material));
        v.put("approvedBy", approvedBy);
        return v;
    }

    @Test
    void aHumanApprovalOfACompletePayloadReleasesMoneyOut() {
        assertEquals(null, org.sangam.packs.core.MoneyOutApproval.refusal("pfms.payment", approvedCase("p.officer")::get));
    }

    @Test
    void aDeemedApprovalNeverReleasesMoneyOut() {
        String r = org.sangam.packs.core.MoneyOutApproval.refusal("pfms.payment", approvedCase("system")::get);
        assertTrue(r != null && r.contains("deemed"), String.valueOf(r));
        assertEquals(null, org.sangam.packs.core.MoneyOutApproval.refusal("pg.collect", approvedCase("system")::get),
                "money in is not held by the deemed rule");
    }

    @Test
    void moneyOutWithoutAPayeeAccountOrPurposeIsRefused() {
        Map<String, Object> v = approvedCase("p.officer");
        v.put("purpose", " ");
        v.put("materialFields", "amount,currency,purpose");
        String r = org.sangam.packs.core.MoneyOutApproval.refusal("pfms.payment", v::get);
        assertTrue(r != null && r.contains("payeeAccount") && r.contains("purpose"), String.valueOf(r));
    }

    @Test
    void aPaymentWhosePayeeChangedAfterApprovalIsRefused() {
        Map<String, Object> v = approvedCase("p.officer");
        v.put("payeeAccount", "ACC-2");
        String r = org.sangam.packs.core.MoneyOutApproval.refusal("pfms.payment", v::get);
        assertTrue(r != null && r.contains("changed"), String.valueOf(r));
    }

    private static PackDescriptor descriptor(String id, PackDescriptor.ConnectorUse u) {
        return new PackDescriptor(id, "1.0.0", Map.of("en", id), "test", "", List.of(), List.of(), List.of(),
                List.of(), List.of(u), List.of(), List.of(), List.of(), Map.of());
    }

    private static byte[] read(SangamPack p, String path) {
        try (InputStream in = p.open(path)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
