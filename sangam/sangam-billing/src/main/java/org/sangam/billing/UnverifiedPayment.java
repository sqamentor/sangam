// SPDX-License-Identifier: Apache-2.0
package org.sangam.billing;

import org.sangam.core.money.Money;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One payment accepted without its channel's confirmation (RM-03, owner decision 141). The accepted fields are never
 * changed after acceptance; a resolution is recorded beside them ({@link #resolution}), so the original record and its
 * audit history stay.
 *
 * @param tenant             the tenant (from the authenticated caller)
 * @param id                 the record id
 * @param ledgerId           the receivable ledger the payment is for
 * @param consumer           the consumer (account holder)
 * @param office             the office or counter where it was taken (part of the ACCEPT_UNVERIFIED scope)
 * @param channel            a channel configured for unverified acceptance
 * @param paymentRef         the channel's payment reference; one record per (tenant, channel, paymentRef)
 * @param amount             the accepted amount (exact, ledger currency)
 * @param paidAt             when the payer paid, as stated
 * @param payerName          as stated, may be null
 * @param instrument         how the payer paid (CHEQUE, CASH, ...), may be null
 * @param details            further payment details as text, may be empty
 * @param reason             why the channel could not confirm it
 * @param evidenceRef        receipt or evidence reference
 * @param policyVersion      the version of the channel policy applied
 * @param downstreamPermitted the actions that policy permits while pending (empty: none)
 * @param acceptedBy         the authenticated principal that accepted it
 * @param acceptedAt         when it was accepted
 * @param state              the state; {@link State#PAYMENT_ACCEPTED_UNVERIFIED} is pending
 * @param version            incremented by every transition
 * @param resolution         present once it left the pending state
 * @param receiptNo          the receipt of the allocation that followed VERIFIED, null before
 */
public record UnverifiedPayment(String tenant, String id, String ledgerId, String consumer, String office,
                                String channel, String paymentRef, Money amount, Instant paidAt, String payerName,
                                String instrument, Map<String, String> details, String reason, String evidenceRef,
                                String policyVersion, List<String> downstreamPermitted, String acceptedBy,
                                Instant acceptedAt, State state, long version, Resolution resolution,
                                String receiptNo) {

    /** The states of decision 141: one pending state and three final ones. */
    public enum State {
        /** Pending verification: never verified, settled or reconciled; nothing is posted to the ledger. */
        PAYMENT_ACCEPTED_UNVERIFIED,
        /** The channel's confirmation exists (rail reconciliation or an authorised resolution with its reference). */
        VERIFIED,
        /** The payment never arrived. */
        REJECTED,
        /** The payment was returned (for example a dishonoured cheque). */
        REVERSED;

        public boolean pending() {
            return this == PAYMENT_ACCEPTED_UNVERIFIED;
        }
    }

    /**
     * How a pending payment was resolved. {@code verificationRef} is required for VERIFIED; {@code evidenceRef} is the
     * evidence a member of staff relied on for a manual VERIFIED (decision 196), null for a rail verification.
     */
    public record Resolution(State outcome, String reason, String verificationRef, String evidenceRef,
                             String resolvedBy, Instant resolvedAt) {
        public Resolution {
            Objects.requireNonNull(outcome, "outcome");
            if (outcome.pending()) {
                throw new IllegalArgumentException("a resolution leaves " + outcome);
            }
        }
    }

    public UnverifiedPayment {
        details = details == null ? Map.of() : Map.copyOf(details);
        downstreamPermitted = downstreamPermitted == null ? List.of() : List.copyOf(downstreamPermitted);
    }

    /** Whole days since acceptance (ageing). */
    public long ageDays(Instant now) {
        return Math.max(0, Duration.between(acceptedAt, now).toDays());
    }

    UnverifiedPayment resolved(Resolution r) {
        return new UnverifiedPayment(tenant, id, ledgerId, consumer, office, channel, paymentRef, amount, paidAt,
                payerName, instrument, details, reason, evidenceRef, policyVersion, downstreamPermitted, acceptedBy,
                acceptedAt, r.outcome(), version + 1, r, receiptNo);
    }
}
