// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.connector.gov;

import com.fasterxml.jackson.databind.JsonNode;
import org.sangam.integration.connector.Json;
import org.sangam.integration.connector.SecretResolver;
import org.sangam.integration.sim.SimulatorProfile;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Authenticates payment gateway settlement callbacks (R27 "authenticate callbacks", R58) and turns a verified callback
 * into a {@link PaymentAcknowledgement}.
 *
 * <p>Header {@value #SIGNATURE_HEADER}: {@code t=<epochSeconds>,v1=<hex HMAC-SHA256(secret, t + "." + body)>}; several
 * {@code v1} values are accepted (secret rotation). A callback is accepted only if:
 * <ol>
 *   <li>the header parses and {@code t} is within the tolerance (default 5 minutes) of now, in either direction;</li>
 *   <li>one {@code v1} equals the expected HMAC over the exact raw body bytes (constant-time comparison);</li>
 *   <li>the body is a JSON object with {@code orderId} and {@code status};</li>
 *   <li>the (t, signature) pair has not been seen within the tolerance window (replay protection through a pluggable
 *       {@link ReplayGuard}; the default is in-memory, a cluster plugs in a shared store).</li>
 * </ol>
 * Each check failing gives {@link Verification.Rejected} with a reason code; nothing about the secret leaks into it.
 * The tenant of the acknowledgement is the tenant the callback endpoint is bound to (VI.4 rule 6), never the body.
 */
public final class PaymentGatewayCallbackVerifier {

    public static final String SIGNATURE_HEADER = "X-PG-Signature";
    public static final Duration DEFAULT_TOLERANCE = Duration.ofMinutes(5);

    private static final Pattern HEX64 = Pattern.compile("[0-9a-fA-F]{64}");

    /** Remembers accepted callbacks so a replay inside the tolerance window is refused. */
    @FunctionalInterface
    public interface ReplayGuard {
        /**
         * Atomically records {@code key} until {@code expiresAt}. Returns true when it was not recorded before (or its
         * record expired), false for a replay.
         */
        boolean firstSeen(String key, Instant expiresAt);
    }

    /** Process-local {@link ReplayGuard}; expired keys are purged as it is used. Thread-safe. */
    public static final class InMemoryReplayGuard implements ReplayGuard {
        private final Map<String, Instant> seen = new ConcurrentHashMap<>();
        private final Clock clock;
        private final AtomicLong calls = new AtomicLong();

        public InMemoryReplayGuard() {
            this(Clock.systemUTC());
        }

        public InMemoryReplayGuard(Clock clock) {
            this.clock = clock == null ? Clock.systemUTC() : clock;
        }

        @Override
        public boolean firstSeen(String key, Instant expiresAt) {
            Instant now = clock.instant();
            if (calls.incrementAndGet() % 256 == 0) {
                seen.entrySet().removeIf(e -> !e.getValue().isAfter(now));
            }
            boolean[] fresh = {false};
            seen.compute(key, (k, old) -> {
                if (old == null || !old.isAfter(now)) {
                    fresh[0] = true;
                    return expiresAt;
                }
                return old;
            });
            return fresh[0];
        }

        public int size() {
            return seen.size();
        }
    }

    /** Result of {@link #verify}. */
    public sealed interface Verification permits Verification.Valid, Verification.Rejected {

        /** An authentic, fresh, first-seen callback. */
        record Valid(long timestamp, String signature, Map<String, Object> payload) implements Verification {
            public Valid {
                payload = Map.copyOf(payload);
            }

            public String text(String field) {
                Object v = payload.get(field);
                return v == null ? null : v.toString();
            }
        }

        /** Refused; {@code reason} is one of the {@code REASON_*} codes. */
        record Rejected(String reason) implements Verification {
        }
    }

    public static final String REASON_MISSING_SIGNATURE = "MISSING_SIGNATURE";
    public static final String REASON_MALFORMED_SIGNATURE = "MALFORMED_SIGNATURE";
    public static final String REASON_STALE_TIMESTAMP = "STALE_TIMESTAMP";
    public static final String REASON_BAD_SIGNATURE = "BAD_SIGNATURE";
    public static final String REASON_MALFORMED_BODY = "MALFORMED_BODY";
    public static final String REASON_REPLAY = "REPLAY";

    private final byte[] secret;
    private final Duration tolerance;
    private final ReplayGuard replayGuard;
    private final Clock clock;

    /**
     * @param secret      the shared callback secret (already resolved)
     * @param tolerance   allowed clock difference; null = 5 minutes
     * @param replayGuard seen-set; null = a new {@link InMemoryReplayGuard}
     * @param clock       clock; null = UTC system clock
     */
    public PaymentGatewayCallbackVerifier(String secret, Duration tolerance, ReplayGuard replayGuard, Clock clock) {
        Objects.requireNonNull(secret, "secret");
        if (secret.isEmpty()) {
            throw new IllegalArgumentException("callback secret must not be empty");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.tolerance = tolerance == null ? DEFAULT_TOLERANCE : tolerance;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.replayGuard = replayGuard == null ? new InMemoryReplayGuard(this.clock) : replayGuard;
    }

    /** Verifier whose secret is a reference resolved through {@code secrets} (R73). */
    public static PaymentGatewayCallbackVerifier fromSecretRef(SecretResolver secrets, String secretRef,
                                                               Duration tolerance, ReplayGuard replayGuard) {
        return new PaymentGatewayCallbackVerifier(secrets.require(secretRef), tolerance, replayGuard, null);
    }

    /** Verifier for callbacks of the payment gateway simulator ({@link SimulatorProfile#PG_CALLBACK_SECRET}). */
    public static PaymentGatewayCallbackVerifier simulator(SimulatorProfile sims) {
        Objects.requireNonNull(sims, "sims");
        return new PaymentGatewayCallbackVerifier(SimulatorProfile.PG_CALLBACK_SECRET, null, null, null);
    }

    /** Verifies one callback: the raw {@value #SIGNATURE_HEADER} value and the exact raw body bytes. */
    public Verification verify(String signatureHeader, byte[] body) {
        if (signatureHeader == null || signatureHeader.isBlank()) {
            return new Verification.Rejected(REASON_MISSING_SIGNATURE);
        }
        Long t = null;
        List<String> v1 = new ArrayList<>();
        for (String part : signatureHeader.split(",")) {
            int eq = part.indexOf('=');
            if (eq <= 0) {
                return new Verification.Rejected(REASON_MALFORMED_SIGNATURE);
            }
            String k = part.substring(0, eq).trim();
            String v = part.substring(eq + 1).trim();
            if (k.equals("t")) {
                if (t != null) {
                    return new Verification.Rejected(REASON_MALFORMED_SIGNATURE);
                }
                try {
                    t = Long.parseLong(v);
                } catch (NumberFormatException e) {
                    return new Verification.Rejected(REASON_MALFORMED_SIGNATURE);
                }
            } else if (k.equals("v1")) {
                if (!HEX64.matcher(v).matches()) {
                    return new Verification.Rejected(REASON_MALFORMED_SIGNATURE);
                }
                v1.add(v.toLowerCase(Locale.ROOT));
            }
        }
        if (t == null || v1.isEmpty()) {
            return new Verification.Rejected(REASON_MALFORMED_SIGNATURE);
        }
        long now = clock.instant().getEpochSecond();
        if (Math.abs(now - t) > tolerance.toSeconds()) {
            return new Verification.Rejected(REASON_STALE_TIMESTAMP);
        }
        byte[] raw = body == null ? new byte[0] : body;
        byte[] expected = HexFormat.of().formatHex(hmac(t, raw)).getBytes(StandardCharsets.US_ASCII);
        String matched = null;
        for (String candidate : v1) {
            if (MessageDigest.isEqual(expected, candidate.getBytes(StandardCharsets.US_ASCII))) {
                matched = candidate;
            }
        }
        if (matched == null) {
            return new Verification.Rejected(REASON_BAD_SIGNATURE);
        }
        JsonNode n = Json.tree(raw);
        if (n == null || !n.isObject() || GovHttp.text(n, "orderId") == null || GovHttp.text(n, "status") == null) {
            return new Verification.Rejected(REASON_MALFORMED_BODY);
        }
        Instant expiresAt = Instant.ofEpochSecond(t).plus(tolerance).plusSeconds(1);
        if (!replayGuard.firstSeen(t + ":" + matched, expiresAt)) {
            return new Verification.Rejected(REASON_REPLAY);
        }
        return new Verification.Valid(t, matched, GovHttp.jsonMap(n));
    }

    /**
     * Normalises a verified callback. messageId = {@code pg#<orderId>#<status>}, stable per settlement event, so the
     * same event re-delivered with a new signature is still applied once by the inbox.
     */
    public PaymentAcknowledgement toAcknowledgement(String tenantId, Verification.Valid valid) {
        String orderId = valid.text("orderId");
        String status = valid.text("status").toUpperCase(Locale.ROOT);
        PaymentAcknowledgement.Status st = PaymentAcknowledgement.Status.from(status);
        String reason = valid.text("reason");
        if (st == PaymentAcknowledgement.Status.FAILED && reason == null) {
            reason = "PAYMENT_FAILED";
        }
        return new PaymentAcknowledgement(PaymentAcknowledgement.SOURCE_PG, "pg#" + orderId + "#" + status, tenantId,
                orderId, valid.text("pgRef"), st, st == PaymentAcknowledgement.Status.SUCCESS ? null : reason,
                valid.text("amount"), clock.instant());
    }

    /** {@link #verify} and, when valid, {@link #toAcknowledgement}; the rejection reason otherwise. */
    public Result verifyAndConvert(String tenantId, String signatureHeader, byte[] body) {
        Verification v = verify(signatureHeader, body);
        return switch (v) {
            case Verification.Valid ok -> new Result(toAcknowledgement(tenantId, ok), null);
            case Verification.Rejected no -> new Result(null, no.reason());
        };
    }

    /** Acknowledgement of a verified callback, or the rejection reason. Exactly one is non-null. */
    public record Result(PaymentAcknowledgement acknowledgement, String rejectionReason) {
        public boolean accepted() {
            return acknowledgement != null;
        }
    }

    private byte[] hmac(long t, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            mac.update((t + ".").getBytes(StandardCharsets.UTF_8));
            return mac.doFinal(body);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
