// SPDX-License-Identifier: Apache-2.0
package org.sangam.registry.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Owner decision 381(4): a fraction in a registry request JSON (the {@code request} field of AssetDelegate and
 * RegistryDelegate) is read exactly, never as a binary Double, so {@code Maps.decimal} and the canonical form keep every
 * digit the process sent.
 */
class JsonExactFractionsTest {

    @Test
    void aFractionInARequestMapIsReadExactly() {
        Map<String, Object> m = Json.map("{\"amount\":12345678901234567.89,\"rate\":0.10}");
        assertInstanceOf(BigDecimal.class, m.get("amount"));
        assertEquals(new BigDecimal("12345678901234567.89"), m.get("amount"));
        assertEquals("12345678901234567.89", Json.canonical(m.get("amount")));
        assertEquals("0.1", Json.canonical(m.get("rate")));
    }

    @Test
    void aFractionInAListIsReadExactly() {
        List<Object> l = Json.list("[1.005]");
        assertEquals(new BigDecimal("1.005"), l.get(0));
    }
}
