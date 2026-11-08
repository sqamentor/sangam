// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.acceptance;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfilesResolver;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base of every acceptance test (*IT, run by failsafe in the {@code sangam-db-it} profile): the whole Sangam server on
 * the Tier-1 database of {@link AcceptanceDatabase}, with the database's own Spring profile. The engine creates its
 * ACT_* schema ({@code SANGAM_ENGINE_SCHEMA_UPDATE=true}); the product schema comes from the Liquibase changelog, as in
 * production. One Spring context per JVM is shared by subclasses that add no context-changing annotations.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles(resolver = AbstractAcceptanceIT.DbProfile.class)
public abstract class AbstractAcceptanceIT {

    /** Active profile = the database name ({@code postgres}, {@code oracle}, {@code sqlserver}, {@code mysql}). */
    public static final class DbProfile implements ActiveProfilesResolver {
        @Override
        public String[] resolve(Class<?> testClass) {
            return new String[] {AcceptanceDatabase.name()};
        }
    }

    // Fresh random keys per run, never committed secrets: non-development profiles refuse ephemeral defaults.
    private static final String TEST_KEK = randomBase64(32);
    private static final String TEST_PEPPER = randomBase64(32);
    private static final String[] REGISTRY_SIGNER = ed25519();
    private static final String[] BUNDLE_SIGNER = ed25519();

    /** Base64 PKCS#8 private key and X.509 public key of a fresh Ed25519 pair. */
    private static String[] ed25519() {
        try {
            java.security.KeyPair kp = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            return new String[] {java.util.Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded()),
                    java.util.Base64.getEncoder().encodeToString(kp.getPublic().getEncoded())};
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Groups of a full administrator in the acceptance tenants. */
    public static final java.util.List<String> ADMIN_GROUPS =
            java.util.List.of("sangam-admin", "sangam-user", "sangam-operator", "sangam-worker");

    /**
     * A production-path bearer token (OIDC JWT, verified by the server's decoder) for {@code person} in {@code tenant}.
     * Distinct persons are distinct identities, as maker-checker requires.
     */
    protected static String bearer(String tenant, String person, java.util.List<String> groups) {
        return AcceptanceIdp.get().token(tenant, person, groups);
    }

    private static String randomBase64(int bytes) {
        byte[] k = new byte[bytes];
        new java.security.SecureRandom().nextBytes(k);
        return java.util.Base64.getEncoder().encodeToString(k);
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", AcceptanceDatabase::jdbcUrl);
        r.add("spring.datasource.username", AcceptanceDatabase::username);
        r.add("spring.datasource.password", AcceptanceDatabase::password);
        r.add("operaton.bpm.database.schema-update", () -> "true");
        // a fresh random key-encryption key per run (never a committed secret); non-h2 profiles require one
        r.add("sangam.security.kek.keys.acc1", () -> TEST_KEK);
        r.add("sangam.security.kek.current-id", () -> "acc1");
        r.add("sangam.registry.signer.private-key", () -> REGISTRY_SIGNER[0]);
        r.add("sangam.registry.signer.public-key", () -> REGISTRY_SIGNER[1]);
        r.add("sangam.registry.vault.hmac-pepper", () -> TEST_PEPPER);
        // onboarding bundle signing (AT-7 and every signed bundle): own key pair, trusted by id
        r.add("sangam.onboarding.bundle.key-id", () -> "acc-bundle");
        r.add("sangam.onboarding.bundle.private-key", () -> BUNDLE_SIGNER[0]);
        r.add("sangam.onboarding.bundle.trusted-keys.acc-bundle", () -> BUNDLE_SIGNER[1]);
        // OIDC exactly as in production: JWTs from AcceptanceIdp, verified by OidcConfig (issuer, expiry, audience)
        r.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> AcceptanceIdp.get().issuer());
        r.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> AcceptanceIdp.get().jwksUri());
        // external government/sector systems are not reachable from a test run: pack connectors without a binding
        // use the packs' simulators (refused on production profiles by IntegrationWiring)
        r.add("sangam.packs.simulator-fallback", () -> "true");
        // static dev token for API calls in tests (never in production; SecurityConfig logs a warning)
        r.add("sangam.security.dev-token.enabled", () -> "true");
        r.add("sangam.security.dev-token.token", () -> "acceptance-token");
        r.add("sangam.security.dev-token.tenant", () -> "acc");
        r.add("sangam.security.dev-token.principal", () -> "acc-admin");
        r.add("sangam.security.dev-token.groups", () -> "sangam-admin,sangam-user,sangam-operator,sangam-worker");
        r.add("sangam.security.jit-provisioning-tenants", () -> "acc");
        r.add("sangam.security.bootstrap.enabled", () -> "true");
        r.add("sangam.security.bootstrap.admin-groups", () -> "sangam-admin");
        r.add("sangam.integration.simulators.enabled", () -> "true");
        // SL-43: the suite drives load the production defaults would throttle; quotas are tested per tenant by
        // TenantQuotaIT through tenant overrides, read without caching so a test's override applies at once
        for (org.sangam.core.quota.QuotaLimit l : org.sangam.core.quota.QuotaLimit.values()) {
            r.add("sangam.quotas.defaults." + l.key(), () -> "-1");
        }
        r.add("sangam.quotas.override-ttl", () -> "0s");
    }
}
