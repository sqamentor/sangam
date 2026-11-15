// SPDX-License-Identifier: Apache-2.0
package org.sangam.channels;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.sangam.channels.messenger.ChannelRuntime;
import org.sangam.channels.messenger.MediaDownloads;
import org.sangam.channels.messenger.MessengerConnectors;
import org.sangam.channels.messenger.ProviderTokens;
import org.sangam.channels.messenger.ProviderTokens.TokenSource;
import org.sangam.channels.messenger.TemplateRules;
import org.sangam.channels.sim.MessengerSimulator;
import org.sangam.core.integration.ConnectorProfile;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.ConnectorResult;
import org.sangam.core.integration.SangamConnector;
import org.sangam.integration.connector.HttpTransport;
import org.sangam.integration.connector.SecretResolver;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** NG-14 provider gaps and the MATRIX and SLACK adapters (M-071), against the loopback simulator. Written, not run (Wave 3). */
class MessengerProvidersTest {

    static ConnectorRequest req(Map<String, Object> in) {
        return new ConnectorRequest("t", UUID.randomUUID(), "a", 1, "root", null, "send", in, null);
    }

    static final SecretResolver SECRETS = SecretResolver.of(Map.of("app-secret", "s3cret", "bad-secret", "bad", "tok", "matrix-token", "bot", "xoxb-sim",
            "secret://mx/token", "matrix-token", "secret://slack/token", "xoxb-sim")); // a reference is secret://...; any other text is a literal value

    @Test
    void weChatStableTokenIsCachedAcrossRuntimeRebuildsAndRefreshedOnceWhenRevoked() {
        try (MessengerSimulator sim = new MessengerSimulator().start()) {
            HttpTransport tr = HttpTransport.forSimulator();
            ProviderTokens tokens = new ProviderTokens(Clock.systemUTC());
            Map<String, Object> entry = Map.of("key", "wc", "channel", "WECHAT", "apiBase", sim.baseUri().toString(), "appId", "wx1", "appSecretRef", "app-secret");
            SangamConnector first = ChannelRuntime.connector(entry, ConnectorProfile.SIMULATOR, tr, SECRETS, tokens, r -> true);
            assertInstanceOf(ConnectorResult.Success.class, first.invoke(req(Map.of("to", "u", "text", "x"))));
            SangamConnector rebuilt = ChannelRuntime.connector(entry, ConnectorProfile.SIMULATOR, tr, SECRETS, tokens, r -> true);
            assertInstanceOf(ConnectorResult.Success.class, rebuilt.invoke(req(Map.of("to", "u", "text", "y"))));
            assertEquals(1, sim.tokenRequests(), "the rebuilt connector reuses the cached token");

            sim.revoke("sim-token-1");
            ConnectorResult afterRevoke = rebuilt.invoke(req(Map.of("to", "u", "text", "z")));
            assertInstanceOf(ConnectorResult.Success.class, afterRevoke, "a token error refreshes the token and repeats once");
            assertEquals(2, sim.tokenRequests());
            assertTrue(sim.sent().get(sim.sent().size() - 1).path().endsWith("/custom/send"));
        }
    }

    @Test
    void aStaticTokenIsNotRefreshedAndATokenFailureSendsNothing() {
        try (MessengerSimulator sim = new MessengerSimulator().start()) {
            HttpTransport tr = HttpTransport.forSimulator();
            ConnectorResult bad = MessengerConnectors.weChat("wc", ConnectorProfile.SIMULATOR, sim.baseUri(), "bad", tr, SecretResolver.of(Map.of()), r -> true)
                    .invoke(req(Map.of("to", "u", "text", "x")));
            assertEquals("WECHAT_40001", ((ConnectorResult.BusinessFailure) bad).code());
            assertEquals(0, sim.tokenRequests());
            assertEquals(1, sim.sent().size(), "a static token is not refreshed, so there is no second send");

            TokenSource refused = new ProviderTokens(Clock.systemUTC()).weChat(sim.baseUri(), "wx1", "bad-secret", SECRETS, tr);
            ConnectorResult r = MessengerConnectors.weChat("wc", ConnectorProfile.SIMULATOR, sim.baseUri(), refused, TemplateRules.providerOnly(), tr, x -> true)
                    .invoke(req(Map.of("to", "u", "text", "x")));
            assertTrue(((ConnectorResult.TransientFailure) r).noEffect(), "no token, no send");
            assertEquals(1, sim.sent().size());
        }
    }

