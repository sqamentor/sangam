// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.crypto;

import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;
import org.sangam.security.dialect.SecurityDialect;
import org.sangam.security.support.Sql;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link TenantKeyStore} on table {@code SEC_TENANT_KEY}: random AES-256 data keys, stored only wrapped (AES-256-GCM
 * under a KEK from {@link MasterKeyProvider}, AAD = the key id). Key-store transactions are short and separate from
 * business transactions. Unwrapped keys are cached in memory; the active key id is re-read after
 * {@code activeTtl} so a rotation on another node takes effect quickly (old envelopes stay readable either way).
 */
public final class JdbcTenantKeyStore implements TenantKeyStore {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int NONCE = 12;

    private record Active(TenantKey key, long loadedAtNanos) {
    }

    private final Transactions tx;
    private final SecurityDialect dialect;
    private final MasterKeyProvider kek;
    private final Duration activeTtl;
    private final Map<String, SecretKey> unwrapped = new ConcurrentHashMap<>();
    private final Map<String, Active> active = new ConcurrentHashMap<>();

    public JdbcTenantKeyStore(Transactions tx, SecurityDialect dialect, MasterKeyProvider kek, Duration activeTtl) {
        this.tx = tx;
        this.dialect = dialect;
        this.kek = kek;
        this.activeTtl = activeTtl == null ? Duration.ofSeconds(60) : activeTtl;
    }

    @Override
    public TenantKey active(String tenantId) {
        Active a = active.get(tenantId);
        if (a != null && System.nanoTime() - a.loadedAtNanos() < activeTtl.toNanos()) {
            return a.key();
        }
        TenantKey k = tx.inTransaction(c -> {
            Optional<String> id = activeId(c, tenantId);
            if (id.isEmpty()) {
                create(c, tenantId, 1);
                id = activeId(c, tenantId);
            }
            String keyId = id.orElseThrow();
            return new TenantKey(keyId, load(c, tenantId, keyId).orElseThrow());
        });
        active.put(tenantId, new Active(k, System.nanoTime()));
        return k;
    }

    @Override
    public Optional<TenantKey> byId(String tenantId, String keyId) {
        if (!keyId.startsWith(tenantId + "/")) {
            return Optional.empty();       // a key of another tenant is never used (VI.4 rule 6)
        }
        SecretKey cached = unwrapped.get(keyId);
        if (cached != null) {
            return Optional.of(new TenantKey(keyId, cached));
        }
        return tx.inTransaction(c -> load(c, tenantId, keyId)).map(k -> new TenantKey(keyId, k));
    }

    @Override
    public String rotate(String tenantId) {
        String id = tx.inTransaction(c -> {
            Optional<Integer> current = Jdbc.one(c, dialect.lockingSelect("version", "SEC_TENANT_KEY",
                    "tenant_id=? AND status='ACTIVE'"), rs -> rs.getInt(1), tenantId);
            int next = Jdbc.one(c, "SELECT COALESCE(MAX(version), 0) FROM SEC_TENANT_KEY WHERE tenant_id=?",
                    rs -> rs.getInt(1), tenantId).orElse(0) + 1;
            current.ifPresent(v -> Jdbc.update(c, "UPDATE SEC_TENANT_KEY SET status='DECRYPT_ONLY', retired_at=? WHERE tenant_id=?"
                    + " AND version=?", Sql.dbNow(c, dialect), tenantId, v));
            create(c, tenantId, next);
            return keyId(tenantId, next);
        });
        active.remove(tenantId);
        return id;
    }

    @Override
    public void destroy(String tenantId, String keyId) {
        tx.inTransaction(c -> {
            int n = Jdbc.update(c, "UPDATE SEC_TENANT_KEY SET status='DESTROYED', wrapped_key=NULL, destroyed_at=?"
                    + " WHERE tenant_id=? AND key_id=? AND status='DECRYPT_ONLY'", Sql.dbNow(c, dialect), tenantId, keyId);
            if (n != 1) {
                throw new IllegalStateException("only a decrypt-only key can be destroyed: " + keyId);
            }
            return null;
        });
        unwrapped.remove(keyId);
    }

