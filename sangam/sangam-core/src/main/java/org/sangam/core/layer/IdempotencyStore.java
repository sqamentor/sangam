// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.layer;

import org.sangam.core.security.FieldEncryptor;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * API idempotency lifecycle (SANGAM.md X.2.2, R41).
 *
 * <ul>
 *   <li>Scope {@code (tenant_id, command_type, idem_key)}; the target belongs to the fingerprint, not the scope.</li>
 *   <li>{@link #begin} is the step-1 claim, in its own short transaction with no other locks.</li>
 *   <li>{@link #complete} is the step-2 write: it runs in the <b>command (engine) transaction</b> as its <b>last write</b>
 *       (lock domain L6, always last), fenced by {@code claim_epoch}; zero rows ⇒ {@link ClaimLostException} ⇒ the
 *       caller rolls the whole command back (property P-1).</li>
 *   <li>Deterministic 4xx results are stored with {@link #failFinal}; transient results and 401/403 are not stored
 *       ({@link #abandon} deletes the claim so an immediate retry executes).</li>
 * </ul>
 * Replays never re-execute. The caller must re-authenticate, re-check authorization without cache and re-apply field
 * masks before returning a {@link Replay} (X.2.2.4 step 4); this store only returns the stored data.
 *
 * <p><b>Response at rest (X.2.2.3, R41, M-007).</b> With a {@link FieldEncryptor} bound, {@code response_body} is stored
 * as {@link #ENCRYPTED_BODY_MARKER} followed by the encryptor's envelope of the Base64 body, under the tenant's active
 * key with the context {@code idempotency:<commandType>:<key>} (so a body copied to another row, command or tenant
 * fails to decrypt). A {@link Replay} carries the decrypted, byte-identical body (T57). Rows written before (or without)
 * an encryptor hold the plain body; they have no marker and are returned as stored. The marker cannot open a legacy
 * body: stored bodies are JSON (the replay is served as {@code application/json}), and a JSON text cannot start with
 * {@code s}. Key rotation: the envelope names its key ({@code <tenant>/k<n>}), so bodies written before a rotation stay
 * readable under the decrypt-only key, and compaction ({@link #cleanup}) removes every body after the response
 * retention, so no stored body depends on a retired key for longer than that.
 */
public final class IdempotencyStore {

    public static final int FP_VERSION = 1;

    public sealed interface BeginResult permits New, Replay, Conflict, InProgress {
    }

    /** The caller owns the claim and must execute the command, then {@link #complete} in the same transaction. */
    public record New(Claim claim) implements BeginResult {
    }

    /** Stored response; {@code compacted} means status and resource reference only (after response retention). */
    public record Replay(int httpStatus, byte[] body, String resourceRef, boolean compacted) implements BeginResult {
    }

    /** 422: same scope, different fingerprint ({@code IDEMPOTENCY_KEY_REUSED}) or principal ({@code ..._PRINCIPAL_MISMATCH}). */
    public record Conflict(int httpStatus, String code) implements BeginResult {
    }

    /** 409 {@code IDEMPOTENCY_IN_PROGRESS}, {@code Retry-After: 1}. */
    public record InProgress(int httpStatus, String code, int retryAfterSeconds) implements BeginResult {
    }

    public record Claim(String tenantId, String commandType, String key, long epoch) {
    }

    /** {@code IdempotencyClaimLost}: another attempt took the claim over; roll the command back. */
    public static final class ClaimLostException extends RuntimeException {
        public ClaimLostException(Claim claim) {
            super("idempotency claim lost: " + claim);
        }
    }

    private final Dialect dialect;
    private final Transactions tx;
    private final String node;
    private final Duration claimTtl;
    private final Duration responseRetention;
    private final Duration keyRetention;
    private final FieldEncryptor encryptor;

    /** Prefix of an encrypted {@code response_body}: this marker, then a {@link FieldEncryptor} envelope (ASCII). */
    public static final String ENCRYPTED_BODY_MARKER = "sgm-idem1:";

    private static final byte[] MARKER = ENCRYPTED_BODY_MARKER.getBytes(StandardCharsets.US_ASCII);

    public IdempotencyStore(Dialect dialect, Transactions tx, String node, Duration claimTtl, Duration responseRetention,
                            Duration keyRetention) {
        this(dialect, tx, node, claimTtl, responseRetention, keyRetention, null);
    }

    /**
     * @param encryptor tenant-key encryption of the stored response body (M-007); null stores it unencrypted, and a
     *                  stored encrypted body then cannot be replayed ({@code ENCRYPTION_UNAVAILABLE})
     */
    public IdempotencyStore(Dialect dialect, Transactions tx, String node, Duration claimTtl, Duration responseRetention,
                            Duration keyRetention, FieldEncryptor encryptor) {
        if (keyRetention.compareTo(responseRetention) < 0) {
            throw new IllegalArgumentException("keyRetention must be >= responseRetention");
        }
        this.dialect = dialect;
        this.tx = tx;
        this.node = node;
        this.claimTtl = claimTtl;
        this.responseRetention = responseRetention;
        this.keyRetention = keyRetention;
        this.encryptor = encryptor;
    }

    /** Defaults from X.2.2: claim TTL 30 s, response retention 24 h, key retention 30 d. */
    public IdempotencyStore(Dialect dialect, Transactions tx, String node) {
        this(dialect, tx, node, (FieldEncryptor) null);
    }

    /** X.2.2 defaults, with the response body encrypted at rest when {@code encryptor} is not null. */
    public IdempotencyStore(Dialect dialect, Transactions tx, String node, FieldEncryptor encryptor) {
        this(dialect, tx, node, Duration.ofSeconds(30), Duration.ofHours(24), Duration.ofDays(30), encryptor);
    }

    /** Authenticated context of a stored body: binds it to its command and key (the tenant is bound by the encryptor). */
    static String bodyContext(String commandType, String key) {
        return "idempotency:" + commandType + ":" + key;
    }

    /** The value written to {@code response_body}: encrypted when an encryptor is bound, else the body unchanged. */
    byte[] sealBody(String tenant, String commandType, String key, byte[] body) {
        if (body == null || encryptor == null) {
            return body;
        }
        String envelope = encryptor.encrypt(tenant, bodyContext(commandType, key), Base64.getEncoder().encodeToString(body));
        return (ENCRYPTED_BODY_MARKER + envelope).getBytes(StandardCharsets.US_ASCII);
    }

    /** The body as the command produced it: decrypted when it carries the marker, a legacy plain body unchanged. */
    byte[] openBody(String tenant, String commandType, String key, byte[] stored) {
        if (!isEncryptedBody(stored)) {
            return stored;
        }
        if (encryptor == null) {
            throw new IllegalStateException("ENCRYPTION_UNAVAILABLE: the stored idempotency response of " + commandType
                    + " is encrypted at rest and no FieldEncryptor is configured");
        }
        String envelope = new String(stored, MARKER.length, stored.length - MARKER.length, StandardCharsets.US_ASCII);
        return Base64.getDecoder().decode(encryptor.decrypt(tenant, bodyContext(commandType, key), envelope));
    }

    /** True for a body written by {@link #sealBody} with an encryptor: the marker followed by an envelope. */
    static boolean isEncryptedBody(byte[] stored) {
        if (stored == null || stored.length <= MARKER.length
                || !Arrays.equals(stored, 0, MARKER.length, MARKER, 0, MARKER.length)) {
            return false;
        }
        return FieldEncryptor.isEncrypted(new String(stored, MARKER.length, stored.length - MARKER.length,
                StandardCharsets.US_ASCII));
    }

    /** X.2.2.2: {@code SHA-256(JCS({commandType, method, pathTemplate, pathParams, semanticQueryParams, body}))}. */
    public static byte[] fingerprint(String commandType, String method, String pathTemplate, Map<String, ?> pathParams,
                                     Map<String, ?> semanticQueryParams, Object body) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("commandType", commandType);
        m.put("method", method);
        m.put("pathTemplate", pathTemplate);
        m.put("pathParams", pathParams == null ? Map.of() : pathParams);
        m.put("semanticQueryParams", semanticQueryParams == null ? Map.of() : semanticQueryParams);
        m.put("body", body);
        return Hashes.sha256(CanonicalJson.write(m));
    }

    /** Begin without a distinct principal (the principal check is then vacuous). */
    public BeginResult begin(String tenant, String commandType, String key, byte[] payloadHash) {
        return begin(tenant, commandType, key, "-", payloadHash);
    }

    /** Step 1: claim (own short transaction). */
    public BeginResult begin(String tenant, String commandType, String key, String principal, byte[] payloadHash) {
        validateKey(key);
        Objects.requireNonNull(payloadHash, "payloadHash");
        for (int round = 0; round < 10; round++) {
            BeginResult r = tx.inTransaction(c -> attempt(c, tenant, commandType, key, principal, payloadHash));
            if (r != null) {
                return r;
            }
        }
        return new InProgress(409, "IDEMPOTENCY_IN_PROGRESS", 1);
    }

    private BeginResult attempt(Connection c, String tenant, String commandType, String key, String principal, byte[] fp) {
        boolean inserted = Jdbc.insertIfAbsent(c, dialect,
                "INSERT INTO IDEMPOTENCY_RECORD(tenant_id, command_type, idem_key, fp_version, fingerprint, principal_id, state,"
                        + " owner_node, claim_epoch, claim_expires_at, created_at)"
                        + " VALUES (?,?,?,?,?,?,'IN_PROGRESS',?,1," + dialect.nowPlusMillisParam() + "," + dialect.now() + ")",
                tenant, commandType, key, FP_VERSION, fp, principal, node, claimTtl.toMillis());
        if (inserted) {
            return new New(new Claim(tenant, commandType, key, 1));
        }
        record Existing(String principal, int fpVersion, byte[] fp, String state, boolean claimExpired, Integer status,
                        byte[] body, String ref) {
        }
        var row = Jdbc.one(c,
                "SELECT principal_id, fp_version, fingerprint, state, CASE WHEN claim_expires_at < " + dialect.now()
                        + " THEN 1 ELSE 0 END AS expired, http_status, response_body, resource_ref FROM IDEMPOTENCY_RECORD"
                        + " WHERE tenant_id=? AND command_type=? AND idem_key=?",
                rs -> new Existing(rs.getString(1), rs.getInt(2), rs.getBytes(3), rs.getString(4), rs.getInt(5) == 1,
                        rs.getObject(6) == null ? null : rs.getInt(6), rs.getBytes(7), rs.getString(8)),
                tenant, commandType, key);
        if (row.isEmpty()) {
            return null;   // abandoned between our insert and read: retry the claim
        }
        Existing e = row.get();
        if (!e.principal.equals(principal)) {
            return new Conflict(422, "IDEMPOTENCY_KEY_PRINCIPAL_MISMATCH");
        }
        if (e.fpVersion != FP_VERSION || !Arrays.equals(e.fp, fp)) {
            // a stored record is compared with its own fp_version; only version 1 exists in the spike
            return new Conflict(422, "IDEMPOTENCY_KEY_REUSED");
        }
        switch (e.state) {
            case "COMPLETED", "FAILED_FINAL" -> {
                return new Replay(e.status, openBody(tenant, commandType, key, e.body), e.ref, false);
            }
            case "COMPACTED" -> {
                return new Replay(e.status, null, e.ref, true);
            }
            case "IN_PROGRESS" -> {
                if (!e.claimExpired) {
                    return new InProgress(409, "IDEMPOTENCY_IN_PROGRESS", 1);
                }
                int n = Jdbc.update(c, "UPDATE IDEMPOTENCY_RECORD SET claim_epoch=claim_epoch+1, owner_node=?, claim_expires_at="
                                + dialect.nowPlusMillisParam() + " WHERE tenant_id=? AND command_type=? AND idem_key=?"
                                + " AND state='IN_PROGRESS' AND claim_expires_at < " + dialect.now() + " AND fingerprint=?",
                        node, claimTtl.toMillis(), tenant, commandType, key, fp);
                if (n == 0) {
                    return null;   // re-read and re-apply the table
                }
                long epoch = Jdbc.one(c, "SELECT claim_epoch FROM IDEMPOTENCY_RECORD WHERE tenant_id=? AND command_type=? AND idem_key=?",
                        rs -> rs.getLong(1), tenant, commandType, key).orElseThrow();
                return new New(new Claim(tenant, commandType, key, epoch));
            }
            default -> throw new IllegalStateException("unknown idempotency state " + e.state);
        }
    }

    /** Step 2, last write of the command transaction (L6). Throws {@link ClaimLostException} on a lost claim. */
    public void complete(Connection c, Claim claim, int httpStatus, byte[] responseBody, String resourceRef) {
        int n = Jdbc.update(c, "UPDATE IDEMPOTENCY_RECORD SET state='COMPLETED', http_status=?, response_body=?, resource_ref=?,"
                        + " completed_at=" + dialect.now() + ", response_expires_at=" + dialect.nowPlusMillisParam()
                        + ", key_expires_at=" + dialect.nowPlusMillisParam()
                        + " WHERE tenant_id=? AND command_type=? AND idem_key=? AND claim_epoch=? AND state='IN_PROGRESS'",
                httpStatus, sealBody(claim.tenantId(), claim.commandType(), claim.key(), responseBody), resourceRef,
                responseRetention.toMillis(), keyRetention.toMillis(),
                claim.tenantId(), claim.commandType(), claim.key(), claim.epoch());
        if (n == 0) {
            throw new ClaimLostException(claim);
        }
    }

    /** Step 3, deterministic business/validation result (400/404/409-precondition/410/422): stored as FAILED_FINAL. */
    public void failFinal(Claim claim, int httpStatus, byte[] responseBody) {
        tx.inTransaction(c -> {
            int n = Jdbc.update(c, "UPDATE IDEMPOTENCY_RECORD SET state='FAILED_FINAL', http_status=?, response_body=?,"
                            + " completed_at=" + dialect.now() + ", response_expires_at=" + dialect.nowPlusMillisParam()
                            + ", key_expires_at=" + dialect.nowPlusMillisParam()
                            + " WHERE tenant_id=? AND command_type=? AND idem_key=? AND claim_epoch=? AND state='IN_PROGRESS'",
                    httpStatus, sealBody(claim.tenantId(), claim.commandType(), claim.key(), responseBody),
                    responseRetention.toMillis(), keyRetention.toMillis(),
                    claim.tenantId(), claim.commandType(), claim.key(), claim.epoch());
            if (n == 0) {
                throw new ClaimLostException(claim);
            }
            return null;
        });
    }

    /** Step 3, transient result or 401/403: nothing is stored, the claim row is deleted so a retry executes at once. */
    public void abandon(Claim claim) {
        tx.inTransaction(c -> Jdbc.update(c,
                "DELETE FROM IDEMPOTENCY_RECORD WHERE tenant_id=? AND command_type=? AND idem_key=? AND claim_epoch=? AND state='IN_PROGRESS'",
                claim.tenantId(), claim.commandType(), claim.key(), claim.epoch()));
    }

    /** Step 5 cleanup: compacts rows past response retention and deletes rows past key retention. Returns rows touched. */
    public int cleanup() {
        return tx.inTransaction(c -> {
            int compacted = Jdbc.update(c, "UPDATE IDEMPOTENCY_RECORD SET state='COMPACTED', response_body=NULL"
                    + " WHERE state IN ('COMPLETED','FAILED_FINAL') AND response_expires_at < " + dialect.now());
            int deleted = Jdbc.update(c, "DELETE FROM IDEMPOTENCY_RECORD WHERE state <> 'IN_PROGRESS' AND key_expires_at < " + dialect.now());
            return compacted + deleted;
        });
    }

    private static void validateKey(String key) {
        if (key == null || key.isEmpty() || key.length() > 128) {
            throw new IllegalArgumentException("400 IDEMPOTENCY_KEY_INVALID: length must be 1..128");
        }
        for (int i = 0; i < key.length(); i++) {
            char ch = key.charAt(i);
            if (ch < 0x20 || ch > 0x7e) {
                throw new IllegalArgumentException("400 IDEMPOTENCY_KEY_INVALID: printable ASCII only");
            }
        }
    }
}
