// SPDX-License-Identifier: Apache-2.0
package org.sangam.field.sync;

import org.sangam.field.FieldException;

import java.util.Base64;

/**
 * The encrypted local-queue contract every Sangam field client (PWA, Android, iOS, desktop) implements. The server
 * never sees the queue; it defines the format so clients interoperate and an audit can check them.
 *
 * <ol>
 *   <li><b>Key.</b> A random 256-bit queue key per device installation, generated on the device and held only in the
 *       platform keystore (Android Keystore / iOS Keychain / WebCrypto non-extractable key in IndexedDB). The server
 *       issues a {@code queueKeyId} label at registration for rotation tracking; it never holds the key.</li>
 *   <li><b>Entry.</b> Each queued packet is the JSON of a {@code CapturePacket} with {@code packetHash} already set,
 *       encrypted with AES-256-GCM, 96-bit random IV, 128-bit tag. AAD = UTF-8 of
 *       {@code tenantId + "|" + deviceId + "|" + operationId + "|" + userId} ({@link #aad}), so an entry cannot be moved
 *       between tenants or devices, replayed under another operation id, or opened for another user. {@code userId} is
 *       the signed-in user who captured the entry (SL-129, owner decision 2026-11-12: one queue key per device, the
 *       user bound into every entry). Under any other login the GCM tag fails: the entry stays encrypted, hidden and
 *       unreplayed, and survives logout until the same user signs in again and syncs it.</li>
 *   <li><b>Envelope.</b> {@code {"v":1,"kid":queueKeyId,"op":operationId,"iv":b64,"ct":b64,"createdAt":iso}};
 *       {@link Envelope} is the server-side shape.</li>
 *   <li><b>operationId.</b> Stable from the moment of capture: UUIDv5(namespace = deviceId UUID, name = deviceSeq) or a
 *       UUIDv7 created once and stored in the entry. Retries reuse it; the server deduplicates by (tenant, operationId)
 *       (R41) and returns the stored result for a replay.</li>
 *   <li><b>Order.</b> Upload in deviceSeq order; delete an entry only after the server answered NEW or REPLAY.
 *       CONFLICT and REJECTED entries are kept and surfaced to the user until the server resolves them.</li>
 *   <li><b>Revocation.</b> If a sync or pull answers DEVICE_REVOKED and the policy says {@code wipeOnRevoke}, the
 *       client destroys the queue key (crypto-erase) and every entry with it.</li>
 *   <li><b>Media.</b> Photo bytes are stored encrypted the same way (AAD = the entry AAD above followed by
 *       {@code "|" + evidenceId}); the packet carries their SHA-256, which is computed before encryption.</li>
 * </ol>
 */
public final class LocalQueueContract {

    public static final int VERSION = 1;
    public static final String CIPHER = "AES/GCM/NoPadding";
    public static final int KEY_BITS = 256;
    public static final int IV_BYTES = 12;
    public static final int TAG_BITS = 128;

    private LocalQueueContract() {
    }

    public record Envelope(int v, String kid, String op, String iv, String ct, String createdAt) {
        public void validate() {
            if (v != VERSION) {
                throw new FieldException("INVALID_ENVELOPE", "unsupported queue envelope version " + v);
            }
            if (op == null || op.isBlank() || iv == null || ct == null) {
                throw new FieldException("INVALID_ENVELOPE", "envelope needs op, iv and ct");
            }
            if (Base64.getDecoder().decode(iv).length != IV_BYTES) {
                throw new FieldException("INVALID_ENVELOPE", "IV must be " + IV_BYTES + " bytes");
            }
        }
    }

    /**
     * The AAD of one queue entry: {@code tenantId|deviceId|operationId|userId}. Every part is required: an entry
     * without a user binding would open under any login.
     */
    public static String aad(String tenantId, String deviceId, String operationId, String userId) {
        return require(tenantId, "tenantId") + "|" + require(deviceId, "deviceId") + "|" + require(operationId, "operationId")
                + "|" + require(userId, "userId");
    }

    private static String require(String part, String name) {
        if (part == null || part.isEmpty()) {
            throw new FieldException("INVALID_BINDING", "queue entry AAD needs " + name);
        }
        return part;
    }
}
