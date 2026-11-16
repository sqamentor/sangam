// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import org.sangam.compliance.identity.EncryptedNationalIdVault;
import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Schema;
import org.sangam.core.layer.Transactions;
import org.sangam.core.security.FieldEncryptor;
import org.sangam.registry.RegistryException;
import org.sangam.registry.RegistryPorts;
import org.sangam.registry.support.HmacIdentifierVault;

/**
 * Decision 389: registry identifiers with clear-value custody in the compliance national-id vault. The registry keeps
 * its keyed hash for matching (unchanged), the clear value is stored only encrypted in {@code CMP_NATIONAL_ID_VAULT},
 * a reveal needs an explicit actor and purpose and is audited on the tenant's compliance chain, and a reference key
 * of one tenant reveals nothing in another. H2 in PostgreSQL mode; a reversible test encryptor that checks tenant and
 * context like the real one.
 */
class RegistryCustodyVaultH2Test {

    static final Dialect D = Dialect.POSTGRESQL;
    static final byte[] REGISTRY_PEPPER = "registry-pepper-0123456789abcdef".getBytes(StandardCharsets.US_ASCII);
    static final byte[] CUSTODY_PEPPER = "custody-pepper-0123456789abcdef!".getBytes(StandardCharsets.US_ASCII);
    static final String ACCOUNT = "50100123456789";

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
            String s = new String(Base64.getDecoder().decode(envelope.substring(envelope.lastIndexOf(':') + 1)),
                    StandardCharsets.UTF_8);
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
        url = "jdbc:h2:mem:dec389vault" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        keepAlive = DriverManager.getConnection(url, "sa", "");
        Schema.create(keepAlive, D);
        ComplianceSchema.create(keepAlive, D);
    }

    @AfterEach
    void close() throws Exception {
        keepAlive.close();
    }

    private Transactions tx() {
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

    private RegistryCustodyVault vault() {
        return new RegistryCustodyVault(new HmacIdentifierVault(REGISTRY_PEPPER),
                new EncryptedNationalIdVault(D, tx(), ENC, CUSTODY_PEPPER, new ComplianceAudit(new AuditChain(D)), null));
    }

    private List<String> chain(String tenant) {
        return Jdbc.query(keepAlive, "SELECT event_type FROM AUDIT_EVENT WHERE chain_id=? ORDER BY seq",
                rs -> rs.getString(1), ComplianceAudit.tenantChain(tenant));
    }

    @Test
    void aStoredValueIsRevealedAndHeldOnlyEncrypted() {
        RegistryCustodyVault v = vault();
        RegistryPorts.ProtectedValue p = v.protect("t1", "BANK_ACCOUNT", ACCOUNT, true);
        assertTrue(p.vaultRef() != null && !p.vaultRef().contains(ACCOUNT), "the registry keeps a reference key only");
        assertEquals("XXXXXXXXXX6789", p.masked());
        assertEquals(ACCOUNT, v.reveal("t1", p.vaultRef(), "payment", "officer-1").orElseThrow());
        List<String> stored = Jdbc.query(keepAlive, "SELECT number_enc FROM CMP_NATIONAL_ID_VAULT", rs -> rs.getString(1));
        assertEquals(1, stored.size());
        assertTrue(FieldEncryptor.isEncrypted(stored.get(0)));
        assertEquals(p.vaultRef(), v.protect("t1", "BANK_ACCOUNT", ACCOUNT, true).vaultRef(),
                "the same value of the same type keeps its reference key");
    }

    @Test
    void theRegistryHashIsUnchanged() {
        HmacIdentifierVault hashOnly = new HmacIdentifierVault(REGISTRY_PEPPER);
        RegistryCustodyVault v = vault();
        assertEquals(hashOnly.protect("t1", "BANK_ACCOUNT", ACCOUNT, false), v.protect("t1", "BANK_ACCOUNT", ACCOUNT, false),
                "an unvaulted identifier is exactly the hash-only result");
        assertEquals(hashOnly.protect("t1", "BANK_ACCOUNT", ACCOUNT, false).hashHex(),
                v.protect("t1", "BANK_ACCOUNT", ACCOUNT, true).hashHex(), "vaulting does not change the lookup hash");
        assertNull(v.protect("t1", "PAN", "ABCDE1234F", false).vaultRef());
        assertTrue(Jdbc.query(keepAlive, "SELECT reference_key FROM CMP_NATIONAL_ID_VAULT WHERE id_type='PAN'",
                rs -> rs.getString(1)).isEmpty(), "an unvaulted identifier never reaches the custody store");
    }

    @Test
    void aRevealWithoutActorOrPurposeIsRefusedAndNotAudited() {
        RegistryCustodyVault v = vault();
        String ref = v.protect("t1", "BANK_ACCOUNT", ACCOUNT, true).vaultRef();
        for (String[] reason : new String[][]{{null, "officer-1"}, {" ", "officer-1"}, {"payment", null}, {"payment", ""}}) {
            assertThrows(RegistryException.class, () -> v.reveal("t1", ref, reason[0], reason[1]),
                    "purpose " + reason[0] + ", actor " + reason[1]);
        }
        assertEquals(List.of(ComplianceAudit.NATIONAL_ID_VAULTED), chain("t1"), "a refused reveal reads nothing");
        HmacIdentifierVault ephemeral = HmacIdentifierVault.ephemeral();
        String devRef = ephemeral.protect("t1", "BANK_ACCOUNT", ACCOUNT, true).vaultRef();
        assertThrows(RegistryException.class, () -> ephemeral.reveal("t1", devRef, "payment", null),
                "the development vault refuses the same way");
    }

    @Test
    void everyRevealIsAuditedWithItsActorAndPurpose() {
        RegistryCustodyVault v = vault();
        String ref = v.protect("t1", "BANK_ACCOUNT", ACCOUNT, true).vaultRef();
        v.reveal("t1", ref, "payment", "officer-1");
        assertEquals(List.of(ComplianceAudit.NATIONAL_ID_VAULTED, ComplianceAudit.NATIONAL_ID_REVEALED), chain("t1"));
        // the envelope carries the payload digest; the payload itself is in AUDIT_PAYLOAD (erasable, AuditChain)
        String payload = Jdbc.one(keepAlive, "SELECT p.payload FROM AUDIT_EVENT e JOIN AUDIT_PAYLOAD p ON p.event_id ="
                        + " e.event_id WHERE e.chain_id=? AND e.event_type=?", rs -> rs.getString(1),
                ComplianceAudit.tenantChain("t1"), ComplianceAudit.NATIONAL_ID_REVEALED).orElseThrow();
        assertTrue(payload.contains("officer-1") && payload.contains("payment"), payload);
        assertFalse(payload.contains(ACCOUNT), "the audit holds the masked value only");
    }

    @Test
    void tenantsAreIsolated() {
        RegistryCustodyVault v = vault();
        RegistryPorts.ProtectedValue t1 = v.protect("t1", "BANK_ACCOUNT", ACCOUNT, true);
        RegistryPorts.ProtectedValue t2 = v.protect("t2", "BANK_ACCOUNT", ACCOUNT, true);
        assertNotEquals(t1.vaultRef(), t2.vaultRef());
        assertNotEquals(t1.hashHex(), t2.hashHex());
        assertTrue(v.reveal("t2", t1.vaultRef(), "payment", "officer-1").isEmpty(),
                "a reference key of another tenant reveals nothing");
    }

    @Test
    void theHashOnlyVaultStillRefusesToHoldClearValues() {
        assertThrows(RegistryException.class,
                () -> new HmacIdentifierVault(REGISTRY_PEPPER).protect("t1", "BANK_ACCOUNT", ACCOUNT, true));
        assertNull(vault().developmentOnlyReason(), "a configured pepper with durable custody is production-safe");
        RegistryCustodyVault randomPepper = new RegistryCustodyVault(HmacIdentifierVault.ephemeral(),
                new EncryptedNationalIdVault(D, tx(), ENC, CUSTODY_PEPPER, new ComplianceAudit(new AuditChain(D)), null));
        assertTrue(randomPepper.developmentOnlyReason() != null,
                "a random hash pepper stays development-only (hashes would not match after a restart)");
    }
}
