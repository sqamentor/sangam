package org.sangam.spike.it.e7;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.sangam.spike.api.EngineAdapter.ExternalWork;
import org.sangam.spike.api.EngineAdapter.InstanceView;
import org.sangam.spike.it.support.ExperimentBase;
import org.sangam.spike.it.support.Ifms;
import org.sangam.spike.it.support.PaymentWorker;
import org.sangam.spike.it.support.PaymentWorker.Outcome;
import org.sangam.spike.it.support.PaymentWorker.Run;
import org.sangam.spike.it.support.Scenario;
import org.sangam.spike.it.support.Workflows;
import org.sangam.spike.layer.CapabilityClass;
import org.sangam.spike.layer.Jdbc;
import org.sangam.spike.layer.OperationLedger;
import org.sangam.spike.layer.ReconciliationQueue;
import org.sangam.spike.layer.Tombstones;
import org.sangam.spike.sim.ifms.IfmsJitSimulator;

/**
 * E7 (SANGAM.md XI.4, VI.5): technical delete of a WF-1 instance while its payment worker holds the lease.
 * The delete command writes the tombstones, the INSTANCE_DELETED audit event and calls {@code cancelInstance}, all in one
 * engine transaction. The worker's late result goes through the product completion path (tombstone check first).
 */
public abstract class AbstractE7DeleteTest extends ExperimentBase {

    static final String T = "tn-e7";
    static final Duration LEASE = Duration.ofSeconds(60);

    IfmsJitSimulator sim;
    Ifms ifms;

    @Override
    protected String experiment() {
        return "E7";
    }

    @Override
    protected void setUp() {
        env.deploy(T, Workflows.WF1_FILES);
        env.setClock(Instant.now());
        sim = new IfmsJitSimulator().start();
        ifms = new Ifms(sim);
        notes.put("deleteCommand", "one engine transaction: Tombstones.write per leased work of the root (from SPIKE_LEASE + "
                + "the work->operation map), Tombstones.recordDeletion (INSTANCE_DELETED audit with open operationIds), "
                + "EngineAdapter.cancelInstance");
        notes.put("lateResultPath", "PaymentWorker.complete: Tombstones.onLateResult before the ledger and completeExternal, "
                + "same transaction");
    }

    @AfterAll
    void stopSimulator() {
        if (sim != null) {
            sim.close();
        }
    }

