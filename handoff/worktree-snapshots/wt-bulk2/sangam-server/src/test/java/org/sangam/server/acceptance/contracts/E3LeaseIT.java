// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.acceptance.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.sangam.core.api.EngineAdapter.ExternalWork;
import org.sangam.core.api.StaleLeaseException;
import org.sangam.core.engine.SangamEngineAdapter;
import org.sangam.core.engine.SangamEngineAdapter.LeasedWork;
import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.CapabilityClass;
import org.sangam.core.layer.OperationLedger;
import org.sangam.core.layer.ReconciliationQueue;
import org.sangam.core.layer.Tombstones;
import org.sangam.integration.sim.Fault;
import org.sangam.integration.sim.ifms.IfmsJitSimulator;
import org.sangam.server.acceptance.contracts.PaymentWorker.Outcome;
import org.sangam.server.acceptance.contracts.PaymentWorker.Run;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * E3 (SANGAM.md XI.4, VI.3, A-1, A-2, A-8, A-10, T35) on Sangam: worker lease + operationId + unknown outcome on a
 * payment external task, against sangam-integration's IFMS JIT simulator. Leases run on the engine clock, which the
 * test moves through the adapter ({@code setClock}). Lease expiry is decided by the server's A-10 expiry policy inside
 * the adapter (Stage B ran a product-side sweep instead; finding F-4 led to A-10).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class E3LeaseIT extends ContractSupport {

    static final Duration LEASE = Duration.ofSeconds(60);
    final String T = REST_TENANT;

    @Autowired
    OperationLedger ledger;

    @Autowired
    ReconciliationQueue reconciliation;

    SangamEngineAdapter adapter;
    Tombstones tombstones;
    IfmsJitSimulator sim;
    Ifms ifms;
    Instant clock;
    UUID controlOperation;

    @BeforeAll
    void setUp() {
        adapter = (SangamEngineAdapter) engine;
        tombstones = new Tombstones(dialect(), reconciliation, new AuditChain(dialect()));
        sim = new IfmsJitSimulator().start();
        ifms = new Ifms(sim);
        clock = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        engine.setClock(clock);
    }

    @AfterAll
    void tearDown() {
        engine.setClock(null);
        if (sim != null) {
            sim.close();
        }
    }

    private void advance(Duration d) {
        clock = clock.plus(d);
        engine.setClock(clock);
    }

    /** A payment model of its own per scenario (own topic), one instance started, waiting in the external task. */
    private String toPayment(String scenario, String topic) {
        String key = key("e3-" + scenario);
        deploy(T, key + ".bpmn", Models.payment(key, topic));
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("billId", key("bill-" + scenario));
        v.put("amount", 50_000L);
        v.put("beneficiaryAccount", "ACC-" + scenario);
        return engine.startProcess(T, key, key("bk-" + scenario), v);
    }

    private PaymentWorker worker(String id, String topic, CapabilityClass cls) {
        return new PaymentWorker(adapter, ledger, tombstones, id, T, topic, ifms, cls, LEASE);
    }

    private long payments(UUID op) {
        return sim.ledger().count(IfmsJitSimulator.EFFECT_PAYMENT, op.toString());
    }

    private boolean ended(String pid) {
        engine.executeDueJobs();
        return engine.getInstance(pid).ended();
    }

    private OperationLedger.Entry ledgerEntry(UUID op) {
        return inEngineTx(c -> ledger.find(c, op).orElseThrow());
    }

    private List<ReconciliationQueue.Item> openItems(UUID op) {
        return inEngineTx(c -> reconciliation.listOpen(c, T)).stream().filter(i -> op.equals(i.operationId())).toList();
    }

    // ------------------------------------------------------------------ (i) class (a): same operationId, pays once

    @Test
    @Order(1)
    void e3_i_a_crashAfterPaymentClassA() {
        sim.capabilityClass(CapabilityClass.A_IDEMPOTENT_WITH_LOOKUP);
        String topic = key("pay-ia");
        String pid = toPayment("ia", topic);
        PaymentWorker w1 = worker("worker-1", topic, CapabilityClass.A_IDEMPOTENT_WITH_LOOKUP);
        PaymentWorker w2 = worker("worker-2", topic, CapabilityClass.A_IDEMPOTENT_WITH_LOOKUP);

        Run r1 = w1.runOnce(true);
        assertEquals(Outcome.CRASHED, r1.outcome());
        assertTrue(r1.pay().accepted(), "first PI accepted: " + r1.pay());
        assertFalse(r1.pay().duplicate());
        UUID op = r1.intent().operationId();
        assertNull(w2.fetch(), "no redispatch while the lease is live");

        advance(LEASE.plusSeconds(1));
        Run r2 = w2.runOnce(false);
        assertEquals(Outcome.COMPLETED, r2.outcome());
        assertEquals(op, r2.intent().operationId(), "same operationId on retry");
        assertNotEquals(r1.work().attemptId(), r2.work().attemptId(), "new attemptId per lease");
        assertTrue(r2.work().lockVersion() > r1.work().lockVersion(), "strictly greater lockVersion");
        assertTrue(r2.pay().duplicate(), "IFMS recognised the repeat by operationId");
        assertEquals(1, payments(op), "paid exactly once");
        assertTrue(ended(pid));
        assertEquals(OperationLedger.State.SUCCEEDED, ledgerEntry(op).state());
        assertEquals(2, ledgerEntry(op).attempts());
    }

    @Test
    @Order(2)
    void e3_i_b_crashAfterPaymentClassB() {
        sim.capabilityClass(CapabilityClass.B_LOOKUP_ONLY);
        String topic = key("pay-ib");
        String pid = toPayment("ib", topic);
        PaymentWorker w1 = worker("worker-1", topic, CapabilityClass.B_LOOKUP_ONLY);
        PaymentWorker w2 = worker("worker-2", topic, CapabilityClass.B_LOOKUP_ONLY);
        Run r1 = w1.runOnce(true);
        UUID op = r1.intent().operationId();
        advance(LEASE.plusSeconds(1));
        Run r2 = w2.runOnce(false);
        assertEquals(Outcome.COMPLETED, r2.outcome());
        assertEquals(op, r2.intent().operationId());
        assertEquals(1, payments(op), "class (b) would pay twice on a blind PI; the lookup prevented it");
        assertEquals(r1.pay().paymentRef(), r2.pay().paymentRef());
        assertTrue(ended(pid));
    }

    // ------------------------------------------------------------------ (ii) class (c), A-8 default

    @Test
    @Order(3)
    void e3_ii_c_classCGoesToReconciliation() {
        sim.capabilityClass(CapabilityClass.C_NEITHER);
        String topic = key("pay-iic");
        String pid = toPayment("iic", topic);
        Run r1 = worker("worker-1", topic, null).runOnce(true);
        UUID op = r1.intent().operationId();
        assertEquals(CapabilityClass.C_NEITHER, ledgerEntry(op).capabilityClass(), "A-8 default");

        advance(LEASE.plusSeconds(1));
        // Two more polls: the adapter's A-10 policy ends the expired lease, applies class (c) and holds the work.
        Run r2 = worker("worker-2", topic, null).runOnce(false);
        Run r3 = worker("worker-3", topic, null).runOnce(false);
        assertEquals(Outcome.NO_WORK, r2.outcome(), "held work is not offered");
        assertEquals(Outcome.NO_WORK, r3.outcome(), "held work is not offered");
        assertEquals(OperationLedger.State.UNKNOWN_OUTCOME, ledgerEntry(op).state());
        List<ReconciliationQueue.Item> items = openItems(op);
        assertEquals(1, items.size());
        assertEquals(ReconciliationQueue.Kind.UNKNOWN_OUTCOME, items.get(0).kind());
        assertEquals(1, payments(op), "no automatic repeat");
        assertFalse(engine.getInstance(pid).ended());

        // Officer (reconciliation console, REST): the bank statement shows the payment; confirm success.
        String paymentRef = sim.payments(op.toString()).get(0).paymentRef();
        long itemId = items.get(0).id();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("action", "CONFIRM_SUCCESS");
        body.put("evidence", "bank statement line for " + paymentRef);
        body.put("result", Map.of("paymentRef", paymentRef));
        body.put("reason", "payment found at the bank");
        Rest resolved = post("/api/v1/ops/reconciliation/" + itemId + "/resolve", body);
        assertEquals(200, resolved.status(), resolved.body());
        assertEquals("RESOLVED", resolved.json().path("state").asText());
        assertEquals(OperationLedger.State.SUCCEEDED, ledgerEntry(op).state());

        Run r4 = worker("worker-4", topic, null).runOnce(false);
        assertEquals(Outcome.COMPLETED_FROM_STORED_OUTCOME, r4.outcome(),
                "after the officer's resolution the held work is released and completed from the stored outcome");
        assertTrue(ended(pid));
        assertEquals(paymentRef, engine.getInstance(pid).variables().get("paymentRef"));
        assertEquals(1, payments(op));
        assertEquals(1, count("SELECT count(*) FROM AUDIT_EVENT WHERE chain_id = ? AND event_type = 'RECONCILIATION_ACTION'", pid));
    }

    /** Not counted in Stage B: shows the duplicate check works and why A-8 defaults to (c). */
    @Test
    @Order(4)
    void e3_ctl_wrongDeclarationPaysTwice() {
        sim.capabilityClass(CapabilityClass.C_NEITHER);
        String topic = key("pay-ctl");
        toPayment("ctl", topic);
        Run r1 = worker("worker-1", topic, CapabilityClass.A_IDEMPOTENT_WITH_LOOKUP).runOnce(true);
        UUID op = r1.intent().operationId();
        controlOperation = op;
        advance(LEASE.plusSeconds(1));
        Run r2 = worker("worker-2", topic, CapabilityClass.A_IDEMPOTENT_WITH_LOOKUP).runOnce(false);
        assertEquals(Outcome.COMPLETED, r2.outcome());
        assertEquals(2, payments(op), "a blind retry against class (c) duplicates the payment");
    }

    // ------------------------------------------------------------------ (iii) stale worker

    @Test
    @Order(5)
    void e3_iii_staleWorkerRejected() {
        sim.capabilityClass(CapabilityClass.A_IDEMPOTENT_WITH_LOOKUP);
        String topic = key("pay-iii");
        String pid = toPayment("iii", topic);
        PaymentWorker w1 = worker("worker-1", topic, CapabilityClass.A_IDEMPOTENT_WITH_LOOKUP);
        PaymentWorker w2 = worker("worker-2", topic, CapabilityClass.A_IDEMPOTENT_WITH_LOOKUP);
        LeasedWork la = w1.fetch();
        ExternalWork a = la.work();
        OperationLedger.Intent ia = w1.intent(la);
        Ifms.Result payA = w1.dispatch(la, ia);
        advance(LEASE.plusSeconds(1));
        LeasedWork lb = w2.fetch();
        assertNotNull(lb);
        ExternalWork b = lb.work();
        assertEquals(a.id(), b.id());
        assertTrue(b.lockVersion() > a.lockVersion());

        List<String> rejections = new ArrayList<>();
        rejections.add(reject(() -> w1.complete(la, ia, payA, false)));
        rejections.add(reject(() -> engine.completeExternal(a.id(), "worker-1", a.lockVersion(), Map.of("paymentRef", "X"))));
        rejections.add(reject(() -> engine.extendLock(a.id(), "worker-1", a.lockVersion(), LEASE)));
        rejections.add(reject(() -> engine.failExternal(a.id(), "worker-1", a.lockVersion(), "late", 3, Duration.ZERO)));
        rejections.add(reject(() -> engine.unlockExternal(a.id(), "worker-1", a.lockVersion())));
        // the old version is stale for the new owner as well
        rejections.add(reject(() -> engine.completeExternal(a.id(), "worker-2", a.lockVersion(), Map.of())));
        assertFalse(engine.getInstance(pid).ended(), "stale calls changed nothing");
        assertTrue(engine.getInstance(pid).activeActivityIds().contains("payment"));

        OperationLedger.Intent ib = w2.intent(lb);
        assertEquals(ia.operationId(), ib.operationId());
        Run done = w2.complete(lb, ib, w2.dispatch(lb, ib), false);
        assertEquals(Outcome.COMPLETED, done.outcome());
        assertTrue(ended(pid));
        // and after completion the old holder is still rejected
        rejections.add(reject(() -> engine.completeExternal(a.id(), "worker-1", a.lockVersion(), Map.of())));
        assertEquals(7, rejections.size());
        assertEquals(1, payments(ia.operationId()));
    }

    private static String reject(Runnable call) {
        RuntimeException e = assertThrows(RuntimeException.class, call::run);
        boolean stale = e instanceof StaleLeaseException || e instanceof OperationLedger.StaleLeaseException;
        assertTrue(stale, "expected a STALE_LEASE rejection, got " + e);
        return e instanceof StaleLeaseException s ? "adapter:" + s.reason() : "ledger:" + e.getMessage();
    }

    // ------------------------------------------------------------------ (iv) network cut for 2x the lease

    @Test
    @Order(6)
    void e3_iv_networkCutExactlyOneCompletion() throws Exception {
        sim.capabilityClass(CapabilityClass.A_IDEMPOTENT_WITH_LOOKUP);
        ifms.lookup("warm-up-token");   // authenticate before the fault is armed
        String topic = key("pay-iv");
        String pid = toPayment("iv", topic);
        PaymentWorker w1 = worker("worker-1", topic, CapabilityClass.A_IDEMPOTENT_WITH_LOOKUP);
        PaymentWorker w2 = worker("worker-2", topic, CapabilityClass.A_IDEMPOTENT_WITH_LOOKUP);
        LeasedWork la = w1.fetch();
        OperationLedger.Intent ia = w1.intent(la);

        sim.faults().once(Fault.delay(Duration.ofMillis(2_000)).onRoute(IfmsJitSimulator.DATA_PATH));
        long received = sim.requestsReceived();
        CompletableFuture<Ifms.Result> inFlight = CompletableFuture.supplyAsync(() -> w1.dispatch(la, ia));
        long deadline = System.currentTimeMillis() + 5_000;
        while (sim.requestsReceived() == received && System.currentTimeMillis() < deadline) {
            Thread.onSpinWait();
        }
        assertNull(w2.fetch(), "no redispatch of LOCKED work before the lease expires");

        advance(LEASE.multipliedBy(2).plusSeconds(1));   // the cut lasts 2x the lease
        Run r2 = w2.runOnce(false);
        assertEquals(Outcome.COMPLETED, r2.outcome());
        assertEquals(ia.operationId(), r2.intent().operationId(), "second attempt reuses the operationId");

        Ifms.Result late = inFlight.get(10, TimeUnit.SECONDS);   // worker-1 comes back
        reject(() -> w1.complete(la, ia, late, false));
        assertTrue(ended(pid));
        long completions = count("SELECT count(*) FROM ACT_HI_ACTINST WHERE PROC_INST_ID_ = ? AND ACT_ID_ = 'payment'"
                + " AND END_TIME_ IS NOT NULL", pid);
        assertEquals(1, completions, "exactly one completion of the payment activity");
        assertEquals(1, payments(ia.operationId()));
    }

    @Test
    @Order(7)
    void e3_sum_noDuplicatePaymentsOverall() {
        Map<String, Long> dups = sim.ledger().duplicates(IfmsJitSimulator.EFFECT_PAYMENT);
        // the control scenario deliberately pays twice; nothing else may
        for (String op : dups.keySet()) {
            assertEquals(String.valueOf(controlOperation), op, "unexpected duplicate payment: " + dups);
        }
    }

    /**
     * Stage B probe E3-hz (finding F-4, hazard confirmed on both spike adapters): a lease ended inside the adapter
     * ({@code executeDueJobs}, e.g. a timer run) before any product sweep re-dispatched class-(c) work and paid twice.
     * Sangam's A-10 hook ("expiry hook: no double payment") must decide inside the adapter: counted here.
     */
    @Test
    @Order(8)
    void e3_hz_adapterInternalExpiryKeepsTheCapabilityClass() {
        sim.capabilityClass(CapabilityClass.C_NEITHER);
        String topic = key("pay-hz");
        String pid = toPayment("hz", topic);
        Run r1 = worker("worker-1", topic, null).runOnce(true);
        UUID op = r1.intent().operationId();
        advance(LEASE.plusSeconds(1));
        engine.executeDueJobs();          // ends the expired lease inside the adapter
        Run r2 = worker("worker-2", topic, null).runOnce(false);
        assertEquals(Outcome.NO_WORK, r2.outcome(), "class (c) work is held, not re-dispatched");
        assertEquals(OperationLedger.State.UNKNOWN_OUTCOME, ledgerEntry(op).state());
        assertEquals(1, payments(op), "no double payment");
        assertFalse(engine.getInstance(pid).ended());
    }

    /**
     * T35 / VI.3 rule 4: the operationId the worker REST API hands out with a lease is the engine's operationId (the
     * one stored on the lease, used by the A-10 expiry policy and the ledger), so a worker using the REST API records
     * its intent under the same identity the server reconciles.
     */
    @Test
    @Order(9)
    void e3_t35_restWorkerApiHandsOutTheEngineOperationId() {
        String topic = key("pay-t35");
        String pid = toPayment("t35", topic);
        Rest r = post("/api/v1/work/fetch-and-lock", Map.of("workerId", "rest-worker-1", "maxTasks", 1,
                "topics", List.of(Map.of("name", topic, "lockDurationMs", 60_000))));
        assertEquals(200, r.status(), r.body());
        JsonNode item = r.json().get(0);
        assertNotNull(item, "one work item: " + r.body());
        String workId = item.path("id").asText();
        String leased = strings("SELECT operation_id FROM SANGAM_LEASE WHERE work_id = ?", workId).get(0);
        assertEquals(leased, item.path("operationId").asText(), "REST operationId == engine (T35) operationId");
        Rest unlock = post("/api/v1/work/" + workId + "/unlock", Map.of("workerId", "rest-worker-1",
                "lockVersion", item.path("lockVersion").asLong(), "attemptId", item.path("attemptId").asText()));
        assertEquals(204, unlock.status(), unlock.body());
        assertFalse(engine.getInstance(pid).ended());
    }
}
