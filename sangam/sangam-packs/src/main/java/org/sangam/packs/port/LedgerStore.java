// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.port;

import java.math.BigDecimal;

/**
 * State carried across instances and years (GP-48): budget balance and encumbrance (GP-11), leave balance, reservation
 * roster points, cumulative expenditure against a sanction (GP-42). Postings are idempotent by {@code reference}, so a
 * retried delegate never posts twice.
 */
public interface LedgerStore {

    BigDecimal balance(String tenantId, String ledger, String account);

    /**
     * Posts {@code delta} to the account.
     *
     * @param allowNegative false rejects a posting that would take the balance below zero
     * @return the result; {@code applied=false} with {@code reason=INSUFFICIENT_BALANCE} when rejected
     */
    Posting post(String tenantId, String ledger, String account, BigDecimal delta, String reference,
                 boolean allowNegative);

    /** Reverses an earlier posting by reference (release of an encumbrance, refund). Idempotent. */
    Posting reverse(String tenantId, String ledger, String account, String reference);

    record Posting(boolean applied, String reason, BigDecimal balanceAfter) {
    }
}
