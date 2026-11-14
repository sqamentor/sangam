// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.events.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Owner decision 232: an unavailable nats channel names the NATS add-on; other families keep their message. */
class ChannelAdaptersAddonMessageTest {

    @Test
    void anUnavailableNatsChannelNamesTheNatsAddOn() {
        String m = ChannelAdapters.unavailable("orders", "nats");
        assertTrue(m.startsWith("channel orders: adapter 'nats' is not available"), m);
        assertTrue(m.contains("NATS add-on"), m);
        assertTrue(m.contains("io.nats:jnats"), m);
    }

    @Test
    void otherFamiliesKeepTheOptionalDependencyMessage() {
        assertEquals("channel c1: adapter 'mqtt' is not available (add its optional client dependency, see README)",
                ChannelAdapters.unavailable("c1", "mqtt"));
        assertFalse(ChannelAdapters.unavailable("c1", "kafka").contains("NATS"));
    }
}
