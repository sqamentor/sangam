package org.sangam.spike.it.e3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.sangam.spike.api.EngineAdapter.ExternalWork;
import org.sangam.spike.api.StaleLeaseException;
import org.sangam.spike.it.support.Env;
import org.sangam.spike.it.support.ExperimentBase;
import org.sangam.spike.it.support.Ifms;
import org.sangam.spike.it.support.PaymentWorker;
import org.sangam.spike.it.support.PaymentWorker.Outcome;
import org.sangam.spike.it.support.PaymentWorker.Run;
import org.sangam.spike.it.support.Scenario;
import org.sangam.spike.it.support.Workflows;
import org.sangam.spike.layer.CapabilityClass;
import org.sangam.spike.layer.OperationLedger;
import org.sangam.spike.layer.ReconciliationQueue;
import org.sangam.spike.sim.Fault;
import org.sangam.spike.sim.ifms.IfmsJitSimulator;

/**
 * E3 (SANGAM.md XI.4): worker lease + operationId + unknown outcome on WF-1's payment external task, with a payment
 * worker calling the IFMS JIT simulator. Lease times use the engine clock, which the test moves (no sleeps).
 */
public abstract class AbstractE3LeaseTest extends ExperimentBase {

    static final String T = "tn-e3";
    static final Duration LEASE = Duration.ofSeconds(60);

    IfmsJitSimulator sim;
    Ifms ifms;
    UUID controlOperation;

    @Override
    protected String experiment() {
        return "E3";
    }

    @Override
    protected void setUp() {
        env.deploy(T, Workflows.WF1_FILES);
        env.setClock(Instant.now());
        sim = new IfmsJitSimulator().start();
        ifms = new Ifms(sim);
        notes.put("leaseDuration", LEASE.toString());
        notes.put("clock", "engine clock fixed and advanced by the test (Env.setClock); no real-time sleeps");
        notes.put("leaseExpirySweep", "product-side sweep (PaymentWorker.sweepExpiredLeases) at the engine clock: "
                + "OperationLedger.onLeaseExpired(op, lockVersion+1) then LeaseTable.expire, one engine transaction, before every fetch");
        notes.put("unknownOutcomeHold", "work whose operation is UNKNOWN_OUTCOME is released (unlockExternal) and never "
                + "dispatched until an officer resolves it; neither adapter offers a 'held' state for external work");
    }

    @AfterAll
    void stopSimulator() {
        if (sim != null) {
            sim.close();
        }
    }

    private PaymentWorker worker(String id, CapabilityClass cls) {
        return new PaymentWorker(env, id, T, ifms, cls, LEASE);
    }

    private long payments(UUID op) {
        return sim.ledger().count(IfmsJitSimulator.EFFECT_PAYMENT, op.toString());
    }

    /**
     * Runs due engine jobs, then reports whether the instance ended. Flowable continues after an external-worker
     * completion through a new async job (patch-ledger-flowable); with the executor off it runs here. Operaton
     * continues synchronously, so this executes nothing there.
     */
    private boolean ended(String pid) {
        int jobs = env.engine.executeDueJobs();
        m.merge("asyncJobsAfterCompletion", jobs, (a, b) -> (Integer) a + (Integer) b);
        return env.engine.getInstance(pid).ended();
    }

    private OperationLedger.Entry ledgerEntry(UUID op) {
        return env.inTx(c -> env.ledger.find(c, op).orElseThrow());
    }

    private List<ReconciliationQueue.Item> openItems(UUID op) {
        return env.inTx(c -> env.reconciliation.listOpen(c, T)).stream()
                .filter(i -> op.equals(i.operationId())).toList();
    }

    // ------------------------------------------------------------------ (i) class (a): same operationId, pays once

