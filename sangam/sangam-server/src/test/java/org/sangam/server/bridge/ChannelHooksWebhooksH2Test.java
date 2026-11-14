// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import org.sangam.rest.config.SpringTxSupport;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.channels.ChannelsSchema;
import org.sangam.channels.bot.BotBridge;
import org.sangam.channels.messenger.OptInRegistry;
import org.sangam.channels.messenger.WeChatCrypto;
import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Schema;
import org.sangam.core.layer.Transactions;
import org.sangam.integration.connector.SecretResolver;
import org.sangam.rest.modules.channels.ChannelHooksController;
import org.sangam.rest.modules.channels.InboundMedia;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.support.TxSupport;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.env.MockEnvironment;

/**
 * Channel hooks to the W3-M 14:01:57Z contract: WeChat security mode, Slack Events API, Matrix application-service
 * transactions, 401 and 404, and the asynchronous media hand-over after the inbox commit. H2; written, not run (Wave 3).
 */
class ChannelHooksWebhooksH2Test {

    static final String AES_KEY = Base64.getEncoder().withoutPadding().encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII));

    record Media(String channel, String principal, String messageId, String mediaRef) {
    }

    final List<Map<String, Object>> started = new ArrayList<>();
    final List<Media> media = new ArrayList<>();
    TxSupport tx;
    JdbcDataSource ds;
    OptInRegistry optIns;
    MockEnvironment env;
    SecretResolver secrets;

    @BeforeEach
    void db() throws Exception {
        ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        try (Connection c = ds.getConnection()) {
            Schema.create(c, Dialect.POSTGRESQL);
            ChannelsSchema.create(c, Dialect.POSTGRESQL);
        }
        tx = new SpringTxSupport(ds, new DataSourceTransactionManager(ds), Dialect.POSTGRESQL);
        optIns = new OptInRegistry(Dialect.POSTGRESQL, Transactions.of(ds), new AuditChain(Dialect.POSTGRESQL));
        optIns.optIn("t", "SLACK", "U1", "alice", "consent form 1");
        optIns.optIn("t", "MATRIX", "@bob:example.org", "bob", "consent form 2");
        optIns.optIn("t", "WECHAT", "openid1", "carol", "consent form 3");
        env = new MockEnvironment()
                .withProperty("sangam.channels.webhooks.t.slack.signing-secret-ref", "slack-secret")
                .withProperty("sangam.channels.webhooks.t.matrix.hs-token-ref", "hs")
                .withProperty("sangam.channels.webhooks.t.matrix.user-id", "@sangam:example.org")
                .withProperty("sangam.channels.webhooks.t.wechat.token-ref", "wx-token")
                .withProperty("sangam.channels.webhooks.t.wechat.encoding-aes-key-ref", "wx-aes")
                .withProperty("sangam.channels.webhooks.t.wechat.app-id", "wx123");
        secrets = SecretResolver.of(Map.of("slack-secret", "s3cret", "hs", "hs-token", "wx-token", "tok", "wx-aes", AES_KEY));
    }

    ChannelHooksController controller(boolean withMediaWorker) {
        BotBridge bot = new BotBridge(new BotBridge.ProcessGateway() {
            @Override
            public boolean correlate(String tenantId, String messageName, String businessKey, Map<String, Object> variables) {
                return false;
            }

            @Override
            public void start(String tenantId, String processKey, String businessKey, Map<String, Object> variables) {
                started.add(variables);
            }
        }, Map.of("*", "bot"));
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        if (withMediaWorker) {
            beans.registerSingleton("media", (InboundMedia) (tenant, channel, externalId, principal, messageId, ref) ->
                    media.add(new Media(channel, principal, messageId, ref)));
        }
        return new ChannelHooksController(bot, optIns, tx, secrets, env, (t, kid) -> java.util.Optional.empty(), beans.getBeanProvider(InboundMedia.class));
    }

    String mediaRow(String channel, String messageId) throws java.sql.SQLException {
        try (Connection c = ds.getConnection();
             var ps = c.prepareStatement("SELECT media_state, media_ref, external_id, principal_id FROM CH_INBOUND_SEEN WHERE tenant_id='t' AND channel=? AND message_id=?")) {
            ps.setString(1, channel);
            ps.setString(2, messageId);
            try (var rs = ps.executeQuery()) {
                assertTrue(rs.next(), "inbox row " + messageId);
                return rs.getString(1) + ":" + rs.getString(2) + ":" + rs.getString(3) + ":" + rs.getString(4);
            }
        }
    }

    static String slackSignature(String secret, String ts, byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update(("v0:" + ts + ":").getBytes(StandardCharsets.UTF_8));
        return "v0=" + HexFormat.of().formatHex(mac.doFinal(body));
    }

    static String weChatSignature(String token, String ts, String nonce) throws Exception {
        String[] parts = {token, ts, nonce};
        Arrays.sort(parts);
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(String.join("", parts).getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void slackUrlVerificationAnswersTheChallengeAfterTheSignature() throws Exception {
        byte[] body = "{\"type\":\"url_verification\",\"challenge\":\"abc123\"}".getBytes(StandardCharsets.UTF_8);
        String ts = String.valueOf(Instant.now().getEpochSecond());
        var r = controller(true).slack("t", body, ts, slackSignature("s3cret", ts, body)).getBody();
        assertEquals("abc123", r.challenge());
        assertNull(r.accepted(), "only the challenge is answered");
        ApiProblem bad = assertThrows(ApiProblem.class, () -> controller(true).slack("t", body, ts, slackSignature("wrong", ts, body)));
        assertEquals(401, bad.status());
        assertEquals("WEBHOOK_SIGNATURE_INVALID", bad.code());
        String old = String.valueOf(Instant.now().minusSeconds(600).getEpochSecond());
        assertEquals(401, assertThrows(ApiProblem.class, () -> controller(true).slack("t", body, old, slackSignature("s3cret", old, body))).status(),
                "a stale timestamp is refused (replay guard)");
        assertEquals(404, assertThrows(ApiProblem.class, () -> controller(true).slack("other", body, ts, "v0=00")).status(), "not configured");
    }

    @Test
    void slackMessageWithAFileGivesTheTextNowAndTheMediaAfterCommitOnce() throws Exception {
        byte[] body = ("{\"type\":\"event_callback\",\"event_id\":\"Ev1\",\"event\":{\"type\":\"message\",\"subtype\":\"file_share\",\"user\":\"U1\","
                + "\"text\":\"see attached\",\"files\":[{\"id\":\"F1\"}]}}").getBytes(StandardCharsets.UTF_8);
        String ts = String.valueOf(Instant.now().getEpochSecond());
        ChannelHooksController c = controller(true);
        var r = c.slack("t", body, ts, slackSignature("s3cret", ts, body)).getBody();
        assertEquals(1, r.accepted());
        assertNull(r.challenge());
        assertEquals(1, started.size());
        assertEquals("see attached", started.get(0).get("botInput"));
        assertTrue(!started.get(0).containsKey("botMedia"), "the media reaches the bot only after download and scan");
        assertEquals(List.of(new Media("SLACK", "alice", "Ev1", "F1")), media);
        assertEquals("PENDING:F1:U1:alice", mediaRow("SLACK", "Ev1"), "the pending media committed with the inbox row (durable before the wake-up)");

        var again = c.slack("t", body, ts, slackSignature("s3cret", ts, body)).getBody();
        assertEquals(1, again.duplicates(), "Slack's retry carries the same event_id");
        assertEquals(1, media.size(), "no second hand-over");
        assertEquals(1, started.size());
    }

    @Test
    void withoutAMediaWorkerTheReferenceGoesToTheBotAsBefore() throws Exception {
        byte[] body = ("{\"type\":\"event_callback\",\"event_id\":\"Ev2\",\"event\":{\"type\":\"message\",\"subtype\":\"file_share\",\"user\":\"U1\","
                + "\"text\":\"\",\"files\":[{\"id\":\"F2\"}]}}").getBytes(StandardCharsets.UTF_8);
        String ts = String.valueOf(Instant.now().getEpochSecond());
        controller(false).slack("t", body, ts, slackSignature("s3cret", ts, body));
        assertEquals(Map.of("mediaRef", "F2"), started.get(0).get("botMedia"));
        assertEquals("null:null:null:null", mediaRow("SLACK", "Ev2"), "no worker: nothing is queued");
    }

    @Test
    void matrixTransactionIsAuthenticatedSkipsTheBridgeUserAndIsIdempotent() throws Exception {
        byte[] body = ("{\"events\":[{\"type\":\"m.room.message\",\"event_id\":\"$e1\",\"sender\":\"@bob:example.org\",\"content\":{\"msgtype\":\"m.text\",\"body\":\"hi\"}},"
                + "{\"type\":\"m.room.message\",\"event_id\":\"$e2\",\"sender\":\"@sangam:example.org\",\"content\":{\"body\":\"echo\"}},"
                + "{\"type\":\"m.room.message\",\"event_id\":\"$e3\",\"sender\":\"@bob:example.org\",\"content\":{\"msgtype\":\"m.image\",\"body\":\"\",\"url\":\"mxc://example.org/abc\"}}]}")
                .getBytes(StandardCharsets.UTF_8);
        ChannelHooksController c = controller(true);
        var r = c.matrix("t", "txn-1", body, "Bearer hs-token", null).getBody();
        assertEquals(new ChannelHooksController.HookResult(2, 0, 0), r, "the bridge's own event is skipped");
        assertEquals(1, started.size(), "the image without text reaches the bot only through the media worker");
        assertEquals(List.of(new Media("MATRIX", "bob", "$e3", "mxc://example.org/abc")), media);
        assertEquals("PENDING:mxc://example.org/abc:@bob:example.org:bob", mediaRow("MATRIX", "$e3"));
        assertEquals("null:null:null:null", mediaRow("MATRIX", "$e1"), "a text message queues no media");
        assertEquals(new ChannelHooksController.HookResult(0, 2, 0), c.matrix("t", "txn-1", body, null, "hs-token").getBody(),
                "the repeated txnId is answered again (legacy access_token) without a second delivery");
        assertEquals(1, media.size());
        assertEquals(401, assertThrows(ApiProblem.class, () -> c.matrix("t", "txn-2", body, "Bearer nope", null)).status());
        assertEquals(404, assertThrows(ApiProblem.class, () -> c.matrix("other", "txn-3", body, "Bearer hs-token", null)).status());
    }

    @Test
    void weChatSecurityModeVerifiesAndDecrypts() throws Exception {
        String plain = "<xml><ToUserName>gh</ToUserName><FromUserName>openid1</FromUserName><CreateTime>1</CreateTime><MsgType>text</MsgType>"
                + "<Content>hello</Content><MsgId>42</MsgId></xml>";
        WeChatCrypto crypto = new WeChatCrypto("tok", AES_KEY, "wx123");
        String enc = crypto.encrypt(plain);
        byte[] body = ("<xml><ToUserName>gh</ToUserName><Encrypt><![CDATA[" + enc + "]]></Encrypt></xml>").getBytes(StandardCharsets.UTF_8);
        String ts = "1700000000";
        String nonce = "n1";
        String sig = weChatSignature("tok", ts, nonce);
        String msgSig = WeChatCrypto.signature("tok", ts, nonce, enc);
        ChannelHooksController c = controller(true);
        assertEquals(new ChannelHooksController.HookResult(1, 0, 0), c.weChat("t", body, ts, nonce, sig, "aes", msgSig).getBody());
        assertEquals("hello", started.get(0).get("botInput"));

        assertEquals(401, assertThrows(ApiProblem.class, () -> c.weChat("t", body, ts, nonce, sig, "aes", "0".repeat(40))).status(),
                "msg_signature over the Encrypt element");
        assertEquals(401, assertThrows(ApiProblem.class, () -> c.weChat("t", body, ts, nonce, "bad", "aes", msgSig)).status(),
                "the URL signature is checked too");
        String otherApp = new WeChatCrypto("tok", AES_KEY, "wx-other").encrypt(plain.replace("42", "43"));
        byte[] foreign = ("<xml><Encrypt><![CDATA[" + otherApp + "]]></Encrypt></xml>").getBytes(StandardCharsets.UTF_8);
        assertEquals(401, assertThrows(ApiProblem.class, () -> c.weChat("t", foreign, ts, nonce, sig, "aes",
                WeChatCrypto.signature("tok", ts, nonce, otherApp))).status(), "a message made for another app id is refused");
        assertEquals(400, assertThrows(ApiProblem.class, () -> c.weChat("t", body, ts, nonce, sig, "rot13", msgSig)).status());
        assertEquals(1, started.size());
    }
}
