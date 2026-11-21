// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.sangam.core.layer.AuditCheckpoints;
import org.sangam.integration.connector.SecretResolver;
import org.sangam.rest.support.PackageSigning;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.mock.env.MockEnvironment;

/**
 * M-074 wiring (owner Q-04): the package signing key and the trusted keys are secret references only; the own public
 * key is always trusted; unsigned packages are allowed on the h2/dev profiles outside production only; the environment
 * label is sangam.onboarding.environment.
 */
class PackageSigningWiringTest {

    static MockEnvironment env(String... profiles) {
        MockEnvironment env = new MockEnvironment();
        env.setConversionService(new ApplicationConversionService());
        env.setActiveProfiles(profiles);
        return env;
    }

    static String b64(byte[] b) {
        return Base64.getEncoder().encodeToString(b);
    }

    @Test
    void withoutAKeyNothingIsSignedAndUnsignedFollowsTheProfile() {
        SecretResolver none = SecretResolver.of(Map.of());
        PackageSigning plain = PackageSigningWiring.signing(env(), none);
        assertNull(plain.signer());
        assertFalse(plain.allowUnsigned(), "outside development unsigned packages are refused");
        assertNull(plain.environment());
        // decision 143: development defaults need a development profile and the switch saying non-production
        assertTrue(PackageSigningWiring.signing(env("h2").withProperty("sangam.security.production", "false"), none).allowUnsigned());
        assertTrue(PackageSigningWiring.signing(env("dev").withProperty("sangam.security.production", "false"), none).allowUnsigned());
        assertFalse(PackageSigningWiring.signing(env("h2"), none).allowUnsigned(), "a profile alone is not enough");
        PackageSigning prodDev = PackageSigningWiring.signing(env("dev", "prod"), none);
        assertFalse(prodDev.allowUnsigned(), "never in production");
        assertTrue(prodDev.production());
        PackageSigning flagged = PackageSigningWiring.signing(env("h2").withProperty("sangam.security.production", "true"), none);
        assertFalse(flagged.allowUnsigned());
        assertEquals("uat", PackageSigningWiring.signing(env().withProperty("sangam.onboarding.environment", "uat"), none)
                .environment());
    }

    @Test
    void theKeyComesFromTheSecretProviderAndItsPublicKeyIsTrusted() throws Exception {
        KeyPair own = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        KeyPair source = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        SecretResolver secrets = SecretResolver.of(Map.of(
                "secret://packages/prod-private", b64(own.getPrivate().getEncoded()),
                "secret://packages/prod-public", b64(own.getPublic().getEncoded()),
                "secret://packages/uat-public", b64(source.getPublic().getEncoded())));
        PackageSigning s = PackageSigningWiring.signing(env()
                .withProperty("sangam.packages.signing.private-key-ref", "secret://packages/prod-private")
                .withProperty("sangam.packages.signing.public-key-ref", "secret://packages/prod-public")
                .withProperty("sangam.packages.signing.trusted-public-key-refs", "secret://packages/uat-public, "), secrets);
        assertTrue(s.canSign());
        assertEquals(AuditCheckpoints.keyId(own.getPublic()), s.signer().keyId());
        assertTrue(s.trusted().publicKey(AuditCheckpoints.keyId(own.getPublic())).isPresent(), "own key trusted");
        assertTrue(s.trusted().publicKey(AuditCheckpoints.keyId(source.getPublic())).isPresent(), "source key trusted");
    }

    @Test
    void aKeyIsOnlyEverAReference() {
        SecretResolver secrets = SecretResolver.of(Map.of("secret://packages/private", "x"));
        IllegalStateException literal = assertThrows(IllegalStateException.class, () -> PackageSigningWiring.signing(env()
                .withProperty("sangam.packages.signing.private-key-ref", "literal-key-material-not-a-secret-ref")
                .withProperty("sangam.packages.signing.public-key-ref", "secret://packages/public"), secrets));
        assertTrue(literal.getMessage().contains("secret reference"), literal.getMessage());
        assertThrows(IllegalStateException.class, () -> PackageSigningWiring.signing(env()
                .withProperty("sangam.packages.signing.private-key-ref", "secret://packages/private"), secrets),
                "the public key reference is required with the private one");
        assertThrows(IllegalStateException.class, () -> PackageSigningWiring.signing(env()
                .withProperty("sangam.packages.signing.trusted-public-key-refs", "secret://missing"), secrets),
                "an unresolved trusted key fails the start-up");
        assertThrows(IllegalStateException.class, () -> PackageSigningWiring.resolve(secrets, "p", "plain"));
    }
}