    @Test
    @Order(1)
    @Scenario(id = "E3-i-a", name = "Crash after successful PI before completion; lease expires; re-fetch with a new "
            + "lockVersion; retry with the same operationId; class (a) pays once", tests = {"T02"})
    void crashAfterPaymentClassA() {
        sim.capabilityClass(IfmsJitSimulator.CapabilityClass.A_IDEMPOTENT_AND_LOOKUP);
        String pid = Workflows.wf1ToPayment(env.engine, T, "e3-i-a", 50_000, "ACC-E3-1");
        PaymentWorker w1 = worker("worker-1", CapabilityClass.A_IDEMPOTENT_WITH_LOOKUP);
        PaymentWorker w2 = worker("worker-2", CapabilityClass.A_IDEMPOTENT_WITH_LOOKUP);

        Run r1 = w1.runOnce(true);
        assertEquals(Outcome.CRASHED, r1.outcome());
        assertTrue(r1.pay().accepted(), "first PI accepted");
        assertFalse(r1.pay().duplicate());
        UUID op = r1.intent().operationId();
        assertNull(w2.fetch(), "no redispatch while the lease is live");

        env.advance(LEASE.plusSeconds(1));
        Run r2 = w2.runOnce(false);
        assertEquals(Outcome.COMPLETED, r2.outcome());
        assertEquals(op, r2.intent().operationId(), "same operationId on retry");
        assertNotEquals(r1.work().attemptId(), r2.work().attemptId(), "new attemptId per lease");
        assertTrue(r2.work().lockVersion() > r1.work().lockVersion(), "strictly greater lockVersion");
        assertTrue(r2.pay().duplicate(), "IFMS recognised the repeat by operationId");
        assertEquals(1, payments(op), "paid exactly once");
        assertFalse(sim.ledger().hasDuplicates(IfmsJitSimulator.EFFECT_PAYMENT));
        assertTrue(ended(pid));
        assertEquals(OperationLedger.State.SUCCEEDED, ledgerEntry(op).state());
        assertEquals(2, ledgerEntry(op).attempts());

        m.put("operationId", op.toString());
        m.put("lockVersions", List.of(r1.work().lockVersion(), r2.work().lockVersion()));
        m.put("attemptIds", List.of(r1.work().attemptId(), r2.work().attemptId()));
        m.put("activityInstanceId", r1.work().activityInstanceId());
        m.put("ifmsPaymentsForOperation", payments(op));
        m.put("secondPiDuplicateFlag", r2.pay().duplicate());
        m.put("ledgerAttempts", ledgerEntry(op).attempts());
        m.put("instanceEnded", true);
    }

    @Test
    @Order(2)
    @Scenario(id = "E3-i-b", name = "Same crash with class (b): outcome lookup (PD) before any repeat; no second PI",
            tests = {"T02"})
    void crashAfterPaymentClassB() {
        sim.capabilityClass(IfmsJitSimulator.CapabilityClass.B_LOOKUP_ONLY);
        long before = sim.requestsReceived();
        String pid = Workflows.wf1ToPayment(env.engine, T, "e3-i-b", 50_000, "ACC-E3-2");
        PaymentWorker w1 = worker("worker-1", CapabilityClass.B_LOOKUP_ONLY);
        PaymentWorker w2 = worker("worker-2", CapabilityClass.B_LOOKUP_ONLY);
        Run r1 = w1.runOnce(true);
        UUID op = r1.intent().operationId();
        env.advance(LEASE.plusSeconds(1));
        Run r2 = w2.runOnce(false);
        assertEquals(Outcome.COMPLETED, r2.outcome());
        assertEquals(op, r2.intent().operationId());
        assertEquals(1, payments(op), "class (b) would pay twice on a blind PI; the lookup prevented it");
        assertEquals(r1.pay().paymentRef(), r2.pay().paymentRef());
        assertTrue(ended(pid));
        m.put("operationId", op.toString());
        m.put("ifmsPaymentsForOperation", payments(op));
        m.put("ifmsRequests", sim.requestsReceived() - before);
        m.put("retryUsedLookup", true);
    }

    // ------------------------------------------------------------------ (ii) class (c), A-8 default

