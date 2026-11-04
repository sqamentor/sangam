package org.sangam.spike.it.support;

import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.sangam.spike.api.EngineAdapter.ExternalWork;
import org.sangam.spike.api.LeaseTable;
import org.sangam.spike.layer.CanonicalJson;
import org.sangam.spike.layer.CapabilityClass;
import org.sangam.spike.layer.Hashes;
import org.sangam.spike.layer.Jdbc;
import org.sangam.spike.layer.OperationLedger;
import org.sangam.spike.layer.OperationLedger.Intent;
import org.sangam.spike.layer.Tombstones;

/**
 * The WF-1 payment worker as the product SDK would run it (VI.3, A-1, A-2, A-8), against the IFMS JIT simulator.
 *
 * <ol>
 *   <li>{@link #fetch}: product lease-expiry sweep ({@link #sweepExpiredLeases}), then {@code fetchAndLock}. The handler
 *       starts only after the LOCKED confirmation with its lockVersion (A-1).</li>
 *   <li>{@link #intent}: {@code OPERATION_LEDGER.recordIntent} in an engine transaction; operationId is derived from
 *       engine identity (tenant, root, activityInstanceId, loop counter), never from the attempt.</li>
 *   <li>{@link #dispatch}: PI to IFMS with the operationId as idempotency key (class b: PD lookup first on a retry).</li>
 *   <li>{@link #complete}: one engine transaction with the tombstone check (VI.5 rule 3), the ledger outcome (fenced
 *       by lockVersion) and {@code completeExternal} (fenced by SPIKE_LEASE).</li>
 * </ol>
 */
public final class PaymentWorker {

    public static final String TOPIC = "payment";

    public enum Outcome { COMPLETED, COMPLETED_FROM_STORED_OUTCOME, HELD_FOR_RECONCILIATION, CRASHED, GONE, NO_WORK }

    public record Run(Outcome outcome, ExternalWork work, Intent intent, Ifms.Result pay, Object detail) {
    }

    private final Env env;
    private final String workerId;
    private final String tenant;
    private final Ifms ifms;
    private final CapabilityClass declaredClass;
    private final Duration lease;

    /** @param declaredClass the topic's declared capability class; null = undeclared (A-8 default: class c) */
    public PaymentWorker(Env env, String workerId, String tenant, Ifms ifms, CapabilityClass declaredClass, Duration lease) {
        this.env = env;
        this.workerId = workerId;
        this.tenant = tenant;
        this.ifms = ifms;
        this.declaredClass = declaredClass;
        this.lease = lease;
    }

    public String workerId() {
        return workerId;
    }

    // ------------------------------------------------------------------ steps

    public ExternalWork fetch() {
        sweepExpiredLeases(env);
        List<ExternalWork> w = env.engine.fetchAndLock(workerId, TOPIC, 1, lease);
        return w.isEmpty() ? null : w.get(0);
    }

    public Intent intent(ExternalWork w) {
        return env.inTx(c -> {
            OperationLedger.OperationRef ref = new OperationLedger.OperationRef(tenant, w.processInstanceId(),
                    w.activityInstanceId(), 0);
            Intent i = env.ledger.recordIntent(c, ref, declaredClass, fingerprint(w), w.attemptId(), w.lockVersion());
            Jdbc.insertIfAbsent(c, env.dialect, "INSERT INTO IT_WORK_OPERATION(work_id, operation_id, tenant_id, root_id) VALUES (?,?,?,?)",
                    w.id(), i.operationId(), tenant, w.processInstanceId());
            return i;
        });
    }

    public Ifms.Result dispatch(ExternalWork w, Intent i) {
        CapabilityClass cls = declaredClass == null ? CapabilityClass.C_NEITHER : declaredClass;
        if (cls == CapabilityClass.B_LOOKUP_ONLY && i.attempts() > 1) {
            Ifms.Result look = ifms.lookup(i.operationId().toString());
            if (look.httpStatus() == 200 && "SUCCESS".equals(look.status())) {
                return new Ifms.Result(200, "ACCEPTED", true, look.paymentRef(), null, look.body());
            }
        }
        return ifms.pay(i.operationId().toString(), billId(w), amount(w), account(w));
    }