    @Override
    public List<KeyInfo> keys(String tenantId) {
        return tx.inTransaction(c -> Jdbc.query(c, "SELECT key_id, version, status, kek_id FROM SEC_TENANT_KEY WHERE tenant_id=?"
                        + " ORDER BY version",
                rs -> new KeyInfo(rs.getString(1), tenantId, rs.getInt(2), rs.getString(3), rs.getString(4)), tenantId));
    }

    /** Re-wraps every live key of a tenant with the current KEK (after a KEK rotation). Returns the number re-wrapped. */
    public int rewrapAll(String tenantId) {
        return tx.inTransaction(c -> {
            int n = 0;
            for (String[] row : Jdbc.query(c, "SELECT key_id, kek_id FROM SEC_TENANT_KEY WHERE tenant_id=? AND status <> 'DESTROYED'",
                    rs -> new String[]{rs.getString(1), rs.getString(2)}, tenantId)) {
                if (row[1].equals(kek.currentId())) {
                    continue;
                }
                SecretKey k = load(c, tenantId, row[0]).orElseThrow();
                Jdbc.update(c, "UPDATE SEC_TENANT_KEY SET wrapped_key=?, kek_id=? WHERE tenant_id=? AND key_id=?",
                        wrap(k, row[0]), kek.currentId(), tenantId, row[0]);
                n++;
            }
            return n;
        });
    }

    // ------------------------------------------------------------------ internals

    static String keyId(String tenantId, int version) {
        return tenantId + "/k" + version;
    }

    private Optional<String> activeId(Connection c, String tenantId) {
        return Jdbc.one(c, "SELECT key_id FROM SEC_TENANT_KEY WHERE tenant_id=? AND status='ACTIVE'", rs -> rs.getString(1), tenantId);
    }

    private void create(Connection c, String tenantId, int version) {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String id = keyId(tenantId, version);
        SecretKey k = new SecretKeySpec(raw, "AES");
        // concurrent first use on two nodes: the duplicate insert is ignored and both read the winner
        Sql.insertIfAbsent(c, dialect, "INSERT INTO SEC_TENANT_KEY(tenant_id, key_id, version, status, kek_id, wrapped_key,"
                        + " created_at, retired_at, destroyed_at) VALUES (?,?,?,'ACTIVE',?,?,?,NULL,NULL)",
                tenantId, id, version, kek.currentId(), wrap(k, id), Sql.dbNow(c, dialect));
    }

    private Optional<SecretKey> load(Connection c, String tenantId, String keyId) {
        SecretKey cached = unwrapped.get(keyId);
        if (cached != null) {
            return Optional.of(cached);
        }
        Optional<String[]> row = Jdbc.one(c, "SELECT kek_id, wrapped_key FROM SEC_TENANT_KEY WHERE tenant_id=? AND key_id=?"
                + " AND status <> 'DESTROYED'", rs -> new String[]{rs.getString(1), rs.getString(2)}, tenantId, keyId);
        if (row.isEmpty()) {
            return Optional.empty();
        }
        SecretKey wrapKey = kek.byId(row.get()[0]).orElseThrow(() ->
                new IllegalStateException("KEK " + row.get()[0] + " of key " + keyId + " is not configured"));
        SecretKey k = unwrap(wrapKey, row.get()[1], keyId);
        unwrapped.put(keyId, k);
        return Optional.of(k);
    }

    private String wrap(SecretKey dek, String keyId) {
        try {
            byte[] nonce = new byte[NONCE];
            RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, kek.current(), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(("sangam-dek:" + keyId).getBytes(StandardCharsets.UTF_8));
            byte[] ct = cipher.doFinal(dek.getEncoded());
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(NONCE + ct.length).put(nonce).put(ct).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("key wrap failed", e);
        }
    }

    private static SecretKey unwrap(SecretKey wrapKey, String wrapped, String keyId) {
        try {
            byte[] all = Base64.getDecoder().decode(wrapped);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, wrapKey, new GCMParameterSpec(128, all, 0, NONCE));
            cipher.updateAAD(("sangam-dek:" + keyId).getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(cipher.doFinal(all, NONCE, all.length - NONCE), "AES");
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("key unwrap failed for " + keyId + " (wrong KEK or tampered row)", e);
        }
    }
}
