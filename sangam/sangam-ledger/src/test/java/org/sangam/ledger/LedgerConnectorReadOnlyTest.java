// SPDX-License-Identifier: Apache-2.0
package org.sangam.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.sangam.core.integration.ConnectorRequest;

/**
 * Owner decisions 320 and 321: of the ledger connector's operations only the balance read {@code available} is
 * read-only. Closing a period, releasing holds and every posting change the books and can never be reached from an API
 * read (the case found in the code-scanning triage was {@code closePeriod} behind a data object's GET). The answer
 * needs no ledger: the connector here has none, so nothing can be posted while asking.
 */
class LedgerConnectorReadOnlyTest {

    @Test
    void onlyTheBalanceReadIsReadOnly() {
        LedgerConnector c = new LedgerConnector(null, null, null);
        Map<String, Boolean> expected = new LinkedHashMap<>();
        expected.put("available", true);
        expected.put("ledger.available", true);        // the connector takes the operation from the id suffix
        expected.put("closePeriod", false);
        expected.put("ledger.closePeriod", false);
        expected.put("releaseHolds", false);
        expected.put("ledger.releaseHolds", false);
        for (String posting : new String[]{"allocate", "distribute", "withdraw", "commit", "encumber", "release", "consume",
                "transfer", "adjust", "reverse", "write_off", "post"}) {
            expected.put(posting, false);
        }
        expected.put("Available", false);              // matched exactly, as invoke matches it
        expected.put("available.closePeriod", false);  // the suffix is what runs
        expected.put("list", false);                   // any other name is a posting in invoke
        expected.put("invoke", false);
        for (String operation : c.descriptor().operations()) {
            assertEquals(true, expected.containsKey(operation), "every declared operation has a row here: " + operation);
        }
        expected.forEach((operation, readOnly) -> assertEquals(readOnly, c.readOnly(new ConnectorRequest("t1",
                UUID.randomUUID(), "a1", 1, null, null, operation, Map.of("ledgerId", "BUDGET"), null)), operation));
    }
}
