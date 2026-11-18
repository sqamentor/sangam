// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.money;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;

import org.sangam.packs.PackTestEngines;
import org.sangam.packs.PackTestTenant;
import org.sangam.packs.port.PackRuntime;
import org.sangam.packs.port.TenantCurrencies;
import org.sangam.packs.sim.LoggingAuditSink;
import org.sangam.packs.sim.SimulatorConnectorGateway;

/**
 * Owner decisions E3, M1, M2: the money fields of a money-out step are bound before its approval, from the verified
 * party registry (never a typed name), the original capture (refund to source) or a statutory payee, with the currency
 * of the tenant profile first, then of the pack; anything missing holds the case.
 */
class MoneyFieldsDelegateTest {

    static ProcessEngine engine;
    static int seq;

    @BeforeAll
    static void setUp() {
        PackRuntime.reset();
        StandaloneInMemProcessEngineConfiguration cfg = new StandaloneInMemProcessEngineConfiguration();
        cfg.setJdbcUrl("jdbc:h2:mem:sangam-money-fields;DB_CLOSE_DELAY=-1");
        cfg.setDatabaseSchemaUpdate("create-drop");
        cfg.setJobExecutorActivate(false);
        PackTestEngines.configure(cfg);
        engine = cfg.buildProcessEngine();
        PackRuntime.install(PackRuntime.ports().withGateway(new SimulatorConnectorGateway())
                .withAudit(new LoggingAuditSink()));
    }

    @AfterEach
    void noTenantCurrency() {
        TenantCurrencies.install(null);
    }

    @AfterAll
    static void tearDown() {
        engine.close();
        engine = null;
        PackRuntime.reset();
    }

    /** A process whose approval task binds the money fields with {@code fields}; {@code packCurrency} null: no pack.json. */
    private static Map<String, Object> run(String fields, String packCurrency, Map<String, Object> vars) {
        String key = "t-money-fields-" + (++seq);
        String f = fields.replaceAll("(\\w+)=([^;]+);?",
                "<operaton:field name=\"$1\"><operaton:string>$2</operaton:string></operaton:field>");
        String bpmn = "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" "
                + "xmlns:operaton=\"http://operaton.org/schema/1.0/bpmn\" targetNamespace=\"t\">"
                + "<process id=\"" + key + "\" isExecutable=\"true\" operaton:historyTimeToLive=\"1\"><startEvent id=\"s\"/>"
                + "<userTask id=\"approve\"><extensionElements><operaton:executionListener event=\"start\" "
                + "class=\"org.sangam.packs.core.MoneyFieldsDelegate\">" + f + "</operaton:executionListener>"
                + "</extensionElements></userTask><endEvent id=\"e\"/>"
                + "<sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"approve\"/>"
                + "<sequenceFlow id=\"f2\" sourceRef=\"approve\" targetRef=\"e\"/></process></definitions>";
        var d = engine.getRepositoryService().createDeployment().addString(key + ".bpmn", bpmn)
                .tenantId(PackTestTenant.ID);
        if (packCurrency != null) {
            d.addString("pack.json", "{\"id\":\"t\",\"money\":{\"unit\":\"MINOR\",\"currency\":\"" + packCurrency
                    + "\",\"exponent\":2}}");
        }
        d.deploy();
        String id = engine.getRuntimeService().createProcessInstanceByKey(key).businessKey(key + "-1")
                .setVariables(vars).processDefinitionTenantId(PackTestTenant.ID).execute().getId();
        return engine.getRuntimeService().getVariables(id);
    }

