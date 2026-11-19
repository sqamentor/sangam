// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.modules.channels;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.sangam.channels.bot.BotBridge;
import org.sangam.channels.messenger.InboundMediaQueue;
import org.sangam.channels.messenger.InboundMessageParsers;
import org.sangam.channels.messenger.OptInRegistry;
import org.sangam.channels.messenger.WeChatCrypto;
import org.sangam.channels.messenger.WebhookVerifiers;
import org.sangam.core.layer.Jdbc;
import org.sangam.integration.connector.SecretResolver;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.support.TenantConfig;
import org.sangam.rest.support.TxSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Provider webhooks, public only because the signature or token is verified on the raw request first. A tenant and channel
 * with no configured secret answer 404, a failed check 401 {@code WEBHOOK_SIGNATURE_INVALID}. After verification, per
 * message and in one transaction: inbox dedupe ({@code CH_INBOUND_SEEN}), opt-in mapping, then the bot bridge with the text,
 * so a failed start leaves the message unseen and the provider's retry works. A sender with no active opt-in is recorded as
 * seen and goes no further.
 *
 * <p>Media is asynchronous (contract: verify, inbox row, answer within 3 s) and durable: the media reference is written onto
 * the message's inbox row in the same transaction ({@link InboundMediaQueue#enqueue}); after the commit {@link InboundMedia}
 * is woken, and its worker downloads, scans (M-082) and stores the media and then gives the document key to the bot. A
 * message with media and no text reaches the bot only that way. Without an {@link InboundMedia} bean the reference goes to
 * the bot as the {@code mediaRef} attribute, as before.
 *
 * <p>Properties under {@code sangam.channels.webhooks.<tenant>}: {@code whatsapp.secret-ref}, {@code whatsapp.verify-token-ref},
 * {@code wechat.token-ref}, {@code wechat.encoding-aes-key-ref} and {@code wechat.app-id} (security mode),
 * {@code teams.audience}, {@code teams.issuer} (default the Bot Framework issuer), {@code slack.signing-secret-ref},
 * {@code matrix.hs-token-ref} and {@code matrix.user-id} (the bridge's own user, whose events are skipped).
 */
@RestController
@RequestMapping("/api/v1/hooks/channels/{tenant}")
public class ChannelHooksController {

    private static final Logger LOG = LoggerFactory.getLogger(ChannelHooksController.class);

    private final BotBridge bot;
    private final OptInRegistry optIns;
    private final TxSupport tx;
    private final SecretResolver secrets;
    private final Environment env;
    private final BiFunction<String, String, Optional<PublicKey>> botKeys;
    private final ObjectProvider<InboundMedia> media;

    public ChannelHooksController(BotBridge bot, OptInRegistry optIns, TxSupport tx, SecretResolver secrets, Environment env,
                                  BiFunction<String, String, Optional<PublicKey>> sangamBotFrameworkKeys, ObjectProvider<InboundMedia> media) {
        this.bot = bot;
        this.optIns = optIns;
        this.tx = tx;
        this.secrets = secrets;
        this.env = env;
        this.botKeys = sangamBotFrameworkKeys;
        this.media = media;
    }

    /** {@code ChannelHookResult}. */
    public record HookResult(int accepted, int duplicates, int notOptedIn) {
    }

    /** {@code ChannelSlackHookResult}: the challenge of a {@code url_verification}, otherwise the counts. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SlackHookResult(String challenge, Integer accepted, Integer duplicates, Integer notOptedIn) {
    }

    /** Meta's subscription handshake. */
    @GetMapping("/whatsapp")
    public ResponseEntity<String> whatsAppVerify(@PathVariable String tenant, @RequestParam("hub.verify_token") String token,
            @RequestParam("hub.challenge") String challenge) {
        String want = secrets.require(configured(tenant, "whatsapp.verify-token-ref"));
        if (!MessageDigest.isEqual(want.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
            throw refused();
        }
        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(challenge);
    }

    @PostMapping("/whatsapp")
    public ResponseEntity<HookResult> whatsApp(@PathVariable String tenant, @RequestBody byte[] raw,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature) {
        String secret = secrets.require(configured(tenant, "whatsapp.secret-ref"));
        if (!WebhookVerifiers.whatsApp(secret, raw, signature)) {
            throw refused();
        }
        return ResponseEntity.ok(handle(tenant, "WHATSAPP", InboundMessageParsers.whatsApp(raw)));
    }

    /** WeChat's URL check: the same signature, echoed. */
    @GetMapping("/wechat")
    public ResponseEntity<String> weChatVerify(@PathVariable String tenant, @RequestParam String timestamp, @RequestParam String nonce,
            @RequestParam String signature, @RequestParam String echostr) {
        verifyWeChat(tenant, timestamp, nonce, signature);
        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(echostr);
    }

    /**
     * Plain mode as before. Security mode ({@code encrypt_type=aes}): {@code msg_signature} is verified over the
     * {@code Encrypt} element and the message decrypted with the tenant's EncodingAESKey; the decrypted app id must be
     * {@code wechat.app-id}. Any failure is 401.
     */
    @PostMapping("/wechat")
    public ResponseEntity<HookResult> weChat(@PathVariable String tenant, @RequestBody byte[] raw, @RequestParam String timestamp,
            @RequestParam String nonce, @RequestParam String signature,
            @RequestParam(value = "encrypt_type", required = false) String encryptType,
            @RequestParam(value = "msg_signature", required = false) String msgSignature) {
        verifyWeChat(tenant, timestamp, nonce, signature);
        byte[] plain = raw;
        if ("aes".equalsIgnoreCase(encryptType)) {
            WeChatCrypto crypto = new WeChatCrypto(secrets.require(configured(tenant, "wechat.token-ref")),
                    secrets.require(configured(tenant, "wechat.encoding-aes-key-ref")), configured(tenant, "wechat.app-id"));
            plain = crypto.decryptInbound(raw, timestamp, nonce, msgSignature).orElseThrow(ChannelHooksController::refused);
        } else if (encryptType != null && !encryptType.isBlank() && !"raw".equalsIgnoreCase(encryptType)) {
            throw new ApiProblem(400, "VALIDATION_FAILED", "encrypt_type must be aes or raw");
        }
        return ResponseEntity.ok(handle(tenant, "WECHAT", List.of(InboundMessageParsers.weChat(plain))));
    }

    @PostMapping("/teams")
    public ResponseEntity<HookResult> teams(@PathVariable String tenant, @RequestBody byte[] raw,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        String audience = configured(tenant, "teams.audience");
        String issuer = webhookSettings(tenant).getOrDefault("teams.issuer", WebhookVerifiers.BOT_FRAMEWORK_ISSUER);
        if (!WebhookVerifiers.botFramework(authorization, issuer, audience, serviceUrl(raw), kid -> botKeys.apply(tenant, kid), Instant.now())) {
            throw refused();
        }
        InboundMessageParsers.Parsed p = InboundMessageParsers.teams(raw);
        return ResponseEntity.ok(handle(tenant, "TEAMS", p == null ? List.of() : List.of(p)));
    }

    /** Slack Events API: v0 request signature and timestamp window first; {@code url_verification} answers the challenge. */
    @PostMapping("/slack")
    public ResponseEntity<SlackHookResult> slack(@PathVariable String tenant, @RequestBody byte[] raw,
            @RequestHeader("X-Slack-Request-Timestamp") String timestamp, @RequestHeader("X-Slack-Signature") String signature) {
        String secret = secrets.require(configured(tenant, "slack.signing-secret-ref"));
        if (!WebhookVerifiers.slack(secret, raw, timestamp, signature, Instant.now())) {
            throw refused();
        }
        String challenge = InboundMessageParsers.slackChallenge(raw);
        if (challenge != null) {
            return ResponseEntity.ok(new SlackHookResult(challenge, null, null, null));
        }
        InboundMessageParsers.Parsed p = InboundMessageParsers.slack(raw);
        HookResult r = handle(tenant, "SLACK", p == null ? List.of() : List.of(p));
        return ResponseEntity.ok(new SlackHookResult(null, r.accepted(), r.duplicates(), r.notOptedIn()));
    }

    /**
     * Matrix application-service transaction. A repeated {@code txnId} carries the same event ids, which the inbox has
     * already seen, so it is answered again without a second delivery.
     */
    @PutMapping("/matrix/transactions/{txnId}")
    public ResponseEntity<HookResult> matrix(@PathVariable String tenant, @PathVariable String txnId, @RequestBody byte[] raw,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(value = "access_token", required = false) String accessToken) {
        String hsToken = secrets.require(configured(tenant, "matrix.hs-token-ref"));
        String ownUser = configured(tenant, "matrix.user-id");
        if (!WebhookVerifiers.matrixAppService(hsToken, authorization, accessToken)) {
            throw refused();
        }
        return ResponseEntity.ok(handle(tenant, "MATRIX", InboundMessageParsers.matrix(raw, ownUser)));
    }

    /** The token's serviceUrl claim is compared with this value; an unreadable body fails that comparison. */
    private static String serviceUrl(byte[] raw) {
        try {
            Object v = org.sangam.integration.connector.Json.readMap(new String(raw, StandardCharsets.UTF_8)).get("serviceUrl");
            return v == null ? "" : v.toString();
        } catch (RuntimeException e) {
            return "";
        }
    }

    private void verifyWeChat(String tenant, String timestamp, String nonce, String signature) {
        if (!WebhookVerifiers.weChat(secrets.require(configured(tenant, "wechat.token-ref")), timestamp, nonce, signature)) {
            throw refused();
        }
    }

    private enum Kind { ACCEPTED, DUPLICATE, NOT_OPTED_IN }

    private record Outcome(Kind kind, String principal, boolean mediaLater) {
    }

    HookResult handle(String tenant, String channel, List<InboundMessageParsers.Parsed> messages) {
        int accepted = 0;
        int duplicates = 0;
        int notOptedIn = 0;
        InboundMedia later = media.getIfAvailable();
        for (InboundMessageParsers.Parsed p : messages) {
            Outcome o = one(tenant, channel, p, later != null);
            switch (o.kind()) {
                case ACCEPTED -> accepted++;
                case DUPLICATE -> duplicates++;
                case NOT_OPTED_IN -> notOptedIn++;
            }
            if (o.mediaLater()) {
                // after the commit, only a wake-up: the pending media is already on the inbox row, and the worker's poll finds it anyway
                try {
                    later.received(tenant, channel, p.externalId(), o.principal(), p.messageId(), p.mediaRef());
                } catch (RuntimeException e) {
                    LOG.warn("waking the media worker for {} message {} (tenant {}) failed; its poll takes the row: {}", channel, p.messageId(), tenant,
                            e.toString());
                }
            }
        }
        return new HookResult(accepted, duplicates, notOptedIn);
    }

    private Outcome one(String tenant, String channel, InboundMessageParsers.Parsed p, boolean asyncMedia) {
        return tx.inTx(() -> {
            boolean fresh = Jdbc.insertIfAbsent(tx.connection(), tx.dialect(),
                    "INSERT INTO CH_INBOUND_SEEN(tenant_id,channel,message_id,seen_at) VALUES (?,?,?,?)", tenant, channel,
                    p.messageId(), Jdbc.dbNow(tx.connection(), tx.dialect()));
            if (!fresh) {
                return new Outcome(Kind.DUPLICATE, null, false);
            }
            Optional<String> principal = optIns.principalOf(tenant, channel, p.externalId());
            if (principal.isEmpty()) {
                return new Outcome(Kind.NOT_OPTED_IN, null, false);
            }
            boolean mediaLater = asyncMedia && p.mediaRef() != null && !p.mediaRef().isBlank();
            if (mediaLater) {
                // durable: the pending media commits with the inbox row, or neither does
                InboundMediaQueue.enqueue(tx.connection(), tx.dialect(), tenant, channel, p.messageId(), p.externalId(), principal.get(), p.mediaRef());
            }
            if (!mediaLater) {
                bot.onInbound(new BotBridge.InboundMessage(tenant, channel, p.externalId(), principal.get(), p.messageId(), p.text(),
                        p.mediaRef() == null ? Map.of() : Map.of("mediaRef", p.mediaRef())));
            } else if (p.text() != null && !p.text().isBlank()) {
                bot.onInbound(new BotBridge.InboundMessage(tenant, channel, p.externalId(), principal.get(), p.messageId(), p.text(), Map.of()));
            }
            return new Outcome(Kind.ACCEPTED, principal.get(), mediaLater);
        });
    }

    private String configured(String tenant, String key) {
        String v = webhookSettings(tenant).get(key);
        if (v == null || v.isBlank()) {
            throw ApiProblem.notFound("channel webhook", tenant + "/" + key.substring(0, key.indexOf('.')).toLowerCase(Locale.ROOT));
        }
        return v;
    }

    /**
     * The tenant's {@code sangam.channels.webhooks[<tenant>].*} keys (bracket notation for an id with a dot, such as
     * {@code GOI.FIN}); empty for an unusable id.
     */
    private Map<String, String> webhookSettings(String tenant) {
        return TenantConfig.<Map<String, String>>bind(env, "sangam.channels.webhooks", tenant,
                org.springframework.core.ResolvableType.forClassWithGenerics(Map.class, String.class, String.class)).orElse(Map.of());
    }

    private static ApiProblem refused() {
        return new ApiProblem(401, "WEBHOOK_SIGNATURE_INVALID", "The webhook signature did not verify");
    }
}