    /** Technical delete (VI.5.1): tombstones for every leased work of the root, audit, engine delete; one transaction. */
    private List<UUID> technicalDelete(String pid, String actor, String reason) {
        Instant now = env.clock();
        return env.inTx(c -> {
            List<Object[]> leased = Jdbc.query(c, "SELECT m.work_id, m.operation_id, l.lock_version, l.expires_at FROM IT_WORK_OPERATION m"
                            + " JOIN spike_lease l ON l.work_id = m.work_id WHERE m.root_id = ? AND l.worker_id IS NOT NULL",
                    rs -> new Object[]{rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4)}, pid);
            List<UUID> ops = new ArrayList<>();
            for (Object[] r : leased) {
                UUID op = UUID.fromString((String) r[1]);
                Duration remaining = Duration.ofMillis((Long) r[3] - now.toEpochMilli());
                env.tombstones.write(c, T, (String) r[0], op, pid, (Long) r[2], remaining);
                ops.add(op);
            }
            env.tombstones.recordDeletion(c, T, pid, actor, reason, ops);
            env.engine.cancelInstance(pid, reason);
            return ops;
        });
    }

    private long audit(String pid, String type) {
        return env.count("SELECT count(*) FROM AUDIT_EVENT WHERE chain_id = ? AND event_type = ?", pid, type);
    }

    private boolean deleted(String pid) {
        InstanceView v = env.engine.getInstance(pid);
        return (v == null || v.ended()) && env.count("SELECT count(*) FROM ACT_RU_EXECUTION WHERE PROC_INST_ID_ = ?", pid) == 0;
    }

    @Test
    @Order(1)
    @Scenario(id = "E7-1", name = "Delete while the payment worker is mid-call; the worker then completes: 410 GONE, "
            + "late result audited, LATE_RESULT_AFTER_DELETE item, instance stays deleted", tests = {"T11", "T39"})
    void deleteDuringPaymentThenLateCompletion() {
        String pid = Workflows.wf1ToPayment(env.engine, T, "e7-1", 75_000, "ACC-E7-1");
        PaymentWorker w = new PaymentWorker(env, "worker-1", T, ifms, CapabilityClass.A_IDEMPOTENT_WITH_LOOKUP, LEASE);
        ExternalWork work = w.fetch();
        assertNotNull(work);
        OperationLedger.Intent intent = w.intent(work);
        Ifms.Result pay = w.dispatch(work, intent);           // the payment happens at IFMS ...
        assertTrue(pay.accepted());

        List<UUID> ops = technicalDelete(pid, "admin-3", "technical delete: bill entered twice");   // ... meanwhile
        assertEquals(List.of(intent.operationId()), ops);
        assertTrue(deleted(pid));
        Tombstones.Tombstone ts = env.inTx(c -> env.tombstones.find(c, T, work.id()).orElseThrow());
        assertEquals(Tombstones.State.CANCEL_REQUESTED, ts.state());
        assertEquals(work.lockVersion(), ts.lockVersion());

        Run late = w.complete(work, intent, pay, false);      // the worker reports success after the delete
        assertEquals(Outcome.GONE, late.outcome());
        Tombstones.Gone gone = (Tombstones.Gone) late.detail();
        assertEquals(410, gone.httpStatus());
        assertEquals("GONE_INSTANCE_DELETED", gone.code());
        assertNotNull(gone.reconciliationItemId());
        ReconciliationQueue.Item item = env.inTx(c -> env.reconciliation.get(c, gone.reconciliationItemId()).orElseThrow());
        assertEquals(ReconciliationQueue.Kind.LATE_RESULT_AFTER_DELETE, item.kind());
        assertEquals(ReconciliationQueue.State.OPEN, item.state());
        assertEquals(intent.operationId(), item.operationId());
        assertEquals(1, audit(pid, "INSTANCE_DELETED"));
        assertEquals(1, audit(pid, "LATE_RESULT_AFTER_DELETE"));

        // the instance is never revived: no runtime rows, no work offered, retry refused on the item
        env.engine.executeDueJobs();
        assertTrue(deleted(pid));
        assertNull(new PaymentWorker(env, "worker-2", T, ifms, CapabilityClass.A_IDEMPOTENT_WITH_LOOKUP, LEASE).fetch());
        assertThrows(IllegalArgumentException.class, () -> env.inTx(c -> env.reconciliation.resolve(c, item.id(),
                ReconciliationQueue.Action.RETRY_SAME_OPERATION, "officer-1", "{}")));
        ReconciliationQueue.Item done = env.inTx(c -> env.reconciliation.resolve(c, item.id(),
                ReconciliationQueue.Action.CONFIRM_SUCCESS, "officer-1", "{\"paymentRef\":\"" + pay.paymentRef() + "\",\"recovery\":\"refund requested\"}"));
        assertEquals(ReconciliationQueue.State.RESOLVED, done.state());
        assertTrue(deleted(pid));
        assertEquals(1, sim.ledger().count(IfmsJitSimulator.EFFECT_PAYMENT, intent.operationId().toString()));

        // What the raw adapter does with the same late call when nothing checks the tombstone first (finding)
        String raw;
        try {
            env.engine.completeExternal(work.id(), "worker-1", work.lockVersion(), Map.of("paymentRef", pay.paymentRef()));
            raw = "accepted (!)";
        } catch (RuntimeException e) {
            raw = e.getClass().getName() + ": " + e.getMessage();
        }
        long staleLeaseRows = env.count("SELECT count(*) FROM spike_lease WHERE work_id = ?", work.id());
        assertTrue(deleted(pid));

        m.put("tombstoneState", ts.state().name());
        m.put("lateResultHttp", gone.httpStatus() + " " + gone.code());
        m.put("reconciliationItem", item.kind().name() + " #" + item.id());
        m.put("auditINSTANCE_DELETED", 1);
        m.put("auditLATE_RESULT_AFTER_DELETE", 1);
        m.put("retryActionRefused", true);
        m.put("instanceStaysDeleted", true);
        m.put("ifmsPayments", 1);
        m.put("rawAdapterLateCompleteWithoutTombstoneCheck", raw);
        m.put("spikeLeaseRowsLeftAfterDelete", staleLeaseRows);
    }

    @Test
    @Order(2)
    @Scenario(id = "E7-2", name = "Delete while the worker holds the lease and never answers: the tombstoned lease "
            + "expires without cancel ack and becomes UNKNOWN_OUTCOME (VI.5 rule 4)", tests = {"T11"})
    void tombstonedLeaseExpiresToUnknownOutcome() throws Exception {
        String pid = Workflows.wf1ToPayment(env.engine, T, "e7-2", 75_000, "ACC-E7-2");
        PaymentWorker w = new PaymentWorker(env, "worker-1", T, ifms, CapabilityClass.A_IDEMPOTENT_WITH_LOOKUP, LEASE);
        ExternalWork work = w.fetch();
        OperationLedger.Intent intent = w.intent(work);
        w.dispatch(work, intent);
        env.advance(LEASE.minusMillis(400));                  // 400 ms of the lease remain at the delete
        technicalDelete(pid, "admin-3", "technical delete");
        Thread.sleep(800);                                    // the tombstone lease end is on the DB clock
        int swept = env.inTx(env.tombstones::sweepExpiredLeases);
        assertEquals(1, swept);
        Tombstones.Tombstone ts = env.inTx(c -> env.tombstones.find(c, T, work.id()).orElseThrow());
        assertEquals(Tombstones.State.UNKNOWN_OUTCOME, ts.state());
        List<ReconciliationQueue.Item> open = env.inTx((Connection c) -> env.reconciliation.listOpen(c, T)).stream()
                .filter(i -> intent.operationId().equals(i.operationId())).toList();
        assertEquals(1, open.size());
        assertEquals(ReconciliationQueue.Kind.UNKNOWN_OUTCOME, open.get(0).kind());
        assertEquals(OperationLedger.State.UNKNOWN_OUTCOME,
                env.inTx(c -> env.ledger.find(c, intent.operationId()).orElseThrow()).state());
        assertTrue(deleted(pid));
        m.put("tombstonesSwept", swept);
        m.put("tombstoneState", ts.state().name());
        m.put("reconciliationItem", open.get(0).kind().name());
    }

    @Test
    @Order(3)
    @Scenario(id = "E7-3", name = "Late fail / outcomeUnknown after delete: 410; an item only when an effect may have "
            + "happened; instance stays deleted", tests = {"T11"})
    void lateFailAndUnknownAfterDelete() {
        String pid = Workflows.wf1ToPayment(env.engine, T, "e7-3", 75_000, "ACC-E7-3");
        PaymentWorker w = new PaymentWorker(env, "worker-1", T, ifms, CapabilityClass.A_IDEMPOTENT_WITH_LOOKUP, LEASE);
        ExternalWork work = w.fetch();
        w.intent(work);
        technicalDelete(pid, "admin-3", "technical delete");
        Tombstones.LateResultDecision fail = env.inTx(c -> env.tombstones.onLateResult(c, T, work.id(), work.lockVersion(),
                Tombstones.ResultKind.FAIL, false, "{\"error\":\"IFMS 503\"}"));
        Tombstones.LateResultDecision unknown = env.inTx(c -> env.tombstones.onLateResult(c, T, work.id(), work.lockVersion(),
                Tombstones.ResultKind.OUTCOME_UNKNOWN, false, "{\"error\":\"timeout\"}"));
        Tombstones.Gone g1 = (Tombstones.Gone) fail;
        Tombstones.Gone g2 = (Tombstones.Gone) unknown;
        assertEquals(410, g1.httpStatus());
        assertNull(g1.reconciliationItemId(), "no effect reported: audited, no item");
        assertEquals(410, g2.httpStatus());
        assertNotNull(g2.reconciliationItemId(), "outcome unknown: item opened");
        assertEquals(2, audit(pid, "LATE_RESULT_AFTER_DELETE"));
        assertTrue(deleted(pid));
        m.put("lateFail", "410, item=" + g1.reconciliationItemId());
        m.put("lateOutcomeUnknown", "410, item=" + g2.reconciliationItemId());
    }
}
