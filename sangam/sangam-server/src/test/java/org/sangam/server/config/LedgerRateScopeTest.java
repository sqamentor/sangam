// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.sangam.ledger.LedgerPorts;

/** SL-104 (ADR-D-20): the ledger's exchange-rate profile scope has no country default. */
class LedgerRateScopeTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 12);

    @Test
    void sameCurrencyNeedsNoScope() {
        LedgerPorts.RatePort rates = LedgerWiring.profileRates(null, () -> null, t -> DAY);
        assertEquals(Optional.of(BigDecimal.ONE), rates.rate("XAA", "XAA", "OFFICIAL", DAY));
    }

    @Test
    void aCrossCurrencyRateWithoutAScopeFailsNamingTheProperty() {
        for (String unset : new String[]{null, "", "  "}) {
            LedgerPorts.RatePort rates = LedgerWiring.profileRates(unset, () -> null, t -> DAY);
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> rates.rate("XAA", "XBB", "OFFICIAL", DAY));
            assertTrue(e.getMessage().contains("sangam.ledger.rate-scope"), e.getMessage());
        }
    }

    @Test
    void aConfiguredScopeWithoutAProfileResolverHasNoRate() {
        LedgerPorts.RatePort rates = LedgerWiring.profileRates("scope-1", () -> null, t -> DAY);
        assertEquals(Optional.empty(), rates.rate("XAA", "XBB", "OFFICIAL", DAY));
    }
}