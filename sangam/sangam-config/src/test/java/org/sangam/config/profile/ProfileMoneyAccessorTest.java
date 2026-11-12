// SPDX-License-Identifier: Apache-2.0
package org.sangam.config.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import org.sangam.config.common.ConfigException;
import org.sangam.core.money.Money;

/**
 * SL-102: a profile money value is an integer of minor units of the profile's own {@code locale.currency}
 * (SD-398 / PACKS4), read as typed {@link Money}. Free text ("2CR"), numeric strings, decimals and floats are refused,
 * and so is a profile without a currency: nothing is guessed and no country default exists.
 */
class ProfileMoneyAccessorTest {

    private static ResolvedProfile profile(String currency, Object value) {
        Map<String, Object> locale = new HashMap<>();
        if (currency != null) {
            locale.put("currency", currency);
        }
        Map<String, Object> data = new HashMap<>();
        data.put("locale", locale);
        if (value != null) {
            data.put("limits", Map.of("upTo", value));
        }
        return new ResolvedProfile("T", null, LocalDate.of(2026, 11, 12), data, List.of("T@1"), "T@1#abc123",
                Map.of(), List.of());
    }

    @Test
    void anIntegerIsMinorUnitsOfTheProfileCurrency() {
        assertEquals(Money.ofMinor(1234, "USD"), profile("USD", 1234).money("limits.upTo"));
        assertEquals(Money.ofMinor(100_000_000_000L, "USD"), profile("USD", 100_000_000_000L).money("limits.upTo"));
        assertEquals(Money.ofMinor(7, "USD"), profile("USD", BigInteger.valueOf(7)).money("limits.upTo"));
        assertEquals(Money.ofMinor(-5, "USD"), profile("USD", -5).money("limits.upTo"));
    }

    @Test
    void theCurrencyIsTheProfilesOwnWhateverItsExponent() {
        Money yen = profile("JPY", 500).money("limits.upTo");
        assertEquals("JPY", yen.currencyCode());
        assertEquals(0, yen.exponent());
        assertEquals(500, yen.minor());
        Money dinar = profile("KWD", 1500).money("limits.upTo");
        assertEquals("KWD", dinar.currencyCode());
        assertEquals(3, dinar.exponent());
        assertEquals(0, new BigDecimal("1.500").compareTo(dinar.toMajor()));
    }

    @Test
    void freeTextNumericStringsDecimalsAndFloatsAreRefused() {
        for (Object v : List.of("2CR", "5L", "10K", "50000", "12.50", "₹5,00,000", 12.5d, 3.0f, new BigDecimal("100"),
                new BigDecimal("100.25"), true)) {
            ConfigException e = assertThrows(ConfigException.class, () -> profile("USD", v).money("limits.upTo"),
                    "refused: " + v + " (" + v.getClass().getSimpleName() + ")");
            assertEquals("MONEY_NOT_MINOR_UNITS", e.code(), String.valueOf(v));
            assertTrue(e.getMessage().contains("limits.upTo"), e.getMessage());
            assertTrue(e.getMessage().contains("profile T (T@1#abc123)"), e.getMessage());
        }
    }

    @Test
    void anIntegerBeyondTheMinorUnitRangeIsRefused() {
        BigInteger huge = BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE);
        ConfigException e = assertThrows(ConfigException.class, () -> profile("USD", huge).money("limits.upTo"));
        assertEquals("MONEY_NOT_MINOR_UNITS", e.code());
    }

    @Test
    void anAbsentAmountIsNull() {
        assertNull(profile("USD", null).money("limits.upTo"));
    }

    @Test
    void aProfileWithoutCurrencyIsRefusedNeverDefaulted() {
        ConfigException e = assertThrows(ConfigException.class, () -> profile(null, 1000).money("limits.upTo"));
        assertEquals("MONEY_CURRENCY_MISSING", e.code());
        assertTrue(e.getMessage().contains("limits.upTo"), e.getMessage());
        assertTrue(e.getMessage().contains("profile T"), e.getMessage());
    }
}
