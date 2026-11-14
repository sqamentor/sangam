// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.deploy;

import org.junit.jupiter.api.Test;
import org.sangam.server.SangamServerApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * SG-38: the server started the way the Helm chart starts it in production ({@code SANGAM_SECURITY_PRODUCTION=true},
 * credentials injected as {@code SANGAM_DB_USER}/{@code SANGAM_DB_PASSWORD}, see HelmProductionSettingsTraceTest),
 * on an in-memory H2 database (no Docker; an {@code h2:mem} URL names no host, so the production URL rule accepts it).
 *
 * <p>Order-independent by design: {@link #productionBaselineStarts()} proves that the baseline settings are all valid;
 * every other test breaks exactly ONE production setting and asserts that start-up fails with that setting's own
 * message, whichever guard runs first. All values are command-line arguments, so they outrank the profile files.
 * Part of the test phase (a full server context); written with the code, run after the push.
 */
class ProductionStartupFailFastTest {

    /** Secret material is generated per run (never a literal key), as AbstractAcceptanceIT does. */
    private static final String TEST_KEK = randomBase64(32);
    private static final String TEST_PEPPER = randomBase64(32);
    private static final String[] REGISTRY_SIGNER = ed25519();
    /** A mounted secrets directory ({@code sangam.secrets.providers=file}) holding the audit checkpoint key pair. */
    private static final Path SECRETS = secretsDirectory();

    private static String randomBase64(int bytes) {
        byte[] k = new byte[bytes];
        new SecureRandom().nextBytes(k);
        return Base64.getEncoder().encodeToString(k);
    }

    /** Base64 PKCS#8 private key and X.509 public key of a fresh Ed25519 pair. */
    private static String[] ed25519() {
        try {
            KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            return new String[] {Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded()),
                    Base64.getEncoder().encodeToString(kp.getPublic().getEncoded())};
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Path secretsDirectory() {
        try {
            Path root = Files.createTempDirectory("sg38-secrets");
            Path audit = Files.createDirectories(root.resolve("audit"));
            String[] checkpoint = ed25519();
            // exact bytes, no trailing line break (SL-65)
            Files.writeString(audit.resolve("checkpoint-private"), checkpoint[0], StandardCharsets.UTF_8);
            Files.writeString(audit.resolve("checkpoint-public"), checkpoint[1], StandardCharsets.UTF_8);
            return root;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Valid production settings; each case changes one of them. */
    private static Map<String, String> baseline(String db) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("sangam.security.production", "true");
        m.put("spring.datasource.url", "jdbc:h2:mem:" + db + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000;MODE=PostgreSQL");
        m.put("SANGAM_DB_USER", "sangam_app");
        m.put("SANGAM_DB_PASSWORD", UUID.randomUUID().toString());
        m.put("sangam.security.kek.keys.k1", TEST_KEK);
        m.put("sangam.security.kek.current-id", "k1");
        // RM-06: production refuses the registry's development-only signer and vault (the consent port is the
        // compliance module's, on by default)
        m.put("sangam.registry.signer.private-key", REGISTRY_SIGNER[0]);
        m.put("sangam.registry.signer.public-key", REGISTRY_SIGNER[1]);
        m.put("sangam.registry.vault.hmac-pepper", TEST_PEPPER);
        // decision 125: no malware scanner in this test, so the explicit operator waiver
        m.put("sangam.malware.production-waiver", "SG-38 start-up test: no scanner in the test JVM");
        // decision 126 part 1: signed audit checkpoints, keys by secret reference from the mounted directory
        m.put("sangam.secrets.providers", "file");
        m.put("sangam.secrets.file.root", SECRETS.toString());
        m.put("sangam.audit.checkpoint.private-key-ref", "secret://audit/checkpoint-private");
        m.put("sangam.audit.checkpoint.public-key-ref", "secret://audit/checkpoint-public");
        // decision 126 part 2 (W4-A): the ledger module is on by default and needs its GL export connector id
        m.put("sangam.ledger.gl-connector", "gl-test");
        // the h2 profile's development conveniences, which production refuses or must not use
        m.put("sangam.security.bootstrap.enabled", "false");
        m.put("sangam.security.dev-token.enabled", "false");
        m.put("sangam.debug.enabled", "false");
        m.put("server.port", "0");
        m.put("management.server.port", "0");
        m.put("sangam.engine.allow-multiple-engines-per-jvm", "true");
        return m;
    }

    private static ConfigurableApplicationContext start(Map<String, String> settings) {
        String[] args = settings.entrySet().stream().map(e -> "--" + e.getKey() + "=" + e.getValue()).toArray(String[]::new);
        return new SpringApplicationBuilder(SangamServerApplication.class).profiles("h2").run(args);
    }

    /** Starts with the baseline changed by {@code change}; start-up must fail with {@code expected} in the cause chain. */
    private static void assertRefused(String db, Consumer<Map<String, String>> change, String expected) {
        Map<String, String> settings = baseline(db);
        change.accept(settings);
        try (ConfigurableApplicationContext ctx = start(settings)) {
            fail("start-up succeeded, expected it to fail with: " + expected);
        } catch (RuntimeException e) {
            StringBuilder chain = new StringBuilder();
            for (Throwable t = e; t != null; t = t.getCause()) {
                chain.append(t.getClass().getSimpleName()).append(": ").append(t.getMessage()).append('\n');
            }
            assertTrue(chain.toString().contains(expected), () -> "expected '" + expected + "' in:\n" + chain);
        }
    }

    @Test
    void productionBaselineStarts() {
        try (ConfigurableApplicationContext ctx = start(baseline("sg38-baseline"))) {
            assertTrue(ctx.isActive());
            assertTrue(ctx.getEnvironment().getProperty("sangam.security.production", Boolean.class, false));
        }
    }

    @Test
    void loopbackDatabaseUrlIsRefused() {
        assertRefused("sg38-url", m -> m.put("spring.datasource.url", "jdbc:postgresql://localhost:5432/sangam"),
                "point at a loopback host");
    }

    @Test
    void missingDatabasePasswordIsRefused() {
        assertRefused("sg38-password", m -> m.remove("SANGAM_DB_PASSWORD"), "SANGAM_DB_PASSWORD is unset");
    }

    @Test
    void missingDatabaseUserIsRefused() {
        assertRefused("sg38-user", m -> m.remove("SANGAM_DB_USER"), "SANGAM_DB_USER is unset");
    }

    @Test
    void missingKeyEncryptionKeyIsRefused() {
        assertRefused("sg38-kek", m -> {
            m.remove("sangam.security.kek.keys.k1");
            m.remove("sangam.security.kek.current-id");
        }, "No key-encryption key configured");
    }

    @Test
    void developmentBootstrapIsRefused() {
        assertRefused("sg38-bootstrap", m -> m.put("sangam.security.bootstrap.enabled", "true"),
                "sangam.security.bootstrap.enabled=true is not allowed with sangam.security.production=true");
    }

    @Test
    void invalidProductionSwitchIsRefused() {
        // decision 143: neither guessed nor defaulted; refused before any other bean is created
        assertRefused("sg38-switch", m -> m.put("sangam.security.production", "yes"),
                "must be true or false (decision 143)");
    }

    @Test
    void registryDevelopmentSignerIsRefused() {
        assertRefused("sg38-registry", m -> {
            m.remove("sangam.registry.signer.private-key");
            m.remove("sangam.registry.signer.public-key");
        }, "sangam-registry refuses to start in production");
    }

    @Test
    void jwkSetUriWithoutIssuerIsRefused() {
        // RM-09: the decoder is lazy, so no key set is fetched; the start-up check refuses before any token
        assertRefused("sg38-oidc", m -> m.put("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                "https://idp.sg38.test/realms/t/protocol/openid-connect/certs"),
                "OIDC token validation is incomplete in production (RM-09)");
    }

    @Test
    void missingMalwareScannerWithoutWaiverIsRefused() {
        assertRefused("sg38-malware", m -> m.remove("sangam.malware.production-waiver"), "(decision 125)");
    }

    @Test
    void missingAuditCheckpointKeyIsRefused() {
        assertRefused("sg38-checkpoint", m -> m.remove("sangam.audit.checkpoint.private-key-ref"), "(decision 126)");
    }

    @Test
    void ledgerWithoutGlConnectorIsRefused() {
        assertRefused("sg38-gl", m -> m.remove("sangam.ledger.gl-connector"),
                "sangam.ledger.gl-connector must be set in production");
    }
}
