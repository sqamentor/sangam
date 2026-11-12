// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.deploy;

import org.junit.jupiter.api.Test;
import org.sangam.server.SangamServerApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

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

    /** 32 bytes (0x00..0x1f), base64: a syntactically valid key-encryption key for the test only. */
    private static final String TEST_KEK = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";

    /** Valid production settings; each case changes one of them. */
    private static Map<String, String> baseline(String db) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("sangam.security.production", "true");
        m.put("spring.datasource.url", "jdbc:h2:mem:" + db + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000;MODE=PostgreSQL");
        m.put("SANGAM_DB_USER", "sangam_app");
        m.put("SANGAM_DB_PASSWORD", UUID.randomUUID().toString());
        m.put("sangam.security.kek.keys.k1", TEST_KEK);
        m.put("sangam.security.kek.current-id", "k1");
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
}
