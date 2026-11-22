// SPDX-License-Identifier: Apache-2.0
package org.sangam.config.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.ConnectorResult;

/**
 * Register L-3, Follow-up 3: a payee the registry does not hold yet is resolved to no party (partyStatus NOT_FOUND).
 * {@link ConnectorResult.Success} copies its output with {@code Map.copyOf}, which refuses a null value, so the
 * answer {@code partyId=null} threw a NullPointerException inside the connector; it was caught and answered as
 * PAYEE_DIRECTORY_ERROR with no message, and the bill ended at bl_rjPayeeCheck (ui.yml run 44976972360: every
 * walkthrough bill, payee given by name and bank account only). The unknown payee is an answer: COMPLETED, with no
 * partyId in it, and the bill goes on to its funds check.
 */
class PayeeConnectorUnknownPayeeTest {

    private static PayeeDirectory answering(PayeeDirectory.Resolution res) {
        return new PayeeDirectory() {
            @Override
            public Resolution resolve(String tenant, Map<String, Object> payee) {
                return res;
            }

            @Override
            public Resolution register(String tenant, Map<String, Object> payee, String operationId, String actor) {
                return res;
            }
        };
    }

    private static ConnectorResult call(PayeeDirectory dir, String op) {
        return new PayeeConnector(dir).invoke(new ConnectorRequest("GOI.E2E", UUID.randomUUID(), "a1", 1, null, null, op,
                Map.of("payee", "Mahesh Kumar Yadav", "payeeAccount", "00004207100101"), null));
    }

    @Test
    void anUnknownPayeeIsAnsweredWithoutAPartyId() {
        PayeeDirectory dir = answering(new PayeeDirectory.Resolution(null, "NOT_FOUND", false, false, List.of()));
        ConnectorResult r = call(dir, "payee.resolve");
        ConnectorResult.Success ok = assertInstanceOf(ConnectorResult.Success.class, r, "payee.resolve of an unknown payee: " + r);
        assertEquals("NOT_FOUND", ok.output().get("partyStatus"));
        assertFalse(ok.output().containsKey("partyId"), "no party: no partyId in the answer");
        assertEquals(Boolean.FALSE, ok.output().get("multiple"), "the bill's gateway reads payeeMultiple");
        assertEquals(0, ok.output().get("matchCount"));
    }

    @Test
    void aKnownPayeeStillCarriesItsPartyId() {
        PayeeDirectory dir = answering(new PayeeDirectory.Resolution("P-1", "ACTIVE", true, false, List.of()));
        ConnectorResult.Success ok = assertInstanceOf(ConnectorResult.Success.class, call(dir, "payee.resolve"));
        assertEquals("P-1", ok.output().get("partyId"));
        assertEquals("P-1", ok.providerRef());
    }
}
