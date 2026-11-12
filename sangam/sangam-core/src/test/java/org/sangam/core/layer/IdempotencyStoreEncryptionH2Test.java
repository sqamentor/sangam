// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.layer;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.security.FieldEncryptor;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M-007 (R41, X.2.2.3): a stored idempotency response is encrypted at rest under the tenant key and replayed
 * byte-identical (T57); rows written without an encryptor stay readable; a body is bound to its tenant, command and key.
 */
class IdempotencyStoreEncryptionH2Test {

    /** Reversible test encryptor with the envelope shape of the real one; it checks tenant and context like AAD. */
    static final class TestEncryptor implements FieldEncryptor {
        int keyVersion = 1;

        @Override
        public String encrypt(String tenantId, String context, String plaintext) {
            String sealed = tenantId + "\u0000" + context + "\u0000" + plaintext;
            return PREFIX + tenantId + "/k" + keyVersion + ":"
                    + Base64.getEncoder().encodeToString(reverse(sealed.getBytes(StandardCharsets.UTF_8)));
        }

        @Override
        public String decrypt(String tenantId, String context, String envelope) {
            String data = envelope.substring(envelope.lastIndexOf(':') + 1);
            String sealed = new String(reverse(Base64.getDecoder().decode(data)), StandardCharsets.UTF_8);
            String expected = tenantId + "\u0000" + context + "\u0000";
            if (!sealed.startsWith(expected)) {
                throw new IllegalStateException("authentication failed");
            }
            return sealed.substring(expected.length());
        }

        @Override
        public String encryptJson(String tenantId, String context, Map<String, ?> json) {
            return encrypt(tenantId, context, CanonicalJson.write(json));
        }

        @Override
        public Map<String, Object> decryptJson(String tenantId, String context, String envelope) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String reencrypt(String tenantId, String context, String envelope) {
            return encrypt(tenantId, context, decrypt(tenantId, context, envelope));
        }

        private static byte[] reverse(byte[] b) {
            byte[] out = new byte[b.length];
            for (int i = 0; i < b.length; i++) {
                out[i] = b[b.length - 1 - i];
            }
            return out;
        }
    }

    static final byte[] FP = Hashes.sha256("request");
    static final byte[] BODY = "{\"id\":\"case-1\",\"name\":\"Asha\"}".getBytes(StandardCharsets.UTF_8);

    Transactions tx;
    TestEncryptor encryptor;
    IdempotencyStore encrypted;
    IdempotencyStore plain;

