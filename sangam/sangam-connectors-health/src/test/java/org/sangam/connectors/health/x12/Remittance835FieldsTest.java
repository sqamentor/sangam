// SPDX-License-Identifier: Apache-2.0
package org.sangam.connectors.health.x12;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.sangam.connectors.health.sim.HealthSimulator;

/** The fields claim posting relies on. The claim balance rule itself is [U]: not read from a public TR3 text. */
class Remittance835FieldsTest {

    @Test
    @SuppressWarnings("unchecked")
    void perClaimTotalsAndBalanceFlag() {
        String x12 = HealthSimulator.remittance835("PCN1", "100.00", "70.00", "10.00", false);
        Map<String, Object> m = HealthSimulator.decode835(x12);
        assertEquals(10_000L, m.get("chargeAmount"), "integer cents (SD-398)");
        assertEquals("MINOR", m.get("amountUnit"));
        assertEquals("USD", m.get("currency"));
        Map<String, Object> c = ((List<Map<String, Object>>) m.get("claims")).get(0);
        Map<String, Object> totals = (Map<String, Object>) c.get("adjustmentTotals");
        assertEquals(0, new java.math.BigDecimal("1000").compareTo(new java.math.BigDecimal(String.valueOf(totals.get("PR")))), totals.toString());
        assertEquals(0, new java.math.BigDecimal("2000").compareTo(new java.math.BigDecimal(String.valueOf(totals.get("CO")))), totals.toString());
        assertEquals(true, c.get("claimBalanced"));
    }
}