    @Test
    void templateRulesRefuseUnapprovedTemplatesBeforeAnyCall() {
        try (MessengerSimulator sim = new MessengerSimulator().start()) {
            HttpTransport tr = HttpTransport.forSimulator();
            Map<String, Object> entry = Map.of("key", "wa", "channel", "WHATSAPP", "apiBase", sim.uri("/wa").toString(), "phoneNumberId", "1", "tokenRef", "tok",
                    "templates", List.of(Map.of("name", "case_update", "languages", List.of("en", "hi"), "params", 2)));
            SangamConnector wa = ChannelRuntime.connector(entry, ConnectorProfile.SIMULATOR, tr, SECRETS, r -> true);
            List<Object> twoParams = List.of(Map.of("type", "body", "parameters", List.of(Map.of("type", "text", "text", "a"), Map.of("type", "text", "text", "b"))));

            ConnectorResult unknown = wa.invoke(req(Map.of("to", "9", "template", "promo", "components", twoParams)));
            assertEquals("TEMPLATE_NOT_APPROVED", ((ConnectorResult.BusinessFailure) unknown).code());
            ConnectorResult language = wa.invoke(req(Map.of("to", "9", "template", "case_update", "language", "fr", "components", twoParams)));
            assertEquals("TEMPLATE_NOT_APPROVED", ((ConnectorResult.BusinessFailure) language).code());
            ConnectorResult params = wa.invoke(req(Map.of("to", "9", "template", "case_update", "language", "hi", "components", List.of())));
            assertEquals("TEMPLATE_PARAMS_MISMATCH", ((ConnectorResult.BusinessFailure) params).code());
            assertTrue(sim.sent().isEmpty(), "refused templates never reach the provider");

            assertInstanceOf(ConnectorResult.Success.class, wa.invoke(req(Map.of("to", "9", "template", "case_update", "language", "hi", "components", twoParams))));
            assertEquals(1, sim.sent().size());
            assertTrue(sim.sent().get(0).body().contains("\"case_update\""));
        }
    }

    @Test
    void weChatTemplateMessageGoesToTheTemplateEndpoint() {
        try (MessengerSimulator sim = new MessengerSimulator().start()) {
            HttpTransport tr = HttpTransport.forSimulator();
            Map<String, Object> entry = Map.of("key", "wc", "channel", "WECHAT", "apiBase", sim.baseUri().toString(), "tokenRef", "tok",
                    "templates", List.of(Map.of("name", "TPL-1", "params", 1)));
            SangamConnector wc = ChannelRuntime.connector(entry, ConnectorProfile.SIMULATOR, tr, SECRETS, r -> true);
            ConnectorResult ok = wc.invoke(req(Map.of("to", "openid", "template", "TPL-1", "data", Map.of("first", Map.of("value", "hello")), "url", "https://example.org/c/1")));
            ConnectorResult.Success s = assertInstanceOf(ConnectorResult.Success.class, ok);
            assertEquals("200228332", s.providerRef());
            assertTrue(sim.sent().get(0).path().endsWith("/cgi-bin/message/template/send"));
            assertTrue(sim.sent().get(0).body().contains("\"template_id\":\"TPL-1\""));
            assertEquals("TEMPLATE_PARAMS_MISMATCH", ((ConnectorResult.BusinessFailure) wc.invoke(req(Map.of("to", "openid", "template", "TPL-1", "data", Map.of())))).code());
        }
    }

