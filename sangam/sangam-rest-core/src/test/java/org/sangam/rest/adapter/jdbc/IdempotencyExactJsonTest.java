// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.adapter.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/** Decisions 138 and 381(4): the idempotency adapter reads a stored response body's money fractions exactly. */
class IdempotencyExactJsonTest {

    private static final String AMOUNT = "12345678901234567.8901234567890123";

    @Test
    void aStoredMoneyFractionRoundTripsExactly() throws Exception {
        String body = "{\"code\":\"CONFLICT\",\"amount\":" + AMOUNT + "}";
        JsonNode n = JdbcIdempotencyAdapter.JSON.readTree(body.getBytes(StandardCharsets.UTF_8));
        assertTrue(n.get("amount").isBigDecimal(), "a fraction is read as a binary Double");
        assertEquals(new BigDecimal(AMOUNT), n.get("amount").decimalValue());
        Map<String, Object> m = JdbcIdempotencyAdapter.JSON.readValue(body, new TypeReference<>() { });
        assertEquals(body, JdbcIdempotencyAdapter.JSON.writeValueAsString(m));
        assertEquals("CONFLICT", JdbcIdempotencyAdapter.problemCode(body.getBytes(StandardCharsets.UTF_8)));
    }
}
