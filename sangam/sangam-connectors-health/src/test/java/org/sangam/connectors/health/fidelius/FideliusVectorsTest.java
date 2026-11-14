// SPDX-License-Identifier: Apache-2.0
package org.sangam.connectors.health.fidelius;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Decision 243: Fidelius on Bouncy Castle against the MIT test vectors of abdm_fidelius (generated with fidelius-cli
 * 1.2.0 on Bouncy Castle). {@code test_vectors.json} is a byte-exact script copy of
 * {@code abdm_fidelius/test/fixtures/test_vectors.json} at commit 2549a0e8e1c119df611bfa3e286606301232b0d5 (record in
 * DOWNLOADS-WAVE4.md, 2026-11-13T04:01:49Z), never edited; its MIT notice is {@code LICENSE-abdm_fidelius.txt}.
 * Interoperability evidence only: no ABDM conformance is claimed.
 */
class FideliusVectorsTest {

    /** SHA-256 of the fetched file as recorded in DOWNLOADS-WAVE4.md; the copy must be byte-exact. */
    static final String VECTORS_SHA256 = "DD753875634D1E5A00AA2C0327AB345B2D7CA12DCB9893085FE30591D5B88C71";
    static final String VECTORS = "test_vectors.json";
    private static final Base64.Decoder B64 = Base64.getDecoder();

    record Side(byte[] privateKey, byte[] publicKey, byte[] nonce, byte[] x509) {
    }

    record Vector(String name, Side sender, Side receiver, String plaintext, byte[] encrypted, byte[] sharedSecret) {
        @Override
        public String toString() {
            return name;
        }
    }

    static byte[] resource() throws Exception {
        try (InputStream in = FideliusVectorsTest.class.getResourceAsStream(VECTORS)) {
            assertNotNull(in, "test vector resource");
            return in.readAllBytes();
        }
    }

    static Stream<Vector> vectors() throws Exception {
        JsonNode root = new ObjectMapper().readTree(resource());
        List<Vector> out = new ArrayList<>();
        for (JsonNode v : root.path("vectors")) {
            assertEquals("curve25519", v.path("curve").asText(), v.path("name").asText());
            JsonNode secret = v.path("intermediates").path("sharedSecret");
            out.add(new Vector(v.path("name").asText(),
                    side(v.path("sender"), v.path("senderX509PublicKey")),
                    side(v.path("receiver"), v.path("receiverX509PublicKey")),
                    v.path("plaintext").asText(), B64.decode(v.path("encryptedData").asText()),
                    secret.isMissingNode() ? null : B64.decode(secret.asText())));
        }
        return out.stream();
    }

    private static Side side(JsonNode n, JsonNode x509) {
        return new Side(B64.decode(n.path("privateKey").asText()), B64.decode(n.path("publicKey").asText()),
                B64.decode(n.path("nonce").asText()), x509.isMissingNode() ? null : B64.decode(x509.asText()));
    }

