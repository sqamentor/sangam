// SPDX-License-Identifier: Apache-2.0
package org.sangam.ledger;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Balance buckets of one account. One shape serves every ledger kind:
 * <table>
 *   <tr><th>bucket</th><th>LIMIT</th><th>QUANTITY</th><th>RECEIVABLE</th></tr>
 *   <tr><td>total</td><td>limit received from the parent stage / source</td><td>quantity received</td><td>demand raised</td></tr>
 *   <tr><td>distributed</td><td>passed down to child-stage accounts</td><td>transferred out to other locations</td><td>-</td></tr>
 *   <tr><td>committed</td><td>commitments (sanctions, orders)</td><td>reservations</td><td>-</td></tr>
 *   <tr><td>encumbered</td><td>obligations (bills in process)</td><td>picked, not issued</td><td>waived / written off</td></tr>
 *   <tr><td>consumed</td><td>expenditure / payments</td><td>issued</td><td>collected</td></tr>
 * </table>
 * {@code available = total - distributed - committed - encumbered - consumed} (for RECEIVABLE: the outstanding demand).
 *
 * @param state  OPEN, FROZEN (by a freeze order), EXPIRED (period of availability over: adjustments only), CANCELLED
 * @param attrs  account attributes used by policies (e.g. section, chargedVoted, expiry date of a lot, conditions)
 */
public record Balance(AccountKey key, String currency, BigDecimal total, BigDecimal distributed, BigDecimal committed,
                      BigDecimal encumbered, BigDecimal consumed, String state, long version, Map<String, Object> attrs) {

    public static final String OPEN = "OPEN";
    public static final String FROZEN = "FROZEN";
    public static final String EXPIRED = "EXPIRED";
    public static final String CANCELLED = "CANCELLED";

    public static Balance empty(AccountKey key, String currency) {
        return new Balance(key, currency, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, OPEN, 0, Map.of());
    }

    public BigDecimal available() {
        return total.subtract(distributed).subtract(committed).subtract(encumbered).subtract(consumed);
    }

    public BigDecimal bucket(Bucket b) {
        return switch (b) {
            case TOTAL -> total;
            case DISTRIBUTED -> distributed;
            case COMMITTED -> committed;
            case ENCUMBERED -> encumbered;
            case CONSUMED -> consumed;
        };
    }

    public Balance plus(Map<Bucket, BigDecimal> d) {
        return new Balance(key, currency, total.add(d.getOrDefault(Bucket.TOTAL, BigDecimal.ZERO)),
                distributed.add(d.getOrDefault(Bucket.DISTRIBUTED, BigDecimal.ZERO)),
                committed.add(d.getOrDefault(Bucket.COMMITTED, BigDecimal.ZERO)),
                encumbered.add(d.getOrDefault(Bucket.ENCUMBERED, BigDecimal.ZERO)),
                consumed.add(d.getOrDefault(Bucket.CONSUMED, BigDecimal.ZERO)), state, version + 1, attrs);
    }

    public Balance withState(String s) {
        return new Balance(key, currency, total, distributed, committed, encumbered, consumed, s, version + 1, attrs);
    }

    public Balance withAttrs(Map<String, Object> a) {
        return new Balance(key, currency, total, distributed, committed, encumbered, consumed, state, version + 1, Map.copyOf(a));
    }

    public enum Bucket { TOTAL, DISTRIBUTED, COMMITTED, ENCUMBERED, CONSUMED }
}