    /**
     * Records the outcome and completes the work in one engine transaction. Throws the lease/ledger stale exceptions
     * (409 STALE_LEASE) with nothing changed. Returns GONE (410) when the instance was deleted (tombstone).
     */
    public Run complete(ExternalWork w, Intent i, Ifms.Result pay, boolean storedOutcome) {
        String paymentRef = pay == null ? storedPaymentRef(i) : pay.paymentRef();
        String outcomeJson = CanonicalJson.write(Map.of("paymentRef", String.valueOf(paymentRef)));
        return env.inTx(c -> {
            Tombstones.LateResultDecision d = env.tombstones.onLateResult(c, tenant, w.id(), w.lockVersion(),
                    Tombstones.ResultKind.COMPLETE, true, outcomeJson);
            if (d instanceof Tombstones.Gone gone) {
                return new Run(Outcome.GONE, w, i, pay, gone);
            }
            if (!storedOutcome) {
                env.ledger.recordOutcome(c, i.operationId(), w.lockVersion(), true, outcomeJson);
            }
            Map<String, Object> vars = new LinkedHashMap<>();
            vars.put("paymentRef", paymentRef);
            vars.put("operationId", i.operationId().toString());
            env.engine.completeExternal(w.id(), workerId, w.lockVersion(), vars);
            return new Run(storedOutcome ? Outcome.COMPLETED_FROM_STORED_OUTCOME : Outcome.COMPLETED, w, i, pay, null);
        });
    }

    /** fetch → intent → dispatch → complete; {@code crashAfterDispatch} stops before reporting (process died). */
    public Run runOnce(boolean crashAfterDispatch) {
        ExternalWork w = fetch();
        if (w == null) {
            return new Run(Outcome.NO_WORK, null, null, null, null);
        }
        return handle(w, crashAfterDispatch);
    }

    public Run handle(ExternalWork w, boolean crashAfterDispatch) {
        Intent i = intent(w);
        if (!i.mayDispatch()) {
            if (i.state() == OperationLedger.State.SUCCEEDED) {
                return complete(w, i, null, true);
            }
            // UNKNOWN_OUTCOME (or FAILED): no automatic repeat; give the lease back, the operation waits for an officer.
            env.engine.unlockExternal(w.id(), workerId, w.lockVersion());
            return new Run(Outcome.HELD_FOR_RECONCILIATION, w, i, null, i.state());
        }
        Ifms.Result pay = dispatch(w, i);
        if (crashAfterDispatch) {
            return new Run(Outcome.CRASHED, w, i, pay, null);
        }
        if (!pay.accepted()) {
            throw new IllegalStateException("payment not accepted: " + pay);
        }
        return complete(w, i, pay, false);
    }

    // ------------------------------------------------------------------ product lease-expiry sweep

    /** One expired lease handled by the sweep. */
    public record Expired(String workId, long expiredVersion, UUID operationId, CapabilityClass.LeaseExpiryAction action) {
    }

    /**
     * Product-side lease-expiry sweep (VI.3.1 "LOCKED --lease expired--> AVAILABLE (lock_version+1)" and A-8), at the
     * engine clock, in one engine transaction: for each held, expired lease of a known operation it applies the
     * capability class through {@link OperationLedger#onLeaseExpired} with the version the expiry moves to, then ends
     * the leases ({@link LeaseTable#expire}). Class (c) opens an UNKNOWN_OUTCOME reconciliation item here.
     */
    public static List<Expired> sweepExpiredLeases(Env env) {
        Instant now = env.clock();
        return env.inTx(c -> sweep(c, env, now));
    }

    private static List<Expired> sweep(Connection c, Env env, Instant now) {
        // Expired held leases, row-locked by primary key in work_id order with the dialect's lock clause (product-api
        // LeaseTable, A-15). Stage B used "SELECT ... JOIN IT_WORK_OPERATION ... FOR UPDATE OF l" (PostgreSQL only);
        // the set of rows handled is the same: held, expired, with a known operation.
        List<Expired> out = new ArrayList<>();
        for (LeaseTable.Row lease : LeaseTable.lockExpired(c, now)) {
            String op = Jdbc.one(c, "SELECT operation_id FROM IT_WORK_OPERATION WHERE work_id = ?", rs -> rs.getString(1),
                    lease.workId()).orElse(null);
            if (op == null) {
                continue;
            }
            UUID operationId = UUID.fromString(op);
            long v = lease.lockVersion();
            out.add(new Expired(lease.workId(), v, operationId, env.ledger.onLeaseExpired(c, operationId, v + 1)));
        }
        LeaseTable.expire(c, now);
        return out;
    }

    // ------------------------------------------------------------------ helpers

    private String storedPaymentRef(Intent i) {
        String stored = i.storedOutcome();
        if (stored == null) {
            return null;
        }
        int k = stored.indexOf("\"paymentRef\":\"");
        if (k < 0) {
            return null;
        }
        int s = k + "\"paymentRef\":\"".length();
        return stored.substring(s, stored.indexOf('"', s));
    }

    static byte[] fingerprint(ExternalWork w) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("billId", billId(w));
        m.put("amount", Long.toString(amount(w)));
        m.put("account", account(w));
        return Hashes.sha256(CanonicalJson.write(m));
    }

    static String billId(ExternalWork w) {
        return String.valueOf(w.variables().get("billId"));
    }

    static long amount(ExternalWork w) {
        return ((Number) w.variables().get("amount")).longValue();
    }

    static String account(ExternalWork w) {
        return String.valueOf(w.variables().get("beneficiaryAccount"));
    }
}
