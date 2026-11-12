// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * SL-32 / integrator decision 9 (2026-11-12): an amount inside a simulator directive of an acceptance scenario is
 * written in major units and shipped in minor units, like every other pack amount (packgen
 * {@code money.convert_directive}: the data key lands in {@code <resultPrefix><Key>}, here {@code budgetAvailable},
 * a money variable). Non-money data and plain outcome directives ship unchanged.
 */
class SimDirectiveMoneyTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode scenario(String dir, String id) throws Exception {
        try (InputStream in = SimDirectiveMoneyTest.class.getClassLoader()
                .getResourceAsStream("packs/" + dir + "/pack.json")) {
            for (JsonNode a : JSON.readTree(in).path("acceptance")) {
                if (id.equals(a.path("id").asText())) {
                    return a;
                }
            }
        }
        throw new AssertionError(dir + " has no scenario " + id);
    }

    @Test
    void budgetAvailabilityInADirectiveIsShippedInMinorUnits() throws Exception {
        JsonNode s = scenario("p1-bill-payment", "J-P1-E11");
        // authored as available=310000 (rupees, as the given says); 3,10,000.00 INR = 31,000,000 paise
        assertEquals("available=31000000", s.path("startVariables").path("sim_ifms_budget").asText());
        assertEquals(47_500_000L, s.path("startVariables").path("amount").asLong(), "the bill, 4,75,000.00 INR");
    }

    @Test
    void nonMoneyDataAndOutcomeDirectivesAreUnchanged() throws Exception {
        assertEquals("hit=true", scenario("sec-bank-account-opening", "SEC-BANK-AO-2").path("startVariables")
                .path("sim_sanctions_screen").asText());
        assertEquals("valid=false", scenario("ifms-bill", "BL-1").path("startVariables")
                .path("sim_config_documentType").asText());
        assertEquals("UNKNOWN", scenario("p1-bill-payment", "AC-03").path("startVariables")
                .path("sim_pfms_payment").asText());
    }

    @Test
    void theRevisedBillFitsTheBudgetInTheSameUnits() throws Exception {
        JsonNode run = scenario("p1-bill-payment", "J-P1-E11").path("run");
        long revised = run.path("answers").path("p1_revise").path("vars").path("amount").asLong();
        assertEquals(30_000_000L, revised, "3,00,000.00 INR in paise");
        assertTrue(revised <= 31_000_000L, "within the available budget");
    }
}