    private static Map<String, Object> vars(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        m.put("materialFields", "employeeId");
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static String held(Runnable r) {
        RuntimeException e = assertThrows(RuntimeException.class, r::run);
        Throwable t = e;
        while (t.getCause() != null && !t.getMessage().contains("MONEY_OUT_HELD")) {
            t = t.getCause();
        }
        assertTrue(t.getMessage().contains("MONEY_OUT_HELD"), t.getMessage());
        return t.getMessage();
    }

    @Test
    void aBankTransferTakesTheVerifiedRegistryAccountAndThePackCurrency() {
        Map<String, Object> v = run("amount=fnfAmount;payeeKeys=employeeId;purpose=Final settlement", "INR",
                vars("employeeId", "E-7", "fnfAmount", 250_000L, "payee", "Typed Name", "account", "TYPED-ACC"));
        assertEquals(250_000L, ((Number) v.get("amount")).longValue());
        assertEquals("000123456789@SIMB0000001", v.get("payeeAccount"), "the registry account, not the typed one");
        assertEquals("SIM-PV-E-7", v.get("payeeVersionId"));
        assertEquals("INR", v.get("currency"));
        assertEquals("Final settlement", v.get("purpose"));
        assertEquals("employeeId,amount,payeeAccount,currency,purpose", v.get("materialFields"));
    }

    @Test
    void anAmountAboveItsCapOrWithoutTheCapIsHeld() {
        // e-mandate MAX type: the actual debit, never more than the registered maximum
        Map<String, Object> ok = run("amount=debitAmount;cap=amount;payeeKeys=payeePartyId;purpose=Mandate debit",
                "INR", vars("payeePartyId", "M-1", "debitAmount", 400L, "amount", 500L));
        assertEquals(400L, ((Number) ok.get("amount")).longValue());
        String over = held(() -> run("amount=debitAmount;cap=amount;payeeKeys=payeePartyId;purpose=Mandate debit",
                "INR", vars("payeePartyId", "M-1", "debitAmount", 600L, "amount", 500L)));
        assertTrue(over.contains("exceeds"), over);
        String noCap = held(() -> run("amount=debitAmount;cap=mandateMax;payeeKeys=payeePartyId;purpose=Mandate debit",
                "INR", vars("payeePartyId", "M-1", "debitAmount", 400L)));
        assertTrue(noCap.contains("mandateMax"), noCap);
    }

    @Test
    void aNameIsNeverAPayeeAccount() {
        String why = held(() -> run("payeeKeys=payeePartyId;purpose=Bill payment", "INR",
                vars("amount", 100L, "payee", "M/s Supplier", "account", "1234")));
        assertTrue(why.contains("a name is never a payee account"), why);
    }

    @Test
    void theTenantProfileCurrencyComesBeforeThePackCurrency() {
        TenantCurrencies.install(t -> "USD");
        Map<String, Object> v = run("payeeKeys=customerId;purpose=Disbursal", "INR", vars("customerId", "C-1",
                "amount", 100L));
        assertEquals("USD", v.get("currency"));
    }

    @Test
    void noTenantCurrencyAndNoPackCurrencyIsHeldNeverDefaulted() {
        String why = held(() -> run("payeeKeys=customerId;purpose=Disbursal", null, vars("customerId", "C-1",
                "amount", 100L)));
        assertTrue(why.contains("no currency"), why);
    }

    @Test
    void aRefundGoesToTheOriginalCaptureOrIsHeld() {
        Map<String, Object> v = run("amount=refundDue;captureVar=originalPaymentRef;purpose=Return refund", "INR",
                vars("refundDue", 5_000L, "originalPaymentRef", "PG-TXN-9"));
        assertEquals("capture:PG-TXN-9", v.get("payeeAccount"));
        String why = held(() -> run("amount=refundDue;captureVar=originalPaymentRef;purpose=Return refund", "INR",
                vars("refundDue", 5_000L)));
        assertTrue(why.contains("original capture"), why);
    }

    @Test
    void aStatutoryPayeeIsResolvedInTheRegistry() {
        Map<String, Object> v = run("amount=tcsAmount;statutoryParty=GSTN;purpose=TCS deposit", "INR",
                vars("tcsAmount", 1_000L));
        assertEquals("000123456789@SIMB0000001", v.get("payeeAccount"));
    }

    // ---- owner decision M5: a compensation refund released by its capture record ----

    private static Map<String, Object> captureThenRefund(String captureRef, long captured, long refund,
                                                         String refundCurrency) {
        String key = "t-capture-" + (++seq);
        String bpmn = "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" "
                + "xmlns:operaton=\"http://operaton.org/schema/1.0/bpmn\" targetNamespace=\"t\">"
                + "<process id=\"" + key + "\" isExecutable=\"true\" operaton:historyTimeToLive=\"1\"><startEvent id=\"s\"/>"
                + "<task id=\"charge\"><extensionElements><operaton:executionListener event=\"end\" "
                + "class=\"org.sangam.packs.core.CaptureRefunds$Record\"><operaton:field name=\"snapshotVar\">"
                + "<operaton:string>chargeCaptureRef</operaton:string></operaton:field></operaton:executionListener>"
                + "</extensionElements></task>"
                + "<task id=\"later\"><extensionElements><operaton:executionListener event=\"start\" "
                + "expression=\"${execution.setVariable('amount', " + refund + ")}\"/></extensionElements></task>"
                + "<userTask id=\"refund\"><extensionElements><operaton:executionListener event=\"start\" "
                + "class=\"org.sangam.packs.core.CaptureRefunds$Release\"><operaton:field name=\"captureVar\">"
                + "<operaton:string>chargeCaptureRef</operaton:string></operaton:field><operaton:field name=\"purpose\">"
                + "<operaton:string>Saga refund</operaton:string></operaton:field></operaton:executionListener>"
                + "</extensionElements></userTask><endEvent id=\"e\"/>"
                + "<sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"charge\"/>"
                + "<sequenceFlow id=\"f2\" sourceRef=\"charge\" targetRef=\"later\"/>"
                + "<sequenceFlow id=\"f3\" sourceRef=\"later\" targetRef=\"refund\"/>"
                + "<sequenceFlow id=\"f4\" sourceRef=\"refund\" targetRef=\"e\"/></process></definitions>";
        engine.getRepositoryService().createDeployment().addString(key + ".bpmn", bpmn)
                .addString("pack.json", "{\"id\":\"t\",\"money\":{\"currency\":\"INR\"}}")
                .tenantId(PackTestTenant.ID).deploy();
        Map<String, Object> v = vars("executionOutcome", "DONE", "opExternalRef", captureRef, "amount", captured);
        String id = engine.getRuntimeService().createProcessInstanceByKey(key).businessKey(key + "-1")
                .setVariables(v).processDefinitionTenantId(PackTestTenant.ID).execute().getId();
        return engine.getRuntimeService().getVariables(id);
    }

    @Test
    void aRefundOfAtMostTheCaptureToTheSameSourceOnceIsReleasedByTheCaptureRecord() {
        Map<String, Object> v = captureThenRefund("PG-CAP-1", 10_000L, 10_000L, null);
        assertEquals("capture-record", v.get("approvedBy"));
        assertEquals("capture:PG-CAP-1", v.get("payeeAccount"));
        assertEquals(null, org.sangam.packs.core.MoneyOutApproval.refusal("payment.refund", v::get),
                "the refund payment step accepts the release");
    }

    @Test
    void aRefundAboveTheCaptureIsHeldForAFreshSanction() {
        String why = held(() -> captureThenRefund("PG-CAP-2", 10_000L, 10_001L, null));
        assertTrue(why.contains("fresh sanction"), why);
    }

    @Test
    void aSecondDifferentRefundOnTheSameCaptureIsHeld() {
        captureThenRefund("PG-CAP-3", 10_000L, 4_000L, null);
        String why = held(() -> captureThenRefund("PG-CAP-3", 10_000L, 3_000L, null));
        assertTrue(why.contains("fresh sanction"), why);
    }

    @Test
    void aRefundInAnotherCurrencyThanTheCaptureIsHeld() {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        // the capture is recorded in the pack's INR; by the refund the tenant profile names USD
        TenantCurrencies.install(t -> calls.getAndIncrement() == 0 ? null : "USD");
        String why = held(() -> captureThenRefund("PG-CAP-4", 10_000L, 10_000L, null));
        assertTrue(why.contains("fresh sanction"), why);
    }

    @Test
    void aMissingZeroOrFractionalAmountIsHeld() {
        held(() -> run("payeeKeys=customerId;purpose=X", "INR", vars("customerId", "C-1")));
        held(() -> run("payeeKeys=customerId;purpose=X", "INR", vars("customerId", "C-1", "amount", 0L)));
        held(() -> run("payeeKeys=customerId;purpose=X", "INR", vars("customerId", "C-1",
                "amount", new java.math.BigDecimal("10.5"))));
    }
}
