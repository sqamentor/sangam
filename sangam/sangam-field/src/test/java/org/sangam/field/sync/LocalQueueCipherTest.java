// SPDX-License-Identifier: Apache-2.0
package org.sangam.field.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.sangam.field.FieldException;
import org.sangam.field.FieldJson;
import org.sangam.field.evidence.CapturePacket;
import org.sangam.field.evidence.PacketHasher;

/**
 * SL-129 (owner decision 2026-11-12): the local-queue AAD is {@code tenantId|deviceId|operationId|userId}, and the JVM
 * reference cipher interoperates with the PWA ({@code sangam-ui/src/lib/offline}). The PWA's pinned vectors are read
 * from its test sources ({@code crypto.test.ts}, {@code canonical.test.ts}), so a change on either side fails here.
 */
class LocalQueueCipherTest {

    // ---------------------------------------------------------------- the PWA's pinned vectors

    private static Path offlineDir() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (Path p = dir; p != null; p = p.getParent()) {
            Path candidate = p.resolve("sangam-ui/src/lib/offline");
            if (Files.isRegularFile(candidate.resolve("crypto.test.ts"))) {
                return candidate;
            }
        }
        throw new AssertionError("sangam-ui/src/lib/offline not found above " + dir);
    }

    private static String ts(String file) throws IOException {
        return Files.readString(offlineDir().resolve(file), StandardCharsets.UTF_8);
    }

    private static Matcher find(String source, String regex) {
        Matcher m = Pattern.compile(regex).matcher(source);
        assertTrue(m.find(), "pinned vector not found in the PWA test: " + regex);
        return m;
    }

    @Test
    void theAadMatchesThePwasPinnedBinding() throws IOException {
        String crypto = ts("crypto.test.ts");
        Matcher who = find(crypto, "const who: Binding = \\{ tenantId: '([^']*)', deviceId: '([^']*)', userId: '([^']*)' \\}");
        Matcher pinned = find(crypto, "expect\\(aad\\(who, '([^']*)'\\)\\)\\.toBe\\('([^']*)'\\)");
        String aad = LocalQueueContract.aad(who.group(1), who.group(2), pinned.group(1), who.group(3));
        assertEquals(pinned.group(2), aad);
        assertEquals("t1|d1|op-1|u1", aad, "contract order: tenant, device, operation, user");
    }

    @Test
    void theUuidV5RuleMatchesThePwasPinnedVector() throws Exception {
        Matcher v = find(ts("crypto.test.ts"), "uuidV5\\(c\\.subtle, '([^']*)', '([^']*)'\\)\\)\\.toBe\\('([^']*)'\\)");
        assertEquals(v.group(3), uuidV5(UUID.fromString(v.group(1)), v.group(2)).toString());
        assertEquals("886313e1-3b8a-5372-9b90-0c9aee199e5d",
                uuidV5(UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8"), "python.org").toString());
    }

    @Test
    void packetHashesMatchThePwasPinnedPackets() throws Exception {
        String canonical = ts("canonical.test.ts");
        String sampleHash = find(canonical, "packetHash\\(subtle, sample\\)\\)\\.toBe\\('([0-9a-f]{64})'\\)").group(1);
        String edgeHash = find(canonical, "packetHash\\(subtle, edge\\)\\)\\.toBe\\('([0-9a-f]{64})'\\)").group(1);

        // JSON.stringify(sample) as the PWA uploads it
        CapturePacket sample = FieldJson.read("{\"packetId\":\"op-1\",\"type\":\"VISIT_SUBMIT\",\"operationId\":\"op-1\","
                + "\"deviceId\":\"dev-1\",\"deviceSeq\":1,\"appVersion\":\"0.1.0\",\"assignmentId\":\"a-1\",\"baseVersion\":3,"
                + "\"deviceTime\":\"2026-11-12T10:00:00Z\",\"answers\":{\"q1\":true,\"q2\":2.5,"
                + "\"q3\":\"\u0928\u092e\u0938\u094d\u0924\u0947 \\\"x\\\"\\n\",\"q4\":null,\"q5\":[1,null,\"b\"]},"
                + "\"fix\":{\"lat\":26.9,\"lon\":75.8,\"accuracyM\":12}}", CapturePacket.class);
        assertEquals("{\"answers\":{\"q1\":true,\"q2\":\"2.5\",\"q3\":\"\u0928\u092e\u0938\u094d\u0924\u0947 \\\"x\\\"\\n\","
                + "\"q5\":[\"1\",null,\"b\"]},\"appVersion\":\"0.1.0\",\"assignmentId\":\"a-1\",\"baseVersion\":\"3\","
                + "\"deviceId\":\"dev-1\",\"deviceSeq\":\"1\",\"deviceTime\":\"2026-11-12T10:00:00Z\",\"fix\":{\"accuracyM\":\"12\","
                + "\"lat\":\"26.9\",\"lon\":\"75.8\",\"mockLocation\":false},\"media\":[],\"operationId\":\"op-1\",\"packetId\":\"op-1\","
                + "\"payload\":{},\"type\":\"VISIT_SUBMIT\"}", PacketHasher.canonical(sample));
        assertEquals(sampleHash, PacketHasher.hash(sample));
        assertEquals("3912dba8d24a0e4c484628fe4ef98e46cb9df8bf278ac9a7bf7be1071ec37e6d", sampleHash);

        // JSON.stringify(edge): operationId = UUIDv5(deviceId, "7") per the contract
        String ns = "6ba7b810-9dad-11d1-80b4-00c04fd430c8";
        String op = uuidV5(UUID.fromString(ns), "7").toString();
        CapturePacket edge = FieldJson.read("{\"packetId\":\"" + op + "\",\"type\":\"VISIT_SUBMIT\",\"operationId\":\"" + op + "\","
                + "\"deviceId\":\"" + ns + "\",\"deviceSeq\":7,\"assignmentId\":\"a-2\",\"deviceTime\":\"2026-11-12T10:00:00.120Z\","
                + "\"answers\":{\"big\":1e+21,\"tiny\":1e-7,\"neg\":0,\"f\":0.30000000000000004,\"int\":100,"
                + "\"ctl\":\"a\\u0001b\\tc\\\\d\",\"sur\":\"x\\ud800y\",\"list\":[\"A\",\"B\"],\"nested\":{\"z\":1,\"a\":{\"b\":false}}},"
                + "\"notes\":\"line1\\r\\nline2\",\"fix\":{\"lat\":26,\"lon\":-75.123456789,\"accuracyM\":3.5,\"altitudeM\":400.25,"
                + "\"provider\":\"gps\",\"mockLocation\":true,\"fixTime\":\"2026-11-12T09:59:59Z\"},\"payload\":{\"k\":\"v\"},"
                + "\"prevHash\":\"" + "ab".repeat(32) + "\"}", CapturePacket.class);
        assertEquals(edgeHash, PacketHasher.hash(edge));
        assertEquals("7bc2e9d7d0bbeedb8a639c62caeadb29e6c8611720983433144598fa8c261800", edgeHash);
    }

    // ---------------------------------------------------------------- AES-GCM interop

    /** Key 00..1f, IV 00..0b, AAD "t1|d1|op-1|u1", plaintext {"secret":"नोट"}: sealed by an independent AES-GCM. */
    static final String VECTOR_IV = "AAECAwQFBgcICQoL";
    static final String VECTOR_CT = "PCClfqaXp2+ve7VrFUGYyAg2I6vSBu3HjGhP5Qq6HvYjKPLVXM0=";
    static final String SECRET = "{\"secret\":\"\u0928\u094b\u091f\"}";

    static SecretKey vectorKey() {
        byte[] k = new byte[32];
        for (int i = 0; i < k.length; i++) {
            k[i] = (byte) i;
        }
        return new SecretKeySpec(k, "AES");
    }

    static LocalQueueContract.Envelope vector() {
        return new LocalQueueContract.Envelope(1, "qk-1", "op-1", VECTOR_IV, VECTOR_CT, "2026-11-12T10:00:00.000Z");
    }

    @Test
    void opensAnEntrySealedWithTheUserBoundAadByAnotherImplementation() {
        LocalQueueCipher cipher = new LocalQueueCipher(vectorKey(), "qk-1");
        assertEquals(SECRET, cipher.open("t1", "d1", "u1", vector()));
    }

    @Test
    void anEntryOpensOnlyForTheSameTenantDeviceOperationAndUser() {
        LocalQueueCipher cipher = new LocalQueueCipher(LocalQueueCipher.newKey(), "qk-1");
        LocalQueueContract.Envelope env = cipher.seal("t1", "d1", "op-1", "u1", SECRET);
        assertEquals(1, env.v());
        assertEquals("qk-1", env.kid());
        assertEquals("op-1", env.op());
        assertEquals(12, Base64.getDecoder().decode(env.iv()).length);
        assertEquals(SECRET.getBytes(StandardCharsets.UTF_8).length + 16, Base64.getDecoder().decode(env.ct()).length, "128-bit tag");
        assertEquals(SECRET, cipher.open("t1", "d1", "u1", env));

        assertTampered(() -> cipher.open("t1", "d1", "u2", env), "another user (logout, then a different login)");
        assertTampered(() -> cipher.open("t2", "d1", "u1", env), "another tenant");
        assertTampered(() -> cipher.open("t1", "d2", "u1", env), "another device");
        LocalQueueContract.Envelope otherOp = new LocalQueueContract.Envelope(env.v(), env.kid(), "op-2", env.iv(), env.ct(), env.createdAt());
        assertTampered(() -> cipher.open("t1", "d1", "u1", otherOp), "another operation id");
        assertTampered(() -> new LocalQueueCipher(LocalQueueCipher.newKey(), "qk-2").open("t1", "d1", "u1", env), "another key");
    }

    @Test
    void anEntrySealedWithoutTheUserNoLongerOpens() throws Exception {
        // the pre-SL-129 AAD tenant|device|operation: such an entry must not open for any user
        byte[] iv = Base64.getDecoder().decode(VECTOR_IV);
        Cipher c = Cipher.getInstance(LocalQueueContract.CIPHER);
        c.init(Cipher.ENCRYPT_MODE, vectorKey(), new GCMParameterSpec(LocalQueueContract.TAG_BITS, iv));
        c.updateAAD("t1|d1|op-1".getBytes(StandardCharsets.UTF_8));
        String ct = Base64.getEncoder().encodeToString(c.doFinal(SECRET.getBytes(StandardCharsets.UTF_8)));
        assertNotEquals(VECTOR_CT, ct);
        LocalQueueContract.Envelope old = new LocalQueueContract.Envelope(1, "qk-1", "op-1", VECTOR_IV, ct, "2026-11-12T10:00:00.000Z");
        assertTampered(() -> new LocalQueueCipher(vectorKey(), "qk-1").open("t1", "d1", "u1", old), "no user binding");
    }

    @Test
    void everyAadPartIsRequired() {
        LocalQueueCipher cipher = new LocalQueueCipher(LocalQueueCipher.newKey(), "qk-1");
        FieldException e = assertThrows(FieldException.class, () -> cipher.seal("t1", "d1", "op-1", null, SECRET));
        assertEquals("INVALID_BINDING", e.code());
        assertEquals("INVALID_BINDING", assertThrows(FieldException.class, () -> cipher.open("t1", "d1", "", vector())).code());
        assertEquals("INVALID_BINDING", assertThrows(FieldException.class, () -> LocalQueueContract.aad("", "d1", "op", "u1")).code());
        assertEquals("INVALID_BINDING", assertThrows(FieldException.class, () -> LocalQueueContract.aad("t1", null, "op", "u1")).code());
        assertEquals("INVALID_BINDING", assertThrows(FieldException.class, () -> LocalQueueContract.aad("t1", "d1", null, "u1")).code());
    }

    @Test
    void validatesTheEnvelopeAsThePwaDoes() {
        // crypto.test.ts 'validates the envelope as LocalQueueContract.Envelope.validate does'
        String iv12 = Base64.getEncoder().encodeToString("x".repeat(12).getBytes(StandardCharsets.UTF_8));
        String iv8 = Base64.getEncoder().encodeToString("x".repeat(8).getBytes(StandardCharsets.UTF_8));
        assertTrue(invalid(new LocalQueueContract.Envelope(2, "k", "o", iv12, "", "")).contains("version"));
        assertTrue(invalid(new LocalQueueContract.Envelope(1, "k", "", iv12, "", "")).contains("op"));
        assertTrue(invalid(new LocalQueueContract.Envelope(1, "k", "o", iv8, "", "")).contains("12 bytes"));
    }

    private static String invalid(LocalQueueContract.Envelope env) {
        FieldException e = assertThrows(FieldException.class, env::validate);
        assertEquals("INVALID_ENVELOPE", e.code());
        return e.getMessage();
    }

    private static void assertTampered(org.junit.jupiter.api.function.Executable open, String why) {
        FieldException e = assertThrows(FieldException.class, open, why);
        assertEquals("QUEUE_TAMPERED", e.code(), why);
    }

    /** RFC 4122 name-based UUID, version 5 (SHA-1), as LocalQueueContract item 4 and the PWA's uuidV5 use it. */
    static UUID uuidV5(UUID namespace, String name) throws Exception {
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        sha1.update(ByteBuffer.allocate(16).putLong(namespace.getMostSignificantBits())
                .putLong(namespace.getLeastSignificantBits()).array());
        byte[] h = sha1.digest(name.getBytes(StandardCharsets.UTF_8));
        h[6] = (byte) ((h[6] & 0x0f) | 0x50);
        h[8] = (byte) ((h[8] & 0x3f) | 0x80);
        ByteBuffer b = ByteBuffer.wrap(h, 0, 16);
        return new UUID(b.getLong(), b.getLong());
    }
}
