// SPDX-License-Identifier: Apache-2.0
package org.sangam.compliance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Hashes;

/**
 * Owner decision 369 (e): the principal token of the audit payloads and the identifier hash of a one-time-code
 * challenge are HMAC-SHA256 under a key from the secrets store, never a plain SHA-256; without the key they are refused
 * by name ({@code PRINCIPAL_KEY_NOT_CONFIGURED}), never written unkeyed.
 */
class PrincipalKeyTest {

    static final byte[] KEY = "sangam-compliance-test-principal-key".getBytes(StandardCharsets.UTF_8);

    static String hmac(byte[] key, String text) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return Hashes.hex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void theTokenIsTheKeyedHashNeverThePlainOne() throws Exception {
        PrincipalKey k = PrincipalKey.of(KEY);
        String token = k.token("t1", "citizen-1");
        assertEquals(hmac(KEY, "principal:t1\u0000citizen-1").substring(0, 32), token);
        assertNotEquals(Hashes.hex(Hashes.sha256("principal:t1\u0000citizen-1")).substring(0, 32), token,
                "the unkeyed token of before the decision");
        assertEquals(token, PrincipalKey.of(KEY).token("t1", "citizen-1"), "stable for the same key");
        assertNotEquals(token, k.token("t2", "citizen-1"), "tenant-scoped");
        byte[] other = KEY.clone();
        other[KEY.length - 1] ^= 1;
        assertNotEquals(token, PrincipalKey.of(other).token("t1", "citizen-1"), "another key, another token");
    }

    @Test
    void theUsesAreSeparatedByDomain() throws Exception {
        PrincipalKey k = PrincipalKey.of(KEY);
        assertEquals(hmac(KEY, "otp-identifier:t1\u0000EMAIL\u0000a@example.in"), k.identifierHash("t1", "EMAIL", "a@example.in"));
        assertEquals(hmac(KEY, "client-address:t1\u0000203.0.113.7"), k.clientAddressHash("t1", "203.0.113.7"));
        assertFalse(k.identifierHash("t1", "EMAIL", "x").startsWith(k.token("t1", "EMAIL\u0000x")));
    }

    @Test
    void withoutAKeyEveryUseIsRefusedByName() {
        PrincipalKey none = PrincipalKey.unconfigured();
        assertFalse(none.configured());
        for (Runnable use : new Runnable[] {() -> none.token("t1", "p"), () -> none.identifierHash("t1", "EMAIL", "p"),
                () -> none.clientAddressHash("t1", "203.0.113.7")}) {
            ComplianceException e = assertThrows(ComplianceException.class, use::run);
            assertEquals(ComplianceException.Code.PRINCIPAL_KEY_NOT_CONFIGURED, e.code());
            assertEquals(409, e.httpStatus());
        }
        ComplianceAudit audit = new ComplianceAudit(new AuditChain(Dialect.POSTGRESQL));
        assertEquals(ComplianceException.Code.PRINCIPAL_KEY_NOT_CONFIGURED,
                assertThrows(ComplianceException.class, () -> audit.principalToken("t1", "p")).code(),
                "an audit built without a key refuses, it never falls back to an unkeyed token");
    }

    @Test
    void aShortOrMalformedKeyIsAConfigurationError() {
        assertThrows(IllegalArgumentException.class, () -> PrincipalKey.of(new byte[PrincipalKey.MIN_BYTES - 1]));
        assertThrows(IllegalArgumentException.class, () -> PrincipalKey.of(null));
        assertThrows(IllegalArgumentException.class, () -> PrincipalKey.fromBase64(" "));
        IllegalArgumentException bad = assertThrows(IllegalArgumentException.class, () -> PrincipalKey.fromBase64("not base64 %%"));
        assertFalse(bad.getMessage().contains("%%"), "the value is never echoed");
        String b64 = Base64.getEncoder().encodeToString(KEY);
        assertEquals(PrincipalKey.of(KEY).token("t1", "p"), PrincipalKey.fromBase64(" " + b64 + "\n").token("t1", "p"));
        assertTrue(PrincipalKey.of(KEY).configured());
        assertFalse(PrincipalKey.of(KEY).toString().contains(new String(KEY, StandardCharsets.UTF_8)));
    }
}
