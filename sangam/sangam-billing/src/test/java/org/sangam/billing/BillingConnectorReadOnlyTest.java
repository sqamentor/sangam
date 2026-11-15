// SPDX-License-Identifier: Apache-2.0
package org.sangam.billing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.sangam.core.integration.ConnectorRequest;

/**
 * Owner decisions 320 and 321: the billing connector's {@code accounts} and {@code arrears} read; invoice, collect,
 * penalty and waive post on the ledger and can never be reached from an API read (the billing GET endpoints go through
 * {@code ModuleConnectors.read}). The connector here has no service: nothing can be posted while asking.
 */
class BillingConnectorReadOnlyTest {

    @Test
    void onlyAccountsAndArrearsAreReadOnly() {
        BillingConnector c = new BillingConnector(null, null, null, null, null, null);
        Map<String, Boolean> expected = new LinkedHashMap<>();
        expected.put("accounts", true);
        expected.put("arrears", true);
        expected.put("billing.arrears", true);     // the connector takes the operation from the id suffix
        expected.put("invoice", false);
        expected.put("collect", false);
        expected.put("penalty", false);
        expected.put("waive", false);
        expected.put("billing.waive", false);
        expected.put("arrears.waive", false);      // the suffix is what runs
        expected.put("list", false);
        expected.put("invoke", false);
        for (String operation : c.descriptor().operations()) {
            assertEquals(true, expected.containsKey(operation), "every declared operation has a row here: " + operation);
        }
        expected.forEach((operation, readOnly) -> assertEquals(readOnly, c.readOnly(new ConnectorRequest("t1",
                UUID.randomUUID(), "a1", 1, null, null, operation, Map.of("ledgerId", "DEMAND"), null)), operation));
    }
}
