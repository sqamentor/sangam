// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.job;

import java.nio.charset.StandardCharsets;

/**
 * M-056 (SANGAM.md R39, X.2.1.2): the fixed virtual slot of a job for partitioned executors,
 * {@code slot = murmur3_32(tenant_id || ':' || root_id) mod S}. Pure and stable: the slot is computed once when the
 * job is created, stored with the job and never recomputed, so it must not depend on the JVM, the locale or the
 * partition count. Hash: MurmurHash3 x86 32-bit, seed 0, over the UTF-8 bytes; the 32-bit result is read as unsigned
 * before the modulo.
 *
 * <p>Partitioned executors are off by default (X.2.1.1) and stay off until the T54 gate; this class has no effect on
 * job acquisition by itself.
 */
public final class ExecutorSlots {

    /** X.2.1.2 rule 1: the default number of slots of a cluster. */
    public static final int DEFAULT_SLOTS = 4_096;
    /** X.2.1.2 rule 1: the largest number of slots. */
    public static final int MAX_SLOTS = 65_536;

    private ExecutorSlots() {
    }

    /**
     * @param tenantId the job's tenant id (the engine's default tenant for untenanted jobs)
     * @param rootId   the root process instance id of the job
     * @param slots    the cluster's slot count {@code S}, 1 to {@link #MAX_SLOTS}
     * @return the slot, {@code 0 <= slot < slots}
     * @throws IllegalArgumentException on a null or blank id, or a slot count out of range
     */
    public static int slotOf(String tenantId, String rootId, int slots) {
        if (tenantId == null || tenantId.isBlank() || rootId == null || rootId.isBlank()) {
            throw new IllegalArgumentException("tenant id and root id are required for a job slot");
        }
        if (slots < 1 || slots > MAX_SLOTS) {
            throw new IllegalArgumentException("slot count must be 1.." + MAX_SLOTS + ": " + slots);
        }
        return Integer.remainderUnsigned(murmur3x86_32((tenantId + ":" + rootId).getBytes(StandardCharsets.UTF_8), 0),
                slots);
    }

    /** MurmurHash3 x86 32-bit (Austin Appleby, public domain reference algorithm). */
    static int murmur3x86_32(byte[] data, int seed) {
        final int c1 = 0xcc9e2d51;
        final int c2 = 0x1b873593;
        int h1 = seed;
        int blocks = data.length / 4;
        for (int i = 0; i < blocks; i++) {
            int k1 = (data[4 * i] & 0xff) | (data[4 * i + 1] & 0xff) << 8 | (data[4 * i + 2] & 0xff) << 16
                    | (data[4 * i + 3] & 0xff) << 24;
            k1 *= c1;
            k1 = Integer.rotateLeft(k1, 15);
            k1 *= c2;
            h1 ^= k1;
            h1 = Integer.rotateLeft(h1, 13);
            h1 = h1 * 5 + 0xe6546b64;
        }
        int tail = blocks * 4;
        int k1 = 0;
        switch (data.length & 3) {
            case 3:
                k1 ^= (data[tail + 2] & 0xff) << 16;
                // fall through
            case 2:
                k1 ^= (data[tail + 1] & 0xff) << 8;
                // fall through
            case 1:
                k1 ^= data[tail] & 0xff;
                k1 *= c1;
                k1 = Integer.rotateLeft(k1, 15);
                k1 *= c2;
                h1 ^= k1;
                break;
            default:
                break;
        }
        h1 ^= data.length;
        h1 ^= h1 >>> 16;
        h1 *= 0x85ebca6b;
        h1 ^= h1 >>> 13;
        h1 *= 0xc2b2ae35;
        h1 ^= h1 >>> 16;
        return h1;
    }
}
