// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.sql.Connection;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.AuditCheckpoints;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Schema;
import org.sangam.core.layer.Transactions;
import org.sangam.integration.connector.SecretResolver;
import org.sangam.rest.support.TxSupport;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.env.MockEnvironment;

/**
 * M-073 scheduler bean: on only with {@code sangam.audit.checkpoint.private-key-ref}; keys only as secret references
 * (Q-04); settings 1h / 10000 / 1h by default; one pass writes the due checkpoint, signed, and exports it.
 */
class AuditCheckpointWiringTest {

    static MockEnvironment env() {
        MockEnvironment env = new MockEnvironment();
        env.setConversionService(new ApplicationConversionService());
        return env;
    }

    @Test
    void offUnlessAPrivateKeyReferenceIsConfigured() {
        ConditionalOnProperty c = AuditCheckpointWiring.class.getAnnotation(ConditionalOnProperty.class);
        assertEquals("sangam.audit.checkpoint.private-key-ref", String.join(",", c.name()));
    }

    @Test
    void settingsDefaultToTheOwnerDecision() {
        assertEquals(new AuditCheckpoints.Settings(Duration.ofHours(1), 10_000, Duration.ofHours(1)),
                AuditCheckpointWiring.settings(env()));
        assertEquals(new AuditCheckpoints.Settings(Duration.ofMinutes(15), 500, Duration.ofMinutes(30)),
                AuditCheckpointWiring.settings(env().withProperty("sangam.audit.checkpoint.interval", "15m")
                        .withProperty("sangam.audit.checkpoint.max-entries", "500")
                        .withProperty("sangam.audit.checkpoint.overlap", "30m")));
    }

    @Test
    void aKeyIsOnlyEverAReference() {
        SecretResolver secrets = SecretResolver.of(Map.of("secret://audit/checkpoint-private", "PKCS8"));
        assertEquals("PKCS8", AuditCheckpointWiring.key(secrets, env().withProperty(
                "sangam.audit.checkpoint.private-key-ref", "secret://audit/checkpoint-private"), "private-key-ref"));
        IllegalStateException literal = assertThrows(IllegalStateException.class, () -> AuditCheckpointWiring.key(secrets,
                env().withProperty("sangam.audit.checkpoint.private-key-ref", "MC4CAQAwBQYDK2VwBCIEI"), "private-key-ref"));
        assertTrue(literal.getMessage().contains("secret reference"), literal.getMessage());
        assertThrows(IllegalStateException.class, () -> AuditCheckpointWiring.key(secrets, env(), "public-key-ref"),
                "the public key reference is required too");
        assertThrows(IllegalStateException.class, () -> AuditCheckpointWiring.key(secrets, env().withProperty(
                "sangam.audit.checkpoint.public-key-ref", "secret://missing"), "public-key-ref"), "unresolved reference");
    }

    @Test
    void onePassWritesTheDueCheckpointAndExportsIt(@TempDir Path exportDir) throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:cpwiring" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        try (Connection c = ds.getConnection()) {
            Schema.create(c, Dialect.POSTGRESQL);
            AuditCheckpoints.create(c, Dialect.POSTGRESQL);
        }
        AuditChain chain = new AuditChain(Dialect.POSTGRESQL);
        Transactions.of(ds).inTransaction(c -> chain.append(c, "t1", "root-1", "E", Map.of("i", 1)));

        KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        SecretResolver secrets = SecretResolver.of(Map.of(
                "secret://audit/cp-private", Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded()),
                "secret://audit/cp-public", Base64.getEncoder().encodeToString(kp.getPublic().getEncoded())));
        MockEnvironment env = env().withProperty("sangam.audit.checkpoint.private-key-ref", "secret://audit/cp-private")
                .withProperty("sangam.audit.checkpoint.public-key-ref", "secret://audit/cp-public")
                .withProperty("sangam.audit.checkpoint.export-dir", exportDir.toString());
        TxSupport tx = new TxSupport(ds, new DataSourceTransactionManager(ds), TxSupport.detectDialect(ds));
        org.springframework.beans.factory.support.DefaultListableBeanFactory bf =
                new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        bf.registerSingleton("secrets", secrets);

        AuditCheckpointWiring wiring = new AuditCheckpointWiring();
        AuditCheckpoints checkpoints = wiring.sangamAuditCheckpoints(ds, tx, bf.getBeanProvider(SecretResolver.class), env);
        AuditCheckpointWiring.Scheduler scheduler = new AuditCheckpointWiring.Scheduler(checkpoints,
                AuditCheckpoints.directory(exportDir));
        assertEquals(1, scheduler.runOnce(), "the tenant's first checkpoint is due");
        assertEquals(0, scheduler.runOnce(), "nothing due right after it");
        assertNotNull(checkpoints.latest("t1").orElse(null));
        try (var files = Files.walk(exportDir)) {
            assertEquals(1, files.filter(p -> p.toString().endsWith(".json")).count(), "exported outside the database");
        }
        scheduler.close();
    }
}
