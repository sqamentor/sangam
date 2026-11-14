// SPDX-License-Identifier: Apache-2.0
package org.sangam.billing;

import org.sangam.core.money.Money;
import org.sangam.ledger.AccountKey;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Penalty and interest accrual while a payment is pending (owner decision 195). {@link BillingService#levyPenalties}
 * reads the consumer's pending unverified payments here; the part of the arrears a pending payment covers (oldest due
 * first, the receipt plan's order) stops accruing from the business date of its acceptance, and the amount not levied
 * is recorded per payment ({@link #recordPauses}). VERIFIED forgives it (the payment counts from acceptance); REJECTED
 * and REVERSED levy it after all ({@link UnverifiedPayments}, audit {@code PAYMENT_UNVERIFIED_ACCRUAL_RESUMED}).
 * {@link JdbcUnverifiedPaymentStore} implements it on {@code BILL_UNVERIFIED_ACCRUAL_PAUSE}.
 */
public interface AccrualPauses {

    /** A pending payment of the consumer: the amount it covers counts as paid at {@code acceptedAt} while pending. */
    record Pending(String paymentId, Money amount, Instant acceptedAt) {
    }

    /**
     * Penalty not levied on {@code baseAccount} at levy date {@code asOf} because {@code paymentId} was pending; it
     * belongs on {@code penaltyAccount} if the payment is REJECTED or REVERSED.
     *
     * @param pausedFrom the business date of the payment's acceptance, from which the covered part stopped accruing
     */
    record Pause(String paymentId, String baseAccountId, LocalDate asOf, LocalDate pausedFrom,
                 AccountKey penaltyAccount, Money suppressed) {
    }

    /** No pause is ever applied (a host without unverified payments). */
    AccrualPauses NONE = new AccrualPauses() {
        @Override
        public List<Pending> pending(String tenant, String ledgerId, String consumer) {
            return List.of();
        }

        @Override
        public Map<String, Money> pausedOn(String tenant, String ledgerId, String consumer, LocalDate asOf) {
            return Map.of();
        }

        @Override
        public boolean recordPauses(String tenant, String ledgerId, String consumer, LocalDate asOf,
                                    List<String> paymentIds, List<Pause> pauses) {
            return paymentIds.isEmpty();
        }
    };

    /** The consumer's pending payments on the ledger, oldest acceptance first. */
    List<Pending> pending(String tenant, String ledgerId, String consumer);

    /**
     * The base accounts of the consumer that already have pauses for levy date {@code asOf}, with the total paused on
     * each, whatever the payments' states now. The first levy of a date decides such an account: a later levy of the
     * same date records no new pause for it and levies only the unpaused penalty less that total (the ledger keeps the
     * first posting of the date, so this completes a levy interrupted between its pauses and its postings). Otherwise a
     * payment REJECTED in between would be levied twice, once by its resumption and once by the repeated levy.
     */
    Map<String, Money> pausedOn(String tenant, String ledgerId, String consumer, LocalDate asOf);

    /**
     * Records the pauses of one levy atomically: every payment in {@code paymentIds} is locked and must still be pending.
     * A pause already recorded for the same (payment, base account, levy date) is kept, as the ledger keeps the first
     * posting of a levy date (reference {@code pen:<account>:<date>}): the first levy of a date decides both.
     *
     * @return false, with nothing written, when one of them is no longer pending (the levy is computed again)
     */
    boolean recordPauses(String tenant, String ledgerId, String consumer, LocalDate asOf, List<String> paymentIds,
                         List<Pause> pauses);
}
