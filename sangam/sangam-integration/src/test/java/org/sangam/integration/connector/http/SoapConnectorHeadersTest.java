// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.connector.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.sangam.core.integration.ConnectorProfile;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.ConnectorResult;
import org.sangam.core.layer.CapabilityClass;
import org.sangam.integration.connector.HttpTransport;

/**
 * SL-66: SOAP header names are case-insensitive. The headers the connector sets (Content-Type, SOAPAction,
 * X-Sangam-Operation-Id) replace a configured header of the same name in any letter case, and each header is sent once.
 * SL-65: a configured header or a soapAction that is not valid HTTP is refused before anything is sent. The stub records
 * every value of every header, so a second value in another letter case shows.
 */
class SoapConnectorHeadersTest {

    HttpServer server;
    URI endpoint;
    final List<Map<String, List<String>>> seen = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", x -> {
            Map<String, List<String>> h = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            x.getRequestHeaders().forEach((k, v) -> h.put(k, List.copyOf(v)));
            seen.add(h);
            x.getRequestBody().readAllBytes();
            byte[] body = ("<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body><ok/>"
                    + "</soap:Body></soap:Envelope>").getBytes(StandardCharsets.UTF_8);
            x.getResponseHeaders().add("Content-Type", "text/xml; charset=utf-8");
            x.sendResponseHeaders(200, body.length);
            x.getResponseBody().write(body);
            x.close();
        });
        server.start();
        endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/soap");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private SoapConnector connector(SoapConnector.Version version, Map<String, String> headers) {
        return new SoapConnector(new SoapConnector.Config("soap", endpoint, version, CapabilityClass.A_IDEMPOTENT_WITH_LOOKUP,
                Duration.ofSeconds(5), headers, ConnectorProfile.LIVE), HttpTransport.forSimulator());
    }

    private static ConnectorRequest request(UUID op, String soapAction) {
        return new ConnectorRequest("t1", op, "a1", 1, null, null, "invoke",
                Map.of("bodyXml", "<m:Pay xmlns:m=\"urn:m\"/>", "soapAction", soapAction), Instant.now().plusSeconds(10));
    }

    @Test
    void soap11HeadersOfTheConnectorReplaceConfiguredOnesOfAnyLetterCase() {
        Map<String, String> configured = Map.of("content-type", "text/plain", "SOAPACTION", "\"urn:forged\"",
                "x-sangam-operation-id", "forged", "X-Custom", "kept");
        UUID op = UUID.randomUUID();
        ConnectorResult r = connector(SoapConnector.Version.SOAP_11, configured).invoke(request(op, "urn:pay"));
        assertInstanceOf(ConnectorResult.Success.class, r, String.valueOf(r));
        Map<String, List<String>> h = seen.get(0);
        assertEquals(List.of("text/xml; charset=utf-8"), h.get("Content-Type"));
        assertEquals(List.of("\"urn:pay\""), h.get("SOAPAction"));
        assertEquals(List.of(op.toString()), h.get("X-Sangam-Operation-Id"));
        assertEquals(List.of("kept"), h.get("X-Custom"), "a configured header the connector does not set is sent");
    }

    @Test
    void soap12ContentTypeOfTheConnectorReplacesAConfiguredOneOfAnyLetterCase() {
        ConnectorResult r = connector(SoapConnector.Version.SOAP_12, Map.of("CONTENT-TYPE", "text/plain"))
                .invoke(request(UUID.randomUUID(), "urn:pay"));
        assertInstanceOf(ConnectorResult.Success.class, r, String.valueOf(r));
        assertEquals(List.of("application/soap+xml; charset=utf-8; action=\"urn:pay\""), seen.get(0).get("Content-Type"));
    }

    @Test
    void aConfiguredHeaderNameGivenTwiceOrNotValidHttpIsRefusedWhenBuilt() {
        assertThrows(IllegalArgumentException.class,
                () -> connector(SoapConnector.Version.SOAP_11, Map.of("X-Trace", "1", "x-trace", "2")));
        assertThrows(IllegalArgumentException.class, () -> connector(SoapConnector.Version.SOAP_11, Map.of("X Bad", "v")));
        IllegalArgumentException value = assertThrows(IllegalArgumentException.class,
                () -> connector(SoapConnector.Version.SOAP_11, Map.of("X-Trace", "v\r\nX-Injected: 1")));
        assertFalse(value.getMessage().contains("X-Injected"), value.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"urn:pay\r\nX-Injected: 1", "urn:\"pay", "urn:pay\\"})
    void aSoapActionThatCannotBeSentInAHeaderIsRefusedBeforeAnythingIsSent(String action) {
        for (SoapConnector.Version v : SoapConnector.Version.values()) {
            ConnectorResult r = connector(v, Map.of()).invoke(request(UUID.randomUUID(), action));
            ConnectorResult.BusinessFailure f = assertInstanceOf(ConnectorResult.BusinessFailure.class, r, v + ": " + r);
            assertEquals("INVALID_REQUEST", f.code(), v.name());
            assertFalse(f.message().contains("X-Injected"), v + ": " + f.message());
        }
        assertTrue(seen.isEmpty(), "nothing was sent");
    }
}
