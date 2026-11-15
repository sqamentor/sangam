// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.sangam.core.layer.Hashes;

/**
 * One UUIDv5 implementation ({@link Hashes#uuidV5}); {@link OperationIdentity#uuidV5} delegates to it. Operation ids are
 * persisted (ledger, tombstones, worker leases), so the delegation must not change a single id: the values are compared
 * with the implementation {@code OperationIdentity} carried before, kept here as {@link #legacy}, and with literals
 * computed by it.
 */
class UuidV5SingleImplementationTest {

    private static final UUID DNS = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8");
    private static final UUID URL = UUID.fromString("6ba7b811-9dad-11d1-80b4-00c04fd430c8");

    /** RFC 9562 Appendix A.4: UUIDv5 of "www.example.com" in the DNS namespace. */
    @Test
    void rfc9562TestVector() {
        UUID expected = UUID.fromString("2ed6657d-e927-568b-95e1-2665a8aea6a2");
        assertEquals(expected, Hashes.uuidV5(DNS, "www.example.com"));
        assertEquals(expected, OperationIdentity.uuidV5(DNS, "www.example.com"));
        assertEquals(5, expected.version());
        assertEquals(2, expected.variant());
    }

    @Test
    void theOperationNamespaceIsUnchanged() {
        assertEquals(UUID.fromString("ff539a00-48fc-502b-8d12-d4a360a546dc"), OperationIdentity.NAMESPACE);
        assertEquals(OperationIdentity.NAMESPACE, legacy(URL, "urn:sangam:operation-id:v2-business-key"));
    }

    @Test
    void persistedOperationIdsAreUnchanged() {
        assertEquals(UUID.fromString("be68ec70-229e-5260-8b30-e3efd9e26960"),
                OperationIdentity.of("t1", "bk-1", "payment", OperationIdentity.scope(0, null, null)));
        assertEquals(UUID.fromString("66823a2d-ef13-5018-a473-ce2780575e01"),
                OperationIdentity.of(null, "root-1", "approve", OperationIdentity.scope(3, 2, "abc")));
        // non-ASCII tenant and business key (UTF-8 name bytes), and a separator inside a component
        assertEquals(UUID.fromString("0495b45b-bbf6-5544-aeb9-95cb65cbd82b"),
                OperationIdentity.of("राज", "बिल-१", "a|b",
                        OperationIdentity.scope(0, null, null)));
    }

    @Test
    void bothEntryPointsEqualTheFormerImplementationForManyInputs() {
        List<UUID> namespaces = List.of(DNS, URL, OperationIdentity.NAMESPACE, new UUID(0L, 0L), new UUID(-1L, -1L),
                new UUID(Long.MIN_VALUE, Long.MAX_VALUE));
        List<String> names = List.of("", "a", "www.example.com", "op-v2|2:t1|4:bk-1|7:payment|7:v0|l-|k",
                "राजस्थान", "😀 surrogate pair", "x".repeat(10_000),
                "line\nbreak\ttab\u0000nul");
        for (UUID ns : namespaces) {
            for (String name : names) {
                UUID old = legacy(ns, name);
                assertEquals(old, Hashes.uuidV5(ns, name), () -> "Hashes.uuidV5 " + ns + " / " + name.length());
                assertEquals(old, OperationIdentity.uuidV5(ns, name), () -> "OperationIdentity.uuidV5 " + ns);
            }
        }
    }

    /** The UUIDv5 code that {@code OperationIdentity} held before it delegated to {@code Hashes} (byte for byte). */
    private static UUID legacy(UUID namespace, String name) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] b = new byte[16];
            long m = namespace.getMostSignificantBits();
            long l = namespace.getLeastSignificantBits();
            for (int i = 0; i < 8; i++) {
                b[i] = (byte) (m >>> (56 - 8 * i));
                b[8 + i] = (byte) (l >>> (56 - 8 * i));
            }
            sha1.update(b);
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
}
