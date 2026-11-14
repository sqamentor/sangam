// SPDX-License-Identifier: Apache-2.0
package org.sangam.billing;

import org.junit.jupiter.api.Test;
import org.sangam.core.money.Money;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decision 141: a channel is enabled for unverified acceptance only when it is trusted and its operating policy is fully
 * stated; anything missing or malformed disables it, never a permissive default.
 */
class UnverifiedPaymentPolicyTest {

    static final String P = UnverifiedPaymentPolicy.PREFIX + "counter.";

    @Test
    void aFullyStatedPolicyEnablesTheChannel() {
        Map<String, String> m = UnverifiedPaymentsH2Test.counterPolicy();
        m.put(P + "downstream-permitted", "PRINT_PROVISIONAL_ACKNOWLEDGEMENT, PRINT_PROVISIONAL_ACKNOWLEDGEMENT");
        UnverifiedPaymentPolicy.ChannelPolicy c = UnverifiedPaymentPolicy.from(Set.of("counter"), m::get)
                .channel("counter").orElseThrow();
        assertEquals("counter-2026-1", c.version());
        assertEquals(Money.parse("500.00", "INR"), c.maxAmount());
        assertTrue(c.makerChecker());
        assertEquals(Duration.ofDays(3), c.ageingAlertAfter());
        assertEquals(List.of("PRINT_PROVISIONAL_ACKNOWLEDGEMENT"), c.downstreamPermitted());
    }

    @Test
    void noAmountLimitMustBeStatedExplicitly() {
        Map<String, String> m = UnverifiedPaymentsH2Test.counterPolicy();
        m.put(P + "max-amount", "NONE");
        assertNull(UnverifiedPaymentPolicy.from(Set.of("counter"), m::get).channel("counter").orElseThrow().maxAmount());
    }

    @Test
    void eachMissingOrMalformedKeyDisablesTheChannel() {
        Map<String, String> bad = new HashMap<>();
        bad.put("policy-version", null);
        bad.put("currency", "RUPEES");
        bad.put("max-amount", "-1");
        bad.put("maker-checker", "yes");
        bad.put("ageing-alert-after", "3 days");
        for (Map.Entry<String, String> e : bad.entrySet()) {
            for (String value : new String[]{null, " ", e.getValue()}) {
                Map<String, String> m = UnverifiedPaymentsH2Test.counterPolicy();
                m.put(P + e.getKey(), value);
                UnverifiedPaymentPolicy policy = UnverifiedPaymentPolicy.from(Set.of("counter"), m::get);
                assertTrue(policy.channel("counter").isEmpty(), e.getKey() + "=" + value + " must disable the channel");
                assertTrue(policy.unresolved().get("counter").contains(e.getKey()), policy.unresolved().toString());
            }
        }
    }

    /** Decision 194: maker-checker is not optional; a channel configured without it is disabled, not run without it. */
    @Test
    void makerCheckerFalseDisablesTheChannel() {
        for (String off : new String[]{"false", "FALSE", " false "}) {
            Map<String, String> m = UnverifiedPaymentsH2Test.counterPolicy();
            m.put(P + "maker-checker", off);
            UnverifiedPaymentPolicy policy = UnverifiedPaymentPolicy.from(Set.of("counter"), m::get);
            assertTrue(policy.channel("counter").isEmpty(), "maker-checker=" + off + " must disable the channel");
            assertTrue(policy.unresolved().get("counter").contains("maker-checker"), policy.unresolved().toString());
        }
    }

    @Test
    void onlyTrustedChannelsCanBeEnabled() {
        Map<String, String> m = UnverifiedPaymentsH2Test.counterPolicy();
        UnverifiedPaymentPolicy policy = UnverifiedPaymentPolicy.from(Set.of(), m::get);
        assertTrue(policy.channel("counter").isEmpty(), "a policy without the channel in the trusted list is inert");
        assertTrue(UnverifiedPaymentPolicy.none().channel("counter").isEmpty());
        assertFalse(UnverifiedPaymentPolicy.from(Set.of("counter"), m::get).channel(null).isPresent());
    }
}
