// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import org.sangam.packs.spi.PackDescriptor;
import org.sangam.packs.spi.PackRegistry;
import org.sangam.packs.spi.SangamPack;

/**
 * P-6 Step 2, cause (A), joint decision EV-M-1000 option a1: the pension sanction's service-record verification
 * ({@code ps_verify}, a separation-of-duties step) and the Head of Office's sanction ({@code ps_sanction}, role HOO
 * under CCS (Pension) Rules 2021 r.59) are different seats of office OFFICE-P. SANGAM.md IX.5 rule 5 refuses one seat
 * acting on both, so a pack whose verifier is the sanctioning seat can never sanction a pension (422 SOD_VIOLATION).
 */
class PensionVerifierSanctionerSeatsTest {

    @Test
    void theVerifierIsAnotherSeatOfThePensionOfficeThanTheSanctioner() throws Exception {
        SangamPack pack = PackRegistry.discover().deploymentOrder().stream()
                .filter(p -> p.descriptor().id().equals("gov.pension")).findFirst().orElseThrow();
        String bpmn;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("packs/pension/sangam-pension-sanction.bpmn")) {
            assertNotNull(in, "pension sanction model");
            bpmn = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Matcher m = Pattern.compile("verifySeat=([A-Z0-9-]+)").matcher(bpmn);
        assertEquals(true, m.find(), "the pack names its verifying seat");
        String verifier = m.group(1);
        List<PackDescriptor.SeedSeat> seats = pack.descriptor().seats();
        PackDescriptor.SeedSeat v = seats.stream().filter(s -> s.id().equals(verifier)).findFirst().orElse(null);
        assertNotNull(v, "the verifying seat " + verifier + " is a seed seat");
        assertEquals("OFFICE-P", v.office(), "the verifier sits in the pension office");
        List<String> sanctioners = seats.stream().filter(s -> s.role().equals("HOO") && s.office().equals("OFFICE-P"))
                .map(PackDescriptor.SeedSeat::id).toList();
        assertEquals(1, sanctioners.size(), "one Head of Office seat sanctions: " + sanctioners);
        assertNotEquals(sanctioners.get(0), verifier, "verifier and sanctioner are different seats (IX.5 rule 5)");
    }
}
