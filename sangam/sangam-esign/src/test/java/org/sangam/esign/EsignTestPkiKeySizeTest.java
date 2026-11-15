// SPDX-License-Identifier: Apache-2.0
package org.sangam.esign;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.security.KeyPair;
import java.security.interfaces.RSAPublicKey;

import org.bouncycastle.asn1.x509.KeyUsage;
import org.junit.jupiter.api.Test;

/**
 * The test PKI hands out a weak RSA key only through a method whose name says so. The ordinary helpers
 * ({@link EsignTestPki#rsa}, {@link EsignTestPki#user(String, EsignTestPki.Party, int, int, java.time.Instant)}) refuse
 * anything below {@link EsignTestPki#MIN_RSA_BITS} bits, so a test cannot pick up a weak key by passing a number.
 */
class EsignTestPkiKeySizeTest {

    private static int bits(KeyPair kp) {
        return ((RSAPublicKey) kp.getPublic()).getModulus().bitLength();
    }

    @Test
    void theOrdinaryHelperRefusesAKeyBelowTheMinimum() throws Exception {
        for (int weak : new int[]{512, 1024, 2047}) {
            assertThrows(IllegalArgumentException.class, () -> EsignTestPki.rsa(weak), String.valueOf(weak));
        }
        assertEquals(2048, bits(EsignTestPki.rsa(2048)));
    }

    @Test
    void aWeakKeyComesOnlyFromTheNamedMethod() throws Exception {
        assertEquals(1024, bits(EsignTestPki.weakRsaForNegativeTest(1024)));
        assertThrows(IllegalArgumentException.class, () -> EsignTestPki.weakRsaForNegativeTest(2048),
                "the weak-key method is not a second way to make an ordinary key");
    }

    @Test
    void aSignerWithAWeakKeyNeedsTheNamedMethodToo() throws Exception {
        try (EsignTestPki pki = new EsignTestPki(() -> java.time.Instant.parse("2026-06-01T10:00:00Z"))) {
            assertThrows(IllegalArgumentException.class,
                    () -> pki.user("A", pki.ca(), KeyUsage.digitalSignature, 1024, EsignTestPki.NOT_AFTER));
            EsignTestPki.Party weak = pki.userWithWeakKeyForNegativeTest("A", pki.ca(), KeyUsage.digitalSignature, 1024,
                    EsignTestPki.NOT_AFTER);
            assertEquals(1024, ((RSAPublicKey) weak.cert().getPublicKey()).getModulus().bitLength());
            assertEquals(2048, ((RSAPublicKey) pki.user("A").cert().getPublicKey()).getModulus().bitLength());
        }
    }
}
