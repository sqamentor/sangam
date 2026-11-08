// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.publish;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * The webhook signature scheme shared by {@link HttpWebhookPublisher} (sender) and {@code WebhookInboxReceiver}
 * (receiver), R27: header {@code X-Sangam-Signature: t=<epochSeconds>,v1=<hex HMAC-SHA256(secret, t + "." + body)>}.
 * Same construction as Stripe's {@code Stripe-Signature} (timestamp-prefixed payload, so a captured request cannot be
 * replayed outside the tolerance; several {@code v1} entries during a secret rotation) and GitHub's
 * {@code X-Hub-Signature-256} (HMAC-SHA256 hex, constant-time comparison).
 */
public final class WebhookSignature {

    public static final String HEADER = "X-Sangam-Signature";
    /** Stripe's default tolerance; a zero tolerance would reject every real delivery. */
    public static final Duration DEFAULT_TOLERANCE = Duration.ofMinutes(5);

    private WebhookSignature() {
    }

    /** {@code t=<t>,v1=<hex>}, one {@code v1} per secret (current first, then the ones being rotated out). */
    public static String header(long epochSeconds, byte[] body, List<String> secrets) {
        if (secrets == null || secrets.isEmpty()) {
            throw new IllegalArgumentException("at least one signing secret is required");
        }
        StringBuilder sb = new StringBuilder("t=").append(epochSeconds);
        for (String s : secrets) {
            sb.append(",v1=").append(HexFormat.of().formatHex(mac(s.getBytes(StandardCharsets.UTF_8), epochSeconds, body)));
        }
        return sb.toString();
    }

    /**
     * True when {@code header} carries a {@code t} within {@code tolerance} of {@code nowEpochSeconds} and at least one
     * {@code v1} equal (constant time) to the HMAC of {@code t + "." + body} under {@code secret}.
     */
    public static boolean verify(byte[] secret, String header, byte[] body, Duration tolerance, long nowEpochSeconds) {
        if (secret == null || header == null || body == null) {
            return false;
        }
        long t = -1;
        List<String> v1 = new ArrayList<>(2);
        for (String part : header.split(",")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length != 2) {
                continue;
            }
            if (kv[0].equals("t")) {
                try {
                    t = Long.parseLong(kv[1].trim());
                } catch (NumberFormatException e) {
                    return false;
                }
            } else if (kv[0].equals("v1")) {
                v1.add(kv[1].trim());
            }
        }
        long tol = (tolerance == null ? DEFAULT_TOLERANCE : tolerance).toSeconds();
        if (t < 0 || v1.isEmpty() || Math.abs(nowEpochSeconds - t) > tol) {
            return false;
        }
        byte[] expected = HexFormat.of().formatHex(mac(secret, t, body)).getBytes(StandardCharsets.US_ASCII);
        boolean ok = false;
        for (String candidate : v1) {
            ok |= MessageDigest.isEqual(expected, candidate.getBytes(StandardCharsets.US_ASCII));
        }
        return ok;
    }

    private static byte[] mac(byte[] secret, long epochSeconds, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            mac.update((epochSeconds + ".").getBytes(StandardCharsets.UTF_8));
            return mac.doFinal(body);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
