// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.publish.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.Outbox;
import org.sangam.integration.connector.EgressPolicy;
import org.sangam.integration.connector.HttpTransport;
import org.sangam.integration.connector.Json;
import org.sangam.integration.connector.RetryPolicy;
import org.sangam.integration.connector.SecretResolver;
import org.sangam.integration.relay.DeferPublishException;
import org.sangam.integration.relay.PermanentPublishException;

/**
 * M-057 external history projector sinks: the search-index document is written with PUT under the outbox event id (a
 * redelivery overwrites the same document), carries the projection fields, uses the configured credentials by
 * reference, and maps the cluster's answers onto done / permanent / deferred / retried. The Kafka record key of the
 * history topic is the event id ({@code KafkaPublisherKeyTest} covers the key choice).
 */
class HistoryProjectionPublishersTest {

    record Req(String method, String path, Map<String, String> headers, String body) {
    }

    HttpServer server;
    final List<Req> requests = new CopyOnWriteArrayList<>();
    volatile int status = 201;
    volatile String retryAfter;
    String base;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", x -> {
            Map<String, String> h = new LinkedHashMap<>();
            x.getRequestHeaders().forEach((k, v) -> h.put(k.toLowerCase(java.util.Locale.ROOT), String.join(",", v)));
            requests.add(new Req(x.getRequestMethod(), x.getRequestURI().getRawPath(), h,
                    new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            if (retryAfter != null) {
                x.getResponseHeaders().add("Retry-After", retryAfter);
            }
            byte[] b = "{\"result\":\"created\"}".getBytes(StandardCharsets.UTF_8);
            x.sendResponseHeaders(status, b.length);
            x.getResponseBody().write(b);
            x.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    HttpTransport transport() {
        return new HttpTransport(EgressPolicy.of(List.of(EgressPolicy.Rule.parse("*", base, true)), 0, 1 << 20),
                null, Duration.ofSeconds(2), Duration.ofSeconds(5), RetryPolicy.none());
    }

    static final UUID ID = UUID.fromString("0b6a3c1e-7f00-4a8e-9a55-3d4c2e1f0a99");

    static Outbox.OutboxEvent event(String tenant, String payload) {
        return new Outbox.OutboxEvent(7L, ID, tenant, "pi-1", 3L, "sangam.history.activity.start", payload);
    }

    SearchIndexPublisher publisher(String index, String user, String passwordRef, String apiKeyRef, Map<String, String> secrets) {
        return new SearchIndexPublisher(transport(),
                new SearchIndexPublisher.Settings(URI.create(base + "/"), index, user, passwordRef, apiKeyRef),
                SecretResolver.of(secrets));
    }

    @Test
    void theDocumentIsPutUnderTheEventIdWithTheProjectionFieldsAndARedeliveryHitsTheSameDocument() throws Exception {
        SearchIndexPublisher p = publisher("sangam-history", null, null, null, Map.of());
        Outbox.OutboxEvent e = event("T1", "{\"activityId\":\"pay\",\"activityType\":\"serviceTask\"}");
        p.publish(SearchIndexPublisher.DESTINATION, e);
        p.publish(SearchIndexPublisher.DESTINATION, e);

        assertEquals(2, requests.size());
        for (Req r : requests) {
            assertEquals("PUT", r.method());
            assertEquals("/sangam-history/_doc/" + ID, r.path());
            assertFalse(r.headers().containsKey("authorization"), "no credentials configured, none sent");
        }
        JsonNode d = Json.tree(requests.get(0).body());
        assertEquals(ID.toString(), d.path("eventId").asText());
        assertEquals("T1", d.path("tenantId").asText());
        assertEquals("pi-1", d.path("rootId").asText());
        assertEquals(3L, d.path("rootSeq").asLong());
        assertEquals("sangam.history.activity.start", d.path("eventType").asText());
        assertEquals("pay", d.path("data").path("activityId").asText());
        assertEquals(requests.get(0).body(), requests.get(1).body(), "the same event gives the same document");
    }

    @Test
    void aPerTenantIndexIsFoldedToLowerCaseAndAnInvalidNameIsPermanent() throws Exception {
        publisher("hist-{tenant}", null, null, null, Map.of()).publish(SearchIndexPublisher.DESTINATION, event("Dept7", "{}"));
        assertEquals("/hist-dept7/_doc/" + ID, requests.get(0).path());

        assertThrows(PermanentPublishException.class,
                () -> publisher("hist-{tenant}", null, null, null, Map.of()).publish("x", event("a/b", "{}")));
        assertThrows(PermanentPublishException.class, () -> SearchIndexPublisher.index("_hidden", "T1"));
        assertThrows(PermanentPublishException.class, () -> SearchIndexPublisher.index("..", "T1"));
        assertThrows(PermanentPublishException.class, () -> SearchIndexPublisher.index("a:b", "T1"));
        assertEquals(1, requests.size(), "an invalid index is refused before any call");
    }

    @Test
    void aPayloadThatIsNotJsonIsKeptAsText() {
        JsonNode d = SearchIndexPublisher.document(event("T1", "not json"));
        assertTrue(d.path("data").isMissingNode());
        assertEquals("not json", d.path("dataText").asText());
    }

    @Test
    void basicAndApiKeyCredentialsComeFromSecretReferences() throws Exception {
        publisher("h", "projector", "secret://hist/password", null, Map.of("secret://hist/password", "p4ss"))
                .publish("x", event("T1", "{}"));
        assertEquals("Basic " + Base64.getEncoder().encodeToString("projector:p4ss".getBytes(StandardCharsets.UTF_8)),
                requests.get(0).headers().get("authorization"));

        publisher("h", null, null, "secret://hist/key", Map.of("secret://hist/key", "aWQ6a2V5"))
                .publish("x", event("T1", "{}"));
        assertEquals("ApiKey aWQ6a2V5", requests.get(1).headers().get("authorization"));

        assertThrows(IllegalStateException.class,
                () -> publisher("h", null, null, "secret://hist/missing", Map.of()).publish("x", event("T1", "{}")),
                "an unresolvable reference fails the attempt (retried), never sends without credentials");
        assertEquals(2, requests.size());
    }

    @Test
    void settingsRefuseHalfOrDoubleCredentials() {
        URI u = URI.create("https://search.example");
        assertThrows(IllegalArgumentException.class, () -> new SearchIndexPublisher.Settings(u, "h", "user", null, null));
        assertThrows(IllegalArgumentException.class, () -> new SearchIndexPublisher.Settings(u, "h", null, "secret://p", null));
        assertThrows(IllegalArgumentException.class,
                () -> new SearchIndexPublisher.Settings(u, "h", "user", "secret://p", "secret://k"));
        assertThrows(IllegalArgumentException.class, () -> new SearchIndexPublisher.Settings(u, " ", null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new SearchIndexPublisher.Settings(URI.create("search.example"), "h", null, null, null));
    }

    @Test
    void answersMapToPermanentDeferredOrRetried() {
        SearchIndexPublisher p = publisher("h", null, null, null, Map.of());
        status = 400;
        assertThrows(PermanentPublishException.class, () -> p.publish("x", event("T1", "{}")));
        status = 429;
        retryAfter = "7";
        DeferPublishException d = assertThrows(DeferPublishException.class, () -> p.publish("x", event("T1", "{}")));
        assertTrue(d.getMessage().contains("7 s"), d.getMessage());
        retryAfter = null;
        status = 503;
        assertThrows(IllegalStateException.class, () -> p.publish("x", event("T1", "{}")));
        status = 401;
        assertThrows(IllegalStateException.class, () -> p.publish("x", event("T1", "{}")),
                "a credential problem is retried (fixable by configuration), not dead-lettered at once");
    }

    @Test
    void anUnlistedHostIsAPermanentEgressRefusal() {
        SearchIndexPublisher p = new SearchIndexPublisher(transport(),
                new SearchIndexPublisher.Settings(URI.create("http://127.0.0.2:9/"), "h", null, null, null),
                SecretResolver.of(Map.of()));
        assertThrows(PermanentPublishException.class, () -> p.publish("x", event("T1", "{}")));
        assertTrue(requests.isEmpty());
    }
}
