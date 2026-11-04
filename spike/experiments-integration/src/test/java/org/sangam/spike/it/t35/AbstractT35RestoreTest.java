package org.sangam.spike.it.t35;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.sangam.spike.api.EngineAdapter.ExternalWork;
import org.sangam.spike.it.support.EngineSelection;
import org.sangam.spike.it.support.Env;
import org.sangam.spike.it.support.ExperimentBase;
import org.sangam.spike.it.support.Ifms;
import org.sangam.spike.it.support.PaymentWorker;
import org.sangam.spike.it.support.Pg;
import org.sangam.spike.it.support.Scenario;
import org.sangam.spike.it.support.Workflows;
import org.sangam.spike.layer.CapabilityClass;
import org.sangam.spike.layer.Inbox;
import org.sangam.spike.layer.OperationLedger;
import org.sangam.spike.sim.EffectsLedger;
import org.sangam.spike.sim.ifms.IfmsJitSimulator;
import org.testcontainers.containers.Container;

/**
 * T35 (Part III §16, X.2.6 / VI.2.6): restore the database to an older snapshot while the broker still holds newer
 * messages. PostgreSQL only: the snapshot is {@code pg_dump -Fc} and the restore {@code DROP DATABASE .. WITH (FORCE)} +
 * {@code CREATE DATABASE} + {@code pg_restore}, all executed inside the Testcontainers PostgreSQL container
 * ({@code execInContainer}). The IFMS JIT simulator (capability class a: idempotent by operationId + PD lookup) and the
 * in-memory "broker" log are outside the database, so they keep everything that happened after the snapshot.
 *
 * <p>Flow (WF-1; one class per engine, Operaton and Flowable):
 * <ol>
 *   <li><b>Before the snapshot:</b> bills b1..b3 arrive as broker messages; inbox receive (dedup key tenant/source/
 *       messageId), inbox processing starts WF-1 (business start) in the same engine transaction that marks the message
 *       PROCESSED; checker + approver complete; the payment worker pays one bill completely, records the intent (lease
 *       held) of a second, and leaves the third waiting.</li>
 *   <li><b>Snapshot</b> (pg_dump).</li>
 *   <li><b>After the snapshot (lost by the restore):</b> the in-flight payment is dispatched and completed; the waiting
 *       bill is paid; a redelivery of an old message is dropped by the inbox; bills b4, b5 arrive and start; b4 is paid.</li>
 *   <li><b>Restore</b>: engine and pools closed, database replaced by the snapshot, engine restarted on it.</li>
 *   <li><b>Replay</b>: the broker consumer is rewound to before the snapshot offset (overlap of one message), every
 *       newer message is delivered again (and the whole log a second time); inbox processing and the payment workers
 *       run until there is no work left.</li>
 * </ol>
 * <b>Pass (T35-1):</b> one business start per bill (inbox dedup); no operationId paid twice in the simulator's effects
 * ledger; every bill's instance ended, every ledger operation SUCCEEDED with the paymentRef IFMS holds for that
 * operationId, no open reconciliation item. <b>T35-2</b> (informational) records what the pass criteria do not
 * cover: business-level double payments under a NEW operationId (a bill whose WF-1 start was lost gets new engine ids,
 * hence a new operationId, R36) and engine-id reuse after the restore.
 */
@EngineSelection.PostgresOnly
public abstract class AbstractT35RestoreTest extends ExperimentBase {

    static final String T = "tn-t35";
    static final String SOURCE = "bill-portal";
    static final long AMOUNT = 50_000L;
    static final Duration LEASE = Duration.ofSeconds(60);
    static final String DUMP = "/tmp/t35-snapshot.dump";

    /** Broker log entry (offset order). The broker is outside the database and survives the restore. */
    record BrokerMessage(int offset, String messageId, String billId) {
    }

    IfmsJitSimulator sim;
    Ifms ifms;
    final List<BrokerMessage> broker = new ArrayList<>();
    /** Committed consumer offset (next offset to read), kept by the broker, not by the database. */
    int consumerOffset;
    int snapshotOffset;
    Instant lastClock;

    /** bill -> role in the scenario, for the JSON. */
    final Map<String, String> role = new TreeMap<>();
    /** engine ids issued after the snapshot (lost by the restore): process instance ids and external work ids. */
    final Set<String> idsIssuedAfterSnapshot = new LinkedHashSet<>();
    final Set<String> idsIssuedAfterRestore = new LinkedHashSet<>();
    final Map<String, Object> phases = new LinkedHashMap<>();

