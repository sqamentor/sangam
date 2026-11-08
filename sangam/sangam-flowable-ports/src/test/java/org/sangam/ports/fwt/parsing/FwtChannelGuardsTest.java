// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.fwt.parsing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.sun.net.httpserver.HttpServer;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Inbox;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Schema;
import org.sangam.core.layer.Transactions;
import org.sangam.ports.PortsSchema;
import org.sangam.ports.events.EventLog;
import org.sangam.ports.events.EventRegistry;
import org.sangam.ports.events.InboundEventProcessor;
import org.sangam.ports.events.InboundEventProcessor.Outcome;
import org.sangam.ports.events.channel.ChannelAdapters;
import org.sangam.ports.events.channel.WebhookInboundEndpoint;
import org.sangam.ports.support.PortsProblem;

/**
 * FW-02 / FW-41 / FW-47 channel scenarios (FLOWABLE-WEAKNESS-AUDIT §5) on H2 (MODE=PostgreSQL) with the real event
 * registry, inbox, event log and channel adapters; remote endpoints are local {@code HttpServer}s on 127.0.0.1.
 */
class FwtChannelGuardsTest {

    static final String T = "tA";
    JdbcDataSource ds;
    Transactions tx;
    EventRegistry registry;
    EventLog log;
    Inbox inbox;
    HttpServer server;
    final AtomicInteger secretLookups = new AtomicInteger();
    final AtomicInteger redirectedHits = new AtomicInteger();

    final ChannelAdapters.Environment env = new ChannelAdapters.Environment() {
        @Override
        public Optional<String> secret(String ref) {
            secretLookups.incrementAndGet();
            return "hook-secret".equals(ref) ? Optional.of("s3cr3t") : Optional.empty();
        }

        @Override
        public Set<String> egressHosts() {
            return Set.of("127.0.0.1");
        }
    };

