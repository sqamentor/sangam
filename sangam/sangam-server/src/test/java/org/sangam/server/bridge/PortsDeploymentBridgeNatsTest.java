// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.DeploymentPort;
import org.sangam.server.config.AddonGuardWiring;

/**
 * Owner decision 232: a deployment carrying a nats channel answers 501 CAPABILITY_NOT_INSTALLED without the NATS
 * add-on, before the app deployment service, the engine or the audit is touched; other channels are unaffected.
 */
class PortsDeploymentBridgeNatsTest {

    static byte[] channel(String type) {
        return ("{\"key\":\"orders\",\"channelType\":\"" + type + "\",\"type\":\"inbound\",\"topics\":[\"orders\"],"
                + "\"properties\":{\"serverUri\":\"nats://broker:4222\",\"stream\":\"S\",\"consumer\":\"C\"}}")
                .getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void aNatsChannelWithoutTheAddOnIs501() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("process.bpmn", "<definitions/>".getBytes(StandardCharsets.UTF_8));
        files.put("orders.channel", channel("NATS"));
        ApiProblem p = assertThrows(ApiProblem.class, () -> PortsDeploymentBridge.requireChannelClients(files, false));
        assertEquals(501, p.status());
        assertEquals("CAPABILITY_NOT_INSTALLED", p.code());
        assertEquals("orders.channel", p.extensions().get("resource"));
        assertTrue(String.valueOf(p.extensions().get("module")).contains("NATS add-on"), String.valueOf(p.extensions()));
    }

    @Test
    void theDeployRouteRefusesBeforeAnythingIsWritten() {
        // no base port, app service or audit: any use of them would fail the test with a NullPointerException
        PortsDeploymentBridge bridge = new PortsDeploymentBridge(null, null, null);
        Map<String, byte[]> files = Map.of("orders.channel.json", channel("nats"));
        ApiProblem p = assertThrows(ApiProblem.class, () -> bridge.deploy(null,
                new DeploymentPort.DeployRequest("orders-app", null, false, files)));
        assertEquals(501, p.status(), "the server class path has no jnats (decision 232)");
        assertTrue(!AddonGuardWiring.natsPresent(PortsDeploymentBridge.class.getClassLoader()));
    }

    @Test
    void otherChannelsAndThePresentAddOnPass() {
        assertDoesNotThrow(() -> PortsDeploymentBridge.requireChannelClients(Map.of("k.channel", channel("kafka")), false));
        assertDoesNotThrow(() -> PortsDeploymentBridge.requireChannelClients(Map.of("n.channel", channel("nats")), true));
        assertDoesNotThrow(() -> PortsDeploymentBridge.requireChannelClients(
                Map.of("bad.channel", "not json".getBytes(StandardCharsets.UTF_8)), false),
                "a channel that does not parse is left to the app deployment's validation");
    }
}
