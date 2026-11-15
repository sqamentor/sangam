// SPDX-License-Identifier: Apache-2.0
package org.sangam.channels.mail;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.channels.ChannelsSchema;
import org.sangam.channels.messenger.ProviderTokens.TokenSource;
import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Schema;
import org.sangam.core.layer.Transactions;
import org.sangam.core.ports.DocumentStore;
import org.sangam.integration.connector.EgressPolicy;
import org.sangam.integration.connector.HttpTransport;
import org.sangam.integration.connector.Json;
import org.sangam.integration.connector.SecretResolver;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Microsoft Graph mail source (NG-27, M-071) against a loopback fake of the public Graph v1.0 mail endpoints. The fake
 * serves several methods on one path, which the shared simulator base does not, so it uses the JDK server directly.
 * Written, not run (Wave 3).
 */
class GraphMailSourceTest {

    HttpServer server;
    URI base;
    final Set<String> read = ConcurrentHashMap.newKeySet();
    final List<String> requests = new CopyOnWriteArrayList<>();
    volatile boolean rejectToken = false;
    Transactions tx;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v1.0/users/", this::graph);
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1.0");
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        try (Connection c = ds.getConnection()) {
            Schema.create(c, Dialect.POSTGRESQL);
            ChannelsSchema.create(c, Dialect.POSTGRESQL);
        }
        tx = Transactions.of(ds);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void graph(HttpExchange x) throws IOException {
        String path = x.getRequestURI().getPath();
        requests.add(x.getRequestMethod() + " " + path + (x.getRequestURI().getRawQuery() == null ? "" : "?" + x.getRequestURI().getRawQuery()));
        String auth = x.getRequestHeaders().getFirst("Authorization");
        if (auth == null || !auth.startsWith("Bearer ") || (rejectToken && auth.equals("Bearer old"))) {
            reply(x, 401, "application/json", "{\"error\":{\"code\":\"InvalidAuthenticationToken\"}}".getBytes(StandardCharsets.UTF_8));
            return;
        }
        String mb = "/v1.0/users/ops%40example.org";
        String mbDecoded = "/v1.0/users/ops@example.org";
        String p = path.startsWith(mbDecoded) ? path.substring(mbDecoded.length()) : path.startsWith(mb) ? path.substring(mb.length()) : null;
        if (p == null) {
            reply(x, 404, "application/json", "{}".getBytes(StandardCharsets.UTF_8));
            return;
        }
        if (x.getRequestMethod().equals("GET") && p.equals("/mailFolders/inbox/messages")) {
            List<Map<String, Object>> value = new ArrayList<>();
            for (String id : List.of("g1", "g2", "g3")) {
                if (!read.contains(id)) {
                    value.add(Map.of("id", id, "internetMessageId", "<" + id + "@example.org>", "subject", "s-" + id, "hasAttachments", !id.equals("g1"),
                            "from", Map.of("emailAddress", Map.of("address", "citizen@example.org")), "body", Map.of("contentType", "text", "content", "body " + id)));
                }
            }
            reply(x, 200, "application/json", Json.bytes(Map.of("value", value)));
            return;
        }
        if (x.getRequestMethod().equals("GET") && p.equals("/messages/g2/attachments")) {
            reply(x, 200, "application/json", Json.bytes(Map.of("value", List.of(
                    Map.of("@odata.type", "#microsoft.graph.fileAttachment", "id", "a1", "name", "form.pdf", "contentType", "application/pdf",
                            "contentBytes", Base64.getEncoder().encodeToString("%PDF".getBytes(StandardCharsets.UTF_8))),
                    Map.of("@odata.type", "#microsoft.graph.itemAttachment", "id", "a2", "name", "forwarded", "contentType", "message/rfc822")))));
            return;
        }
        if (x.getRequestMethod().equals("GET") && p.equals("/messages/g2/attachments/a2/$value")) {
            reply(x, 200, "message/rfc822", "Subject: fwd\r\n\r\nhello".getBytes(StandardCharsets.UTF_8));
            return;
        }
        if (x.getRequestMethod().equals("GET") && p.equals("/messages/g3/attachments")) {
            reply(x, 200, "application/json", Json.bytes(Map.of("value", List.of(
                    Map.of("@odata.type", "#microsoft.graph.referenceAttachment", "id", "a3", "name", "link", "contentType", "text/html")))));
            return;
        }
        if (x.getRequestMethod().equals("PATCH") && p.startsWith("/messages/")) {
            Map<String, Object> in = Json.readMap(new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (Boolean.TRUE.equals(in.get("isRead"))) {
                read.add(p.substring("/messages/".length()));
            }
            reply(x, 200, "application/json", "{}".getBytes(StandardCharsets.UTF_8));
            return;
        }
        reply(x, 404, "application/json", "{}".getBytes(StandardCharsets.UTF_8));
    }

    private static void reply(HttpExchange x, int status, String type, byte[] body) throws IOException {
        x.getResponseHeaders().add("Content-Type", type);
        x.sendResponseHeaders(status, body.length);
        try (var out = x.getResponseBody()) {
            out.write(body);
        }
    }

    static final class MemoryStore implements DocumentStore {
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

    static TokenSource fixed(String token) {
        return TokenSource.ofSecret(SecretResolver.of(Map.of("secret://graph/token", token)), "secret://graph/token");
    }

    GraphMailSource source(TokenSource tokens) {
        return new GraphMailSource(new GraphMailSource.Config("t", base, "ops@example.org", null), HttpTransport.forSimulator(), tokens);
    }

    @Test
    void fetchesUnreadMailWithFileAndItemAttachments() throws Exception {
        try (GraphMailSource src = source(fixed("tok"))) {
            List<InboundMail.Mail> mails = src.fetch(10);
            assertEquals(3, mails.size());
            InboundMail.Mail g2 = mails.get(1);
            assertEquals("<g2@example.org>", g2.messageId());
            assertEquals("citizen@example.org", g2.from());
            assertEquals("body g2", g2.text());
            assertEquals(2, g2.attachments().size());
            assertArrayEquals("%PDF".getBytes(StandardCharsets.UTF_8), g2.attachments().get(0).content());
            assertEquals("forwarded.eml", g2.attachments().get(1).name());
            assertEquals("message/rfc822", g2.attachments().get(1).contentType());
            assertNull(mails.get(2).attachments().get(0).content(), "a reference attachment has no bytes");
            assertTrue(requests.stream().anyMatch(r -> r.contains("$filter=isRead%20eq%20false")), "only unread mail is listed");
        }
    }

    @Test
    void pollStoresAcknowledgesAndPoisonsTheReferenceAttachment() throws Exception {
        MemoryStore store = new MemoryStore();
        List<String> events = new ArrayList<>();
        List<String> poison = new ArrayList<>();
        InboundMail in = new InboundMail(Dialect.POSTGRESQL, tx, new AuditChain(Dialect.POSTGRESQL), store, (n, b) -> true,
                (t, c, m, a) -> events.add(m.messageId() + ":" + a.size()), (t, c, id, why) -> poison.add(id + ":" + why));
        try (GraphMailSource src = source(fixed("tok"))) {
            assertEquals(new InboundMail.PollResult(2, 0, 1), in.poll("t", "graph-inbox", src, 10));
        }
        assertEquals(List.of("<g1@example.org>:0", "<g2@example.org>:2"), events);
        assertEquals(1, poison.size());
        assertTrue(poison.get(0).startsWith("<g3@example.org>:attachment content not available"));
        assertEquals(Set.of("g1", "g2", "g3"), read, "every handled mail is marked read, the poisoned one too");
        assertEquals(2, store.m.size());
        try (GraphMailSource again = source(fixed("tok"))) {
            assertTrue(again.fetch(10).isEmpty());
        }
    }

    @Test
    void aRefusedTokenIsRefreshedOnceAndAFailureLeavesMailUnread() throws Exception {
        rejectToken = true;
        String[] current = {"old"};
        TokenSource refreshing = new TokenSource() {
            @Override
            public String token(String tenantId) {
                return current[0];
            }

            @Override
            public void invalidate(String tenantId) {
                current[0] = "new";
            }

            @Override
            public boolean refreshable() {
                return true;
            }
        };
        try (GraphMailSource src = source(refreshing)) {
            assertEquals(3, src.fetch(10).size());
        }
        assertEquals("new", current[0]);
        try (GraphMailSource stuck = source(fixed("old"))) {
            assertThrows(IOException.class, () -> stuck.fetch(10));
        }
        assertTrue(read.isEmpty());
    }

    @Test
    void egressDenyAllReachesNothing() {
        GraphMailSource denied = new GraphMailSource(new GraphMailSource.Config("t", base, "ops@example.org", "inbox"),
                new HttpTransport(EgressPolicy.denyAll(), null, null, null, null), fixed("tok"));
        assertThrows(IOException.class, () -> denied.fetch(10));
        assertTrue(requests.isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new GraphMailSource.Config("t", null, " ", null));
    }
}
