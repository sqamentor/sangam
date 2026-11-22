// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.ConnectorResult;
import org.sangam.integration.connector.ConnectorRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

/**
 * Register L-3, Follow-up 3: the bill's payee check ({@code bl_payee}: connector {@code registry.party}, operation
 * {@code payee.resolve}, inputs partyId, payee, payeeAccount, ifsc) on the real server (h2 profile), for a payee the
 * registry does not know yet, given by name and bank account only, as the officer enters it on the start form.
 * ui.yml run 44976972360: every walkthrough bill ended at bl_rjPayeeCheck with payeeStatus FAILED and reason
 * PAYEE_DIRECTORY_ERROR, while the same probe through POST /registry/parties/match as the administrator answered 200
 * with no match (run 44981470510). An unknown payee is an answer (no party, partyStatus NOT_FOUND), not a directory
 * error.
 */
@SpringBootTest(properties = {"sangam.engine.allow-multiple-engines-per-jvm=true",
        "operaton.bpm.job-execution.enabled=false"})
@ActiveProfiles("h2")
@DirtiesContext
class PayeeResolveByAccountH2Test {

    @Autowired
    ConnectorRegistry registry;

    private ConnectorResult resolve(Map<String, Object> input) {
        return registry.require("dev", "registry.party").invoke(new ConnectorRequest("dev", UUID.randomUUID(), "a1", 1,
                "root-l3", "E2E-WALK/BILL/payee", "payee.resolve", input, null));
    }

    @Test
    void anUnknownPayeeGivenByNameAndAccountIsAnswered() {
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("payee", "Mahesh Kumar Yadav");
        in.put("payeeAccount", "00004207100101");
        ConnectorResult r = resolve(in);
        ConnectorResult.Success ok = assertInstanceOf(ConnectorResult.Success.class, r, "payee.resolve of an unknown payee: " + r);
        assertEquals("NOT_FOUND", ok.output().get("partyStatus"), "no party yet: " + r);
    }

    @Test
    void anUnknownPayeeWithItsIfscIsAnswered() {
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("payee", "Mahesh Kumar Yadav");
        in.put("payeeAccount", "00004207100101");
        in.put("ifsc", "ZXRJ9JPR001");
        ConnectorResult r = resolve(in);
        assertInstanceOf(ConnectorResult.Success.class, r, "payee.resolve with an IFSC: " + r);
    }
}
