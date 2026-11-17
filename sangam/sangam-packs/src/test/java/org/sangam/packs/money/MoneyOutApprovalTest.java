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
import org.sangam.packs.spi.PackResource;
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
            + "<process id=\"p\" isExecutable=\"true\" operaton:historyTimeToLive=\"P1D\">";

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
            for (PackResource r : p.descriptor().resources()) {
                if (r.kind() == PackResource.Kind.BPMN) {
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
        engine.getRepositoryService().createDeployment().addString("t-money-out.bpmn", bpmn).deploy();
        Map<String, Object> v = new HashMap<>();
        v.put("verifyApproval", false);
        v.put("amount", 100);
        String id = engine.getRuntimeService().startProcessInstanceByKey("t-money-out", "MO-1", v).getId();
        assertNotNull(engine.getTaskService().createTaskQuery().processInstanceId(id).taskDefinitionKey("held")
                .singleResult(), "an OUT connector without a recorded approval is held with APPROVAL_INVALIDATED");
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
