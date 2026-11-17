// SPDX-License-Identifier: Apache-2.0
package org.sangam.config.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.ConnectorResult;

/**
 * The connector call step (ConnectorCallDelegate) writes {@code <prefix>Status}, {@code <prefix>ReasonCode},
 * {@code <prefix>Ref}, {@code <prefix>Attempt} and {@code <prefix>OperationId} as the call's own bookkeeping and then
 * every answer field as {@code <prefix><Field>}. The payee check of the bill packs (checked_query, prefix "payee") reads
 * {@code payeeStatus == 'COMPLETED'}; an answer field "status" (the party status ACTIVE) overwrote it, so every
 * resolved payee read as "check not confirmed" (IfmsPacksIT.gpfAdvance: payeeStatus=ACTIVE, rejected
 * PAYEE_CHECK_NOT_CONFIRMED). The party status is answered as {@code partyStatus}.
 */
class PayeeConnectorResultNamesTest {

    static final List<String> CALL_BOOKKEEPING = List.of("status", "reasonCode", "ref", "attempt", "operationId");

    @Test
    void theAnswerNeverNamesTheCallBookkeepingAndCarriesThePartyStatus() {
        PayeeDirectory dir = new PayeeDirectory() {
            @Override
            public Resolution resolve(String tenant, Map<String, Object> payee) {
                return new Resolution("P-1", "ACTIVE", true, false, List.of());
            }

            @Override
            public Resolution register(String tenant, Map<String, Object> payee, String operationId, String actor) {
                return resolve(tenant, payee);
            }
        };
        for (String op : List.of("payee.resolve", "payee.register")) {
            ConnectorResult.Success r = (ConnectorResult.Success) new PayeeConnector(dir).invoke(new ConnectorRequest("ZZ", UUID.randomUUID(), "a1", 1,
                    null, null, op, Map.of("payee", "A"), null));
            for (String reserved : CALL_BOOKKEEPING) {
                assertFalse(r.output().containsKey(reserved), op + " answers the call bookkeeping name " + reserved);
            }
            assertEquals("ACTIVE", r.output().get("partyStatus"), op);
        }
    }
}
