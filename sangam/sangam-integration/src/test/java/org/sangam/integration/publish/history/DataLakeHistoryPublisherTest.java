// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.publish.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

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
import org.sangam.integration.connector.s3.S3ObjectClient;
import org.sangam.integration.relay.DeferPublishException;
import org.sangam.integration.relay.PermanentPublishException;

/**
 * M-057 data-lake history sink: one NDJSON object per history event under a tenant and UTC-day partition taken from the
 * version 7 event id (a redelivery overwrites the same object), and the store's answers map onto done / permanent /
 * deferred / retried.
 */
class DataLakeHistoryPublisherTest {

    record Req(String method, String path, Map<String, String> headers, String body) {
    }

    HttpServer server;
    final List<Req> requests = new CopyOnWriteArrayList<>();
    volatile int status = 200;
    volatile String retryAfter;
    String base;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", x -> {
            Map<String, String> h = new LinkedHashMap<>();
            x.getRequestHeaders().forEach((k, v) -> h.put(k.toLowerCase(Locale.ROOT), String.join(",", v)));
            requests.add(new Req(x.getRequestMethod(), x.getRequestURI().getRawPath(), h,
                    new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            if (retryAfter != null) {
                x.getResponseHeaders().add("Retry-After", retryAfter);
            }
            x.sendResponseHeaders(status, -1);
            x.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    DataLakeHistoryPublisher publisher(String prefix) {
        HttpTransport t = new HttpTransport(EgressPolicy.of(List.of(EgressPolicy.Rule.parse("*", base, true)), 0, 1 << 20),
                null, Duration.ofSeconds(2), Duration.ofSeconds(5), RetryPolicy.none());
        S3ObjectClient client = new S3ObjectClient(new S3ObjectClient.Settings(URI.create(base), "lake", "eu-west-1", true,
                "secret://lake/access", "secret://lake/secret", null), t,
                SecretResolver.of(Map.of("secret://lake/access", "AKIDEXAMPLE", "secret://lake/secret", "s3cr3t")),
                Clock.fixed(Instant.parse("2026-11-12T10:00:00Z"), ZoneOffset.UTC));
        return new DataLakeHistoryPublisher(client, prefix);
    }

    /** A version 7 UUID whose first 48 bits are 2026-11-12T08:30:00Z. */
    static final UUID V7 = v7(Instant.parse("2026-11-12T08:30:00Z").toEpochMilli());

    static UUID v7(long millis) {
        long msb = (millis << 16) | 0x7000L | 0x0123L;
        long lsb = 0x8000_0000_0000_0001L;
        return new UUID(msb, lsb);
    }

    static Outbox.OutboxEvent event(String tenant, UUID id) {
        return new Outbox.OutboxEvent(9L, id, tenant, "pi-1", 4L, "sangam.history.task.complete",
                "{\"taskId\":\"t-1\",\"activityId\":\"approve\"}");
    }

    @Test
    void eachEventIsOneNdjsonObjectUnderItsTenantAndDayAndARedeliveryOverwritesIt() throws Exception {
        DataLakeHistoryPublisher p = publisher("sangam-history");
        Outbox.OutboxEvent e = event("T1", V7);
        p.publish(DataLakeHistoryPublisher.DESTINATION, e);
        p.publish(DataLakeHistoryPublisher.DESTINATION, e);
        assertEquals(2, requests.size());
        for (Req r : requests) {
            assertEquals("PUT", r.method());
            assertEquals("/lake/sangam-history/tenant=T1/date=2026-11-12/" + V7 + ".json", r.path());
            assertEquals("application/x-ndjson", r.headers().get("content-type"));
            assertTrue(r.headers().get("authorization").startsWith("AWS4-HMAC-SHA256 "));
            assertTrue(r.body().endsWith("\n") && r.body().indexOf('\n') == r.body().length() - 1, "one line");
        }
        assertEquals(V7.toString(), Json.tree(requests.get(0).body().trim()).path("eventId").asText());
    }

    @Test
    void anEventIdThatIsNotVersion7IsFiledAsUndatedAndAnUnsafeTenantIsPermanent() throws Exception {
        assertEquals("2026-11-12", DataLakeHistoryPublisher.date(V7));
        UUID v4 = UUID.fromString("0b6a3c1e-7f00-4a8e-9a55-3d4c2e1f0a99");
        assertEquals(DataLakeHistoryPublisher.UNDATED, DataLakeHistoryPublisher.date(v4));
        assertEquals("tenant=T1/date=undated/" + v4 + ".json", DataLakeHistoryPublisher.key("", event("T1", v4)));
        assertThrows(PermanentPublishException.class, () -> DataLakeHistoryPublisher.key("", event("a/b", V7)));
        assertThrows(PermanentPublishException.class, () -> DataLakeHistoryPublisher.key("", event("t 1", V7)));
        assertThrows(PermanentPublishException.class, () -> DataLakeHistoryPublisher.key("", event(null, V7)));
        assertTrue(requests.isEmpty());
    }

    @Test
    void storeAnswersMapOntoPermanentDeferredAndRetried() {
        DataLakeHistoryPublisher p = publisher("");
        Outbox.OutboxEvent e = event("T1", V7);

        status = 403;
        assertThrows(PermanentPublishException.class, () -> p.publish(DataLakeHistoryPublisher.DESTINATION, e));

        status = 503;
        retryAfter = "30";
        DeferPublishException d = assertThrows(DeferPublishException.class,
                () -> p.publish(DataLakeHistoryPublisher.DESTINATION, e));
        assertEquals(Duration.ofSeconds(30), d.delay());

        retryAfter = null;
        assertThrows(IllegalStateException.class, () -> p.publish(DataLakeHistoryPublisher.DESTINATION, e),
                "503 without Retry-After: retried with the relay's back-off");
        status = 500;
        assertThrows(IllegalStateException.class, () -> p.publish(DataLakeHistoryPublisher.DESTINATION, e));
    }

    @Test
    void aMissingCredentialIsPermanentAndNothingIsSent() {
        HttpTransport t = new HttpTransport(EgressPolicy.of(List.of(EgressPolicy.Rule.parse("*", base, true)), 0, 1 << 20),
                null, Duration.ofSeconds(2), Duration.ofSeconds(5), RetryPolicy.none());
        DataLakeHistoryPublisher p = new DataLakeHistoryPublisher(new S3ObjectClient(new S3ObjectClient.Settings(
                URI.create(base), "lake", "eu-west-1", true, "secret://lake/access", "secret://lake/secret", null), t,
                SecretResolver.of(Map.of()), Clock.systemUTC()), "");
        assertThrows(PermanentPublishException.class, () -> p.publish(DataLakeHistoryPublisher.DESTINATION, event("T1", V7)));
        assertTrue(requests.isEmpty());
    }
}
