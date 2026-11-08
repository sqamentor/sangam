// SPDX-License-Identifier: Apache-2.0
package org.sangam.connectors.health.x12;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.sangam.connectors.health.sim.HealthSimulator;

/** SG-20: 835 amounts are exact minor units; a malformed amount is reported, never read as zero. */
class Remittance835MoneyTest {

    @Test
    void exactBalanceInCents() {
        Map<String, Object> m = HealthSimulator.decode835(HealthSimulator.remittance835("P1", "100000.01", "99999.99", "0.01", false));
        assertEquals(true, m.get("balanced"));
        assertEquals(List.of(), m.get("invalidAmounts"));
    }

    /** The old decoder turned "12,50" into 0, so a corrupt CAS amount could still "balance". */
    @Test
    @SuppressWarnings("unchecked")
    void malformedAmountIsNotZero() {
        String x12 = HealthSimulator.remittance835("P2", "100.00", "70.00", "10.00", false).replace("CAS*CO*45*20.00", "CAS*CO*45*20,00");
        Map<String, Object> m = HealthSimulator.decode835(x12);
        Map<String, Object> c = ((List<Map<String, Object>>) m.get("claims")).get(0);
        assertEquals(false, c.get("claimBalanced"));
        assertTrue(((List<String>) m.get("invalidAmounts")).stream().anyMatch(s -> s.contains("20,00")), m.toString());
    }

    @Test
    void subCentAmountIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> HealthSimulator.remittance835("P3", "10.001", "5", "0", false));
        List<String> invalid = new java.util.ArrayList<>();
        assertEquals(null, X12Decoders.amount("0.001", "USD", "CLP04", invalid));
        assertEquals(List.of("CLP04=0.001"), invalid);
    }
}
