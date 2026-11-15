// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.layer;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/** SHA-256 and RFC 4122 name-based UUIDv5. */
public final class Hashes {

    private Hashes() {
    }

    public static byte[] sha256(byte[]... parts) {
        MessageDigest md = digest("SHA-256");
        for (byte[] p : parts) {
            md.update(p);
        }
        return md.digest();
    }

    public static byte[] sha256(String s) {
        return sha256(s.getBytes(StandardCharsets.UTF_8));
    }

    public static String hex(byte[] b) {
        return b == null ? null : HexFormat.of().formatHex(b);
    }

    /**
     * RFC 9562 §5.5 (RFC 4122 §4.3) version-5 (SHA-1) UUID of {@code name} in {@code namespace}. The only UUIDv5
     * implementation of the product: {@code OperationIdentity.uuidV5} delegates here.
     */
    public static UUID uuidV5(UUID namespace, String name) {
        MessageDigest md = digest("SHA-1");
        ByteBuffer ns = ByteBuffer.allocate(16);
        ns.putLong(namespace.getMostSignificantBits()).putLong(namespace.getLeastSignificantBits());
        md.update(ns.array());
        md.update(name.getBytes(StandardCharsets.UTF_8));
        byte[] h = md.digest();
        h[6] = (byte) ((h[6] & 0x0f) | 0x50);   // version 5
        h[8] = (byte) ((h[8] & 0x3f) | 0x80);   // IETF variant
        ByteBuffer bb = ByteBuffer.wrap(h, 0, 16);
        return new UUID(bb.getLong(), bb.getLong());
    }

    /**
     * RFC 9562 UUIDv7 now, from {@link IdSource#SYSTEM} (wall clock, {@code SecureRandom} tail). Code that can take an
     * {@link IdSource} should call {@link IdSource#timeOrderedUuid()} on the injected one instead (D11, M-097).
     */
    public static UUID uuidV7() {
        return IdSource.SYSTEM.timeOrderedUuid();
    }

    /**
     * RFC 9562 UUIDv7: 48-bit Unix milliseconds {@code epochMillis}, version 7, 12 + 62 random bits from {@code random}
     * (roughly time-ordered event ids). A pure function of its arguments.
     */
    public static UUID uuidV7(long epochMillis, java.util.random.RandomGenerator random) {
        long msb = (epochMillis << 16) | 0x7000L | (random.nextInt() & 0x0fffL);
        long lsb = (random.nextLong() & 0x3fffffffffffffffL) | 0x8000000000000000L;
        return new UUID(msb, lsb);
    }

    private static MessageDigest digest(String alg) {
        try {
            return MessageDigest.getInstance(alg);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