    @Test
    @Order(3)
    @Scenario(id = "E3-ii-c", name = "Undeclared topic (A-8 default class c) on an IFMS without idempotency/lookup: "
            + "no automatic repeat, UNKNOWN_OUTCOME reconciliation item, resolved by an officer", tests = {"T02", "T36"})
    void classCGoesToReconciliation() {
        sim.capabilityClass(IfmsJitSimulator.CapabilityClass.C_NEITHER);
        String pid = Workflows.wf1ToPayment(env.engine, T, "e3-ii-c", 50_000, "ACC-E3-3");
        PaymentWorker w1 = worker("worker-1", null);
        Run r1 = w1.runOnce(true);
        UUID op = r1.intent().operationId();
        assertEquals(CapabilityClass.C_NEITHER, ledgerEntry(op).capabilityClass(), "A-8 default");

        env.advance(LEASE.plusSeconds(1));
        List<PaymentWorker.Expired> swept = PaymentWorker.sweepExpiredLeases(env);
        assertEquals(1, swept.size());
        assertEquals(CapabilityClass.LeaseExpiryAction.UNKNOWN_OUTCOME, swept.get(0).action());
        assertEquals(OperationLedger.State.UNKNOWN_OUTCOME, ledgerEntry(op).state());
        List<ReconciliationQueue.Item> items = openItems(op);
        assertEquals(1, items.size());
        assertEquals(ReconciliationQueue.Kind.UNKNOWN_OUTCOME, items.get(0).kind());

        // Two more polls: the work is fetched (engine lease expired) but never dispatched again.
        Run r2 = worker("worker-2", null).runOnce(false);
        Run r3 = worker("worker-3", null).runOnce(false);
        assertEquals(Outcome.HELD_FOR_RECONCILIATION, r2.outcome());
        assertEquals(Outcome.HELD_FOR_RECONCILIATION, r3.outcome());
        assertEquals(1, payments(op), "no automatic repeat");
        assertFalse(env.engine.getInstance(pid).ended());

        // Officer: the bank statement (simulator record) shows the payment; confirm success with the result.
        String paymentRef = sim.payments(op.toString()).get(0).paymentRef();
        long itemId = items.get(0).id();
        ReconciliationQueue.Item resolved = env.inTx(c -> env.reconciliation.resolve(c, itemId,
                ReconciliationQueue.Action.CONFIRM_SUCCESS, "officer-17", "{\"paymentRef\":\"" + paymentRef + "\"}"));
        assertEquals(ReconciliationQueue.State.RESOLVED, resolved.state());
        assertEquals(OperationLedger.State.SUCCEEDED, ledgerEntry(op).state());

        Run r4 = worker("worker-4", null).runOnce(false);
        assertEquals(Outcome.COMPLETED_FROM_STORED_OUTCOME, r4.outcome());
        assertTrue(ended(pid));
        assertEquals(paymentRef, env.engine.getInstance(pid).variables().get("paymentRef"));
        assertEquals(1, payments(op));
        long auditActions = env.count("SELECT count(*) FROM AUDIT_EVENT WHERE chain_id = ? AND event_type = 'RECONCILIATION_ACTION'", pid);
        assertEquals(1, auditActions);

        m.put("operationId", op.toString());
        m.put("ifmsPaymentsForOperation", payments(op));
        m.put("pollsHeldWithoutDispatch", 2);
        m.put("reconciliationItemId", itemId);
        m.put("resolution", "CONFIRM_SUCCESS by officer-17, paymentRef " + paymentRef);
        m.put("reconciliationAuditEvents", auditActions);
        m.put("instanceEndedAfterResolution", true);
    }

    @Test
    @Order(4)
    @Scenario(id = "E3-ctl", name = "Control: the same class-(c) IFMS with the topic wrongly declared (a) pays twice "
            + "(shows the duplicate check works and why A-8 defaults to c)", counted = false)
    void controlWrongDeclarationPaysTwice() {
        sim.capabilityClass(IfmsJitSimulator.CapabilityClass.C_NEITHER);
        Workflows.wf1ToPayment(env.engine, T, "e3-ctl", 50_000, "ACC-E3-4");
        Run r1 = worker("worker-1", CapabilityClass.A_IDEMPOTENT_WITH_LOOKUP).runOnce(true);
        UUID op = r1.intent().operationId();
        controlOperation = op;
        env.advance(LEASE.plusSeconds(1));
        Run r2 = worker("worker-2", CapabilityClass.A_IDEMPOTENT_WITH_LOOKUP).runOnce(false);
        assertEquals(Outcome.COMPLETED, r2.outcome());
        assertEquals(2, payments(op), "a blind retry against class (c) duplicates the payment");
        m.put("ifmsPaymentsForOperation", payments(op));
        m.put("duplicatesDetected", sim.ledger().duplicates(IfmsJitSimulator.EFFECT_PAYMENT).get(op.toString()));
    }

    // ------------------------------------------------------------------ (iii) stale worker

