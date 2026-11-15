// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.sangam.compliance.ComplianceException;
import org.sangam.compliance.PrincipalKey;
import org.sangam.integration.connector.SecretResolver;
import org.springframework.mock.env.MockEnvironment;

/**
 * Owner decision 369 (e): the compliance principal key comes from the secrets store through
 * {@code sangam.compliance.principal-key-ref}. A secret reference only (a literal key is refused at start), base64 of
 * at least 32 bytes; unset, the server starts and every keyed reference is refused by name (fail closed).
 */
class CompliancePrincipalKeyWiringTest {

    static final byte[] KEY = "sangam-server-test-principal-key-0001".getBytes(StandardCharsets.UTF_8);
    static final String REF = "secret://compliance/principal-key";

    static MockEnvironment env(String ref) {
        MockEnvironment env = new MockEnvironment();
        if (ref != null) {
            env.setProperty(ComplianceWiring.PRINCIPAL_KEY_REF, ref);
        }
        return env;
    }

    @Test
    void theKeyIsReadFromTheSecretsStore() {
        SecretResolver secrets = SecretResolver.of(Map.of(REF, Base64.getEncoder().encodeToString(KEY)));
        PrincipalKey k = ComplianceWiring.principalKey(env(" " + REF + " "), secrets);
        assertTrue(k.configured());
        assertEquals(PrincipalKey.of(KEY).token("t1", "citizen-1"), k.token("t1", "citizen-1"));
    }

    @Test
    void unsetTheServerStartsAndEveryKeyedReferenceIsRefused() {
        PrincipalKey k = ComplianceWiring.principalKey(env(null), SecretResolver.of(Map.of()));
        assertFalse(k.configured());
        assertEquals(ComplianceException.Code.PRINCIPAL_KEY_NOT_CONFIGURED,
                assertThrows(ComplianceException.class, () -> k.token("t1", "citizen-1")).code());
        assertFalse(ComplianceWiring.principalKey(env(" "), SecretResolver.of(Map.of())).configured());
    }

    @Test
    void aLiteralKeyAnUnknownSecretOrAShortKeyStopTheStart() {
        String b64 = Base64.getEncoder().encodeToString(KEY);
        IllegalStateException literal = assertThrows(IllegalStateException.class,
                () -> ComplianceWiring.principalKey(env(b64), SecretResolver.of(Map.of())));
        assertTrue(literal.getMessage().contains("secret reference"), literal.getMessage());
        assertFalse(literal.getMessage().contains(b64), "the key is never echoed");
        assertThrows(IllegalStateException.class, () -> ComplianceWiring.principalKey(env(REF), SecretResolver.of(Map.of())));
        IllegalStateException shortKey = assertThrows(IllegalStateException.class, () -> ComplianceWiring.principalKey(env(REF),
                SecretResolver.of(Map.of(REF, Base64.getEncoder().encodeToString(new byte[16])))));
        assertTrue(shortKey.getMessage().contains(ComplianceWiring.PRINCIPAL_KEY_REF), shortKey.getMessage());
        assertThrows(IllegalStateException.class, () -> ComplianceWiring.principalKey(env(REF),
                SecretResolver.of(Map.of(REF, "not base64 %%"))));
    }
}
