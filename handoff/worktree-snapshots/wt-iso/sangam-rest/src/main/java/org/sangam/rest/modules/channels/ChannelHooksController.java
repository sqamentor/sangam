// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.modules.channels;

import java.security.PublicKey;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;

import org.sangam.channels.bot.BotBridge;
import org.sangam.channels.messenger.InboundMessageParsers;
import org.sangam.channels.messenger.OptInRegistry;
import org.sangam.channels.messenger.WebhookVerifiers;
import org.sangam.core.layer.Jdbc;
import org.sangam.integration.connector.SecretResolver;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.support.TxSupport;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Provider webhooks, public only because the signature on the raw body is verified first. A tenant and channel with no
 * configured secret answer 404. After verification: inbox dedupe ({@code CH_INBOUND_SEEN}), opt-in mapping, then the bot
 * bridge, in one transaction, so a failed start leaves the message unseen and the provider's retry works. A sender with
 * no active opt-in is recorded as seen and goes no further.
 *
 * <p>Properties under {@code sangam.channels.webhooks.<tenant>}: {@code whatsapp.secret-ref}, {@code whatsapp.verify-token-ref},
 * {@code wechat.token-ref}, {@code teams.audience}, {@code teams.issuer} (default the Bot Framework issuer).
 */
@RestController
@RequestMapping("/api/v1/hooks/channels/{tenant}")
public class ChannelHooksController {

    private final BotBridge bot;
    private final OptInRegistry optIns;
    private final TxSupport tx;
    private final SecretResolver secrets;
    private final Environment env;
    private final BiFunction<String, String, Optional<PublicKey>> botKeys;

    public ChannelHooksController(BotBridge bot, OptInRegistry optIns, TxSupport tx, SecretResolver secrets, Environment env,
                                  BiFunction<String, String, Optional<PublicKey>> sangamBotFrameworkKeys) {
        this.bot = bot;
        this.optIns = optIns;
        this.tx = tx;
        this.secrets = secrets;
        this.env = env;
        this.botKeys = sangamBotFrameworkKeys;
    }

    public record HookResult(int accepted, int duplicates, int notOptedIn) {
    }

    /** Meta's subscription handshake. */
    @GetMapping("/whatsapp")
    public ResponseEntity<String> whatsAppVerify(@PathVariable String tenant, @RequestParam("hub.verify_token") String token,
            @RequestParam("hub.challenge") String challenge) {
        String want = secrets.require(configured(tenant, "whatsapp.verify-token-ref"));
        if (!java.security.MessageDigest.isEqual(want.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                token.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            throw refused();
        }
        return ResponseEntity.ok().contentType(org.springframework.http.MediaType.TEXT_PLAIN).body(challenge);
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
        return ResponseEntity.ok().contentType(org.springframework.http.MediaType.TEXT_PLAIN).body(echostr);
    }

    @PostMapping("/wechat")
    public ResponseEntity<HookResult> weChat(@PathVariable String tenant, @RequestBody byte[] raw, @RequestParam String timestamp,
            @RequestParam String nonce, @RequestParam String signature) {
        verifyWeChat(tenant, timestamp, nonce, signature);
        return ResponseEntity.ok(handle(tenant, "WECHAT", List.of(InboundMessageParsers.weChat(raw))));
    }

    @PostMapping("/teams")
    public ResponseEntity<HookResult> teams(@PathVariable String tenant, @RequestBody byte[] raw,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        String audience = configured(tenant, "teams.audience");
        String issuer = env.getProperty(prefix(tenant) + "teams.issuer", WebhookVerifiers.BOT_FRAMEWORK_ISSUER);
        if (!WebhookVerifiers.botFramework(authorization, issuer, audience, serviceUrl(raw), kid -> botKeys.apply(tenant, kid), Instant.now())) {
            throw refused();
        }
        return ResponseEntity.ok(handle(tenant, "TEAMS", List.of(InboundMessageParsers.teams(raw))));
    }

    /** The token's serviceUrl claim is compared with this value; an unreadable body fails that comparison. */
    private static String serviceUrl(byte[] raw) {
        try {
            Object v = org.sangam.integration.connector.Json.readMap(new String(raw, java.nio.charset.StandardCharsets.UTF_8)).get("serviceUrl");
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

    private HookResult handle(String tenant, String channel, List<InboundMessageParsers.Parsed> messages) {
        int accepted = 0;
        int duplicates = 0;
        int notOptedIn = 0;
        for (InboundMessageParsers.Parsed p : messages) {
            switch (one(tenant, channel, p)) {
                case 0 -> accepted++;
                case 1 -> duplicates++;
                default -> notOptedIn++;
            }
        }
        return new HookResult(accepted, duplicates, notOptedIn);
    }

    private int one(String tenant, String channel, InboundMessageParsers.Parsed p) {
        return tx.inTx(() -> {
            boolean fresh = Jdbc.insertIfAbsent(tx.connection(), tx.dialect(),
                    "INSERT INTO CH_INBOUND_SEEN(tenant_id,channel,message_id,seen_at) VALUES (?,?,?,?)", tenant, channel,
                    p.messageId(), Jdbc.dbNow(tx.connection(), tx.dialect()));
            if (!fresh) {
                return 1;
            }
            Optional<String> principal = optIns.principalOf(tenant, channel, p.externalId());
            if (principal.isEmpty()) {
                return 2;
            }
            bot.onInbound(new BotBridge.InboundMessage(tenant, channel, p.externalId(), principal.get(), p.messageId(), p.text(),
                    p.mediaRef() == null ? Map.of() : Map.of("mediaRef", p.mediaRef())));
            return 0;
        });
    }

    private String configured(String tenant, String key) {
        String v = tenant.matches("[A-Za-z0-9_-]{1,64}") ? env.getProperty(prefix(tenant) + key) : null;
        if (v == null || v.isBlank()) {
            throw ApiProblem.notFound("channel webhook", tenant + "/" + key.substring(0, key.indexOf('.')).toLowerCase(Locale.ROOT));
        }
        return v;
    }

    private static String prefix(String tenant) {
        return "sangam.channels.webhooks." + tenant + ".";
    }

    private static ApiProblem refused() {
        return new ApiProblem(401, "WEBHOOK_SIGNATURE_INVALID", "The webhook signature did not verify");
    }
}
