// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.connector.http;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.sangam.core.integration.ConnectorProfile;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.ConnectorResult;
import org.sangam.core.layer.CapabilityClass;
import org.sangam.integration.connector.HttpTransport;
import org.sangam.integration.connector.SecretResolver;

import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** SL-18 (4): static service-token headers for an edge access gate come from the secret port, not from config. */
class HttpRestConnectorServiceTokenTest {

    @Test
    void defaultHeaderSecretReferencesAreResolvedFromTheSecretPort() throws Exception {
        Map<String, String> seen = new ConcurrentHashMap<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            ex.getRequestHeaders().forEach((k, v) -> seen.put(k.toLowerCase(Locale.ROOT), v.get(0)));
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, 2);
            ex.getResponseBody().write("{}".getBytes());
            ex.close();
        });
        server.start();
        try {
            URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            HttpRestConnector.Config cfg = new HttpRestConnector.Config("edge", base, CapabilityClass.A_IDEMPOTENT_WITH_LOOKUP,
                    null, null, null,
                    Map.of("X-Edge-Client-Id", "secret://edge/client-id", "X-Edge-Client-Secret", "secret://edge/client-secret",
                            "X-Plain", "literal"),
                    Duration.ofSeconds(5), ConnectorProfile.SIMULATOR);
            SecretResolver secrets = SecretResolver.of(Map.of("secret://edge/client-id", "id-123",
                    "secret://edge/client-secret", "s3cr3t"));
            HttpRestConnector c = new HttpRestConnector(cfg, HttpTransport.forSimulator(), secrets);
            ConnectorResult r = c.invoke(new ConnectorRequest("t1", UUID.randomUUID(), "a1", 1, null, null, "invoke",
                    Map.of("method", "GET", "path", "/ping"), Instant.now().plusSeconds(10)));
            assertInstanceOf(ConnectorResult.Success.class, r, String.valueOf(r));
            assertEquals("id-123", seen.get("x-edge-client-id"));
            assertEquals("s3cr3t", seen.get("x-edge-client-secret"));
            assertEquals("literal", seen.get("x-plain"));
        } finally {
            server.stop(0);
        }
    }
}
