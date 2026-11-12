// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Schema;
import org.sangam.core.layer.Transactions;
import org.sangam.core.security.FieldEncryptor;
import org.sangam.security.audit.SecurityAuditService;
import org.sangam.security.dialect.SecurityDialect;

/**
 * M-085: the scheduled field re-encryption job ({@link FieldReencryptionJob}) on H2 in PostgreSQL mode, with the real
 * tenant key store ({@link JdbcTenantKeyStore}) and a store of envelopes ({@code DOC.body = "enc:" + envelope}).
 * Covers: every row moves to the active key with its plaintext unchanged; resume after a stop, each row rewritten once;
 * a newer rotation restarts the run for the new key; dry run writes nothing; a row under a destroyed key fails and is
 * left as it is; a row the application changed meanwhile is never overwritten; other tenants are untouched; the
 * completion event and the metric. Written for the test phase (not run here).
 */
class FieldReencryptionJobH2Test {

    static final String T = "t1";
    static final String U = "t2";

    Transactions tx;
    JdbcTenantKeyStore keys;
    AesGcmFieldEncryptor enc;
    final Map<String, AtomicLong> metric = new ConcurrentHashMap<>();

    @BeforeEach
    void db() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:reenc" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        try (Connection c = ds.getConnection()) {
            Schema.create(c, Dialect.POSTGRESQL);   // AUDIT_EVENT, AUDIT_PAYLOAD, ROOT_SEQ, AUTH_POLICY_VERSION
        }
        tx = Transactions.of(ds);
        tx.inTransaction(c -> {
            Jdbc.update(c, "CREATE TABLE SEC_TENANT_KEY (tenant_id VARCHAR(64) NOT NULL, key_id VARCHAR(80) NOT NULL,"
                    + " version INTEGER NOT NULL, status VARCHAR(16) NOT NULL, kek_id VARCHAR(64) NOT NULL, wrapped_key VARCHAR(255),"
                    + " created_at TIMESTAMP WITH TIME ZONE NOT NULL, retired_at TIMESTAMP WITH TIME ZONE,"
                    + " destroyed_at TIMESTAMP WITH TIME ZONE, PRIMARY KEY (tenant_id, version))");
            Jdbc.update(c, "CREATE TABLE SEC_REENCRYPT_PROGRESS (tenant_id VARCHAR(64) NOT NULL, source VARCHAR(64) NOT NULL,"
                    + " target_key_id VARCHAR(80) NOT NULL, status VARCHAR(16) NOT NULL, last_key VARCHAR(255),"
                    + " scanned BIGINT NOT NULL, rewritten BIGINT NOT NULL, failed BIGINT NOT NULL,"
                    + " started_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL,"
                    + " finished_at TIMESTAMP WITH TIME ZONE, PRIMARY KEY (tenant_id, source))");
            Jdbc.update(c, "CREATE TABLE DOC (tenant_id VARCHAR(64) NOT NULL, id VARCHAR(64) NOT NULL, body TEXT,"
                    + " PRIMARY KEY (tenant_id, id))");
            return null;
        });
        keys = new JdbcTenantKeyStore(tx, SecurityDialect.H2, MasterKeyProvider.ephemeralForDevelopment(), Duration.ZERO);
        enc = new AesGcmFieldEncryptor(keys);
    }

    /** The test store: DOC rows of one tenant, ordered by id, envelope behind "enc:", context "doc:" + id. */
    static class DocSource implements ReencryptionSource {
        @Override
        public String name() {
            return "doc";
        }

        @Override
        public List<Row> page(Connection c, String tenantId, String afterKey, int limit) {
            return Jdbc.query(c, "SELECT id, body FROM DOC WHERE tenant_id=? AND id > ? ORDER BY id LIMIT " + limit,
                    rs -> {
                        String body = rs.getString(2);
                        String env = body != null && body.startsWith("enc:") ? body.substring(4) : null;
                        return new Row(rs.getString(1), "doc:" + rs.getString(1), body, env);
                    }, tenantId, afterKey == null ? "" : afterKey);
        }

        @Override
        public String withEnvelope(String stored, String newEnvelope) {
            return "enc:" + newEnvelope;
        }

        @Override
        public boolean replace(Connection c, String tenantId, String key, String expectedStored, String newStored) {
            return Jdbc.update(c, "UPDATE DOC SET body=? WHERE tenant_id=? AND id=? AND body=?",
                    newStored, tenantId, key, expectedStored) == 1;
        }
    }

    FieldReencryptionJob job(int batch) {
        return new FieldReencryptionJob(SecurityDialect.H2, tx, enc, new SecurityAuditService(SecurityDialect.H2),
                (tenant, source, outcome, rows) -> metric.computeIfAbsent(tenant + "|" + outcome, k -> new AtomicLong()).addAndGet(rows),
                batch);
    }

    void put(String tenant, String id, String plaintext) {
        String body = plaintext == null ? "clear-legacy" : "enc:" + enc.encrypt(tenant, "doc:" + id, plaintext);
        tx.inTransaction(c -> Jdbc.update(c, "INSERT INTO DOC(tenant_id, id, body) VALUES (?,?,?)", tenant, id, body));
    }

    String body(String tenant, String id) {
        return tx.inTransaction(c -> Jdbc.one(c, "SELECT body FROM DOC WHERE tenant_id=? AND id=?", rs -> rs.getString(1),
                tenant, id).orElseThrow());
    }

    String keyOf(String tenant, String id) {
        return FieldEncryptor.keyIdOf(body(tenant, id).substring(4));
    }

    String plain(String tenant, String id) {
        return enc.decrypt(tenant, "doc:" + id, body(tenant, id).substring(4));
    }

    long metric(String tenant, String outcome) {
        AtomicLong v = metric.get(tenant + "|" + outcome);
        return v == null ? 0 : v.get();
    }

    long completedEvents(String tenant) {
        return tx.inTransaction(c -> Jdbc.one(c, "SELECT COUNT(*) FROM AUDIT_EVENT WHERE tenant_id=? AND chain_id=?"
                + " AND event_type='FIELD_REENCRYPTION_COMPLETED'", rs -> rs.getLong(1), tenant, "sec:" + tenant).orElse(0L));
    }

    String progress(String tenant, String column) {
        return tx.inTransaction(c -> Jdbc.one(c, "SELECT " + column + " FROM SEC_REENCRYPT_PROGRESS WHERE tenant_id=? AND source='doc'",
                rs -> rs.getString(1), tenant).orElse(null));
    }

    @Test
    void everyRowMovesToTheActiveKeyWithItsPlaintextUnchanged() {
        for (int i = 1; i <= 5; i++) {
            put(T, "d" + i, "value-" + i);
        }
        put(T, "d6", null);                      // a clear legacy value: skipped, never touched
        String old = keys.active(T).keyId();
        String fresh = keys.rotate(T);
        assertNotEquals(old, fresh);

        FieldReencryptionJob.Result r = job(100).run(T, new DocSource(), 10);

        assertTrue(r.done());
        assertEquals(6, r.scanned());
        assertEquals(5, r.rewritten());
        assertEquals(0, r.failed());
        for (int i = 1; i <= 5; i++) {
            assertEquals(fresh, keyOf(T, "d" + i));
            assertEquals("value-" + i, plain(T, "d" + i));
        }
        assertEquals("clear-legacy", body(T, "d6"));
        assertEquals("DONE", progress(T, "status"));
        assertEquals(fresh, progress(T, "target_key_id"));
        assertEquals(1, completedEvents(T), "one completion event on the tenant security chain");
        assertEquals(5, metric(T, "rewritten"));
        assertEquals(1, metric(T, "skipped"));

        FieldReencryptionJob.Result again = job(100).run(T, new DocSource(), 10);
        assertTrue(again.done());
        assertEquals(0, again.scanned(), "a DONE run for the active key does nothing");
        assertEquals(1, completedEvents(T));
    }

    @Test
    void aStoppedRunResumesAfterTheLastCommittedBatchAndRewritesEachRowOnce() {
        for (int i = 1; i <= 5; i++) {
            put(T, "d" + i, "value-" + i);
        }
        String fresh = keys.rotate(T);
        FieldReencryptionJob.Result first = job(2).run(T, new DocSource(), 1);
        assertFalse(first.done());
        assertEquals(2, first.rewritten());
        assertEquals("d2", progress(T, "last_key"));
        assertEquals("RUNNING", progress(T, "status"));

        FieldReencryptionJob.Result rest = job(2).run(T, new DocSource(), 10);
        assertTrue(rest.done());
        assertEquals(3, rest.rewritten());
        assertEquals(5, metric(T, "rewritten"), "every row rewritten exactly once over both passes");
        for (int i = 1; i <= 5; i++) {
            assertEquals(fresh, keyOf(T, "d" + i));
        }
        assertEquals("5", progress(T, "rewritten"));
    }

    @Test
    void aNewerRotationRestartsTheRunForTheNewKey() {
        for (int i = 1; i <= 4; i++) {
            put(T, "d" + i, "value-" + i);
        }
        keys.rotate(T);
        job(2).run(T, new DocSource(), 1);          // d1, d2 moved to k2
        String k3 = keys.rotate(T);
        FieldReencryptionJob.Result r = job(2).run(T, new DocSource(), 10);
        assertTrue(r.done());
        assertEquals(k3, r.targetKeyId());
        assertEquals(4, r.scanned(), "restarted from the first row");
        for (int i = 1; i <= 4; i++) {
            assertEquals(k3, keyOf(T, "d" + i));
            assertEquals("value-" + i, plain(T, "d" + i));
        }
    }

    @Test
    void aDryRunCountsThePendingRowsAndWritesNothing() {
        for (int i = 1; i <= 3; i++) {
            put(T, "d" + i, "value-" + i);
        }
        String old = keys.active(T).keyId();
        keys.rotate(T);
        put(T, "d4", "value-4");                    // already under the new key
        FieldReencryptionJob.Result r = job(2).dryRun(T, new DocSource());
        assertTrue(r.dryRun());
        assertEquals(4, r.scanned());
        assertEquals(3, r.pending());
        assertEquals(old, keyOf(T, "d1"), "nothing rewritten");
        assertEquals(null, progress(T, "status"), "no progress row");
        assertEquals(0, completedEvents(T));
        assertEquals(3, metric(T, "pending"));
    }

    @Test
    void aRowUnderADestroyedKeyFailsAndIsLeftAsItIs() {
        put(T, "d1", "value-1");
        String k1 = keys.active(T).keyId();
        keys.rotate(T);
        put(T, "d2", "value-2");
        String k2 = keys.active(T).keyId();
        put(T, "d0", "value-0");
        // d0 and d2 under k2; move d1 by hand to nothing: destroy k1 after a second rotation
        String k3 = keys.rotate(T);
        keys.destroy(T, k1);
        String before = body(T, "d1");

        FieldReencryptionJob.Result r = job(100).run(T, new DocSource(), 10);

        assertTrue(r.done());
        assertEquals(1, r.failed());
        assertEquals(2, r.rewritten());
        assertEquals(before, body(T, "d1"), "the crypto-shredded value is left as it is");
        assertEquals(k3, keyOf(T, "d0"));
        assertEquals(k3, keyOf(T, "d2"));
        assertNotEquals(k2, keyOf(T, "d2"));
        assertEquals("1", progress(T, "failed"));
        assertEquals(1, metric(T, "failed"));
        assertThrows(org.sangam.core.security.SangamSecurityException.class, () -> plain(T, "d1"));
    }

    @Test
    void aRowTheApplicationChangedMeanwhileIsNeverOverwritten() {
        put(T, "d1", "value-1");
        put(T, "d2", "value-2");
        String fresh = keys.rotate(T);
        DocSource racing = new DocSource() {
            @Override
            public List<Row> page(Connection c, String tenantId, String afterKey, int limit) {
                List<Row> rows = super.page(c, tenantId, afterKey, limit);
                // the application rewrites d1 after the job read it (in the job's transaction, as a concurrent writer
                // that committed first would be seen by the compare-and-set)
                Jdbc.update(c, "UPDATE DOC SET body=? WHERE tenant_id=? AND id='d1'",
                        "enc:" + enc.encrypt(tenantId, "doc:d1", "value-1-new"), tenantId);
                return rows;
            }
        };
        FieldReencryptionJob.Result r = job(100).run(T, racing, 10);
        assertEquals(1, r.rewritten(), "only d2");
        assertEquals("value-1-new", plain(T, "d1"), "the application's value stays");
        assertEquals(fresh, keyOf(T, "d1"));
        assertEquals(fresh, keyOf(T, "d2"));
        assertEquals("value-2", plain(T, "d2"));
    }

    @Test
    void anotherTenantIsUntouched() {
        put(T, "d1", "value-1");
        put(U, "d1", "other-1");
        String uKey = keys.active(U).keyId();
        keys.rotate(T);
        keys.rotate(U);
        job(100).run(T, new DocSource(), 10);
        assertEquals(uKey, keyOf(U, "d1"), "tenant t2 is a run of its own");
        assertEquals("other-1", plain(U, "d1"));
        assertEquals(null, progress(U, "status"));
    }

    @Test
    void aSourceNeedsAShortName() {
        DocSource unnamed = new DocSource() {
            @Override
            public String name() {
                return "x".repeat(65);
            }
        };
        assertThrows(IllegalArgumentException.class, () -> job(10).run(T, unnamed, 1));
        assertThrows(IllegalArgumentException.class, () -> job(0));
    }
}