    @Test
    void theVectorsAreTheRecordedByteExactCopyWithEveryVector() throws Exception {
        assertEquals(VECTORS_SHA256,
                HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-256").digest(resource())),
                "test_vectors.json must stay the byte-exact copy recorded in DOWNLOADS-WAVE4.md");
        assertEquals(List.of("canonical_readme", "simple_ascii", "single_char", "fhir_json", "special_chars",
                "repeated_data", "canonical_x509_pubkey"), vectors().map(Vector::name).toList());
        assertNotNull(FideliusVectorsTest.class.getResourceAsStream("LICENSE-abdm_fidelius.txt"), "MIT notice kept");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vectors")
    void eachPublicKeyIsItsPrivateKeyTimesTheBasePoint(Vector v) {
        assertArrayEquals(v.sender().publicKey(), Fidelius.publicKey(v.sender().privateKey()));
        assertArrayEquals(v.receiver().publicKey(), Fidelius.publicKey(v.receiver().privateKey()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vectors")
    void bothSidesAgreeOnTheSharedSecret(Vector v) {
        byte[] s = Fidelius.sharedSecret(v.sender().privateKey(), v.receiver().publicKey());
        assertArrayEquals(s, Fidelius.sharedSecret(v.receiver().privateKey(), v.sender().publicKey()));
        assertEquals(32, s.length);
        if (v.sharedSecret() != null) {
            assertArrayEquals(v.sharedSecret(), s, "the vector's intermediate shared secret");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vectors")
    void theReceiverDecryptsTheVectorAndTheSenderReproducesItExactly(Vector v) throws Exception {
        byte[] plain = v.plaintext().getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(plain, Fidelius.decrypt(v.encrypted(), v.receiver().privateKey(), v.receiver().nonce(),
                v.sender().publicKey(), v.sender().nonce()));
        assertArrayEquals(v.encrypted(), Fidelius.encrypt(plain, v.sender().privateKey(), v.sender().nonce(),
                v.receiver().publicKey(), v.receiver().nonce()), "deterministic for the same keys and nonces");
    }

    @Test
    void theX509FormOfTheCanonicalKeysIsTheSamePoint() throws Exception {
        Vector v = vectors().filter(x -> x.name().equals("canonical_x509_pubkey")).findFirst().orElseThrow();
        byte[] sender = Fidelius.publicKeyFromX509(v.sender().x509());
        assertArrayEquals(v.sender().publicKey(), sender);
        assertArrayEquals(v.receiver().publicKey(), Fidelius.publicKeyFromX509(v.receiver().x509()));
        assertArrayEquals(v.plaintext().getBytes(StandardCharsets.UTF_8), Fidelius.decrypt(v.encrypted(),
                v.receiver().privateKey(), v.receiver().nonce(), sender, v.sender().nonce()));
        assertArrayEquals(sender, Fidelius.publicKeyFromX509(Fidelius.x509PublicKey(sender)), "own X.509 form");
        // decision 243: our X.509 form is byte for byte the reference's (fidelius-cli on Bouncy Castle)
        assertArrayEquals(v.sender().x509(), Fidelius.x509PublicKey(v.sender().publicKey()), "sender X.509 bytes");
        assertArrayEquals(v.receiver().x509(), Fidelius.x509PublicKey(v.receiver().publicKey()), "receiver X.509 bytes");
    }

    @Test
    void alteredDataOrAWrongNonceOrKeyIsRefused() throws Exception {
        Vector v = vectors().filter(x -> x.name().equals("simple_ascii")).findFirst().orElseThrow();
        byte[] altered = v.encrypted().clone();
        altered[0] ^= 1;
        assertThrows(Fidelius.FideliusException.class, () -> Fidelius.decrypt(altered, v.receiver().privateKey(),
                v.receiver().nonce(), v.sender().publicKey(), v.sender().nonce()));
        byte[] nonce = v.sender().nonce().clone();
        nonce[31] ^= 1;
        assertThrows(Fidelius.FideliusException.class, () -> Fidelius.decrypt(v.encrypted(),
                v.receiver().privateKey(), v.receiver().nonce(), v.sender().publicKey(), nonce));
        Fidelius.KeyPair other = Fidelius.generateKeyPair(new SecureRandom());
        assertThrows(Fidelius.FideliusException.class, () -> Fidelius.decrypt(v.encrypted(), other.privateKey(),
                v.receiver().nonce(), v.sender().publicKey(), v.sender().nonce()));
    }

    @Test
    void malformedKeysAndNoncesAreRefusedNeverTransformed() {
        Fidelius.KeyPair kp = Fidelius.generateKeyPair(new SecureRandom());
        byte[] offCurve = kp.publicKey();
        offCurve[64] ^= 1;
        assertThrows(IllegalArgumentException.class, () -> Fidelius.sharedSecret(kp.privateKey(), offCurve));
        assertThrows(IllegalArgumentException.class, () -> Fidelius.sharedSecret(kp.privateKey(), new byte[33]));
        assertThrows(IllegalArgumentException.class, () -> Fidelius.publicKey(new byte[32]), "zero scalar");
        byte[] n = BigInteger.ONE.shiftLeft(255).toByteArray();
        assertThrows(IllegalArgumentException.class,
                () -> Fidelius.publicKey(java.util.Arrays.copyOfRange(n, n.length - 32, n.length)), "scalar >= n");
        assertThrows(IllegalArgumentException.class, () -> Fidelius.encrypt(new byte[1], kp.privateKey(), new byte[31],
                kp.publicKey(), new byte[32]));
        assertThrows(IllegalArgumentException.class, () -> Fidelius.publicKeyFromX509(new byte[] {0x30, 0x00}));
    }

    @Test
    void ownKeyPairsRoundTripAndNeverPrintThePrivateKey() throws Exception {
        SecureRandom r = new SecureRandom();
        Fidelius.KeyPair a = Fidelius.generateKeyPair(r);
        Fidelius.KeyPair b = Fidelius.generateKeyPair(r);
        assertArrayEquals(a.publicKey(), Fidelius.publicKey(a.privateKey()));
        byte[] na = new byte[32];
        byte[] nb = new byte[32];
        r.nextBytes(na);
        r.nextBytes(nb);
        byte[] msg = "{\"resourceType\":\"Bundle\"}".getBytes(StandardCharsets.UTF_8);
        byte[] enc = Fidelius.encrypt(msg, a.privateKey(), na, b.publicKey(), nb);
        assertArrayEquals(msg, Fidelius.decrypt(enc, b.privateKey(), nb, a.publicKey(), na));
        assertFalse(a.toString().contains(HexFormat.of().formatHex(a.privateKey())));
        assertTrue(a.toString().contains("publicKey"));
    }
}
