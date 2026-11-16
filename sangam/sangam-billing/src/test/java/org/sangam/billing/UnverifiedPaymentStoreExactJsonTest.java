// SPDX-License-Identifier: Apache-2.0
package org.sangam.billing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/** Decisions 138 and 381(4): the unverified-payment store reads a stored money fraction exactly, never as Double. */
class UnverifiedPaymentStoreExactJsonTest {

    private static final String AMOUNT = "12345678901234567.8901234567890123";

    @Test
    void aStoredMoneyFractionRoundTripsExactly() throws Exception {
        String stored = "{\"amount\":" + AMOUNT + ",\"fee\":0.1}";
        JsonNode n = JdbcUnverifiedPaymentStore.JSON.readTree(stored);
        assertTrue(n.get("amount").isBigDecimal(), "a fraction is read as a binary Double");
        assertEquals(new BigDecimal(AMOUNT), n.get("amount").decimalValue());
        Map<String, Object> m = JdbcUnverifiedPaymentStore.JSON.readValue(stored, new TypeReference<>() { });
        assertEquals(new BigDecimal(AMOUNT), m.get("amount"));
        assertEquals(new BigDecimal("0.1"), m.get("fee"));
        assertEquals(stored, JdbcUnverifiedPaymentStore.JSON.writeValueAsString(m));
    }
}
