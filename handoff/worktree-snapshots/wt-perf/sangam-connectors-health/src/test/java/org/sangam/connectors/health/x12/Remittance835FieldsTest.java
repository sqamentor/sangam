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
        assertEquals("100.00", String.valueOf(m.get("chargeAmount")));
        Map<String, Object> c = ((List<Map<String, Object>>) m.get("claims")).get(0);
        Map<String, String> totals = (Map<String, String>) c.get("adjustmentTotals");
        assertEquals(0, new java.math.BigDecimal("10").compareTo(new java.math.BigDecimal(totals.get("PR"))), totals.toString());
        assertEquals(0, new java.math.BigDecimal("20").compareTo(new java.math.BigDecimal(totals.get("CO"))), totals.toString());
        assertEquals(true, c.get("claimBalanced"));
    }
}
