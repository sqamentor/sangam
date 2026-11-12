// SPDX-License-Identifier: Apache-2.0
package org.sangam.channels.messenger;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** WeChat encrypted mode, Slack and Matrix inbound checks and parsers, template rules (M-071). Written, not run (Wave 3). */
class InboundProvidersTest {

    /** 43 characters: Base64 of 32 bytes without the trailing "=". */
    static final String AES_KEY = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII)).substring(0, 43);

    @Test
    void weChatEncryptedModeRoundTripsAndRefusesTampering() {
        WeChatCrypto crypto = new WeChatCrypto("tok", AES_KEY, "wx-app");
        String plain = "<xml><MsgId>42</MsgId><FromUserName>openid-1</FromUserName><Content>नमस्ते</Content></xml>";
        String enc = crypto.encrypt(plain, new byte[16]);
        assertEquals(plain, crypto.decrypt(enc).orElseThrow());
        assertEquals(0, Base64.getDecoder().decode(enc).length % 32, "PKCS#7 to a 32-byte block");

        String sig = WeChatCrypto.signature("tok", "1700000000", "n1", enc);
        byte[] body = ("<xml><ToUserName><![CDATA[gh]]></ToUserName><Encrypt><![CDATA[" + enc + "]]></Encrypt></xml>").getBytes(StandardCharsets.UTF_8);
        byte[] opened = crypto.decryptInbound(body, "1700000000", "n1", sig).orElseThrow();
        InboundMessageParsers.Parsed p = InboundMessageParsers.weChat(opened);
        assertEquals("42", p.messageId());
        assertEquals("नमस्ते", p.text());

        assertTrue(crypto.decryptInbound(body, "1700000001", "n1", sig).isEmpty(), "a wrong timestamp breaks msg_signature");
        assertTrue(new WeChatCrypto("tok", AES_KEY, "other-app").decrypt(enc).isEmpty(), "a message for another app id is refused");
        assertTrue(crypto.decrypt("not base64 !").isEmpty());
        assertTrue(crypto.decrypt(Base64.getEncoder().encodeToString(new byte[31])).isEmpty(), "not a whole block");
        assertThrows(IllegalArgumentException.class, () -> new WeChatCrypto("tok", "short", "wx-app"));

        String reply = crypto.encryptReply("<xml><Content>ok</Content></xml>", "1700000000", "n2");
        String replyEnc = InboundMessageParsers.weChatEncrypted(reply.getBytes(StandardCharsets.UTF_8));
        assertEquals("<xml><Content>ok</Content></xml>", crypto.decrypt(replyEnc).orElseThrow());
        assertTrue(reply.contains("<MsgSignature><![CDATA[" + WeChatCrypto.signature("tok", "1700000000", "n2", replyEnc) + "]]>"));
        assertNull(InboundMessageParsers.weChatEncrypted("<xml><Content>plain</Content></xml>".getBytes(StandardCharsets.UTF_8)), "plain mode has no Encrypt");
    }

    @Test
    void weChatEventsWithoutMsgIdGetTheDocumentedDedupeKey() {
        InboundMessageParsers.Parsed p = InboundMessageParsers.weChat(
                "<xml><FromUserName>openid-1</FromUserName><CreateTime>1700000000</CreateTime><MsgType>event</MsgType><Event>subscribe</Event></xml>"
                        .getBytes(StandardCharsets.UTF_8));
        assertEquals("openid-1:1700000000", p.messageId());
    }

    @Test
    void slackSigningAndEvents() throws Exception {
        byte[] body = "{\"type\":\"event_callback\",\"event_id\":\"Ev1\",\"event\":{\"type\":\"message\",\"user\":\"U1\",\"text\":\"hi\",\"ts\":\"1.2\"}}"
                .getBytes(StandardCharsets.UTF_8);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("signing".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update("v0:1700000000:".getBytes(StandardCharsets.UTF_8));
        String sig = "v0=" + HexFormat.of().formatHex(mac.doFinal(body));
        Instant now = Instant.ofEpochSecond(1700000100);
        assertTrue(WebhookVerifiers.slack("signing", body, "1700000000", sig, now));
        assertFalse(WebhookVerifiers.slack("other", body, "1700000000", sig, now));
        assertFalse(WebhookVerifiers.slack("signing", body, "1700000000", sig, Instant.ofEpochSecond(1700000400)), "older than five minutes: replay");
        assertFalse(WebhookVerifiers.slack("signing", body, "x", sig, now));
        assertFalse(WebhookVerifiers.slack("signing", body, "1700000000", null, now));

        InboundMessageParsers.Parsed p = InboundMessageParsers.slack(body);
        assertEquals("Ev1", p.messageId());
        assertEquals("U1", p.externalId());
        assertNull(InboundMessageParsers.slack("{\"type\":\"event_callback\",\"event_id\":\"Ev2\",\"event\":{\"type\":\"message\",\"bot_id\":\"B1\",\"text\":\"echo\"}}"
                .getBytes(StandardCharsets.UTF_8)), "bot messages are ignored");
        assertNull(InboundMessageParsers.slack("{\"type\":\"event_callback\",\"event\":{\"type\":\"message\",\"subtype\":\"message_changed\"}}".getBytes(StandardCharsets.UTF_8)));
        assertEquals("c-1", InboundMessageParsers.slackChallenge("{\"type\":\"url_verification\",\"challenge\":\"c-1\"}".getBytes(StandardCharsets.UTF_8)));
        assertNull(InboundMessageParsers.slackChallenge(body));
    }

    @Test
    void matrixAppServiceTransactions() {
        assertTrue(WebhookVerifiers.matrixAppService("hs", "Bearer hs", null));
        assertTrue(WebhookVerifiers.matrixAppService("hs", null, "hs"), "older homeservers use the access_token parameter");
        assertFalse(WebhookVerifiers.matrixAppService("hs", "Bearer nope", "hs"), "the header wins when present");
        assertFalse(WebhookVerifiers.matrixAppService("", null, ""));
        byte[] txn = ("{\"events\":[{\"type\":\"m.room.message\",\"event_id\":\"$1\",\"sender\":\"@alice:x\",\"room_id\":\"!r:x\",\"content\":{\"msgtype\":\"m.text\",\"body\":\"hi\"}},"
                + "{\"type\":\"m.room.message\",\"event_id\":\"$2\",\"sender\":\"@bridge:x\",\"content\":{\"body\":\"echo\"}},"
                + "{\"type\":\"m.room.member\",\"event_id\":\"$3\",\"sender\":\"@bob:x\"},"
                + "{\"type\":\"m.room.message\",\"event_id\":\"$4\",\"sender\":\"@alice:x\",\"content\":{\"msgtype\":\"m.image\",\"body\":\"p.png\",\"url\":\"mxc://x/abc\"}}]}")
                .getBytes(StandardCharsets.UTF_8);
        List<InboundMessageParsers.Parsed> p = InboundMessageParsers.matrix(txn, "@bridge:x");
        assertEquals(2, p.size());
        assertEquals("$1", p.get(0).messageId());
        assertEquals("mxc://x/abc", p.get(1).mediaRef());
    }

    @Test
    void templateRulesFromTheChannelEntry() {
        assertFalse(TemplateRules.fromEntry(Map.of()).enforced());
        assertTrue(TemplateRules.providerOnly().check("anything", null, 9).isEmpty());
        TemplateRules r = TemplateRules.fromEntry(Map.of("templates", List.of(Map.of("name", "a", "languages", List.of("EN")), Map.of("name", "b", "params", "1"))));
        assertTrue(r.check("a", "en", 3).isEmpty(), "languages compare without case; no params rule for a");
        assertEquals("TEMPLATE_NOT_APPROVED", r.check("a", null, 0).orElseThrow()[0]);
        assertEquals("TEMPLATE_PARAMS_MISMATCH", r.check("b", null, 0).orElseThrow()[0]);
        assertTrue(r.check("b", "any", 1).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> TemplateRules.fromEntry(Map.of("templates", "a")));
        assertThrows(IllegalArgumentException.class, () -> TemplateRules.fromEntry(Map.of("templates", List.of(Map.of("params", 1)))));
        assertThrows(IllegalArgumentException.class, () -> TemplateRules.fromEntry(Map.of("templates", List.of(Map.of("name", "c", "params", "-1")))));
        assertEquals(2, TemplateRules.whatsAppBodyParams(List.of(Map.of("type", "header"), Map.of("type", "BODY", "parameters", List.of(1, 2)))));
    }

    @Test
    void tokenLifetimeKeepsAMarginButNeverCachesPastExpiry() {
        assertEquals(7140, ProviderTokens.lifetime(7200));
        assertEquals(30, ProviderTokens.lifetime(60), "half the life when the margin would eat it");
        assertEquals(0, ProviderTokens.lifetime(-5));
    }

    @Test
    void mediaFileNamesNeverCarryAPath() {
        assertEquals("a.pdf", MediaDownloads.fileName("attachment; filename=\"../../a.pdf\""));
        assertEquals("b.jpg", MediaDownloads.fileName("attachment; filename=b.jpg"));
        assertNull(MediaDownloads.fileName("inline"));
        assertNull(MediaDownloads.fileName(null));
    }
}
