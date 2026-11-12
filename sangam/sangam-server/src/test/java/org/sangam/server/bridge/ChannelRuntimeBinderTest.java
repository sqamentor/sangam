// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.ConnectorResult;
import org.sangam.integration.connector.ConnectorRegistry;
import org.sangam.integration.connector.SecretResolver;
import org.sangam.rest.modules.channels.ChannelStatusSource;
import org.sangam.rest.port.Page;
import org.sangam.rest.port.RegistryPort;
import org.springframework.mock.env.MockEnvironment;

/** M-071 wiring and decision 76: every registry channel gets a connector, and enterprise connectors come from properties. Written, not run (Wave 3). */
class ChannelRuntimeBinderTest {

    static RegistryPort registry(List<Map<String, Object>> entries) {
        return (RegistryPort) Proxy.newProxyInstance(RegistryPort.class.getClassLoader(), new Class<?>[] {RegistryPort.class},
                (p, m, a) -> {
                    if (m.getName().equals("list")) {
                        return Page.of(entries, entries.size(), false);
                    }
                    throw new UnsupportedOperationException(m.getName());
                });
    }

    @Test
    void matrixAndSlackEntriesAreActiveAndEnterpriseConnectorsAreRegistered() {
        ConnectorRegistry connectors = new ConnectorRegistry();
        MockEnvironment env = new MockEnvironment()
                .withProperty("sangam.channels.enterprise.t.erp.type", "sap-rfc")
                .withProperty("sangam.channels.enterprise.t.erp.host", "sap.example.org")
                .withProperty("sangam.channels.enterprise.t.erp.system-number", "00")
                .withProperty("sangam.channels.enterprise.t.erp.client", "100")
                .withProperty("sangam.channels.enterprise.t.erp.user", "RFC_USER")
                .withProperty("sangam.channels.enterprise.t.erp.password-ref", "sap-pw")
                .withProperty("sangam.channels.enterprise.t.erp.profile", "SIMULATOR")
                .withProperty("sangam.channels.enterprise.t.orders.type", "sap-odata")
                .withProperty("sangam.channels.enterprise.t.orders.service-root", "https://sap.example.org/sap/opu/odata/sap/Z_SRV")
                .withProperty("sangam.channels.enterprise.t.orders.auth-ref", "sap-basic")
                .withProperty("sangam.channels.enterprise.t.itsm.type", "servicenow")
                .withProperty("sangam.channels.enterprise.t.itsm.instance", "https://example.service-now.com")
                .withProperty("sangam.channels.enterprise.t.itsm.auth-ref", "sn-basic")
                .withProperty("sangam.channels.enterprise.t.itsm.correlation-column", "correlation_id")
                .withProperty("sangam.channels.enterprise.t.broken.type", "sap-odata");
        ChannelRuntimeBinder binder = new ChannelRuntimeBinder(registry(List.of(
                Map.of("key", "room", "channel", "MATRIX", "apiBase", "https://matrix.example.org", "tokenRef", "mx"),
                Map.of("key", "team-chat", "channel", "slack", "tokenRef", "sl"),
                Map.of("key", "bad", "channel", "FAX"))), connectors, null, SecretResolver.of(Map.of("sap-pw", "x")), env);

        binder.refresh("t");

        List<ChannelStatusSource.ChannelStatus> status = binder.status("t");
        assertEquals("ACTIVE", status.get(0).state());
        assertEquals("ACTIVE", status.get(1).state());
        assertEquals("INVALID", status.get(2).state());
        assertEquals(3, status.size(), "enterprise connectors are not channel entries");
        assertTrue(connectors.find("t", "room").isPresent());
        assertTrue(connectors.find("t", "team-chat").isPresent());
        assertEquals("sap-odata", connectors.require("t", "orders").descriptor().type());
        assertTrue(connectors.find("t", "itsm").isPresent());
        assertTrue(connectors.find("t", "broken").isEmpty(), "a definition without its required fields is not registered");
        assertTrue(connectors.find("other", "erp").isEmpty());

        ConnectorResult r = connectors.require("t", "erp").invoke(new ConnectorRequest("t", UUID.randomUUID(), "a", 1, "root", null, "call",
                Map.of("function", "Z_X"), null));
        // no sangam.channels.egress.t rules: the tenant egress denies the SAP host before JCo is touched (the absent-JCo
        // answer itself is covered by sangam-channels SapRfcAndODataTest)
        assertTrue(assertInstanceOf(ConnectorResult.TransientFailure.class, r).noEffect(), "nothing was sent");
        assertSame(binder.tokens(), binder.tokens(), "one token cache per server");
    }
}
