// SPDX-License-Identifier: Apache-2.0
package org.sangam.onboarding.readiness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import org.sangam.onboarding.api.Problem;
import org.sangam.packs.spi.PackRegistry;
import org.sangam.packs.spi.SangamPack;

/**
 * SL-50: the bank payment checker's concurrence limit above the single-level band is not from a verified source. The
 * pack ships it as a declared placeholder, and readiness blocks go-live until the tenant overrides the decision.
 */
class PackPlaceholderReadinessTest {

    static SangamPack bankPayment() {
        return PackRegistry.discover().all().stream()
                .filter(p -> p.descriptor().id().equals("sec.bank.payment-dual-auth")).findFirst().orElseThrow();
    }

    @Test
    void unconfirmedConcurrenceLimitBlocksGoLive() {
        SangamPack p = bankPayment();
        assertEquals(1, p.descriptor().placeholders().size());
        assertEquals("sec-bank-pay-powers", p.descriptor().placeholders().get(0).decision());
        assertTrue(p.descriptor().placeholders().get(0).reason().contains("owner/bank policy confirmation required"));
        List<Problem> found = ReadinessChecker.packPlaceholders(Map.of(), List.of(p));
        assertEquals(1, found.size());
        assertTrue(found.get(0).isError());
        assertEquals("PACK_PLACEHOLDER_UNCONFIRMED", found.get(0).code());
    }

    @Test
    void tenantOverrideOfTheDecisionClearsIt() {
        assertTrue(ReadinessChecker.packPlaceholders(Map.of("sec-bank-pay-powers", "<definitions/>"),
                List.of(bankPayment())).isEmpty());
    }
}
