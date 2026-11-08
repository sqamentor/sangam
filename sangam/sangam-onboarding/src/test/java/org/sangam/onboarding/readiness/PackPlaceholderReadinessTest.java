// SPDX-License-Identifier: Apache-2.0
package org.sangam.onboarding.readiness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import org.sangam.onboarding.api.Problem;
import org.sangam.packs.spi.PackDescriptor;
import org.sangam.packs.spi.PackRegistry;
import org.sangam.packs.spi.SangamPack;

/**
 * SL-50: the concurrence rule of the bank payment pack is decided by the owner (2026-11-08: the checker's concurrence
 * is capped at the band of the authoriser covering the amount), so it is no longer an unconfirmed placeholder. What is
 * still genuinely tenant-specific is the SYNTHETIC band amounts, which live in two decisions (the limits table that
 * yields {@code authBandMax} and the powers table): both are declared placeholders and readiness blocks go-live until
 * the tenant overrides both.
 */
class PackPlaceholderReadinessTest {

    static final List<String> BAND_DECISIONS = List.of("sec-bank-pay-limits", "sec-bank-pay-powers");

    static SangamPack bankPayment() {
        return PackRegistry.discover().all().stream()
                .filter(p -> p.descriptor().id().equals("sec.bank.payment-dual-auth")).findFirst().orElseThrow();
    }

    @Test
    void syntheticBandAmountsBlockGoLiveButTheDecidedCapRuleDoesNot() {
        SangamPack p = bankPayment();
        List<PackDescriptor.Placeholder> ph = p.descriptor().placeholders();
        assertEquals(BAND_DECISIONS, ph.stream().map(PackDescriptor.Placeholder::decision).toList());
        for (PackDescriptor.Placeholder x : ph) {
            assertTrue(x.reason().contains("band amounts are SYNTHETIC"), x.reason());
            assertTrue(x.reason().contains("SL-50, 2026-11-08"), "the decided cap rule is cited: " + x.reason());
            assertFalse(x.reason().contains("owner/bank policy confirmation required"), "cap rule no longer open");
        }
        List<Problem> found = ReadinessChecker.packPlaceholders(Map.of(), List.of(p));
        assertEquals(2, found.size());
        assertTrue(found.stream().allMatch(Problem::isError));
        assertTrue(found.stream().allMatch(f -> "PACK_PLACEHOLDER_UNCONFIRMED".equals(f.code())));
    }

    @Test
    void overridingOnlyOneBandDecisionStillBlocks() {
        assertEquals(1, ReadinessChecker.packPlaceholders(Map.of("sec-bank-pay-powers", "<definitions/>"),
                List.of(bankPayment())).size());
    }

    @Test
    void tenantOverrideOfBothBandDecisionsClearsIt() {
        assertTrue(ReadinessChecker.packPlaceholders(
                Map.of("sec-bank-pay-limits", "<definitions/>", "sec-bank-pay-powers", "<definitions/>"),
                List.of(bankPayment())).isEmpty());
    }
}
