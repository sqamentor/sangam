// SPDX-License-Identifier: Apache-2.0
package org.sangam.compliance.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.compliance.ComplianceAudit;
import org.sangam.compliance.ComplianceSchema;
import org.sangam.compliance.config.ComplianceConfig;
import org.sangam.compliance.jurisdiction.JurisdictionRegistry;
import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Schema;
import org.sangam.core.layer.Transactions;
import org.sangam.core.security.FieldEncryptor;

/**
 * M-078: the national-id data vault keeps only an encrypted number and a keyed lookup hash, gives one reference key
 * per number, audits issue and reveal with the masked number, and binds each ciphertext to its row. H2 in PostgreSQL
 * mode; a reversible test encryptor that checks tenant and context like the real one.
 */
class EncryptedNationalIdVaultH2Test {

    static final Dialect D = Dialect.POSTGRESQL;
    static final byte[] PEPPER = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII);
    static final String NUMBER = "234123412346"; // a Verhoeff-valid test number of the IN pack's format

    String url;
    Connection keepAlive;

    /** Base64 of "tenant|context|plaintext": not secure, but fails on a tenant or context mismatch as the real one does. */
    static final FieldEncryptor ENC = new FieldEncryptor() {
        @Override
        public String encrypt(String tenantId, String context, String plaintext) {
            return PREFIX + "test/k1:" + Base64.getEncoder().encodeToString(
                    (tenantId + "|" + context + "|" + plaintext).getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public String decrypt(String tenantId, String context, String envelope) {
            String s = new String(Base64.getDecoder().decode(envelope.substring(envelope.lastIndexOf(':') + 1)), StandardCharsets.UTF_8);
            String expected = tenantId + "|" + context + "|";
            if (!s.startsWith(expected)) {
                throw new IllegalStateException("tenant or context mismatch");
            }
            return s.substring(expected.length());
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
    };

    @BeforeEach
    void db() throws Exception {
        url = "jdbc:h2:mem:m078vault" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        keepAlive = DriverManager.getConnection(url, "sa", "");
        Schema.create(keepAlive, D);
        ComplianceSchema.create(keepAlive, D);
    }

    @AfterEach
    void close() throws Exception {
        keepAlive.close();
    }

    Transactions tx() {
        return new Transactions() {
            @Override
            public <T> T inTransaction(Function<Connection, T> work) {
                try (Connection c = DriverManager.getConnection(url, "sa", "")) {
                    c.setAutoCommit(false);
                    T out = work.apply(c);
                    c.commit();
                    return out;
                } catch (java.sql.SQLException e) {
                    throw new IllegalStateException(e);
                }
            }
        };
    }

    EncryptedNationalIdVault vault() {
        JurisdictionRegistry packs = JurisdictionRegistry.discover();
        ComplianceConfig.Provider config = t -> ComplianceConfig.fromProperties(packs, Map.of("jurisdiction", "IN"));
        return new EncryptedNationalIdVault(D, tx(), ENC, PEPPER, new ComplianceAudit(new AuditChain(D)), config);
    }

    @Test
    void sameNumberSameKeyAndTheNumberIsNeverStoredInClear() {
        EncryptedNationalIdVault v = vault();
        String k1 = v.referenceKey("t1", "AADHAAR", NUMBER);
        String k2 = v.referenceKey("t1", "AADHAAR", NUMBER);
        assertEquals(k1, k2, "one reference key per number (one-to-one mapping)");
        assertFalse(k1.contains(NUMBER));
        List<String[]> rows = Jdbc.query(keepAlive, "SELECT reference_key, lookup_hash, number_enc, masked FROM CMP_NATIONAL_ID_VAULT",
                rs -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)});
        assertEquals(1, rows.size());
        assertEquals(64, rows.get(0)[1].length(), "HMAC-SHA256 hex fits {str64}");
        assertFalse(rows.get(0)[1].contains(NUMBER));
        assertTrue(FieldEncryptor.isEncrypted(rows.get(0)[2]));
        assertEquals("XXXXXXXX2346", rows.get(0)[3], "the IN pack keeps the last 4 digits");
    }

    @Test
    void tenantsAndTypesNeverShareAKey() {
        EncryptedNationalIdVault v = vault();
        String a = v.referenceKey("t1", "AADHAAR", NUMBER);
        assertNotEquals(a, v.referenceKey("t2", "AADHAAR", NUMBER));
        assertNotEquals(a, v.referenceKey("t1", "OTHER_ID", NUMBER));
        assertNotEquals(v.lookupHash("t1", "AADHAAR", NUMBER), v.lookupHash("t2", "AADHAAR", NUMBER));
    }

    @Test
    void revealReturnsTheNumberAndIsAudited() {
        EncryptedNationalIdVault v = vault();
        String key = v.referenceKey("t1", "AADHAAR", NUMBER);
        assertEquals(NUMBER, v.reveal("t1", key, "authentication", "user-1").orElseThrow());
        assertTrue(v.reveal("t1", "unknown", "authentication", "user-1").isEmpty());
        assertTrue(v.reveal("t2", key, "authentication", "user-1").isEmpty(), "a key of another tenant is unknown");
        List<String> events = Jdbc.query(keepAlive, "SELECT event_type FROM AUDIT_EVENT WHERE chain_id=? ORDER BY seq",
                rs -> rs.getString(1), ComplianceAudit.tenantChain("t1"));
        assertEquals(List.of(ComplianceAudit.NATIONAL_ID_VAULTED, ComplianceAudit.NATIONAL_ID_REVEALED), events);
    }

    @Test
    void aCiphertextMovedToAnotherRowDoesNotDecrypt() {
        EncryptedNationalIdVault v = vault();
        String a = v.referenceKey("t1", "AADHAAR", NUMBER);
        String b = v.referenceKey("t1", "AADHAAR", "999941057058");
        String encA = Jdbc.one(keepAlive, "SELECT number_enc FROM CMP_NATIONAL_ID_VAULT WHERE reference_key=?", rs -> rs.getString(1), a)
                .orElseThrow();
        Jdbc.update(keepAlive, "UPDATE CMP_NATIONAL_ID_VAULT SET number_enc=? WHERE reference_key=?", encA, b);
        assertThrows(IllegalStateException.class, () -> v.reveal("t1", b, "authentication", "user-1"));
    }

    @Test
    void refusesAWeakPepperAndMissingInput() {
        assertThrows(IllegalArgumentException.class, () -> new EncryptedNationalIdVault(D, tx(), ENC, new byte[8],
                new ComplianceAudit(new AuditChain(D)), null));
        EncryptedNationalIdVault v = vault();
        assertThrows(RuntimeException.class, () -> v.referenceKey("t1", "AADHAAR", " "));
        assertThrows(RuntimeException.class, () -> v.reveal("t1", "k", " ", "user-1"));
    }
}
