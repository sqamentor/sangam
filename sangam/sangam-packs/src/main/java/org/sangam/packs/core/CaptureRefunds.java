// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.core;

import java.math.BigDecimal;

import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.ExecutionListener;
import org.operaton.bpm.engine.delegate.Expression;

import org.sangam.packs.port.LedgerStore;
import org.sangam.packs.port.PackRuntime;

/**
 * Owner decision M5: the capture record a compensation refund is checked against. One ledger account per capture
 * ({@link #LEDGER}, account = the capture reference) holds the captured amount in its currency.
 * <ul>
 *   <li>{@link Record} (end listener of the capture step): when the step succeeded ({@code outcomeVar} equals
 *       {@code success}), snapshots the capture reference ({@code captureVar}) into {@code snapshotVar}, so a later
 *       execution cannot overwrite it, and posts the captured amount (reference {@value #CAPTURE_REF}, idempotent).</li>
 *   <li>{@link Release} (start listener of the refund in the compensation handler): auto-releases only when the refund
 *       is at most the captured amount, goes to the same source ({@code capture:<ref>}) in the same currency, and no
 *       earlier refund exists against that capture. The refund posts with the one reference {@value #REFUND_REF}, so it
 *       is idempotent on the capture id: a replay of the same refund passes, any other refund on that capture is
 *       refused. Anything else is held ({@code MONEY_OUT_HELD}): it needs a fresh sanction.</li>
 * </ul>
 */
public final class CaptureRefunds {

    /** The ledger of capture records. */
    public static final String LEDGER = "capture-record";
    static final String CAPTURE_REF = "capture";
    static final String REFUND_REF = "refund";
    /** The approver recorded on an auto-released refund (never the deemed principal). */
    public static final String RELEASED_BY = "capture-record";

    private CaptureRefunds() {
    }

    /** End listener of the capture step. Fields: captureVar, snapshotVar, outcomeVar, success, amount (variable). */
    public static class Record implements ExecutionListener {
        private Expression captureVar;
        private Expression snapshotVar;
        private Expression outcomeVar;
        private Expression success;
        private Expression amount;

        @Override
        public void notify(DelegateExecution ex) {
            String outcome = Vars.str(ex, Vars.field(outcomeVar, ex, "executionOutcome"));
            if (!Vars.field(success, ex, "DONE").equals(outcome)) {
                return; // nothing was captured
            }
            Object ref = ex.getVariable(Vars.field(captureVar, ex, "opExternalRef"));
            if (ref == null || ref.toString().isBlank()) {
                throw BatchSeal.held("the capture succeeded without a capture reference; a refund could never be "
                        + "matched to it (owner decision M5)");
            }
            BigDecimal minor = Vars.decimal(ex.getVariable(Vars.field(amount, ex, "amount")));
            String ccy = MoneyFieldsDelegate.currencyOf(ex);
            ex.setVariable(Vars.field(snapshotVar, ex), ref.toString());
            LedgerStore.Posting p = PackRuntime.ledgers().post(Vars.tenant(ex), LEDGER, ref.toString(), ccy,
                    LedgerStore.amount(minor.longValueExact(), ccy), CAPTURE_REF, false);
            if (!p.applied()) {
                throw BatchSeal.held("capture record of " + ref + " refused: " + p.reason());
            }
            Vars.audit(ex, "CAPTURE_RECORDED", Vars.map("capture", ref.toString(), "amount", minor, "currency", ccy));
        }
    }

    /**
     * Start listener of the refund: binds the money fields (as {@link MoneyFieldsDelegate} with {@code captureVar}),
     * checks the capture record and records the release as the approval of this refund.
     */
    public static class Release extends MoneyFieldsDelegate {
        @Override
        public void execute(DelegateExecution ex) {
            super.execute(ex);
            String account = Vars.str(ex, "payeeAccount");
            String ref = account.substring("capture:".length());
            String ccy = Vars.str(ex, "currency");
            long minor = Vars.decimal(ex.getVariable("amount")).longValueExact();
            String tenant = Vars.tenant(ex);
            BigDecimal captured = PackRuntime.ledgers().balance(tenant, LEDGER, ref);
            if (captured == null || captured.signum() <= 0) {
                throw hold(ex, "no capture record with a balance for " + ref + "; the refund needs a fresh sanction");
            }
            LedgerStore.Posting p = PackRuntime.ledgers().post(tenant, LEDGER, ref, ccy,
                    LedgerStore.amount(minor, ccy).negate(), REFUND_REF, false);
            if (!p.applied()) {
                throw hold(ex, "refund of " + minor + " " + ccy + " against capture " + ref + " refused (" + p.reason()
                        + "): more than captured, another currency or an earlier refund; it needs a fresh sanction");
            }
            ex.setVariable(Vars.APPROVED_HASH, Vars.materialHash(ex));
            ex.setVariable("approvedBy", RELEASED_BY);
            Vars.audit(ex, "CAPTURE_REFUND_RELEASED", Vars.map("capture", ref, "amount", minor, "currency", ccy,
                    "replay", "DUPLICATE_REFERENCE".equals(p.reason())));
        }
    }
}
