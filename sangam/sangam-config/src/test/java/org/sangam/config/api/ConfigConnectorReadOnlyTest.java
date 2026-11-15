// SPDX-License-Identifier: Apache-2.0
package org.sangam.config.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.sangam.config.catalogue.NumberSeries;
import org.sangam.config.master.ClassificationRegistry;
import org.sangam.config.master.MasterStore;
import org.sangam.config.profile.InMemoryProfileRepository;
import org.sangam.config.profile.LayerModel;
import org.sangam.config.profile.ProfileResolver;
import org.sangam.core.integration.ConnectorRequest;

/**
 * Owner decisions 320 and 321: the configuration connector reads the resolved profile in every operation except
 * {@code series.next}, which issues a number (a state change). The payee connector keeps the default: it declares
 * nothing read-only.
 */
class ConfigConnectorReadOnlyTest {

    static ConnectorRequest req(String operation) {
        return new ConnectorRequest("ZZ", UUID.randomUUID(), "a1", 1, null, null, operation, Map.of(), null);
    }

    @Test
    void everyOperationReadsExceptTheNumberSeries() {
        ConfigConnector c = new ConfigConnector(new ProfileResolver(new InMemoryProfileRepository(), LayerModel.open()),
                new ClassificationRegistry(new MasterStore.InMemory()), new NumberSeries.InMemory());
        Map<String, Boolean> expected = new LinkedHashMap<>();
        expected.put("profile.get", true);
        expected.put("profile.binding", true);
        expected.put("classification.validate", true);
        expected.put("documentType.get", true);
        expected.put("documentType.check", true);
        expected.put("chain.signatures", true);
        expected.put("crosswalk.map", true);
        expected.put("series.next", false);
        expected.put("next", false);
        expected.put("list", false);
        expected.put("invoke", false);
        for (String operation : c.descriptor().operations()) {
            assertEquals(true, expected.containsKey(operation), "every declared operation has a row here: " + operation);
        }
        expected.forEach((operation, readOnly) -> assertEquals(readOnly, c.readOnly(req(operation)), operation));
    }

    @Test
    void thePayeeConnectorDeclaresNothingReadOnly() {
        PayeeConnector payee = new PayeeConnector(null);
        for (String operation : new String[]{"payee.resolve", "payee.register", "list", "invoke"}) {
            assertFalse(payee.readOnly(req(operation)), operation);
        }
    }
}