    @Override
    protected String experiment() {
        return "T35";
    }

    @Override
    protected void setUp() {
        env.deploy(T, Workflows.WF1_FILES);
        lastClock = env.setClock(Instant.now());
        sim = new IfmsJitSimulator().capabilityClass(IfmsJitSimulator.CapabilityClass.A_IDEMPOTENT_AND_LOOKUP).start();
        ifms = new Ifms(sim);
        notes.put("snapshot", "pg_dump -Fc inside the container (execInContainer); restore: DROP DATABASE .. WITH (FORCE), "
                + "CREATE DATABASE, pg_restore --exit-on-error --no-owner");
        notes.put("broker", "in-memory log with a committed consumer offset, outside the database (stands in for Kafka "
                + "retention); replay = rewind the consumer to snapshot offset - 1");
        notes.put("ifms", "IfmsJitSimulator capability class (a); its effects ledger is the payment truth and is NOT restored");
        notes.put("covered", List.of(
                "inbox dedup by (tenant, source, messageId) across a restore: old messages replayed as DUPLICATE, lost ones ACCEPTED once",
                "business start (WF-1 startProcess) atomic with inbox PROCESSED (same engine transaction)",
                "payment retry after restore with the same operationId for instances that existed at snapshot time "
                        + "(engine ids restored), recognised by IFMS class (a)",
                "an intent + lease that was in flight at snapshot time: lease expiry, product sweep, same operationId, no second payment",
                "reconciliation: every bill ended, ledger SUCCEEDED and its paymentRef equals the IFMS paymentRef of that operationId"));
        notes.put("notCovered", List.of(
                "a bill whose start was lost by the restore restarts with NEW engine ids, so a NEW operationId (R36 derives it "
                        + "from engine identity); IFMS dedup by operationId cannot see the earlier payment: measured in T35-2, not prevented",
                "IFMS capability classes (b) and (c) after a restore (only class (a) here)",
                "outbox events published after the snapshot and re-published after the restore (consumer event_id dedup is E5-2)",
                "the audit chain of lost events (rows after the snapshot are gone; X.2.3 anchoring is not exercised)",
                "point-in-time recovery / WAL replay, Oracle and SQL Server restore tooling, multi-node engines",
                "a real broker (Kafka offsets, retention) and real timers during the restore window"));
    }

    @AfterAll
    void stopSimulator() {
        if (sim != null) {
            sim.close();
        }
    }

    // ------------------------------------------------------------------ broker + inbox

    private void publish(String messageId, String billId) {
        broker.add(new BrokerMessage(broker.size(), messageId, billId));
    }

    /** Delivers the log from {@code from} to the inbox (receive and commit, then ack = advance the offset). */
    private Map<String, Integer> consume(int from) {
        Inbox inbox = inbox();
        Map<String, Integer> result = new LinkedHashMap<>();
        for (int o = from; o < broker.size(); o++) {
            BrokerMessage msg = broker.get(o);
            Inbox.ReceiveResult r = inbox.receive(T, SOURCE, msg.messageId(), msg.billId(), msg.billId(), Duration.ofHours(24));
            result.merge(r.name(), 1, Integer::sum);
            consumerOffset = o + 1;
        }
        return result;
    }

    private Inbox inbox() {
        return new Inbox(env.dialect, env.tx, "inbox-t35", Duration.ofSeconds(30), Duration.ofMillis(1), env.audit);
    }

    /** Inbox processing: the business start (WF-1) commits with PROCESSED. Returns the bills started. */
    private List<String> processInbox(Set<String> recordIds) {
        List<String> started = new ArrayList<>();
        inbox().processOnce(100, (c, m) -> {
            String bill = m.correlationKey();
            String pid = env.engine.startProcess(T, "wf1-bill", bill, Workflows.wf1Vars(bill, AMOUNT, "seat-checker", "ACC-" + bill));
            started.add(bill);
            if (recordIds != null) {
                recordIds.add(pid);
            }
            return true;
        });
        return started;
    }

    private String pidOf(String bill) {
        List<String> ids = env.strings("SELECT ID_ FROM ACT_HI_PROCINST WHERE BUSINESS_KEY_ = ? AND TENANT_ID_ = ?", bill, T);
        if (ids.size() != 1) {
            throw new IllegalStateException(bill + ": " + ids.size() + " instances");
        }
        return ids.get(0);
    }

