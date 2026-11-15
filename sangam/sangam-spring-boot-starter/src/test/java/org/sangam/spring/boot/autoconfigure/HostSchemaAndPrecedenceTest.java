// SPDX-License-Identifier: Apache-2.0
package org.sangam.spring.boot.autoconfigure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.example.sangamhost.SampleHostApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.core.env.Environment;

/**
 * Decision 334 in a real host ({@link SampleHostApplication}, H2 in memory, one context after the other):
 * <ul>
 *   <li>a validated development host on an empty database starts and has the engine and the product schema;</li>
 *   <li>a host setting wins over Sangam's default whether it comes from the host's application.yaml or the host's
 *       default properties ({@code SpringApplicationBuilder.properties}); Sangam's default applies where the host says
 *       nothing; the same in the management child context (a separate management port);</li>
 *   <li>a production host on an empty database is refused with {@code ENGINE_SCHEMA_MISSING} and creates no table;</li>
 *   <li>a production host on a database that has the schema starts, and changes nothing.</li>
 * </ul>
 */
class HostSchemaAndPrecedenceTest {

    static final String USER = "sangam_app";
    static final String PASSWORD = UUID.randomUUID().toString();

    static String url(String db) {
        return "jdbc:h2:mem:" + db + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000;MODE=PostgreSQL";
    }

