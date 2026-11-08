// SPDX-License-Identifier: Apache-2.0
package org.sangam.ledger;

import java.math.BigDecimal;
import java.util.Map;

/**
 * One account leg of an atomic ledger posting.
 *
 * @param deltas         bucket changes
 * @param guardAvailable if not null, the leg applies only when {@code available} after the change stays &gt;= this
 *                       value (0 for "no oversell"); evaluated by the store in the same statement / under the row lock
 * @param requireOpen    refuse when the account is FROZEN / EXPIRED / CANCELLED
 */
public record Movement(AccountKey key, String currency, Map<Balance.Bucket, BigDecimal> deltas, BigDecimal guardAvailable,
                       boolean requireOpen) {

    public Movement {
        deltas = Map.copyOf(deltas);
    }

    /** Net change of {@code available} that this leg causes. */
    public BigDecimal availableDelta() {
        BigDecimal d = deltas.getOrDefault(Balance.Bucket.TOTAL, BigDecimal.ZERO);
        for (Balance.Bucket b : new Balance.Bucket[]{Balance.Bucket.DISTRIBUTED, Balance.Bucket.COMMITTED,
                Balance.Bucket.ENCUMBERED, Balance.Bucket.CONSUMED}) {
            d = d.subtract(deltas.getOrDefault(b, BigDecimal.ZERO));
        }
        return d;
    }
}