    private void toPayment(String bill) {
        String pid = pidOf(bill);
        env.engine.completeTask(Workflows.singleTask(env.engine, T, pid, "wf1_checkerReview").id(), Map.of("checkerDecision", "forward"));
        env.engine.completeTask(Workflows.singleTask(env.engine, T, pid, "wf1_approve").id(), Map.of("approverDecision", "approve"));
    }

    private PaymentWorker worker(String id) {
        return new PaymentWorker(env, id, T, ifms, CapabilityClass.A_IDEMPOTENT_WITH_LOOKUP, LEASE);
    }

    /**
     * Flowable continues a process after an external-worker completion through an async job (README "Flowable
     * continuation"); the async executor is off, so due jobs run here. On Operaton this executes nothing.
     */
    private void continueAsync() {
        int n = env.engine.executeDueJobs();
        m.merge("asyncJobsAfterCompletion", n, (a, b) -> (Integer) a + (Integer) b);
    }

    private static String bill(ExternalWork w) {
        return String.valueOf(w.variables().get("billId"));
    }

    // ------------------------------------------------------------------ snapshot / restore inside the container

    private Container.ExecResult exec(String... cmd) throws Exception {
        String[] full = new String[cmd.length + 2];
        full[0] = "env";
        full[1] = "PGPASSWORD=" + env.db.password();
        System.arraycopy(cmd, 0, full, 2, cmd.length);
        Container.ExecResult r = Pg.container().execInContainer(full);
        if (r.getExitCode() != 0) {
            throw new IllegalStateException(String.join(" ", cmd) + " -> exit " + r.getExitCode() + ": " + r.getStderr());
        }
        return r;
    }

    private Map<String, Object> snapshot() throws Exception {
        long t0 = System.nanoTime();
        exec("pg_dump", "-U", env.db.user(), "-d", env.db.name(), "-Fc", "-f", DUMP);
        String size = exec("sh", "-c", "wc -c < " + DUMP).getStdout().trim();
        return Map.of("tool", "pg_dump -Fc", "bytes", size, "millis", (System.nanoTime() - t0) / 1_000_000);
    }

    private Map<String, Object> restore() throws Exception {
        long t0 = System.nanoTime();
        env.close();   // engine + read pool: no connection may stay on the database
        String db = env.db.name();
        exec("psql", "-U", env.db.user(), "-d", "postgres", "-v", "ON_ERROR_STOP=1", "-c", "DROP DATABASE " + db + " WITH (FORCE)");
        exec("psql", "-U", env.db.user(), "-d", "postgres", "-v", "ON_ERROR_STOP=1", "-c", "CREATE DATABASE " + db);
        exec("pg_restore", "-U", env.db.user(), "-d", db, "--exit-on-error", "--no-owner", DUMP);
        env = Env.open(kind(), env.db);
        // the restored leases and external-task locks expire on the new engine clock
        lastClock = env.setClock(lastClock.plus(LEASE).plusSeconds(1));
        return Map.of("tool", "DROP DATABASE WITH (FORCE) + CREATE DATABASE + pg_restore", "millis", (System.nanoTime() - t0) / 1_000_000);
    }

    // ------------------------------------------------------------------ the scenario

