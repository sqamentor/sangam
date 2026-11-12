// SPDX-License-Identifier: Apache-2.0
package org.sangam.ledger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SL-98: a money ledger (LIMIT, RECEIVABLE, and the default kind LIMIT) without an ISO 4217 currency is refused when its
 * definition is registered, with a message naming the ledger and the profile. Before, the store wrote NULL-currency
 * accounts and scaled money with the quantity exponent without any error (SL-57 set-up NPE).
 */
class LedgerDefinitionCurrencyTest {

    static final String PROFILE = "IN.RJ@3>IN@7#abc123def456";

    static LedgerDefinition def(String id, String kind, String currency) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", id);
        if (kind != null) {
            m.put("kind", kind);
        }
        if (currency != null) {
            m.put("currency", currency);
        }
        m.put("stages", List.of("ALLOCATION", "RELEASE"));
        return LedgerDefinition.from(m);
    }

    static LedgerService service() {
        return new LedgerService(new LedgerStore.InMemory(), null);
    }

    @Test
    void moneyKinds() {
        assertTrue(def("B", "LIMIT", "INR").money());
        assertTrue(def("R", "RECEIVABLE", "INR").money());
        assertTrue(def("D", null, "INR").money(), "the default kind is LIMIT");
        assertFalse(def("S", "QUANTITY", null).money());
    }

    @ParameterizedTest
    @CsvSource(value = {"LIMIT", "RECEIVABLE", "NULL"}, nullValues = "NULL")
    void moneyLedgerWithoutCurrencyIsRefusedAtDefinitionNamingLedgerAndProfile(String kind) {
        LedgerService s = service();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> s.define("T", PROFILE, List.of(def("BUDGET", kind, null))));
        assertTrue(e.getMessage().contains("'BUDGET'"), e.getMessage());
        assertTrue(e.getMessage().contains(PROFILE), e.getMessage());
        assertTrue(e.getMessage().contains("has no currency"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> s.definition("T", "BUDGET"), "nothing was registered");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  "})
    void blankCurrencyIsRefused(String blank) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service().define("T", PROFILE, List.of(def("BUDGET", "LIMIT", blank))));
        assertTrue(e.getMessage().contains("has no currency"), e.getMessage());
    }

    /** A non-ISO code (or a pseudo-currency without a minor unit) would also fall back to the quantity exponent. */
    @ParameterizedTest
    @ValueSource(strings = {"RS", "KG", "XXX"})
    void moneyLedgerWithANonIsoCurrencyIsRefused(String code) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service().define("T", PROFILE, List.of(def("BUDGET", "LIMIT", code))));
        assertTrue(e.getMessage().contains("'" + code + "'"), e.getMessage());
        assertTrue(e.getMessage().contains("'BUDGET'") && e.getMessage().contains(PROFILE), e.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"INR", "USD", "JPY", "KWD"})
    void moneyLedgerWithAnIsoCurrencyIsAccepted(String code) {
        LedgerService s = service();
        assertDoesNotThrow(() -> s.define("T", PROFILE, List.of(def("BUDGET", "LIMIT", code))));
        assertEquals(code, s.definition("T", "BUDGET").currency());
    }

    @Test
    void quantityLedgerNeedsNoCurrency() {
        LedgerService s = service();
        assertDoesNotThrow(() -> s.define("T", PROFILE, List.of(def("STOCK", "QUANTITY", null))));
        assertEquals("STOCK", s.definition("T", "STOCK").id());
    }

    /** One invalid definition refuses the whole set: no half-registered tenant. */
    @Test
    void anInvalidDefinitionRegistersNothing() {
        LedgerService s = service();
        assertThrows(IllegalArgumentException.class, () -> s.define("T", PROFILE,
                List.of(def("GOOD", "LIMIT", "INR"), def("BAD", "RECEIVABLE", null))));
        assertThrows(IllegalArgumentException.class, () -> s.definition("T", "GOOD"));
    }

    /** The two-argument form validates too and names the tenant when no profile is known. */
    @Test
    void definitionWithoutAProfileIsValidatedToo() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service().define("T1", List.of(def("BUDGET", "LIMIT", null))));
        assertTrue(e.getMessage().contains("'BUDGET'") && e.getMessage().contains("tenant T1"), e.getMessage());
    }
}