    @Test
    @Order(5)
    @Scenario(id = "E3-iii", name = "Stale worker completes after another worker re-acquired: rejected (409 STALE_LEASE), "
            + "nothing changes; the current holder completes", tests = {"T03"})
    void staleWorkerRejected() {
        sim.capabilityClass(IfmsJitSimulator.CapabilityClass.A_IDEMPOTENT_AND_LOOKUP);
        String pid = Workflows.wf1ToPayment(env.engine, T, "e3-iii", 50_000, "ACC-E3-5");
        PaymentWorker w1 = worker("worker-1", CapabilityClass.A_IDEMPOTENT_WITH_LOOKUP);
        PaymentWorker w2 = worker("worker-2", CapabilityClass.A_IDEMPOTENT_WITH_LOOKUP);
        ExternalWork a = w1.fetch();
        OperationLedger.Intent ia = w1.intent(a);
        Ifms.Result payA = w1.dispatch(a, ia);
        env.advance(LEASE.plusSeconds(1));
        ExternalWork b = w2.fetch();
        assertNotNull(b);
        assertEquals(a.id(), b.id());
        assertTrue(b.lockVersion() > a.lockVersion());

        List<String> rejections = new ArrayList<>();
        rejections.add(reject(() -> w1.complete(a, ia, payA, false)));
        rejections.add(reject(() -> env.engine.completeExternal(a.id(), "worker-1", a.lockVersion(), Map.of("paymentRef", "X"))));
        rejections.add(reject(() -> env.engine.extendLock(a.id(), "worker-1", a.lockVersion(), LEASE)));
        rejections.add(reject(() -> env.engine.failExternal(a.id(), "worker-1", a.lockVersion(), "late", 3, Duration.ZERO)));
        rejections.add(reject(() -> env.engine.unlockExternal(a.id(), "worker-1", a.lockVersion())));
        // the old version is stale for the new owner as well
        rejections.add(reject(() -> env.engine.completeExternal(a.id(), "worker-2", a.lockVersion(), Map.of())));
        assertFalse(env.engine.getInstance(pid).ended(), "stale calls changed nothing");
        assertTrue(env.engine.getInstance(pid).activeActivityIds().contains("wf1_payment"));

        OperationLedger.Intent ib = w2.intent(b);
        assertEquals(ia.operationId(), ib.operationId());
        Run done = w2.complete(b, ib, w2.dispatch(b, ib), false);
        assertEquals(Outcome.COMPLETED, done.outcome());
        assertTrue(ended(pid));
        // and after completion the old holder is still rejected
        rejections.add(reject(() -> env.engine.completeExternal(a.id(), "worker-1", a.lockVersion(), Map.of())));
        assertEquals(1, payments(ia.operationId()));

        m.put("staleCallsRejected", rejections.size());
        m.put("rejections", rejections);
        m.put("lockVersions", List.of(a.lockVersion(), b.lockVersion()));
        m.put("ifmsPaymentsForOperation", payments(ia.operationId()));
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
    @Scenario(id = "E3-iv", name = "Network cut: the lease expires (2x lease) while worker-1's PI is still running; "
            + "no redispatch before expiry; exactly one completion; the second attempt reuses the operationId",
            tests = {"T04", "T36"})
    void networkCutExactlyOneCompletion() throws Exception {
        sim.capabilityClass(IfmsJitSimulator.CapabilityClass.A_IDEMPOTENT_AND_LOOKUP);
        ifms.lookup("warm-up-token");   // authenticate before the fault is armed
        String pid = Workflows.wf1ToPayment(env.engine, T, "e3-iv", 50_000, "ACC-E3-6");
        PaymentWorker w1 = worker("worker-1", CapabilityClass.A_IDEMPOTENT_WITH_LOOKUP);
        PaymentWorker w2 = worker("worker-2", CapabilityClass.A_IDEMPOTENT_WITH_LOOKUP);
        ExternalWork a = w1.fetch();
        OperationLedger.Intent ia = w1.intent(a);

        sim.faults().once(Fault.delay(Duration.ofMillis(2_000)).onRoute(IfmsJitSimulator.DATA_PATH));
        long received = sim.requestsReceived();
        CompletableFuture<Ifms.Result> inFlight = CompletableFuture.supplyAsync(() -> w1.dispatch(a, ia));
        long deadline = System.currentTimeMillis() + 5_000;
        while (sim.requestsReceived() == received && System.currentTimeMillis() < deadline) {
            Thread.onSpinWait();
        }
        // stream/network gone, lease still live: acknowledged work must not be redispatched (T04)
        ExternalWork early = w2.fetch();
        assertNull(early, "no redispatch of LOCKED work before the lease expires");

        env.advance(LEASE.multipliedBy(2).plusSeconds(1));   // cut lasts 2x the lease
        Run r2 = w2.runOnce(false);
        assertEquals(Outcome.COMPLETED, r2.outcome());
        assertEquals(ia.operationId(), r2.intent().operationId(), "second attempt reuses the operationId");

        Ifms.Result late = inFlight.get(10, TimeUnit.SECONDS);   // worker-1 comes back
        String lateRejection = reject(() -> w1.complete(a, ia, late, false));
        assertTrue(ended(pid));
        long completions = env.count("SELECT count(*) FROM ACT_HI_ACTINST WHERE PROC_INST_ID_ = ? AND ACT_ID_ = 'wf1_payment' AND END_TIME_ IS NOT NULL", pid);
        assertEquals(1, completions, "exactly one completion of wf1_payment");
        assertEquals(1, payments(ia.operationId()));

        m.put("operationId", ia.operationId().toString());
        m.put("fetchBeforeExpiry", "none (lease respected)");
        m.put("worker1LatePiDuplicateFlag", late.duplicate());
        m.put("worker1LateCompletion", lateRejection);
        m.put("paymentActivityCompletions", completions);
        m.put("ifmsPaymentsForOperation", payments(ia.operationId()));
        m.put("lockVersions", List.of(a.lockVersion(), r2.work().lockVersion()));
    }

    @Test
    @Order(7)
    @Scenario(id = "E3-sum", name = "Effects ledger: no operationId paid twice across counted scenarios", tests = {"T02"})
    void noDuplicatePaymentsOverall() {
        Map<String, Long> dups = sim.ledger().duplicates(IfmsJitSimulator.EFFECT_PAYMENT);
        // the control scenario E3-ctl deliberately pays twice; nothing else may
        for (String op : dups.keySet()) {
            assertEquals(String.valueOf(controlOperation), op, "unexpected duplicate payment: " + dups);
        }
        m.put("paymentsTotal", sim.ledger().count(IfmsJitSimulator.EFFECT_PAYMENT));
        m.put("duplicateOperationIds", dups.size());
        m.put("duplicatesOnlyFromControl", dups.isEmpty() ? "none" : "yes (E3-ctl only)");
    }

    @Test
    @Order(8)
    @Scenario(id = "E3-hz", name = "Probe: a lease ended inside the adapter (executeDueJobs, e.g. a timer run, before "
            + "the product sweep) bypasses the A-8 decision; does class-(c) work get re-dispatched?", counted = false)
    void adapterInternalExpiryBypassesCapabilityClass() {
        sim.capabilityClass(IfmsJitSimulator.CapabilityClass.C_NEITHER);
        Workflows.wf1ToPayment(env.engine, T, "e3-hz", 50_000, "ACC-E3-7");
        Run r1 = worker("worker-1", null).runOnce(true);
        UUID op = r1.intent().operationId();
        env.advance(LEASE.plusSeconds(1));
        int jobs = env.engine.executeDueJobs();          // ends the expired SPIKE_LEASE row inside the adapter
        List<PaymentWorker.Expired> swept = PaymentWorker.sweepExpiredLeases(env);
        Run r2 = worker("worker-2", null).runOnce(false);
        m.put("executeDueJobsRan", jobs);
        m.put("productSweepFound", swept.size());
        m.put("secondAttemptOutcome", r2.outcome().name());
        m.put("ledgerStateAfter", ledgerEntry(op).state().name());
        m.put("ifmsPaymentsForOperation", payments(op));
        m.put("hazardConfirmed", payments(op) > 1);
        m.put("explanation", "LeaseTable.expire runs inside fetchAndLock/executeDueJobs of both adapters without a product "
                + "callback; OperationLedger.recordIntent then treats the higher lockVersion as a normal new attempt for any "
                + "capability class, so the A-8 'no automatic repeat' rule depends on the product sweep running first");
    }

    protected Env env() {
        return env;
    }
}
