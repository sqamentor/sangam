// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.operaton.bpm.engine.task.Task;

import org.sangam.packs.port.ConnectorGateway;
import org.sangam.packs.port.ConnectorRequest;
import org.sangam.packs.port.ConnectorResult;
import org.sangam.packs.port.OperationStatus;
import org.sangam.packs.port.PackAuditEvent;
import org.sangam.packs.port.PackRuntime;
import org.sangam.packs.port.VerifiedPayeeLookup;
import org.sangam.packs.port.VerifiedPayees;
import org.sangam.packs.sim.DemoSandbox;
import org.sangam.packs.sim.LoggingAuditSink;
import org.sangam.packs.sim.SimulatorConnectorGateway;
import org.sangam.packs.spi.PackDeployer;
import org.sangam.packs.spi.PackRegistry;

/**
 * Owner option (b) 2026-11-19, {@code sangam.demo.sandbox-tenants}. Before the fix a production installation had no
 * per-tenant sandbox: with no verified-payee adapter every bill of every tenant stopped at bl_payeeHold (VerifiedPayees
 * answered NONE in production) and nothing could be shown paid. Now a listed tenant's bill is verified by the
 * simulator and paid through the sandbox payment simulator, with sandbox=true on its audit events, while production
 * mode stays on and every other tenant behaves exactly as before.
 */
class DemoSandboxTenantsTest {

    static final String BILL = "sangam-ifms-bill";
    static ProcessEngine engine;
    static PackDriver driver;
    LoggingAuditSink audit;
    RealRail real;

    /** The installed (real) gateway: a simulator for every query, but a payment reaching it is counted and refused. */
    static final class RealRail extends SimulatorConnectorGateway {
        final AtomicInteger payments = new AtomicInteger();

        @Override
        public ConnectorResult submit(ConnectorRequest r) {
            if (DemoSandbox.PAYMENT_CONNECTORS.contains(r.connector())) {
                payments.incrementAndGet();
                return ConnectorResult.failed("REAL_RAIL_CALLED", "the real payment rail was called");
            }
            return super.submit(r);
        }
    }

    @BeforeAll
    static void setUp() {
        PackRuntime.reset();
        StandaloneInMemProcessEngineConfiguration cfg = new StandaloneInMemProcessEngineConfiguration();
        cfg.setJdbcUrl("jdbc:h2:mem:sangam-demo-sandbox;DB_CLOSE_DELAY=-1");
        cfg.setDatabaseSchemaUpdate("create-drop");
        cfg.setJobExecutorActivate(false);
        cfg.setHistory("full");
        PackTestEngines.configure(cfg);
        engine = cfg.buildProcessEngine();
        new PackDeployer(engine, PackRegistry.discover()).deployAll(PackTestTenant.ID);
        driver = new PackDriver(engine);
    }

    @AfterAll
    static void tearDown() {
        engine.close();
        engine = null;
        driver = null;
        PackRuntime.reset();
        DemoSandbox.install(null);
    }

    @AfterEach
    void reset() {
        DemoSandbox.install(null);
        VerifiedPayees.install(null);
        PackRuntime.reset();
        driver.resetClock();
    }

    /** Production mode, no verified-payee adapter (production default NONE), the real rail installed. */
    private void production() {
        audit = new LoggingAuditSink();
        real = new RealRail();
        driver.resetClock();
        PackRuntime.install(PackRuntime.ports().withGateway(real).withAudit(audit).withClock(PackDriver.clock())
                .withProduction(true));
    }

    private void bill(String bk) {
        Map<String, Object> v = AllPacksSmokeTest.bag();
        v.put("amount", 1_500_000L);
        v.put("sim_ledger_available", "available=100000000");
        v.put("employeeId", "EMP-" + bk.replace('/', '-'));
        v.put("partyId", "PARTY-" + bk);
        engine.getRuntimeService().createProcessInstanceByKey(BILL).businessKey(bk).setVariables(v)
                .processDefinitionTenantId(PackTestTenant.ID).execute();
        FormAnswers answers = new FormAnswers(engine);
        driver.run(bk, t -> "bl_payeeHold".equals(t.getTaskDefinitionKey()) || "bl_payCorrect".equals(
                t.getTaskDefinitionKey()) ? null : answers.answer(t), 150);
    }

