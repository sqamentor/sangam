// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.acceptance.contracts;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import org.sangam.core.api.EngineAdapter.ExternalWork;
import org.sangam.core.engine.SangamEngineAdapter;
import org.sangam.core.engine.SangamEngineAdapter.LeasedWork;
import org.sangam.core.layer.CanonicalJson;
import org.sangam.core.layer.CapabilityClass;
import org.sangam.core.layer.Hashes;
import org.sangam.core.layer.OperationLedger;
import org.sangam.core.layer.OperationLedger.Intent;
import org.sangam.core.layer.Tombstones;

/**
 * The Stage B WF-1 payment worker (VI.3, A-1, A-2, A-8) re-expressed on Sangam's engine adapter: the lease comes from
 * {@link SangamEngineAdapter#fetchAndLockWork} with the engine's T35 operationId; lease expiry is decided inside the
 * adapter by the server's A-10 {@code ExpiryPolicy} (no product-side sweep is run by the worker any more).
 *
 * <ol>
 *   <li>{@link #fetch}: {@code fetchAndLockWork}; the handler starts only after the LOCKED confirmation.</li>
 *   <li>{@link #intent}: {@code OperationLedger.recordIntent} with the leased operationId, in an engine transaction.</li>
 *   <li>{@link #dispatch}: PI to IFMS with the operationId as idempotency key (class b: PD lookup first on a retry).</li>
 *   <li>{@link #complete}: one engine transaction: tombstone check (VI.5 rule 3), ledger outcome (fenced by
 *       lockVersion), {@code completeExternal} (fenced by the adapter's lease row).</li>
 * </ol>
 */
final class PaymentWorker {

    enum Outcome { COMPLETED, COMPLETED_FROM_STORED_OUTCOME, HELD_FOR_RECONCILIATION, CRASHED, GONE, NO_WORK }

    record Run(Outcome outcome, LeasedWork leased, Intent intent, Ifms.Result pay, Object detail) {
        ExternalWork work() {
            return leased == null ? null : leased.work();
        }
    }

    interface EngineTx {
        <T> T run(Function<java.sql.Connection, T> work);
    }

    private final SangamEngineAdapter engine;
    private final OperationLedger ledger;
    private final Tombstones tombstones;
    private final String workerId;
    private final String tenant;
    private final String topic;
    private final Ifms ifms;
    private final CapabilityClass declaredClass;
    private final Duration lease;

    /** @param declaredClass the topic's declared capability class; null = undeclared (A-8 default: class c) */
    PaymentWorker(SangamEngineAdapter engine, OperationLedger ledger, Tombstones tombstones, String workerId, String tenant,
                  String topic, Ifms ifms, CapabilityClass declaredClass, Duration lease) {
        this.engine = engine;
        this.ledger = ledger;
        this.tombstones = tombstones;
        this.workerId = workerId;
        this.tenant = tenant;
        this.topic = topic;
        this.ifms = ifms;
        this.declaredClass = declaredClass;
        this.lease = lease;
    }

    String workerId() {
        return workerId;
    }

    LeasedWork fetch() {
        List<LeasedWork> w = engine.fetchAndLockWork(workerId, topic, 1, lease);
        return w.isEmpty() ? null : w.get(0);
    }

    Intent intent(LeasedWork lw) {
        ExternalWork w = lw.work();
        return engine.inEngineTransaction(c -> ledger.recordIntent(c, lw.operationId(),
                new OperationLedger.OperationRef(tenant, w.processInstanceId(), w.activityInstanceId(), 0),
                declaredClass, fingerprint(w), w.attemptId(), w.lockVersion()));
    }

    Ifms.Result dispatch(LeasedWork lw, Intent i) {
        CapabilityClass cls = declaredClass == null ? CapabilityClass.C_NEITHER : declaredClass;
        ExternalWork w = lw.work();
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
    Run complete(LeasedWork lw, Intent i, Ifms.Result pay, boolean storedOutcome) {
        ExternalWork w = lw.work();
        String paymentRef = pay == null ? storedPaymentRef(i) : pay.paymentRef();
        String outcomeJson = CanonicalJson.write(Map.of("paymentRef", String.valueOf(paymentRef)));
        return engine.inEngineTransaction(c -> {
            Tombstones.LateResultDecision d = tombstones.onLateResult(c, tenant, w.id(), w.lockVersion(),
                    Tombstones.ResultKind.COMPLETE, true, outcomeJson);
            if (d instanceof Tombstones.Gone gone) {
                return new Run(Outcome.GONE, lw, i, pay, gone);
            }
            if (!storedOutcome) {
                ledger.recordOutcome(c, i.operationId(), w.lockVersion(), true, outcomeJson);
            }
            Map<String, Object> vars = new LinkedHashMap<>();
            vars.put("paymentRef", paymentRef);
            vars.put("operationId", i.operationId().toString());
            engine.completeExternal(w.id(), workerId, w.lockVersion(), vars);
            return new Run(storedOutcome ? Outcome.COMPLETED_FROM_STORED_OUTCOME : Outcome.COMPLETED, lw, i, pay, null);
        });
    }

    /** fetch → intent → dispatch → complete; {@code crashAfterDispatch} stops before reporting (process died). */
    Run runOnce(boolean crashAfterDispatch) {
        LeasedWork w = fetch();
        if (w == null) {
            return new Run(Outcome.NO_WORK, null, null, null, null);
        }
        return handle(w, crashAfterDispatch);
    }

    Run handle(LeasedWork w, boolean crashAfterDispatch) {
        Intent i = intent(w);
        if (!i.mayDispatch()) {
            if (i.state() == OperationLedger.State.SUCCEEDED) {
                return complete(w, i, null, true);
            }
            // UNKNOWN_OUTCOME (or FAILED): no automatic repeat; give the lease back, the operation waits for an officer.
            engine.unlockExternal(w.work().id(), workerId, w.work().lockVersion());
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

    static UUID op(Run r) {
        return r.intent().operationId();
    }

    private static String storedPaymentRef(Intent i) {
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