    @Test
    void matrixAndSlackHaveAdaptersNow() {
        try (MessengerSimulator sim = new MessengerSimulator().start()) {
            HttpTransport tr = HttpTransport.forSimulator();
            SangamConnector matrix = ChannelRuntime.connector(Map.of("key", "mx", "channel", "matrix", "apiBase", sim.baseUri().toString(), "tokenRef", "secret://mx/token"),
                    ConnectorProfile.SIMULATOR, tr, SECRETS, r -> true);
            assertEquals("matrix", matrix.descriptor().type());
            ConnectorRequest one = req(Map.of("to", "!room:example.org", "text", "hello"));
            ConnectorResult.Success a = assertInstanceOf(ConnectorResult.Success.class, matrix.invoke(one));
            ConnectorResult.Success b = assertInstanceOf(ConnectorResult.Success.class, matrix.invoke(one));
            assertEquals(a.providerRef(), b.providerRef(), "the operationId is the Matrix transaction id, so a repeat returns the first event");
            assertEquals("Bearer matrix-token", sim.sent().get(0).authorization());
            assertTrue(sim.sent().get(0).path().endsWith("/send/m.room.message/" + one.operationId()));

            SangamConnector slack = ChannelRuntime.connector(Map.of("key", "sl", "channel", "SLACK", "apiBase", sim.uri("/slack").toString(), "tokenRef", "secret://slack/token"),
                    ConnectorProfile.SIMULATOR, tr, SECRETS, r -> true);
            ConnectorResult.Success posted = assertInstanceOf(ConnectorResult.Success.class, slack.invoke(req(Map.of("to", "C1", "text", "hi"))));
            assertEquals("1700000000.000100", posted.providerRef());
            assertEquals("Bearer xoxb-sim", sim.sent().get(sim.sent().size() - 1).authorization());
            assertEquals("SLACK_channel_not_found", ((ConnectorResult.BusinessFailure) slack.invoke(req(Map.of("to", "C-unknown", "text", "hi")))).code());

            assertThrows(IllegalArgumentException.class, () -> ChannelRuntime.connector(Map.of("key", "x", "channel", "SIGNAL", "tokenRef", "t"),
                    ConnectorProfile.SIMULATOR, tr, SECRETS, r -> true), "unknown channels are still refused");
            assertThrows(IllegalArgumentException.class, () -> ChannelRuntime.connector(Map.of("key", "m", "channel", "MATRIX", "tokenRef", "t"),
                    ConnectorProfile.SIMULATOR, tr, SECRETS, r -> true), "a Matrix entry needs its homeserver");
            assertEquals("RECIPIENT_NOT_OPTED_IN", ((ConnectorResult.BusinessFailure) MessengerConnectors.slack("sl", ConnectorProfile.SIMULATOR, sim.uri("/slack"),
                    TokenSource.ofSecret(SECRETS, "bot"), tr, r -> false).invoke(req(Map.of("to", "C1", "text", "x")))).code());
        }
    }