    private List<String> open(String bk) {
        return engine.getTaskService().createTaskQuery().processInstanceBusinessKey(bk).list().stream()
                .map(Task::getTaskDefinitionKey).toList();
    }

    private String where(String bk) {
        StringBuilder b = new StringBuilder();
        engine.getRuntimeService().createProcessInstanceQuery().processInstanceBusinessKey(bk).list().forEach(pi -> {
            b.append(engine.getRuntimeService().getActiveActivityIds(pi.getId()));
            engine.getRuntimeService().createIncidentQuery().processInstanceId(pi.getId()).list()
                    .forEach(i -> b.append(" incident ").append(i.getActivityId()).append(": ")
                            .append(i.getIncidentMessage()));
        });
        engine.getManagementService().createJobQuery().list().forEach(j -> b.append(" job ").append(j.getId())
                .append(" due ").append(j.getDuedate()).append(" ex ").append(j.getExceptionMessage()));
        return b.toString();
    }

    private List<PackAuditEvent> events(String bk, String type) {
        return audit.timeline(bk).stream().filter(e -> type.equals(e.type())).toList();
    }

    @Test
    void theSettingIsEmptyByDefault() {
        assertTrue(DemoSandbox.tenants().isEmpty());
        assertFalse(DemoSandbox.active());
        ConnectorGateway g = new SimulatorConnectorGateway();
        assertSame(g, DemoSandbox.routing(g), "no sandbox tenant: the installed gateway is used unchanged");
    }

    @Test
    void aListedTenantsBillPassesThePayeeHoldAndIsPaidThroughTheSimulator() {
        DemoSandbox.install(List.of(PackTestTenant.ID));
        production();
        bill("SB/1");
        assertTrue(PackRuntime.production(), "production mode stays on");
        assertEquals(Boolean.TRUE, driver.var("SB/1", "payeeVerified"), "open: " + open("SB/1"));
        assertEquals("SIM-PV-EMP-SB-1", driver.var("SB/1", "payeeVersionId"));
        assertFalse(open("SB/1").contains("bl_payeeHold"));
        assertTrue(driver.ended("SB/1"), "paid and closed: " + open("SB/1") + " at " + where("SB/1"));
        assertEquals(0, real.payments.get(), "the real payment rail is never called for a sandbox tenant");
        assertEquals(Boolean.TRUE, driver.var("SB/1", "op_sandbox"), "the payment result is the sandbox's");
        List<PackAuditEvent> payee = events("SB/1", "PAYEE_VERSION_RECORDED");
        assertFalse(payee.isEmpty());
        payee.forEach(e -> assertEquals(true, e.details().get(DemoSandbox.AUDIT_KEY), e.toString()));
        List<PackAuditEvent> paid = events("SB/1", "EXT_OP_ACKNOWLEDGED").stream()
                .filter(e -> DemoSandbox.PAYMENT_CONNECTORS.contains(String.valueOf(e.details().get("connector"))))
                .toList();
        assertEquals(1, paid.size(), "one sandbox payment: " + audit.timeline("SB/1"));
        List<PackAuditEvent> reconciled = events("SB/1", "CASE_RECONCILED");
        assertTrue(reconciled.stream().anyMatch(e -> "bank.statement".equals(e.details().get("connector"))),
                "matched against the sandbox statement: " + reconciled);
        reconciled.forEach(e -> assertEquals(true, e.details().get(DemoSandbox.AUDIT_KEY), e.toString()));
        assertEquals(true, paid.get(0).details().get(DemoSandbox.AUDIT_KEY));
    }