    @BeforeEach
    void setUp() throws Exception {
        ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:fwtch" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        try (Connection c = ds.getConnection()) {
            Schema.create(c, Dialect.POSTGRESQL);
            PortsSchema.create(c, Dialect.POSTGRESQL);
        }
        tx = Transactions.of(ds);
        registry = new EventRegistry(Dialect.POSTGRESQL, tx, Duration.ZERO);
        log = new EventLog(Dialect.POSTGRESQL, true);
        inbox = new Inbox(Dialect.POSTGRESQL, tx, "n1", Duration.ofMinutes(1), Duration.ofSeconds(5), null);
        registry.deployEvent(T, "{\"key\":\"order\",\"payload\":[{\"name\":\"amount\",\"type\":\"long\"},{\"name\":\"note\",\"type\":\"string\"},"
                + "{\"name\":\"doc\",\"type\":\"json\"}]}", null);
        registry.deployChannel(T, "{\"key\":\"in\",\"channelType\":\"webhook\",\"type\":\"inbound\","
                + "\"channelEventKeyDetection\":{\"fixedValue\":\"order\"}}", null);
        registry.deployChannel(T, "{\"key\":\"signed\",\"channelType\":\"webhook\",\"type\":\"inbound\","
                + "\"channelEventKeyDetection\":{\"fixedValue\":\"order\"},\"properties\":{\"secretRef\":\"hook-secret\"}}", null);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.createContext("/slow", ex -> {
            try {
                Thread.sleep(6_000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            ex.sendResponseHeaders(204, -1);
            ex.close();
        });
        server.createContext("/huge", ex -> {
            ex.getRequestBody().readAllBytes();
            ex.sendResponseHeaders(200, 0); // chunked
            byte[] chunk = new byte[1024 * 1024];
            try (OutputStream out = ex.getResponseBody()) {
                for (int i = 0; i < 1024; i++) { // 1 GiB
                    out.write(chunk);
                }
            } catch (java.io.IOException clientGone) {
                // the client may stop reading; nothing to do
            }
            ex.close();
        });
        server.createContext("/redirect", ex -> {
            ex.getResponseHeaders().add("Location", "http://127.0.0.1:" + server.getAddress().getPort() + "/target");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        server.createContext("/target", ex -> {
            redirectedHits.incrementAndGet();
            ex.sendResponseHeaders(204, -1);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private long logRows(String outcome) {
        return tx.inTransaction(c -> Jdbc.one(c, "SELECT COUNT(*) FROM EVT_LOG WHERE outcome = ?", rs -> rs.getLong(1), outcome).orElse(0L));
    }

    private long inboxRows() {
        return tx.inTransaction(c -> Jdbc.one(c, "SELECT COUNT(*) FROM INBOX", rs -> rs.getLong(1)).orElse(0L));
    }

    static String hmac(String secret, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    // FWT-005
    @Test
    void fwt005_deepNestingHugeStringLongNumberRejectedInvalidInputWithoutThrowing() {
        // limit raised above 20 MB so the Jackson stream constraints (not the size pre-check) decide
        InboundEventProcessor p = new InboundEventProcessor(registry, inbox, log, tx, 32 * 1024 * 1024);
        String deep = "{\"doc\":" + "[".repeat(500) + "]".repeat(500) + "}";
        String hugeString = "{\"note\":\"" + "a".repeat(20 * 1024 * 1024) + "\"}";
        String longNumber = "{\"amount\":" + "9".repeat(1000) + "}";
        int i = 0;
        for (String raw : new String[]{deep, hugeString, longNumber}) {
            InboundEventProcessor.Result r = p.receive(T, "in", raw, Map.of(), "m-" + i++); // returns: the broker acks
            assertEquals(Outcome.REJECTED, r.outcome(), String.valueOf(r.reason()).substring(0, Math.min(200, String.valueOf(r.reason()).length())));
            assertEquals("INVALID_INPUT", r.code());
        }
        assertEquals(3, logRows("REJECTED"));
        assertEquals(0, inboxRows());
        // with the default 1 MiB limit the 20 MB string is refused before parsing
        InboundEventProcessor dflt = new InboundEventProcessor(registry, inbox, log, tx);
        InboundEventProcessor.Result r = dflt.receive(T, "in", hugeString, Map.of(), "m-big");
        assertEquals(Outcome.REJECTED, r.outcome());
        assertEquals("PAYLOAD_TOO_LARGE", r.code());
        // control: a valid event is accepted
        assertEquals(Outcome.ACCEPTED, dflt.receive(T, "in", "{\"amount\":5}", Map.of(), "m-ok").outcome());
    }

    // FWT-006
    @Test
    void fwt006_twoMiBWebhookBodyIs413BeforeHmac() {
        WebhookInboundEndpoint ep = new WebhookInboundEndpoint(registry, new InboundEventProcessor(registry, inbox, log, tx), env);
        String body = "{\"note\":\"" + "a".repeat(2 * 1024 * 1024) + "\"}";
        PortsProblem p = assertThrows(PortsProblem.class, () -> ep.receive(T, "signed", Map.of("X-Sangam-Signature", "sha256=00"), body));
        assertEquals(413, p.status());
        assertEquals("PAYLOAD_TOO_LARGE", p.code());
        assertEquals(0, secretLookups.get(), "no secret resolved and no HMAC computed for an oversized body");
        assertEquals(0, inboxRows());
    }

    // FWT-051
    @Test
    void fwt051_webhookSignatureMissingWrongRight() throws Exception {
        WebhookInboundEndpoint ep = new WebhookInboundEndpoint(registry, new InboundEventProcessor(registry, inbox, log, tx), env);
        String body = "{\"amount\":42}";
        // 403 at the REST layer: SecurityException -> PortsErrors ACTION_NOT_PERMITTED (forbidden)
        assertThrows(SecurityException.class, () -> ep.receive(T, "signed", Map.of("Idempotency-Key", "k1"), body));
        assertThrows(SecurityException.class, () -> ep.receive(T, "signed",
                Map.of("Idempotency-Key", "k2", "X-Sangam-Signature", hmac("wrong", body)), body));
        assertEquals(0, inboxRows(), "nothing accepted for a missing or wrong signature");
        InboundEventProcessor.Result ok = ep.receive(T, "signed",
                Map.of("Idempotency-Key", "k3", "X-Sangam-Signature", hmac("s3cr3t", body)), body);
        assertEquals(Outcome.ACCEPTED, ok.outcome(), ok.reason());
        assertEquals(1, inboxRows());
    }

    private ChannelAdapters.OutboundChannelAdapter outbound(String path, String timeoutMs) {
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + path;
        String json = "{\"key\":\"out\",\"channelType\":\"http\",\"type\":\"outbound\",\"url\":\"" + url + "\","
                + "\"properties\":{\"allowPlainHttp\":\"true\",\"timeoutMs\":\"" + timeoutMs + "\"}}";
        return ChannelAdapters.outbound(org.sangam.ports.support.Json.read(json,
                org.sangam.ports.events.model.ChannelDefinition.class), env);
    }

    // FWT-046
    @Test
    void fwt046_outboundTimeoutZeroIsOneSecond() {
        ChannelAdapters.OutboundChannelAdapter a = outbound("/slow", "0");
        long t0 = System.nanoTime();
        assertThrows(HttpTimeoutException.class, () -> a.send(T, "order", "m1", "{}"));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(ms >= 900 && ms < 4_000, "timeout 0 is clamped to 1 s, took " + ms + " ms");
    }

    // FWT-046
    @Test
    void fwt046_outboundTimeoutNotANumberIsADeployError() {
        String json = "{\"key\":\"out-abc\",\"channelType\":\"http\",\"type\":\"outbound\",\"url\":\"https://127.0.0.1/x\","
                + "\"properties\":{\"timeoutMs\":\"abc\"}}";
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> registry.deployChannel(T, json, null));
        assertTrue(e.getMessage().contains("timeoutMs"), e.getMessage());
        assertTrue(registry.channel(T, "out-abc").isEmpty(), "nothing deployed");
    }

    // FWT-046
    @Test
    void fwt046_outboundOneGiBResponseBodyIsDiscardedNotBuffered() throws Exception {
        ChannelAdapters.OutboundChannelAdapter a = outbound("/huge", "60000");
        // Oracle: peak of the tenured/old pool. Buffering a 1 GiB body needs >= 1 GiB live at once (large arrays go
        // straight to the old generation); a discarded body only churns small young-generation buffers. The plain
        // used-heap delta is not an oracle (young garbage from discarding is counted too).
        List<java.lang.management.MemoryPoolMXBean> old = java.lang.management.ManagementFactory.getMemoryPoolMXBeans().stream()
                .filter(p -> p.getType() == java.lang.management.MemoryType.HEAP)
                .filter(p -> p.getName().contains("Old") || p.getName().contains("Tenured"))
                .toList();
        assertTrue(!old.isEmpty(), "no old-generation pool found");
        System.gc();
        long before = old.stream().mapToLong(p -> p.getUsage().getUsed()).sum();
        old.forEach(java.lang.management.MemoryPoolMXBean::resetPeakUsage);
        a.send(T, "order", "m1", "{}");
        long peak = old.stream().mapToLong(p -> p.getPeakUsage().getUsed()).sum();
        assertTrue(peak - before < 256L * 1024 * 1024, "1 GiB body not buffered; old-gen peak grew " + (peak - before) + " bytes");
    }

    // FWT-046
    @Test
    void fwt046_outboundRedirectIsNotFollowed() {
        ChannelAdapters.OutboundChannelAdapter a = outbound("/redirect", "5000");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> a.send(T, "order", "m1", "{}"));
        assertTrue(e.getMessage().contains("302"), e.getMessage());
        assertEquals(0, redirectedHits.get(), "redirect not followed");
    }
}
