// SPDX-License-Identifier: Apache-2.0
package org.sangam.billing;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Storage of unverified payments (RM-03, owner decisions 141 and 195). Each write and its audit event commit in one
 * transaction ({@link JdbcUnverifiedPaymentStore}: tables {@code BILL_UNVERIFIED_PAYMENT} and
 * {@code BILL_UNVERIFIED_ACCRUAL_PAUSE}, audit through {@code AUDIT_PENDING}). It is also the {@link AccrualPauses} of
 * {@link BillingService}.
 */
public interface UnverifiedPaymentStore extends AccrualPauses {

    /** A transition decided on the locked row: the next record and its audit event. */
    record Transition(UnverifiedPayment next, String auditType, Map<String, Object> audit) {
    }

    /**
     * A filtered page of one ledger's records, oldest acceptance first. Every filter is applied by the store before
     * paging, so a page is never cut from a truncated list.
     *
     * @param state          null: every state
     * @param channel        null: every channel
     * @param office         null: every office
     * @param acceptedBefore null: any age; else only records accepted at or before it
     * @param offset         first row (0-based)
     * @param limit          page size, at least 1
     */
    record Query(String ledgerId, UnverifiedPayment.State state, String channel, String office,
                 Instant acceptedBefore, int offset, int limit) {
        public Query {
            if (ledgerId == null || ledgerId.isBlank()) {
                throw new IllegalArgumentException("a query names its ledger");
            }
            if (offset < 0 || limit < 1) {
                throw new IllegalArgumentException("offset must be >= 0 and limit >= 1");
            }
        }
    }

    /** One page and the number of records matching the filters. */
    record Slice(List<UnverifiedPayment> items, long total) {
        public Slice {
            items = List.copyOf(items);
        }
    }

    /**
     * Writes a new record and its audit event in one transaction.
     *
     * @return false, with nothing written, when (tenant, channel, paymentRef) already exists
     */
    boolean insert(UnverifiedPayment p, String auditType, Map<String, Object> audit);

    Optional<UnverifiedPayment> find(String tenant, String id);

    Optional<UnverifiedPayment> findByRef(String tenant, String channel, String paymentRef);

    /** Oldest first; {@code state} null: every state. */
    List<UnverifiedPayment> list(String tenant, UnverifiedPayment.State state, int limit);

    /** A filtered page of the tenant's records ({@link Query}). */
    Slice query(String tenant, Query query);

    /**
     * Tenants with reconciliation work: a pending record, a VERIFIED one not yet allocated, or a REJECTED or REVERSED
     * one whose paused accrual is not yet levied.
     */
    List<String> tenantsWithWork(int limit);

    /** VERIFIED records whose allocation has not been recorded yet (an interrupted allocation is completed). */
    List<UnverifiedPayment> verifiedNotAllocated(String tenant, int limit);

    /**
     * Locks the record's row, gives the locked record to {@code decide} (which rechecks state and authorisation and
     * throws to refuse), then writes the decided record, guarded by the locked version, and its audit event, all in one
     * transaction. A transition to VERIFIED adds the payment's accrual pauses, read under the same lock, to its audit
     * event as {@code accrual_forgiven} (decision 195: VERIFIED forgives them).
     *
     * @throws UnverifiedPaymentException NOT_FOUND when the record does not exist in the tenant
     */
    UnverifiedPayment transition(String tenant, String id, Function<UnverifiedPayment, Transition> decide);

    /** Records the receipt of the allocation that followed VERIFIED (once: a set receipt is kept). */
    void receipted(String tenant, String id, String receiptNo);

    /** Every accrual pause of the payment (forgiven or levied ones included). */
    List<Pause> pausesOf(String tenant, String paymentId);

    /** The pauses of the payment not yet levied after a REJECTED or REVERSED resolution. */
    List<Pause> pausesToResume(String tenant, String paymentId);

    /** REJECTED or REVERSED records with pauses not yet levied (an interrupted resumption is completed). */
    List<UnverifiedPayment> resumptionDue(String tenant, int limit);

    /**
     * Marks the payment's unlevied pauses levied and writes {@code auditType} with them, in one transaction.
     *
     * @return false, with nothing written, when none was left to mark (another node completed it)
     */
    boolean markResumed(String tenant, String paymentId, Instant at, String auditType, Map<String, Object> audit);
}
