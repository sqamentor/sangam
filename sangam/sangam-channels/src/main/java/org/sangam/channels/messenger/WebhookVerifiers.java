// SPDX-License-Identifier: Apache-2.0
package org.sangam.channels.messenger;

import com.fasterxml.jackson.databind.JsonNode;
import org.sangam.integration.connector.Json;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Function;

/** Inbound webhook checks from the providers' public documents. All comparisons are constant time. A failed check means: drop. */
public final class WebhookVerifiers {

    public static final String BOT_FRAMEWORK_ISSUER = "https://api.botframework.com";
    private static final long CLOCK_SKEW_SECONDS = 300;

    private WebhookVerifiers() {
    }

    /** WhatsApp Cloud API: header {@code X-Hub-Signature-256: sha256=<hex HMAC-SHA256 of the raw body with the app secret>}. */
    public static boolean whatsApp(String appSecret, byte[] rawBody, String signatureHeader) {
        if (signatureHeader == null || !signatureHeader.startsWith("sha256=")) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(appSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] want = mac.doFinal(rawBody);
            return MessageDigest.isEqual(want, HexFormat.of().parseHex(signatureHeader.substring(7)));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * WhatsApp (Meta) webhook subscription handshake, a GET. Returns the {@code hub.challenge} to echo when the mode is
     * {@code subscribe} and the verify token matches; otherwise null, and the host answers 403.
     */
    public static String whatsAppChallenge(String verifyToken, String hubMode, String hubVerifyToken, String hubChallenge) {
        if (!"subscribe".equals(hubMode) || hubVerifyToken == null || hubChallenge == null) {
            return null;
        }
        return MessageDigest.isEqual(verifyToken.getBytes(StandardCharsets.UTF_8), hubVerifyToken.getBytes(StandardCharsets.UTF_8)) ? hubChallenge : null;
    }

    /** WeChat: {@code signature} is the hex SHA-1 of the lexically sorted, concatenated token, timestamp and nonce. */
    public static boolean weChat(String token, String timestamp, String nonce, String signature) {
        if (timestamp == null || nonce == null || signature == null) {
            return false;
        }
        String[] parts = {token, timestamp, nonce};
        Arrays.sort(parts);
        try {
            byte[] want = MessageDigest.getInstance("SHA-1").digest(String.join("", parts).getBytes(StandardCharsets.UTF_8));
            return MessageDigest.isEqual(HexFormat.of().formatHex(want).getBytes(StandardCharsets.US_ASCII), signature.toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    /**
     * Teams / Bot Framework: {@code Authorization: Bearer <RS256 JWT>}. Checks the algorithm, the signature by key id, issuer
     * (see {@link #BOT_FRAMEWORK_ISSUER}), audience (the bot's app id), validity period with five minutes of clock skew, and that
     * the {@code serviceUrl} claim equals the {@code serviceUrl} of the activity in the request body. Keys come from
     * {@code keysByKid}, which the integrator backs with a copy of the provider's published key set refreshed at least every 24
     * hours, so this class makes no outbound call. Channel endorsements are not checked.
     */
    public static boolean botFramework(String authorizationHeader, String expectedIssuer, String expectedAudience, String activityServiceUrl,
                                       Function<String, Optional<PublicKey>> keysByKid, Instant now) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            return false;
        }
        String[] p = authorizationHeader.substring(7).trim().split("\\.");
        if (p.length != 3) {
            return false;
        }
        try {
            Base64.Decoder d = Base64.getUrlDecoder();
            JsonNode header = Json.tree(d.decode(p[0]));
            JsonNode claims = Json.tree(d.decode(p[1]));
            if (header == null || claims == null || !"RS256".equals(header.path("alg").asText())) {
                return false;
            }
            Optional<PublicKey> key = keysByKid.apply(header.path("kid").asText());
            if (key.isEmpty()) {
                return false;
            }
            Signature s = Signature.getInstance("SHA256withRSA");
            s.initVerify(key.get());
            s.update((p[0] + "." + p[1]).getBytes(StandardCharsets.US_ASCII));
            if (!s.verify(d.decode(p[2]))) {
                return false;
            }
            long t = now.getEpochSecond();
            return expectedIssuer.equals(claims.path("iss").asText()) && expectedAudience.equals(claims.path("aud").asText())
                    && claims.path("exp").asLong(0) + CLOCK_SKEW_SECONDS > t && claims.path("nbf").asLong(0) - CLOCK_SKEW_SECONDS <= t
                    && activityServiceUrl != null && activityServiceUrl.equals(claims.path("serviceUrl").asText());
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }
}