    @BeforeEach
    void setUp() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        tx = Transactions.of(ds);
        tx.inTransaction(c -> {
            Schema.create(c, Dialect.POSTGRESQL);
            return null;
        });
        encryptor = new TestEncryptor();
        encrypted = new IdempotencyStore(Dialect.POSTGRESQL, tx, "node-1", encryptor);
        plain = new IdempotencyStore(Dialect.POSTGRESQL, tx, "node-1");
    }

    private void completeWith(IdempotencyStore store, String tenant, String key, byte[] body) {
        IdempotencyStore.New n = assertInstanceOf(IdempotencyStore.New.class, store.begin(tenant, "case.create", key, "p1", FP));
        tx.inTransaction(c -> {
            store.complete(c, n.claim(), 201, body, "/cases/case-1");
            return null;
        });
    }

    private byte[] storedBody(String tenant, String key) {
        return tx.inTransaction(c -> Jdbc.one(c, "SELECT response_body FROM IDEMPOTENCY_RECORD WHERE tenant_id=?"
                + " AND command_type=? AND idem_key=?", rs -> rs.getBytes(1), tenant, "case.create", key).orElseThrow());
    }

    @Test
    void theStoredBodyIsCiphertextAndTheReplayIsByteIdentical() {
        completeWith(encrypted, "T1", "k-1", BODY);
        byte[] atRest = storedBody("T1", "k-1");
        String text = new String(atRest, StandardCharsets.US_ASCII);
        assertTrue(text.startsWith(IdempotencyStore.ENCRYPTED_BODY_MARKER + FieldEncryptor.PREFIX + "T1/k1:"), text);
        assertFalse(text.contains("Asha"), "no plaintext at rest");
        assertTrue(IdempotencyStore.isEncryptedBody(atRest));

        IdempotencyStore.Replay r = assertInstanceOf(IdempotencyStore.Replay.class,
                encrypted.begin("T1", "case.create", "k-1", "p1", FP));
        assertArrayEquals(BODY, r.body(), "T57: the replay returns the exact bytes the command produced");
        assertEquals(201, r.httpStatus());
        assertEquals("/cases/case-1", r.resourceRef());
    }

    @Test
    void aFinalFailureIsEncryptedToo() {
        byte[] problem = "{\"code\":\"VALIDATION_FAILED\",\"detail\":\"Asha\"}".getBytes(StandardCharsets.UTF_8);
        IdempotencyStore.New n = assertInstanceOf(IdempotencyStore.New.class,
                encrypted.begin("T1", "case.create", "k-2", "p1", FP));
        encrypted.failFinal(n.claim(), 422, problem);
        assertTrue(IdempotencyStore.isEncryptedBody(storedBody("T1", "k-2")));
        IdempotencyStore.Replay r = assertInstanceOf(IdempotencyStore.Replay.class,
                encrypted.begin("T1", "case.create", "k-2", "p1", FP));
        assertArrayEquals(problem, r.body());
        assertEquals(422, r.httpStatus());
    }

    @Test
    void aBodyStoredBeforeEncryptionIsReplayedAsStored() {
        completeWith(plain, "T1", "k-3", BODY);
        assertArrayEquals(BODY, storedBody("T1", "k-3"), "no encryptor: stored unencrypted, as before");
        IdempotencyStore.Replay r = assertInstanceOf(IdempotencyStore.Replay.class,
                encrypted.begin("T1", "case.create", "k-3", "p1", FP));
        assertArrayEquals(BODY, r.body(), "legacy plain row, read by a store with an encryptor");
    }

    @Test
    void anEncryptedBodyIsNotReplayedWithoutTheEncryptor() {
        completeWith(encrypted, "T1", "k-4", BODY);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> plain.begin("T1", "case.create", "k-4", "p1", FP));
        assertTrue(e.getMessage().startsWith("ENCRYPTION_UNAVAILABLE"), e.getMessage());
    }

    @Test
    void aBodyCopiedToAnotherKeyDoesNotDecrypt() {
        completeWith(encrypted, "T1", "k-5", BODY);
        completeWith(encrypted, "T1", "k-6", "{\"id\":\"case-2\"}".getBytes(StandardCharsets.UTF_8));
        byte[] stolen = storedBody("T1", "k-5");
        tx.inTransaction(c -> Jdbc.update(c, "UPDATE IDEMPOTENCY_RECORD SET response_body=? WHERE tenant_id=?"
                + " AND command_type=? AND idem_key=?", stolen, "T1", "case.create", "k-6"));
        assertThrows(IllegalStateException.class, () -> encrypted.begin("T1", "case.create", "k-6", "p1", FP));
    }

    @Test
    void aBodyWrittenBeforeAKeyRotationStaysReadable() {
        completeWith(encrypted, "T1", "k-7", BODY);
        encryptor.keyVersion = 2;   // rotation: new writes use k2, k1 is decrypt-only
        completeWith(encrypted, "T1", "k-8", BODY);
        assertTrue(new String(storedBody("T1", "k-8"), StandardCharsets.US_ASCII).contains("T1/k2:"));
        assertArrayEquals(BODY, assertInstanceOf(IdempotencyStore.Replay.class,
                encrypted.begin("T1", "case.create", "k-7", "p1", FP)).body());
        assertArrayEquals(BODY, assertInstanceOf(IdempotencyStore.Replay.class,
                encrypted.begin("T1", "case.create", "k-8", "p1", FP)).body());
    }

    @Test
    void theMarkerNeverMatchesAJsonBody() {
        assertFalse(IdempotencyStore.isEncryptedBody(BODY));
        assertFalse(IdempotencyStore.isEncryptedBody(new byte[0]));
        assertFalse(IdempotencyStore.isEncryptedBody(null));
        assertFalse(IdempotencyStore.isEncryptedBody(IdempotencyStore.ENCRYPTED_BODY_MARKER.getBytes(StandardCharsets.US_ASCII)));
        assertFalse(IdempotencyStore.isEncryptedBody((IdempotencyStore.ENCRYPTED_BODY_MARKER + "{}").getBytes(StandardCharsets.US_ASCII)),
                "the marker alone, without an envelope, is a plain body");
    }

    @Test
    void anEmptyBodyRoundTrips() {
        completeWith(encrypted, "T1", "k-10", new byte[0]);
        assertArrayEquals(new byte[0], assertInstanceOf(IdempotencyStore.Replay.class,
                encrypted.begin("T1", "case.create", "k-10", "p1", FP)).body());
    }
}
