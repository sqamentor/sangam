// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.spi;

/**
 * Owner decision A1 (OWNER-DECISIONS-Q1-15, ARCH-JURIS): the direction of money a pack connector moves. Every connector
 * a pack declares names one; a manifest without it is refused (never defaulted).
 * <ul>
 *   <li>{@link #OUT}: payments, DBT, refunds, debits, settlements. Always needs a recorded human or delegated-power
 *       approval bound to the material payload; no process variable can switch that off.</li>
 *   <li>{@link #IN}: fee collections and receipts. No approval step, but ledger posting, receipt idempotency and
 *       reconciliation still apply.</li>
 *   <li>{@link #NONE}: no money moves (queries, documents, notifications, internal budget ledgers).</li>
 * </ul>
 */
public enum MoneyMovement {
    OUT, IN, NONE;

    /** Parses a manifest value; null, blank or unknown is refused. */
    public static MoneyMovement parse(String connectorId, String v) {
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("connector " + connectorId + ": moneyMovement (OUT, IN or NONE) is required");
        }
        try {
            return valueOf(v.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("connector " + connectorId + ": moneyMovement must be OUT, IN or NONE, not " + v);
        }
    }
}
