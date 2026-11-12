// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.connector.gov;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.sangam.integration.connector.HttpTransport;
import org.sangam.integration.connector.SecretResolver;
import org.sangam.integration.sim.SimulatorProfile;

/** SL-104 (ADR-D-20): a payment gateway's default currency is configuration; the product has no default. */
class PaymentGatewayCurrencyTest {

    private static PaymentGatewayConnector.Config config(String currency) {
        return new PaymentGatewayConnector.Config("pg-1", URI.create("http://127.0.0.1:1"), null,
                SecretResolver.of(Map.of()), HttpTransport.forSimulator(), null, currency, null, Duration.ofSeconds(1));
    }

    @Test
    void aGatewayWithoutADefaultCurrencyIsRefusedAtConstruction() {
        for (String unset : new String[]{null, "", " "}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> config(unset));
            assertTrue(e.getMessage().contains("defaultCurrency"), e.getMessage());
        }
    }

    @Test
    void theConfiguredCurrencyIsNormalisedToUpperCase() {
        assertEquals("XTS", config("xts").defaultCurrency());
    }

    @Test
    void theSimulatorCurrencyIsSimulatorDataNotAProductDefault() {
        assertEquals(SimulatorProfile.PG_CURRENCY, config(SimulatorProfile.PG_CURRENCY).defaultCurrency());
    }
}