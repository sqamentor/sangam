// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.engine;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * Business-operation identity derived from the business key (T35, X.0d pending amendment 1, R36):
 * {@code operationId = UUIDv5(NAMESPACE, tenant | businessKey | activityId | scope)}.
 *
 * <p>Unlike {@code OperationLedger.operationId(tenant, rootId, activityInstanceId, ...)} it does not depend on engine
 * ids, so it survives a database restore that re-creates the instance (T35: the broker holds newer messages than the
 * restored DB) as well as retries, redelivery, lease expiry and migration. {@code scope} separates legitimate repeats
 * of the same activity in the same business case (A-2): the engine adapter builds it from the visit number of the
 * activity, the multi-instance loop counter and an optional model-declared retry-safe key, see {@link #scope}.
 * When an instance has no business key, the root process instance id is used in its place (documented weaker form).
 */
public final class OperationIdentity {

    /** UUIDv5(URL namespace, "urn:sangam:operation-id:v2-business-key"). */
    public static final UUID NAMESPACE = uuidV5(UUID.fromString("6ba7b811-9dad-11d1-80b4-00c04fd430c8"),
            "urn:sangam:operation-id:v2-business-key");

    private OperationIdentity() {
    }

    /** The operation id; every component is length-prefixed, so no separator can be forged. */
    public static UUID of(String tenant, String businessKey, String activityId, String scope) {
        StringBuilder name = new StringBuilder("op-v2");
        for (String part : new String[]{nz(tenant), req(businessKey, "businessKey"), req(activityId, "activityId"),
                nz(scope)}) {
            name.append('|').append(part.length()).append(':').append(part);
        }
        return uuidV5(NAMESPACE, name.toString());
    }

    /**
     * THE operation id of one engine work item (T35). Every path that hands out, records or lists the operation id
     * of engine work (the worker API, the engine adapter's lease, ledger intents, delete tombstones, consequence
     * previews, reconciliation) derives it here, from engine-neutral facts of the work item; there is no second
     * derivation from engine ids.
     *
     * @param businessKey     the instance's business key; null/empty falls back to {@code rootId} (documented weaker form)
     * @param completedVisits finished earlier visits of {@code activityId} in the instance (0 on the first visit)
     * @param loopCounter     the multi-instance loop counter, or null
     * @param retrySafeKey    the value of the variable named by {@code sangam:retrySafeKey}, or null
     */
    public static UUID forWork(String tenant, String businessKey, String rootId, String activityId,
                               long completedVisits, Integer loopCounter, String retrySafeKey) {
        String bk = businessKey != null && !businessKey.isEmpty() ? businessKey : rootId;
        return of(tenant, bk, activityId, scope(completedVisits, loopCounter, retrySafeKey));
    }

    /**
     * The attempt scope:{@code v<visit>|l<loopCounter>|k<retrySafeKey>}. {@code visit} counts earlier completed
     * visits of the activity in the same root (0 on the first visit), so a return-and-resubmit loop gets a new
     * operation, while a retry or a lease expiry of the same visit keeps the old one.
     */
    public static String scope(long visit, Integer loopCounter, String retrySafeKey) {
        return "v" + visit + "|l" + (loopCounter == null ? "-" : loopCounter)
                + "|k" + (retrySafeKey == null ? "" : retrySafeKey);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String req(String s, String what) {
        if (s == null || s.isEmpty()) {
            throw new IllegalArgumentException("operation identity: " + what + " is required");
        }
        return s;
    }

    /** RFC 9562 UUID version 5 (SHA-1, name-based). */
    public static UUID uuidV5(UUID namespace, String name) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update(toBytes(namespace));
            byte[] h = sha1.digest(name.getBytes(StandardCharsets.UTF_8));
            h[6] = (byte) ((h[6] & 0x0f) | 0x50);
            h[8] = (byte) ((h[8] & 0x3f) | 0x80);
            long msb = 0;
            long lsb = 0;
            for (int i = 0; i < 8; i++) {
                msb = (msb << 8) | (h[i] & 0xff);
            }
            for (int i = 8; i < 16; i++) {
                lsb = (lsb << 8) | (h[i] & 0xff);
            }
            return new UUID(msb, lsb);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] toBytes(UUID u) {
        byte[] b = new byte[16];
        long m = u.getMostSignificantBits();
        long l = u.getLeastSignificantBits();
        for (int i = 0; i < 8; i++) {
            b[i] = (byte) (m >>> (56 - 8 * i));
            b[8 + i] = (byte) (l >>> (56 - 8 * i));
        }
        return b;
    }
}