    /** The settings every host here has; all of them are the host's default properties. */
    static Map<String, String> common(String db) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("spring.datasource.url", url(db));
        m.put("spring.datasource.username", USER);
        m.put("spring.datasource.password", PASSWORD);
        m.put("server.port", "0");
        m.put("sangam.engine.allow-multiple-engines-per-jvm", "true");
        return m;
    }

    /** A development host (decision 143: the stated non-production switch and a development profile). */
    static SpringApplicationBuilder development(String db, String configLocation) {
        Map<String, String> m = common(db);
        m.put("spring.config.location", configLocation);
        m.put("sangam.tenant.default", "dev");
        m.put("sangam.security.production", "false");
        return new SpringApplicationBuilder(SampleHostApplication.class).profiles("dev")
                .properties(m.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toArray(String[]::new));
    }

    @Test
    void aDevelopmentHostOnAnEmptyDatabaseCreatesTheSchemaAndEveryHostSettingWins() throws SQLException {
        List<ApplicationContext> children = new ArrayList<>();
        ApplicationListener<ContextRefreshedEvent> child = e -> {
            if (e.getApplicationContext().getParent() != null) {
                children.add(e.getApplicationContext());
            }
        };
        try (ConfigurableApplicationContext ctx = development("dev-" + UUID.randomUUID(),
                "optional:classpath:/precedence-host/")
                .properties("management.server.port=0", "sangam.onboarding.environment=host-default")
                .listeners(child).run()) {
            try (Connection c = ctx.getBean(DataSource.class).getConnection()) {
                assertTrue(table(c, "ACT_RU_EXECUTION"), "the engine created its tables");
                assertTrue(table(c, "DATABASECHANGELOG"), "the product changelog ran");
                assertTrue(table(c, "AUDIT_EVENT"), "the product tables exist");
            }
            assertEquals("true", ctx.getEnvironment().getProperty(SangamDefaultsEnvironmentPostProcessor.ENGINE_SCHEMA_UPDATE), "development default");
            assertSettings(ctx.getEnvironment());
            assertFalse(children.isEmpty(), "a separate management port has a child context");
            for (ApplicationContext c : children) {
                assertSettings(c.getEnvironment());
            }
            HostDefaultModulesTest.assertLowestPrecedence(ctx);
        }
    }

    static void assertSettings(Environment env) {
        assertEquals("host-default", env.getProperty("sangam.onboarding.environment"),
                "the host's default property wins over Sangam's default");
        assertEquals("7s", env.getProperty("sangam.db.unavailable-retry-after"),
                "the host's application.yaml wins over Sangam's default");
        assertEquals("full", env.getProperty("operaton.bpm.history-level"),
                "Sangam's default where the host says nothing");
    }

    @Test
    void aProductionHostOnAnEmptyDatabaseIsRefusedByNameAndCreatesNothing() throws SQLException {
        String db = "prod-empty-" + UUID.randomUUID();
        StringBuilder chain = new StringBuilder();
        try (ConfigurableApplicationContext ctx = production().run(args(productionSettings(db)))) {
            fail("a production host started on a database without the engine tables");
        } catch (RuntimeException e) {
            for (Throwable t = e; t != null; t = t.getCause()) {
                chain.append(t.getClass().getSimpleName()).append(": ").append(t.getMessage()).append('\n');
            }
        }
        assertTrue(chain.toString().contains("ENGINE_SCHEMA_MISSING"), chain::toString);
        assertTrue(chain.toString().contains("SANGAM_LIQUIBASE_MODE=migrate-only"), chain::toString);
        assertFalse(chain.toString().contains("ENGINE-03057"), chain::toString);
        try (Connection c = DriverManager.getConnection(url(db), USER, PASSWORD)) {
            assertEquals(0, publicTables(c), "no table was created");
        }
    }

    @Test
    void aProductionHostOnADatabaseWithTheSchemaStartsAndChangesNothing() throws SQLException {
        String db = "prod-schema-" + UUID.randomUUID();
        // the schema, created once by a development run on the same database (as migrate-only would)
        development(db, "optional:classpath:/no-host-config/").run().close();
        int before;
        try (Connection c = DriverManager.getConnection(url(db), USER, PASSWORD)) {
            before = publicTables(c);
        }
        try (ConfigurableApplicationContext ctx = production().run(args(productionSettings(db)))) {
            assertTrue(ctx.isActive());
            assertEquals("false", ctx.getEnvironment().getProperty(SangamDefaultsEnvironmentPostProcessor.ENGINE_SCHEMA_UPDATE), "check only");
            assertEquals("validate", ctx.getEnvironment().getProperty("sangam.liquibase.mode"), "change nothing");
            assertNotNull(ctx.getBean("sangamEngineAdapter"));
            try (Connection c = ctx.getBean(DataSource.class).getConnection()) {
                assertEquals(before, publicTables(c), "no table added or removed");
            }
        }
    }

    /**
     * Decision 356: a production host that sets {@code operaton.bpm.database.schema-update=true} itself is honoured
     * (the host's setting wins over Sangam's production default, decision 334) and the start logs one WARN naming the
     * setting.
     */
    @Test
    void anExplicitSchemaUpdateInProductionIsHonouredAndWarnedOnce() throws SQLException {
        String db = "prod-update-" + UUID.randomUUID();
        development(db, "optional:classpath:/no-host-config/").run().close();
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(
                org.sangam.server.bridge.EngineSchemaGuard.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logs =
                new ch.qos.logback.core.read.ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        Map<String, String> settings = productionSettings(db);
        settings.put(SangamDefaultsEnvironmentPostProcessor.ENGINE_SCHEMA_UPDATE, "true");
        try (ConfigurableApplicationContext ctx = production().run(args(settings))) {
            assertTrue(ctx.isActive());
            assertEquals("true", ctx.getEnvironment().getProperty(SangamDefaultsEnvironmentPostProcessor.ENGINE_SCHEMA_UPDATE),
                    "the host's explicit setting is honoured");
            List<String> warnings = logs.list.stream()
                    .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN)
                    .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage).toList();
            assertEquals(1, warnings.size(), "logged once at start: " + warnings);
            assertTrue(warnings.get(0).contains("operaton.bpm.database.schema-update=true"), warnings.get(0));
        } finally {
            logger.detachAppender(logs);
        }
    }

    /** A production host: no profile, the switch and the production settings on the command line. */
    static SpringApplicationBuilder production() {
        return new SpringApplicationBuilder(SampleHostApplication.class);
    }

    static String[] args(Map<String, String> settings) {
        return settings.entrySet().stream().map(e -> "--" + e.getKey() + "=" + e.getValue()).toArray(String[]::new);
    }

    /** A production host's settings: those of the server's production start test (SG-38), for a host. */
    static Map<String, String> productionSettings(String db) {
        Map<String, String> m = common(db);
        m.put("spring.config.location", "optional:classpath:/no-host-config/");
        m.put("sangam.security.production", "true");
        m.put("management.server.port", "0");
        m.put("sangam.security.kek.keys.k1", randomBase64(32));
        m.put("sangam.security.kek.current-id", "k1");
        String[] signer = ed25519();
        m.put("sangam.registry.signer.private-key", signer[0]);
        m.put("sangam.registry.signer.public-key", signer[1]);
        m.put("sangam.registry.vault.hmac-pepper", randomBase64(32));
        m.put("sangam.malware.production-waiver", "decision 334 host start test: no scanner in the test JVM");
        m.put("sangam.secrets.providers", "file");
        m.put("sangam.secrets.file.root", SECRETS.toString());
        m.put("sangam.audit.checkpoint.private-key-ref", "secret://audit/checkpoint-private");
        m.put("sangam.audit.checkpoint.public-key-ref", "secret://audit/checkpoint-public");
        m.put("sangam.security.bootstrap.enabled", "false");
        m.put("sangam.security.dev-token.enabled", "false");
        m.put("sangam.security.platform-issuer", "");
        m.put("sangam.debug.enabled", "false");
        return m;
    }

    static final Path SECRETS = secretsDirectory();

    private static String randomBase64(int bytes) {
        byte[] k = new byte[bytes];
        new SecureRandom().nextBytes(k);
        return Base64.getEncoder().encodeToString(k);
    }

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
            Path root = Files.createTempDirectory("d334-secrets");
            Path audit = Files.createDirectories(root.resolve("audit"));
            String[] checkpoint = ed25519();
            Files.writeString(audit.resolve("checkpoint-private"), checkpoint[0], StandardCharsets.UTF_8);
            Files.writeString(audit.resolve("checkpoint-public"), checkpoint[1], StandardCharsets.UTF_8);
            return root;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static boolean table(Connection c, String name) throws SQLException {
        try (ResultSet rs = c.getMetaData().getTables(null, null, name, new String[] {"TABLE"})) {
            return rs.next();
        }
    }

    static int publicTables(Connection c) throws SQLException {
        try (ResultSet rs = c.createStatement().executeQuery(
                "select count(*) from INFORMATION_SCHEMA.TABLES where TABLE_SCHEMA = 'PUBLIC'")) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
