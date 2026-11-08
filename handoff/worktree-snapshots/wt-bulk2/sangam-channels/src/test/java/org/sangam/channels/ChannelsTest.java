// SPDX-License-Identifier: Apache-2.0
package org.sangam.channels;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.operaton.bpm.model.bpmn.Bpmn;
import org.sangam.channels.conversation.ConversationService;
import org.sangam.channels.enterprise.EnterpriseConnectors;
import org.sangam.channels.enterprise.EnterpriseSimulator;
import org.sangam.channels.inbound.PolledChannels;
import org.sangam.channels.mail.InboundMail;
import org.sangam.channels.messenger.InboundMessageParsers;
import org.sangam.channels.messenger.MessengerConnectors;
import org.sangam.channels.messenger.WebhookVerifiers;
import org.sangam.channels.sim.MessengerSimulator;
import org.sangam.core.integration.ConnectorProfile;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.ConnectorResult;
import org.sangam.core.integration.LookupResult;
import org.sangam.core.integration.SangamConnector;
import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Schema;
import org.sangam.core.layer.Transactions;
import org.sangam.core.ports.DocumentStore;
import org.sangam.integration.connector.HttpTransport;
import org.sangam.integration.connector.SecretResolver;
import org.sangam.integration.connector.file.LocalDirectoryTransport;
import org.sangam.ports.events.InboundEventProcessor;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class ChannelsTest {

    Transactions tx;
    AuditChain audit = new AuditChain(Dialect.POSTGRESQL);

    static class MemoryStore implements DocumentStore {
        final Map<String, byte[]> m = new HashMap<>();

        public String type() {
            return "mem";
        }

        public StoredObject put(String key, InputStream c, String t) throws IOException {
            byte[] b = c.readAllBytes();
            m.put(key, b);
            return new StoredObject(key, b.length, "hash");
        }

        public Optional<InputStream> get(String key) {
            return Optional.ofNullable(m.get(key)).map(ByteArrayInputStream::new);
        }

        public boolean exists(String key) {
            return m.containsKey(key);
        }

        public void delete(String key) {
            m.remove(key);
        }
    }

    @BeforeEach
    void db() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        try (Connection c = ds.getConnection()) {
            Schema.create(c, Dialect.POSTGRESQL);
            ChannelsSchema.create(c, Dialect.POSTGRESQL);
        }
        tx = Transactions.of(ds);
    }

    static ConnectorRequest req(String op, Map<String, Object> in) {
        return new ConnectorRequest("t", UUID.randomUUID(), "a", 1, "root", null, op, in, null);
    }

    @Test
    void conversationAccessIdempotencySearchAndLiveDelivery() {
        List<ConversationService.Message> delivered = new ArrayList<>();
        ConversationService s = new ConversationService(Dialect.POSTGRESQL, tx, audit, new MemoryStore(), (t, r, m) -> delivered.add(m), (n, b) -> !n.endsWith(".exe"), (t, p) -> !p.equals("banned"));
        var conv = s.create("t", "alice", "topic", "case-1", "SHORT", Set.of("bob"));
        var a = s.upload("t", conv.id(), "alice", "a.txt", "text/plain", "hi".getBytes());
        assertThrows(IllegalArgumentException.class, () -> s.upload("t", conv.id(), "alice", "a.exe", "x", new byte[1]));
        var m1 = s.send("t", conv.id(), "alice", "Hello 100% World", List.of(a), "k1");
        assertEquals(m1.id(), s.send("t", conv.id(), "alice", "Hello 100% World", List.of(a), "k1").id());
        assertEquals(1, delivered.size());
        assertThrows(ConversationService.AccessDenied.class, () -> s.history("t", conv.id(), "eve", 10));
        assertThrows(ConversationService.AccessDenied.class, () -> s.addParticipant("t", conv.id(), "bob", "banned"));
        assertTrue(s.search("t", "eve", "hello", 10).isEmpty());
        assertEquals(1, s.search("t", "bob", "100%", 10).size());
        assertEquals(1, s.history("t", conv.id(), "bob", 10).get(0).attachments().size());
        assertEquals(1, s.purgeBefore("t", "SHORT", Instant.now().plusSeconds(60), "ops"));
        assertTrue(s.history("t", conv.id(), "bob", 10).isEmpty());
    }

    @Test
    void messengersNeedOptInAndMapResults() {
        try (MessengerSimulator sim = new MessengerSimulator().start()) {
            SecretResolver sec = SecretResolver.of(Map.of());
            HttpTransport tr = HttpTransport.forSimulator();
            SangamConnector wa = MessengerConnectors.whatsApp("wa", ConnectorProfile.SIMULATOR, sim.uri("/wa"), "123", "tok", tr, sec, r -> "good".equals(r.str("to")));
            assertInstanceOf(ConnectorResult.BusinessFailure.class, wa.invoke(req("send", Map.of("to", "stranger", "text", "x"))));
            assertInstanceOf(ConnectorResult.Success.class, wa.invoke(req("send", Map.of("to", "good", "text", "x"))));
            assertEquals("Bearer tok", sim.sent().get(0).authorization());
            SangamConnector teams = MessengerConnectors.teams("te", ConnectorProfile.SIMULATOR, tr, sec, "tok", r -> true);
            assertInstanceOf(ConnectorResult.Success.class, teams.invoke(req("send", Map.of("serviceUrl", sim.baseUri().toString(), "conversationId", "c/1", "text", "x", "card", Map.of("type", "AdaptiveCard")))));
            assertInstanceOf(ConnectorResult.Success.class, MessengerConnectors.weChat("wc", ConnectorProfile.SIMULATOR, sim.baseUri(), "ok", tr, sec, r -> true).invoke(req("send", Map.of("to", "u", "text", "x"))));
            ConnectorResult bad = MessengerConnectors.weChat("wc", ConnectorProfile.SIMULATOR, sim.baseUri(), "bad", tr, sec, r -> true).invoke(req("send", Map.of("to", "u", "text", "x")));
            assertEquals("WECHAT_40001", ((ConnectorResult.BusinessFailure) bad).code());
        }
    }

    @Test
    void registryEntryBuildsConnectorOrIsRefused() {
        HttpTransport tr = HttpTransport.forSimulator();
        SecretResolver sec = SecretResolver.of(Map.of());
        var runtime = org.sangam.channels.messenger.ChannelRuntime.connector(Map.of("key", "ch", "channel", "whatsapp", "tokenRef", "t", "apiBase", "http://127.0.0.1:1", "phoneNumberId", "1"),
                ConnectorProfile.SIMULATOR, tr, sec, r -> true);
        assertEquals("whatsapp", runtime.descriptor().type());
        assertThrows(IllegalArgumentException.class, () -> org.sangam.channels.messenger.ChannelRuntime.connector(Map.of("key", "m", "channel", "MATRIX", "tokenRef", "t"), ConnectorProfile.SIMULATOR, tr, sec, r -> true));
    }

    @Test
    void egressDenyAllSendsNothing() {
        try (MessengerSimulator sim = new MessengerSimulator().start()) {
            HttpTransport denied = new HttpTransport(org.sangam.integration.connector.EgressPolicy.denyAll(), null, null, null, null);
            ConnectorResult r = MessengerConnectors.whatsApp("wa", ConnectorProfile.LIVE, sim.uri("/wa"), "1", "t", denied, SecretResolver.of(Map.of()), x -> true).invoke(req("send", Map.of("to", "g", "text", "x")));
            assertTrue(((ConnectorResult.TransientFailure) r).noEffect());
            assertTrue(sim.sent().isEmpty());
        }
    }

    @Test
    void webhookSignatures() throws Exception {
        byte[] body = "{}".getBytes();
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("s".getBytes(), "HmacSHA256"));
        String hex = HexFormat.of().formatHex(mac.doFinal(body));
        assertTrue(WebhookVerifiers.whatsApp("s", body, "sha256=" + hex));
        assertFalse(WebhookVerifiers.whatsApp("x", body, "sha256=" + hex));
        assertFalse(WebhookVerifiers.whatsApp("s", body, null));
        assertTrue(EnterpriseConnectors.serviceNowWebhookValid("s", body, hex));

        String[] parts = {"tok", "1", "n"};
        java.util.Arrays.sort(parts);
        String sha = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-1").digest(String.join("", parts).getBytes()));
        assertTrue(WebhookVerifiers.weChat("tok", "1", "n", sha));
        assertFalse(WebhookVerifiers.weChat("tok", "2", "n", sha));

        var kp = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        Base64.Encoder e = Base64.getUrlEncoder().withoutPadding();
        String head = e.encodeToString("{\"alg\":\"RS256\",\"kid\":\"k\"}".getBytes());
        String claims = e.encodeToString("{\"iss\":\"i\",\"aud\":\"a\",\"exp\":9999999999,\"serviceUrl\":\"https://s\"}".getBytes());
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(kp.getPrivate());
        sig.update((head + "." + claims).getBytes());
        String jwt = "Bearer " + head + "." + claims + "." + e.encodeToString(sig.sign());
        assertTrue(WebhookVerifiers.botFramework(jwt, "i", "a", "https://s", k -> Optional.of(kp.getPublic()), Instant.now()));
        assertFalse(WebhookVerifiers.botFramework(jwt, "i", "other", "https://s", k -> Optional.of(kp.getPublic()), Instant.now()));
        assertFalse(WebhookVerifiers.botFramework(jwt, "i", "a", "https://s", k -> Optional.empty(), Instant.now()));
        assertFalse(WebhookVerifiers.botFramework(jwt, "i", "a", "https://evil", k -> Optional.of(kp.getPublic()), Instant.now()), "serviceUrl claim must match the activity");

        assertEquals("c1", WebhookVerifiers.whatsAppChallenge("v", "subscribe", "v", "c1"));
        assertNull(WebhookVerifiers.whatsAppChallenge("v", "subscribe", "x", "c1"));
    }

    @Test
    void odataRetriesOnceWhenTheTokenExpired() {
        try (var sim = new org.sangam.channels.enterprise.EnterpriseSimulator()) {
            sim.expireNextToken();
            ConnectorResult r = org.sangam.channels.enterprise.EnterpriseConnectors.sapOData("sap", ConnectorProfile.SIMULATOR, sim.odataRoot(), "a",
                    org.sangam.integration.connector.HttpTransport.forSimulator(), SecretResolver.of(Map.of("a", "Basic x")))
                    .invoke(req("create", Map.of("entitySet", "Orders", "fields", Map.of("k", "v"))));
            assertInstanceOf(ConnectorResult.Success.class, r);
            assertEquals(1, sim.entities().size());
        }
    }

    @Test
    void parsersRefuseDoctype() {
        assertEquals("hi", InboundMessageParsers.weChat("<xml><MsgId>1</MsgId><FromUserName>u</FromUserName><Content>hi</Content></xml>".getBytes()).text());
        assertThrows(IllegalArgumentException.class, () -> InboundMessageParsers.weChat("<!DOCTYPE x [<!ENTITY a \"b\">]><xml/>".getBytes()));
        var p = InboundMessageParsers.whatsApp("{\"entry\":[{\"changes\":[{\"value\":{\"messages\":[{\"id\":\"m\",\"from\":\"9\",\"type\":\"text\",\"text\":{\"body\":\"hey\"}}]}}]}]}".getBytes());
        assertEquals("hey", p.get(0).text());
    }

    @Test
    void mailIsIdempotentAndPoisonIsRecorded() throws Exception {
        MemoryStore store = new MemoryStore();
        List<String> events = new ArrayList<>();
        List<String> poison = new ArrayList<>();
        InboundMail in = new InboundMail(Dialect.POSTGRESQL, tx, audit, store, (n, b) -> !n.equals("virus"), (t, c, m, a) -> events.add(m.messageId() + ":" + a.size()), (t, c, id, why) -> poison.add(id));
        InboundMail.Simulated box = new InboundMail.Simulated()
                .deliver(new InboundMail.Mail("<1>", "a@x", "s", "body", List.of(new InboundMail.Attachment("f", "text/plain", new byte[3]))))
                .deliver(new InboundMail.Mail("<2>", "a@x", "s", "b", List.of(new InboundMail.Attachment("virus", "x", new byte[1]))))
                .deliver(new InboundMail.Mail(null, "a@x", "s", "b", List.of()));
        InboundMail.PollResult r = in.poll("t", "inbox", box, 10);
        assertEquals(new InboundMail.PollResult(1, 0, 2), r);
        assertEquals(List.of("<1>:1"), events);
        assertEquals(1, store.m.size());
        box.deliver(new InboundMail.Mail("<1>", "a@x", "s", "again", List.of()));
        assertEquals(1, in.poll("t", "inbox", box, 10).duplicates());
        assertEquals(1, events.size());
    }

    @Test
    void fileChannelMovesAfterHandover(@TempDir Path dir) throws Exception {
        LocalDirectoryTransport fs = new LocalDirectoryTransport(dir);
        fs.write("in", "a.json", "{}".getBytes());
        fs.write("in", "bad.json", "x".getBytes());
        fs.write("in", "skip.txt", "x".getBytes());
        PolledChannels.Receiver rc = (t, c, raw, h, id) -> raw.equals("x") ? InboundEventProcessor.Outcome.REJECTED : InboundEventProcessor.Outcome.ACCEPTED;
        var s = new PolledChannels.FileChannel("t", "c", fs, "in", "done", "error", Pattern.compile(".*\\.json"), rc).pollOnce(10);
        assertEquals(new PolledChannels.Summary(1, 0, 1), s);
        assertTrue(fs.exists("done", "a.json"));
        assertTrue(fs.exists("error", "bad.json"));
        assertTrue(Files.exists(dir.resolve("in").resolve("skip.txt")));
    }

    @Test
    void sqlChannelResumesFromCursor() {
        tx.inTransaction(c -> {
            Jdbc.update(c, "CREATE TABLE SRC (id BIGINT, v VARCHAR(10))");
            Jdbc.update(c, "INSERT INTO SRC VALUES (1,'a'),(2,'b')");
            return null;
        });
        List<String> ids = new ArrayList<>();
        PolledChannels.SqlChannel ch = new PolledChannels.SqlChannel("t", "c", tx, tx, Dialect.POSTGRESQL, "SELECT id, v FROM SRC WHERE id > CAST(? AS BIGINT) ORDER BY id", "0",
                (t, c, raw, h, id) -> {
                    ids.add(id);
                    return InboundEventProcessor.Outcome.ACCEPTED;
                });
        assertEquals(1, ch.pollOnce(1).accepted());
        assertEquals(1, ch.pollOnce(5).accepted());
        assertEquals(0, ch.pollOnce(5).accepted());
        assertEquals(List.of("c:1", "c:2"), ids);
        assertThrows(IllegalArgumentException.class, () -> new PolledChannels.SqlChannel("t", "c", tx, tx, Dialect.POSTGRESQL, "DELETE FROM SRC", "0", null));
    }

    @Test
    void serviceNowAndSapThroughSimulator() {
        try (EnterpriseSimulator sim = new EnterpriseSimulator()) {
            SecretResolver sec = SecretResolver.of(Map.of());
            HttpTransport tr = HttpTransport.forSimulator();
            SangamConnector sn = EnterpriseConnectors.serviceNow("sn", ConnectorProfile.SIMULATOR, sim.baseUri(), "Basic x", "correlation_id", tr, sec);
            ConnectorRequest r = req("create", Map.of("table", "incident", "fields", Map.of("short_description", "x")));
            assertInstanceOf(LookupResult.NotFound.class, sn.lookup(r));
            assertInstanceOf(ConnectorResult.Success.class, sn.invoke(r), () -> sn.invoke(r).toString());
            assertInstanceOf(LookupResult.Found.class, sn.lookup(r));
            assertEquals(r.operationId().toString(), sim.records().get(0).get("correlation_id"));
            assertInstanceOf(ConnectorResult.BusinessFailure.class, sn.invoke(req("create", Map.of("table", "../x", "fields", Map.of()))));

            SangamConnector sap = EnterpriseConnectors.sapOData("sap", ConnectorProfile.SIMULATOR, sim.odataRoot(), "Basic x", tr, sec);
            ConnectorResult created = sap.invoke(req("create", Map.of("entitySet", "Orders", "fields", Map.of("A", "1"))));
            assertInstanceOf(ConnectorResult.Success.class, created, () -> created.toString());
            assertEquals(1, sim.entities().size());
            assertInstanceOf(ConnectorResult.Success.class, sap.invoke(req("read", Map.of("entitySet", "Orders"))));
        }
    }

    @Test
    void serviceBusCompletesOnlyAfterHandover() throws Exception {
        List<String> calls = new ArrayList<>();
        java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>(List.of("good", "bad"));
        var srv = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0);
        srv.createContext("/", x -> {
            calls.add(x.getRequestMethod() + " " + x.getRequestURI().getPath());
            assertTrue(x.getRequestHeaders().getFirst("Authorization").startsWith("SharedAccessSignature sr="));
            if (x.getRequestMethod().equals("POST")) {
                String m = queue.poll();
                if (m == null) {
                    x.sendResponseHeaders(204, -1);
                } else {
                    x.getResponseHeaders().add("BrokerProperties", "{\"MessageId\":\"" + m + "\"}");
                    x.getResponseHeaders().add("Location", "http://127.0.0.1:" + srv.getAddress().getPort() + "/q/messages/" + m + "/lock");
                    byte[] b = m.getBytes();
                    x.sendResponseHeaders(201, b.length);
                    x.getResponseBody().write(b);
                }
            } else {
                x.sendResponseHeaders(200, -1);
            }
            x.close();
        });
        srv.start();
        try {
            var ch = new PolledChannels.ServiceBusChannel("t", "c", java.net.URI.create("http://127.0.0.1:" + srv.getAddress().getPort()), "q", "key", "sas", HttpTransport.forSimulator(),
                    SecretResolver.of(Map.of()), now -> now.plusSeconds(60), (t, c, raw, h, id) -> raw.equals("bad") ? InboundEventProcessor.Outcome.REJECTED : InboundEventProcessor.Outcome.ACCEPTED);
            assertEquals(new PolledChannels.Summary(1, 0, 1), ch.pollOnce(5));
            assertEquals(1, calls.stream().filter(s -> s.startsWith("DELETE")).count());
            assertTrue(calls.contains("DELETE /q/messages/good/lock"));
        } finally {
            srv.stop(0);
        }
    }

    @Test
    void sampleBotParses() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/bots/sample-bot.bpmn")) {
            Bpmn.validateModel(Bpmn.readModelFromStream(in));
        }
        assertNotNull(StandardCharsets.UTF_8);
    }
}
