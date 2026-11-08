// SPDX-License-Identifier: Apache-2.0
package org.sangam.ledger;

import java.time.Instant;

/**
 * A freeze order: blocks every reducing operation on the accounts matching the predicate until released (in-year
 * cuts, limitação de empenho, sanctions, court orders). One order can freeze many limits (INTL-PFM gap 5).
 * Null predicate fields match anything.
 */
public record FreezeOrder(String tenant, String id, String ledger, String period, String linePrefix, String holderPrefix,
                          String stage, String reason, String orderedBy, Instant at) {

    public boolean matches(AccountKey k) {
        return k.tenant().equals(tenant) && (ledger == null || ledger.equals(k.ledger()))
                && (period == null || period.equals(k.period())) && (linePrefix == null || k.line().startsWith(linePrefix))
                && (holderPrefix == null || k.holder().startsWith(holderPrefix)) && (stage == null || stage.equals(k.stage()));
    }
}
