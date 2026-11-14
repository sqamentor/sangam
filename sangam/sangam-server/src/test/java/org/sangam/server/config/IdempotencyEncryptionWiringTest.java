// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import org.sangam.rest.config.SpringTxSupport;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.IdempotencyStore;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Schema;
import org.sangam.core.security.FieldEncryptor;
import org.sangam.rest.config.SangamRestAutoConfiguration;
import org.sangam.rest.port.IdempotencyPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.RestProperties;
import org.sangam.rest.support.TxSupport;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * SL-139 goes live (M-007, X.2.2.3): the default {@code IdempotencyPort} of {@link SangamRestAutoConfiguration}
 * passes the bound {@link FieldEncryptor} to the store, so a stored response body is encrypted at rest and a replay
 * returns the byte-identical body. Before the fix the bean used the two-argument constructor and every body was stored
 * in plain text although sangam-security binds an encryptor. Without an encryptor the body is stored as before.
 * Lives in sangam-server because that module has the H2 driver on its test classpath.
 */
class IdempotencyEncryptionWiringTest {

    static final byte[] BODY = "{\"id\":\"pi-1\",\"name\":\"संगम\"}".getBytes(StandardCharsets.UTF_8);
    static final byte[] FINGERPRINT = new byte[32];

    /** Reversible stand-in with the envelope shape {@link FieldEncryptor#isEncrypted} recognises; checks the context. */
    static final class TestEncryptor implements FieldEncryptor {
        @Override
        public String encrypt(String tenantId, String context, String plaintext) {
            return PREFIX + tenantId + "/k1:" + Base64.getUrlEncoder().withoutPadding()
                    .encodeToString((context + "|" + plaintext).getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public String decrypt(String tenantId, String context, String envelope) {
            String raw = new String(Base64.getUrlDecoder().decode(envelope.substring(envelope.lastIndexOf(':') + 1)),
                    StandardCharsets.UTF_8);
            if (!raw.startsWith(context + "|") || !FieldEncryptor.keyIdOf(envelope).startsWith(tenantId + "/")) {
                throw new IllegalStateException("context or tenant mismatch");
            }
            return raw.substring(context.length() + 1);
        }

        @Override
        public String encryptJson(String tenantId, String context, Map<String, ?> json) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Map<String, Object> decryptJson(String tenantId, String context, String envelope) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String reencrypt(String tenantId, String context, String envelope) {
            return envelope;
        }
    }

    TxSupport tx;

    @BeforeEach
    void setUp() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:idemwiring" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        ds.setUser("sa");
        ds.setPassword("");
        tx = new SpringTxSupport(ds, new DataSourceTransactionManager(ds), TxSupport.detectDialect(ds));
        tx.newTransactions().inTransaction(c -> {
            Schema.create(c, Dialect.POSTGRESQL);
            return null;
        });
    }

    static ObjectProvider<FieldEncryptor> provider(FieldEncryptor encryptor) {
        DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
        if (encryptor != null) {
            bf.registerSingleton("sangamFieldEncryptor", encryptor);
        }
        return bf.getBeanProvider(FieldEncryptor.class);
    }

    static RequestContext caller() {
        return new RequestContext("T1", "u-1", Set.of("sangam-user"), null, null, null, null, null, "OIDC");
    }

    /** Runs one idempotent command that answers 201 with {@link #BODY}; returns the stored response_body. */
    private byte[] runOnce(IdempotencyPort port, String key) throws Exception {
        IdempotencyPort.Begin b = port.begin(caller(), "process.start", key, FINGERPRINT);
        IdempotencyPort.Claimed claimed = assertInstanceOf(IdempotencyPort.Claimed.class, b);
        port.execute(claimed, () -> new IdempotencyPort.Outcome(201, BODY, "pi-1"));
        return tx.newTransactions().inTransaction(c -> Jdbc.one(c, "SELECT response_body FROM IDEMPOTENCY_RECORD"
                + " WHERE tenant_id=? AND command_type=? AND idem_key=?", rs -> rs.getBytes(1), "T1", "process.start", key)
                .orElseThrow());
    }

    @Test
    void withTheSecurityEncryptorTheStoredBodyIsEncryptedAndTheReplayIsByteIdentical() throws Exception {
        IdempotencyPort port = new SangamRestAutoConfiguration().sangamIdempotencyPort(tx, new RestProperties(),
                provider(new TestEncryptor()));
        byte[] stored = runOnce(port, "k-enc");
        String text = new String(stored, StandardCharsets.US_ASCII);
        assertTrue(text.startsWith(IdempotencyStore.ENCRYPTED_BODY_MARKER + FieldEncryptor.PREFIX), text);
        assertFalse(new String(stored, StandardCharsets.UTF_8).contains("pi-1"), "no plain body at rest");

        IdempotencyPort.Replay replay = assertInstanceOf(IdempotencyPort.Replay.class,
                port.begin(caller(), "process.start", "k-enc", FINGERPRINT));
        assertEquals(201, replay.status());
        assertArrayEquals(BODY, replay.body());
    }

    @Test
    void withoutAnEncryptorTheBodyIsStoredAsBefore() throws Exception {
        IdempotencyPort port = new SangamRestAutoConfiguration().sangamIdempotencyPort(tx, new RestProperties(),
                provider(null));
        assertArrayEquals(BODY, runOnce(port, "k-plain"));
    }
}
