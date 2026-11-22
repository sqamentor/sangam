// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import org.sangam.packs.spi.PackDescriptor;
import org.sangam.packs.spi.PackRegistry;

/**
 * P-6 Step 2, owner decisions (B) "Directorate sanction (b1)" and (C) "AE of sub-division" ([JOINT-DECISION]): a
 * pack's money sanction routes by the pack's own routing table to a role the pack's office has a seat of, never by the
 * core default bands ({@code sangam-core-competent-authority}: SO and up) nor to a seat below the case office.
 */
class UtilConnectionRoutingKeyTest {

    @Test
    void theConnectionSanctionIsTheSubDivisionAEs() throws Exception {
        assertRoutesTo("sec.util.new-connection", "packs/sec-util-new-connection/sangam-sec-util-connection.bpmn",
                "sec-util-connection-authority", "packs/sec-util-new-connection/", "SUBDIVISION", List.of("AE"));
    }

    @Test
    void theDbtBatchSanctionIsTheDirectorates() throws Exception {
        assertRoutesTo("gov.dbt.batch", "packs/dbt-batch/sangam-dbt-batch.bpmn", "dbt-batch-sanction-authority",
                "packs/dbt-batch/", "DIR-WEL", List.of("JD", "DIRECTOR"));
    }

    private void assertRoutesTo(String packId, String bpmnPath, String key, String dir, String office, List<String> roles)
            throws Exception {
        String bpmn = resource(bpmnPath);
        assertTrue(bpmn.contains("routingDecisionKey=" + key + ";"), packId + " sets its own routingDecisionKey " + key);
        String dmn = resource(dir + key + ".dmn");
        Matcher m = Pattern.compile("<outputEntry[^>]*><text>&quot;([A-Z-]+)&quot;</text>|<outputEntry[^>]*><text>\"([A-Z-]+)\"</text>")
                .matcher(dmn);
        List<String> out = new java.util.ArrayList<>();
        while (m.find()) {
            out.add(m.group(1) != null ? m.group(1) : m.group(2));
        }
        assertEquals(roles, out, key + " routes to " + roles);
        List<PackDescriptor.SeedSeat> seats = PackRegistry.discover().deploymentOrder().stream()
                .filter(p -> p.descriptor().id().equals(packId)).findFirst().orElseThrow().descriptor().seats();
        for (String role : roles) {
            assertTrue(seats.stream().anyMatch(s -> s.role().equals(role) && s.office().equals(office)),
                    packId + " seeds a " + role + " seat in " + office);
        }
    }

    private String resource(String path) throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(path)) {
            assertNotNull(in, path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
