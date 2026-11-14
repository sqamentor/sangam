// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.sangam.billing.PaymentConfirmationPolicy;
import org.sangam.billing.UnverifiedPaymentPolicy;
import org.sangam.core.money.Money;
import org.springframework.mock.env.MockEnvironment;

/**
 * RM-03 (owner decisions 136 and 141) at the server wiring: no trusted channel by default, a production start with a
 * non-empty list and no acknowledgement is refused (both production forms, SL-109), and a trusted channel is enabled
 * for unverified acceptance only with its full operating policy.
 */
class BillingPaymentPolicyWiringTest {

    private static MockEnvironment helm() {
        MockEnvironment env = new MockEnvironment().withProperty("sangam.security.production", "true");
        env.setActiveProfiles("postgres", "oidc");
        return env;
    }

    private static MockEnvironment prodProfile() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("postgres", "prod");
        return env;
    }

    @Test
    void theDefaultTrustsNoChannel() {
        PaymentConfirmationPolicy p = new BillingWiring().sangamBillingPaymentPolicy(helm());
        assertTrue(p.trustedChannels().isEmpty());
        assertFalse(p.trusts("counter"));
        assertFalse(p.trusts("cash"));
    }

    @Test
    void productionRefusesAnUnacknowledgedTrustedList() {
        for (MockEnvironment env : new MockEnvironment[] {helm(), prodProfile()}) {
            env.setProperty("sangam.billing.trusted-channels", "counter");
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> new BillingWiring().sangamBillingPaymentPolicy(env));
            assertTrue(e.getMessage().contains("sangam.billing.trusted-channels-acknowledged"), e.getMessage());
            env.setProperty("sangam.billing.trusted-channels-acknowledged", "true");
            assertDoesNotThrow(() -> new BillingWiring().sangamBillingPaymentPolicy(env));
        }
    }

    @Test
    void developmentAcceptsTheListWithoutAcknowledgement() {
        MockEnvironment dev = new MockEnvironment().withProperty("sangam.security.production", "false") // decision 143: stated
                .withProperty("sangam.billing.trusted-channels", "counter");
        dev.setActiveProfiles("h2");
        assertTrue(new BillingWiring().sangamBillingPaymentPolicy(dev).trusts("counter"));
    }

    /** Decision 141: a trusted channel without a fully stated operating policy is disabled, never defaulted. */
    @Test
    void aTrustedChannelWithoutItsOperatingPolicyIsDisabled() {
        MockEnvironment dev = new MockEnvironment().withProperty("sangam.security.production", "false") // decision 143: stated
                .withProperty("sangam.billing.trusted-channels", "counter");
        BillingWiring w = new BillingWiring();
        UnverifiedPaymentPolicy p = w.sangamUnverifiedPaymentPolicy(w.sangamBillingPaymentPolicy(dev), dev);
        assertTrue(p.channel("counter").isEmpty());
        assertTrue(p.unresolved().containsKey("counter"));
    }

    @Test
    void aFullyStatedPolicyEnablesOnlyItsTrustedChannel() {
        MockEnvironment dev = new MockEnvironment().withProperty("sangam.security.production", "false") // decision 143: stated
                .withProperty("sangam.billing.trusted-channels", "counter")
                .withProperty("sangam.billing.unverified.channels.counter.policy-version", "v1")
                .withProperty("sangam.billing.unverified.channels.counter.currency", "INR")
                .withProperty("sangam.billing.unverified.channels.counter.max-amount", "500.00")
                .withProperty("sangam.billing.unverified.channels.counter.maker-checker", "true")
                .withProperty("sangam.billing.unverified.channels.counter.ageing-alert-after", "P3D")
                .withProperty("sangam.billing.unverified.channels.cash.policy-version", "v1");
        BillingWiring w = new BillingWiring();
        UnverifiedPaymentPolicy p = w.sangamUnverifiedPaymentPolicy(w.sangamBillingPaymentPolicy(dev), dev);
        assertEquals(Money.parse("500.00", "INR"), p.channel("counter").orElseThrow().maxAmount());
        assertTrue(p.channel("cash").isEmpty(), "a policy for an untrusted channel enables nothing");
    }
}