    @Test
    void teamsTokenComesFromClientCredentialsAndA401RefreshesItOnce() throws Exception {
        AtomicInteger tokenCalls = new AtomicInteger();
        AtomicInteger activities = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/token", x -> {
            int n = tokenCalls.incrementAndGet();
            byte[] b = ("{\"access_token\":\"bf-" + n + "\",\"expires_in\":3600}").getBytes(StandardCharsets.UTF_8);
            x.getResponseHeaders().add("Content-Type", "application/json");
            x.sendResponseHeaders(200, b.length);
            x.getResponseBody().write(b);
            x.close();
        });
        server.createContext("/v3/conversations/", x -> {
            activities.incrementAndGet();
            boolean first = "Bearer bf-1".equals(x.getRequestHeaders().getFirst("Authorization"));
            byte[] b = (first ? "{}" : "{\"id\":\"act-1\"}").getBytes(StandardCharsets.UTF_8);
            x.getResponseHeaders().add("Content-Type", "application/json");
            x.sendResponseHeaders(first ? 401 : 201, b.length);
            x.getResponseBody().write(b);
            x.close();
        });
        server.start();
        try {
            URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            HttpTransport tr = HttpTransport.forSimulator();
            SecretResolver secrets = SecretResolver.of(Map.of("bf-secret", "sim-secret"));
            SangamConnector teams = ChannelRuntime.connector(Map.of("key", "te", "channel", "TEAMS", "clientId", "app-id", "clientSecretRef", "bf-secret",
                    "tokenUrl", base + "/token"), ConnectorProfile.SIMULATOR, tr, secrets, new ProviderTokens(Clock.systemUTC()), r -> true);
            ConnectorResult r = teams.invoke(req(Map.of("serviceUrl", base.toString(), "conversationId", "c1", "text", "x")));
            assertEquals("act-1", ((ConnectorResult.Success) r).providerRef());
            assertEquals(2, tokenCalls.get(), "the refused token was replaced once");
            assertEquals(2, activities.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void mediaIsDownloadedVerifiedAndStoredAfterTheScan() throws Exception {
        try (MessengerSimulator sim = new MessengerSimulator().start()) {
            HttpTransport tr = HttpTransport.forSimulator();
            MediaDownloads media = new MediaDownloads(tr);
            MediaDownloads.Media wc = media.weChat("t", sim.baseUri(), TokenSource.ofSecret(SECRETS, "tok"), "MEDIA_1");
            assertArrayEquals(MessengerSimulator.MEDIA, wc.content());
            assertEquals("image/jpeg", wc.contentType());
            MediaDownloads.MediaUnavailableException refused = assertThrows(MediaDownloads.MediaUnavailableException.class,
                    () -> media.weChat("t", sim.baseUri(), TokenSource.ofSecret(SecretResolver.of(Map.of("secret://wc/token", "bad")), "secret://wc/token"), "MEDIA_1"));
            assertFalse(refused.retryable());

            MediaDownloads.Media wa = media.whatsApp("t", sim.uri("/wa-media"), TokenSource.ofSecret(SECRETS, "tok"), "m-1");
            assertEquals("image/png", wa.contentType());
            assertArrayEquals(MessengerSimulator.MEDIA, wa.content());

            ChannelsTest.MemoryStore store = new ChannelsTest.MemoryStore();
            MediaDownloads.StoredMedia kept = MediaDownloads.store(store, (n, b) -> true, "t", "WHATSAPP", wa);
            assertTrue(kept.documentKey().startsWith("t/media/whatsapp/"));
            assertThrows(IllegalArgumentException.class, () -> MediaDownloads.store(store, (n, b) -> false, "t", "WHATSAPP", wa));
            assertEquals(1, store.m.size(), "a refused file is not stored");

            assertThrows(MediaDownloads.MediaUnavailableException.class, () -> media.matrix("t", sim.baseUri(), TokenSource.ofSecret(SECRETS, "tok"), "https://not-mxc"));
        }
    }

    @Test
    void egressDenyAllBlocksTokenAndSendAlike() {
        try (MessengerSimulator sim = new MessengerSimulator().start()) {
            HttpTransport denied = new HttpTransport(org.sangam.integration.connector.EgressPolicy.denyAll(), null, null, null, null);
            TokenSource t = new ProviderTokens(Clock.systemUTC()).weChat(sim.baseUri(), "wx1", "app-secret", SECRETS, denied);
            ConnectorResult r = MessengerConnectors.weChat("wc", ConnectorProfile.LIVE, sim.baseUri(), t, TemplateRules.providerOnly(), denied, x -> true)
                    .invoke(req(Map.of("to", "u", "text", "x")));
            assertTrue(((ConnectorResult.TransientFailure) r).noEffect());
            assertEquals(0, sim.tokenRequests());
            assertTrue(sim.sent().isEmpty());
        }
    }
}
