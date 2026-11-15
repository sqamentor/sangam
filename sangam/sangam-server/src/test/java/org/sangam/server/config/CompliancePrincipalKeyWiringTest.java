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
 * at least 32 bytes; unset, the server starts and every keyed reference is refused by name (fail closed). Owner
 * decision 371 (5): unset in production (a missing or contradictory environment setting counts as production), the
 * start is refused.
 */
class CompliancePrincipalKeyWiringTest {

    static final byte[] KEY = "sangam-server-test-principal-key-0001".getBytes(StandardCharsets.UTF_8);
    static final String REF = "secret://compliance/principal-key";

    /** A development installation (decision 143: it states the switch and a development profile). */
    static MockEnvironment env(String ref) {
        MockEnvironment env = new MockEnvironment().withProperty("sangam.security.production", "false");
        env.setActiveProfiles("h2");
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
    void unsetInProductionTheStartIsRefusedByName() {
        MockEnvironment helm = new MockEnvironment().withProperty("sangam.security.production", "true");
        helm.setActiveProfiles("postgres", "oidc");
        MockEnvironment prodProfile = new MockEnvironment();
        prodProfile.setActiveProfiles("postgres", "prod");
        MockEnvironment unset = new MockEnvironment();
        MockEnvironment contradictory = new MockEnvironment().withProperty("sangam.security.production", "true");
        contradictory.setActiveProfiles("h2");
        MockEnvironment blank = new MockEnvironment().withProperty("sangam.security.production", "true")
                .withProperty(ComplianceWiring.PRINCIPAL_KEY_REF, "  ");
        for (MockEnvironment e : java.util.List.of(helm, prodProfile, unset, contradictory, blank)) {
            IllegalStateException refused = assertThrows(IllegalStateException.class,
                    () -> ComplianceWiring.principalKey(e, SecretResolver.of(Map.of())));
            assertTrue(refused.getMessage().startsWith(ComplianceWiring.PRINCIPAL_KEY_REF + " "), refused.getMessage());
            assertTrue(refused.getMessage().contains("required in production"), refused.getMessage());
        }
        // a configured key is used in production as everywhere else
        MockEnvironment configured = new MockEnvironment().withProperty("sangam.security.production", "true")
                .withProperty(ComplianceWiring.PRINCIPAL_KEY_REF, REF);
        assertTrue(ComplianceWiring.principalKey(configured,
                SecretResolver.of(Map.of(REF, Base64.getEncoder().encodeToString(KEY)))).configured());
    }

    @Test
    void unsetOutsideProductionTheServerStartsAndEveryKeyedReferenceIsRefused() {
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