    @Test
    @Order(1)
    @Scenario(id = "T35-1", name = "Restore to an older snapshot while the broker holds newer messages: inbox dedup and "
            + "operationIds prevent duplicate business starts and duplicate payments; instances reconciled", tests = {"T35"})
    void restoreWithNewerBrokerMessages() throws Exception {
        // ---------------- phase 1: before the snapshot
        publish("msg-b1", "b1");
        publish("msg-b2", "b2");
        publish("msg-b3", "b3");
        Map<String, Integer> c1 = consume(consumerOffset);
        List<String> started1 = processInbox(null);
        for (String b : started1) {
            toPayment(b);
        }
        PaymentWorker w1 = worker("worker-1");
        PaymentWorker.Run paidBefore = w1.runOnce(false);
        assertEquals(PaymentWorker.Outcome.COMPLETED, paidBefore.outcome());
        continueAsync();
        role.put(bill(paidBefore.work()), "paid and completed before the snapshot");
        ExternalWork inFlight = w1.fetch();
        OperationLedger.Intent inFlightIntent = w1.intent(inFlight);
        role.put(bill(inFlight), "intent + lease held at the snapshot; dispatched and completed after it (lost)");
        Set<String> waiting = new LinkedHashSet<>(started1);
        waiting.remove(bill(paidBefore.work()));
        waiting.remove(bill(inFlight));
        String waitingBill = waiting.iterator().next();
        role.put(waitingBill, "waiting at payment at the snapshot; paid and completed after it (lost)");
        phases.put("phase1", Map.of("consumed", c1, "started", started1));

        // ---------------- snapshot
        snapshotOffset = consumerOffset;
        phases.put("snapshot", snapshot());

        // ---------------- phase 2: after the snapshot (everything here is lost by the restore, except IFMS + broker)
        Ifms.Result pay = w1.dispatch(inFlight, inFlightIntent);
        assertTrue(pay.accepted());
        w1.complete(inFlight, inFlightIntent, pay, false);
        continueAsync();
        PaymentWorker.Run paidWaiting = w1.runOnce(false);
        assertEquals(waitingBill, bill(paidWaiting.work()));
        idsIssuedAfterSnapshot.add(paidWaiting.work().id());
        continueAsync();
        publish("msg-b2", "b2");          // broker redelivery of an old message
        publish("msg-b4", "b4");
        publish("msg-b5", "b5");
        Map<String, Integer> c2 = consume(consumerOffset);
        List<String> started2 = processInbox(idsIssuedAfterSnapshot);
        for (String b : started2) {
            toPayment(b);
        }
        PaymentWorker.Run paidNew = w1.runOnce(false);
        assertEquals(PaymentWorker.Outcome.COMPLETED, paidNew.outcome());
        idsIssuedAfterSnapshot.add(paidNew.work().id());
        continueAsync();
        role.put(bill(paidNew.work()), "started (inbox) and paid after the snapshot (lost); restarted by the replay");
        for (String b : started2) {
            role.putIfAbsent(b, "started (inbox) after the snapshot, not paid (lost); restarted by the replay");
        }
        Map<String, Long> paymentsBeforeRestore = paymentsByBill();
        phases.put("phase2", Map.of("consumed", c2, "started", started2, "ifmsPaymentsByBill", paymentsBeforeRestore));
        assertEquals(Map.of("ACCEPTED", 2, "DUPLICATE", 1), c2, "redelivery dropped before the restore as well");

        // ---------------- restore
        phases.put("restore", restore());
        Map<String, Object> afterRestore = new LinkedHashMap<>();
        for (String b : List.of("b1", "b2", "b3", "b4", "b5")) {
            afterRestore.put(b, env.instancesWithBusinessKey(b));
        }
        phases.put("instancesRightAfterRestore", afterRestore);

        // ---------------- replay + workers
        int from = Math.max(0, snapshotOffset - 1);
        Map<String, Integer> replay1 = consume(from);
        List<String> restarted = processInbox(idsIssuedAfterRestore);
        Map<String, Integer> replay2 = consume(0);           // the whole log once more: everything is a duplicate now
        List<String> restartedAgain = processInbox(idsIssuedAfterRestore);
        for (String b : restarted) {
            toPayment(b);
        }
        PaymentWorker w2 = worker("worker-2");
        List<String> runs = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            try {
                PaymentWorker.Run r = w2.runOnce(false);
                if (r.outcome() == PaymentWorker.Outcome.NO_WORK) {
                    break;
                }
                idsIssuedAfterRestore.add(r.work().id());
                runs.add(bill(r.work()) + ": " + r.outcome() + (r.pay() != null ? " duplicate=" + r.pay().duplicate() : ""));
            } catch (RuntimeException e) {
                runs.add("error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            continueAsync();
        }
        phases.put("replay", Map.of("fromOffset", from, "firstReplay", replay1, "restarted", restarted,
                "secondReplay", replay2, "restartedBySecondReplay", restartedAgain, "workerRuns", runs));

        // ---------------- checks
        Map<String, Object> perBill = new TreeMap<>();
        boolean allEnded = true;
        boolean ledgerConsistent = true;
        for (String b : List.of("b1", "b2", "b3", "b4", "b5")) {
            long starts = env.instancesWithBusinessKey(b);
            assertEquals(1, starts, "exactly one business start for " + b);
            String pid = pidOf(b);
            boolean ended = env.engine.getInstance(pid).ended();
            allEnded &= ended;
            List<Object[]> ops = env.rows("SELECT operation_id, state, outcome FROM OPERATION_LEDGER WHERE tenant_id = ? AND root_id = ?",
                    3, T, pid);
            List<String> opText = new ArrayList<>();
            for (Object[] op : ops) {
                String opId = (String) op[0];
                List<IfmsJitSimulator.Payment> atIfms = sim.payments(opId);
                boolean ok = "SUCCEEDED".equals(op[1]) && atIfms.size() == 1
                        && String.valueOf(op[2]).contains(atIfms.get(0).paymentRef());
                ledgerConsistent &= ok;
                opText.add(opId + " " + op[1] + " ifmsPayments=" + atIfms.size() + (ok ? " consistent" : " INCONSISTENT"));
            }
            ledgerConsistent &= ops.size() == 1;
            perBill.put(b, Map.of("role", role.getOrDefault(b, "?"), "instances", starts, "ended", ended, "ledger", opText));
        }
        long open = env.inTx(c -> env.reconciliation.listOpen(c, T)).size();
        Map<String, Long> dupOps = sim.ledger().duplicates(IfmsJitSimulator.EFFECT_PAYMENT);

        m.put("roles", role);
        m.put("phases", phases);
        m.put("perBill", perBill);
        m.put("duplicatePaymentsBySameOperationId", dupOps);
        m.put("openReconciliationItems", open);
        m.put("firstReplay", replay1);
        m.put("secondReplay", replay2);

        assertTrue(replay1.getOrDefault("DUPLICATE", 0) >= 1, "pre-snapshot message in the overlap is a duplicate");
        assertEquals(Map.of("DUPLICATE", broker.size()), replay2, "second replay: every message is a duplicate");
        assertTrue(restartedAgain.isEmpty(), "no business start from the second replay");
        assertTrue(dupOps.isEmpty(), "no operationId paid twice: " + dupOps);
        assertTrue(allEnded, "every bill's instance ended: " + perBill);
        assertTrue(ledgerConsistent, "ledger SUCCEEDED with the IFMS paymentRef of its operationId: " + perBill);
        assertEquals(0, open, "no open reconciliation item");
    }

    private Map<String, Long> paymentsByBill() {
        Map<String, Long> out = new TreeMap<>();
        for (EffectsLedger.Effect e : sim.ledger().ofType(IfmsJitSimulator.EFFECT_PAYMENT)) {
            out.merge(e.attributes().get("billId"), 1L, Long::sum);
        }
        return out;
    }

    @Test
    @Order(2)
    @Scenario(id = "T35-2", name = "Probe: what the T35 pass criteria do not cover: payments of the same bill under a "
            + "different operationId after the restore, and engine ids reused after the restore", tests = {"T35"},
            counted = false)
    void businessLevelDuplicatesAfterRestore() {
        Map<String, Long> byBill = paymentsByBill();
        Map<String, Long> paidMoreThanOnce = new TreeMap<>();
        byBill.forEach((b, n) -> {
            if (n > 1) {
                paidMoreThanOnce.put(b, n);
            }
        });
        // reconciliation diff: IFMS operations that the restored ledger does not know (orphans)
        List<String> orphans = new ArrayList<>();
        for (EffectsLedger.Effect e : sim.ledger().ofType(IfmsJitSimulator.EFFECT_PAYMENT)) {
            long known = env.count("SELECT count(*) FROM OPERATION_LEDGER WHERE operation_id = ?", e.key());
            if (known == 0) {
                orphans.add(e.attributes().get("billId") + " " + e.key() + " " + e.attributes().get("paymentRef"));
            }
        }
        Set<String> reused = new LinkedHashSet<>(idsIssuedAfterSnapshot);
        reused.retainAll(idsIssuedAfterRestore);
        m.put("ifmsPaymentsByBill", byBill);
        m.put("billsPaidMoreThanOnce", paidMoreThanOnce);
        m.put("orphanIfmsOperations", orphans);
        m.put("engineIdsIssuedAfterSnapshot", idsIssuedAfterSnapshot);
        m.put("engineIdsIssuedAfterRestore", idsIssuedAfterRestore);
        m.put("engineIdsReusedAfterRestore", reused);
        m.put("reading", "A bill paid more than once means: its WF-1 start was lost by the restore, the replayed message "
                + "started a new instance (new engine ids), so R36 gave a new operationId and IFMS class (a) paid again. "
                + "The orphan list is what a post-restore reconciliation (IFMS feed vs OPERATION_LEDGER) must flag. "
                + "Prevention needs a business-level key (e.g. retrySafeKey = billId or inbox messageId in the operationId, "
                + "or IFMS dedup by billId), not only engine identity.");
        // every business-level duplicate is detectable: each extra payment is an operation the restored ledger lacks
        long extra = paidMoreThanOnce.values().stream().mapToLong(n -> n - 1).sum();
        assertTrue(orphans.size() >= extra, "each extra payment shows up as an orphan IFMS operation: " + orphans);
    }
}