    @Test
    void anUnlistedTenantStillStopsAtThePayeeHold() {
        DemoSandbox.install(List.of("t-some-other-tenant"));
        production();
        bill("SB/2");
        assertEquals(List.of("bl_payeeHold"), open("SB/2"));
        assertEquals("PAYEE_NOT_VERIFIED", driver.var("SB/2", "payeeNotVerifiedReason"));
        assertEquals(0, real.payments.get());
        audit.timeline("SB/2").forEach(e -> assertNull(e.details().get(DemoSandbox.AUDIT_KEY), e.toString()));
    }

    @Test
    void theSimulatorsRefuseEveryOtherTenantWhenCalledDirectly() {
        DemoSandbox.install(Set.of("t-demo"));
        ConnectorRequest pay = new ConnectorRequest("t-real", "pfms.payment", "execute", "op-1", "BK/1", "pi-1",
                Map.of("amount", "10.00"), "h");
        ConnectorResult r = DemoSandbox.PAYMENTS.submit(pay);
        assertEquals(OperationStatus.FAILED, r.status());
        assertEquals(DemoSandbox.REFUSED, r.reasonCode());
        assertEquals(DemoSandbox.REFUSED, DemoSandbox.PAYMENTS.query(pay).reasonCode());
        assertEquals(DemoSandbox.REFUSED, DemoSandbox.PAYMENTS.lookup("t-real", "pfms.payment", "op-1").reasonCode());
        assertEquals(DemoSandbox.REFUSED, DemoSandbox.PAYMENTS.cancel("t-real", "pfms.payment", "op-1", "x")
                .reasonCode());
        assertThrows(IllegalStateException.class, () -> DemoSandbox.PAYMENTS.openReconciliation(
                new org.sangam.packs.port.ReconciliationRequest("t-real", "pfms.payment", "op-1", "BK/1", "pi-1",
                        "UNKNOWN_OUTCOME", Map.of())));
        String m = assertThrows(IllegalStateException.class,
                () -> DemoSandbox.PAYEES.resolve("t-real", "EMP-1", null, LocalDate.of(2026, 11, 19))).getMessage();
        assertTrue(m.startsWith(DemoSandbox.REFUSED), m);
        assertThrows(IllegalStateException.class,
                () -> DemoSandbox.PAYEES.resolve(null, "EMP-1", null, LocalDate.of(2026, 11, 19)));
        // the listed tenant is answered
        assertEquals(OperationStatus.COMPLETED, DemoSandbox.PAYMENTS.submit(new ConnectorRequest("t-demo",
                "pfms.payment", "execute", "op-2", "BK/2", "pi-2", Map.of("amount", "10.00"), "h2")).status());
        assertTrue(DemoSandbox.PAYEES.resolve("t-demo", "EMP-1", null, LocalDate.of(2026, 11, 19))
                instanceof VerifiedPayeeLookup.Verified);
    }

    @Test
    void anUnlistedTenantsPaymentAndPayeeGoToTheInstalledPortsUnchanged() {
        DemoSandbox.install(Set.of("t-demo"));
        production();
        ConnectorRequest pay = new ConnectorRequest("t-real", "pfms.payment", "execute", "op-3", "BK/3", "pi-3",
                Map.of("amount", "10.00"), "h3");
        assertEquals("REAL_RAIL_CALLED", PackRuntime.gateway().submit(pay).reasonCode());
        assertEquals(1, real.payments.get());
        assertEquals(VerifiedPayeeLookup.Reason.PAYEE_NOT_VERIFIED, ((VerifiedPayeeLookup.NotVerified)
                VerifiedPayees.resolve("t-real", "EMP-1", null, LocalDate.of(2026, 11, 19))).reason());
        // a sandbox tenant's non-payment connector also stays on the installed gateway
        ConnectorRequest q = new ConnectorRequest("t-demo", "ledger.available", "query", null, "BK/4", "pi-4",
                Map.of(), "h4");
        assertEquals(OperationStatus.COMPLETED, PackRuntime.gateway().query(q).status());
        assertNull(PackRuntime.gateway().query(q).data().get(DemoSandbox.AUDIT_KEY));
    }

    @Test
    void blankTenantIdsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> DemoSandbox.install(java.util.Arrays.asList("t-a", " ")));
        assertTrue(DemoSandbox.tenants().isEmpty());
    }
}
