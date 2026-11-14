// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;
import org.sangam.core.security.FieldEncryptor;
import org.sangam.ops.secrets.SecretAdmin;
import org.sangam.persistence.SangamSchema;
import org.sangam.security.audit.SecurityAuditService;
import org.sangam.security.crypto.AesGcmFieldEncryptor;
import org.sangam.security.crypto.FieldReencryptionJob;
import org.sangam.security.crypto.JdbcTenantKeyStore;
import org.sangam.security.crypto.MasterKeyProvider;
import org.sangam.security.crypto.ReencryptionSource.Row;
import org.sangam.security.dialect.SecurityDialect;

/**
 * SL-181 (regression): after a tenant key rotation the field re-encryption job moves the secret store
 * ({@code OPS_SECRET}, {@link SecretAdmin}) to the active key, and {@code SecretAdmin.resolver} still reads every value.
 * H2 in PostgreSQL mode with the product schema, the real tenant key store and the real job. Also: the listed secret
 * version is unchanged by the rewrap, other tenants are untouched, a clear legacy value is skipped, and the
 * compare-and-set never overwrites a value imported meanwhile. Written for the test phase (not run here).
 */
class SecretStoreReencryptionSourceH2Test {

    static final String T = "t-sl181";
    static final String U = "u-sl181";

    Transactions tx;
    JdbcTenantKeyStore keys;
    AesGcmFieldEncryptor enc;
    SecretAdmin secrets;
    final SecretStoreReencryptionSource.Secrets source = new SecretStoreReencryptionSource.Secrets();

    @BeforeEach
    void db() {
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:sl181" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        SangamSchema.apply(h2);
        tx = Transactions.of(h2);
        keys = new JdbcTenantKeyStore(tx, SecurityDialect.H2, MasterKeyProvider.ephemeralForDevelopment(), Duration.ZERO);
        enc = new AesGcmFieldEncryptor(keys);
        secrets = new SecretAdmin(Dialect.POSTGRESQL, tx, new AuditChain(Dialect.POSTGRESQL), enc);
    }

    FieldReencryptionJob job(int batch) {
        return new FieldReencryptionJob(SecurityDialect.H2, tx, enc, new SecurityAuditService(SecurityDialect.H2), null, batch);
    }

    String stored(String tenant, String ref) {
        return tx.inTransaction(c -> Jdbc.one(c, "SELECT envelope FROM OPS_SECRET WHERE tenant_id=? AND ref_name=?",
                rs -> rs.getString(1), tenant, ref).orElseThrow());
    }

    int version(String tenant, String ref) {
        return tx.inTransaction(c -> Jdbc.one(c, "SELECT version FROM OPS_SECRET WHERE tenant_id=? AND ref_name=?",
                rs -> rs.getInt(1), tenant, ref).orElseThrow());
    }

    @Test
    void aRotatedTenantsSecretsMoveToTheActiveKeyAndStillResolve() {
        secrets.importAll(T, Map.of("db/password", "pw-1", "api/token", "tok-2", "smtp", "mail-3"), "ops");
        secrets.importAll(T, Map.of("smtp", "mail-3b"), "ops");              // version 2
        secrets.importAll(U, Map.of("db/password", "other-tenant"), "ops");
        tx.inTransaction(c -> Jdbc.update(c, "INSERT INTO OPS_SECRET(tenant_id,ref_name,envelope,version,updated_by,"
                + "updated_at,reason) VALUES (?,?,?,?,?,?,?)", T, "legacy", "clear-legacy", 1, "ops", Instant.now(), null));
        String old = keys.active(T).keyId();
        String uStored = stored(U, "db/password");
        String fresh = keys.rotate(T);
        assertNotEquals(old, fresh);

        FieldReencryptionJob.Result r = job(2).run(T, source, 10);

        assertTrue(r.done());
        assertEquals(4, r.scanned());
        assertEquals(3, r.rewritten());
        assertEquals(0, r.failed());
        for (String ref : List.of("db/password", "api/token", "smtp")) {
            assertEquals(fresh, FieldEncryptor.keyIdOf(stored(T, ref)), ref + " is under the active key");
        }
        assertEquals("pw-1", secrets.resolver(T).require("secret://db/password"));
        assertEquals("tok-2", secrets.resolver(T).require("secret://api/token"));
        assertEquals("mail-3b", secrets.resolver(T).require("secret://smtp"));
        assertEquals(2, version(T, "smtp"), "a rewrap is not a new secret version");
        assertEquals(1, version(T, "db/password"));
        assertEquals("clear-legacy", stored(T, "legacy"), "a row without an envelope is skipped");
        assertEquals(uStored, stored(U, "db/password"), "another tenant is untouched");
        assertEquals("other-tenant", secrets.resolver(U).require("secret://db/password"));
    }

    @Test
    void rowsArePagedInRefOrderAfterTheCursorWithTheResolversContext() {
        secrets.importAll(T, Map.of("b", "2", "a", "1", "c", "3"), "ops");
        secrets.importAll(U, Map.of("a0", "x"), "ops");
        List<Row> rows = tx.inTransaction(c -> {
            List<Row> out = new ArrayList<>(source.page(c, T, null, 2));
            out.addAll(source.page(c, T, out.get(out.size() - 1).key(), 2));
            return out;
        });
        assertEquals(List.of("a", "b", "c"), rows.stream().map(Row::key).toList());
        assertTrue(rows.stream().allMatch(r -> SecretAdmin.CONTEXT.equals(r.context()) && r.envelope().equals(r.stored())));
        assertEquals("ops-secret", source.name());
    }

    @Test
    void replaceIsACompareAndSetThatNeverOverwritesANewerImport() {
        secrets.importAll(T, Map.of("k", "v1"), "ops");
        String before = stored(T, "k");
        String rewrapped = enc.reencrypt(T, SecretAdmin.CONTEXT, before);
        secrets.importAll(T, Map.of("k", "v2"), "ops");                        // the application wrote meanwhile
        boolean stale = tx.inTransaction(c -> source.replace(c, T, "k", before, rewrapped));
        assertFalse(stale, "a value imported meanwhile is never overwritten");
        assertEquals("v2", secrets.resolver(T).require("secret://k"));
        boolean missing = tx.inTransaction(c -> source.replace(c, T, "missing", before, rewrapped));
        assertFalse(missing);

        String current = stored(T, "k");
        String next = source.withEnvelope(current, enc.reencrypt(T, SecretAdmin.CONTEXT, current));
        boolean written = tx.inTransaction(c -> source.replace(c, T, "k", current, next));
        assertTrue(written);
        assertEquals(next, stored(T, "k"));
        assertEquals(2, version(T, "k"));
        assertEquals("v2", secrets.resolver(T).require("secret://k"));
        assertNull(tx.inTransaction(c -> source.page(c, "nobody", null, 5)).stream().findFirst().orElse(null));
    }
}
