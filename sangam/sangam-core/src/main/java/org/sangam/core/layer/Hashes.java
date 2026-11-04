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

    /** RFC 4122 §4.3 version-5 (SHA-1) UUID of {@code name} in {@code namespace}. */
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

    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();

    /** RFC 9562 UUIDv7: 48-bit Unix milliseconds, version 7, random tail (roughly time-ordered event ids). */
    public static UUID uuidV7() {
        long ms = System.currentTimeMillis();
        long msb = (ms << 16) | 0x7000L | (RANDOM.nextInt() & 0x0fffL);
        long lsb = (RANDOM.nextLong() & 0x3fffffffffffffffL) | 0x8000000000000000L;
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
