// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.sangam.registry.RegistryPorts;

/**
 * SL-104 (ADR-D-20): the registry has no default publish connector. Publishing without
 * {@code sangam.registry.publish.connector} fails before anything is written, naming the property.
 */
class RegistryPublishConnectorTest {

    @Test
    void publishingWithoutAConfiguredConnectorFailsNamingTheProperty() {
        for (String unset : new String[]{null, "", " "}) {
            // the TxSupport is never reached: the check comes first
            RegistryPorts.PublishPort p = RegistryPortBridges.publisher(null, unset);
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> p.publish(null, null, "channel-1", "object-1", "doc-1", Map.of()));
            assertTrue(e.getMessage().contains("sangam.registry.publish.connector"), e.getMessage());
        }
    }
}