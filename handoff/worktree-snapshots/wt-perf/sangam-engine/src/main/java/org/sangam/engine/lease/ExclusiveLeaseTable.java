// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.lease;

import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.sangam.core.layer.Jdbc;
import org.sangam.engine.db.EngineDialect;

/**
 * VI.6 exclusivity (R35, A-7): at most one locked task per scope. {@code execution} mode uses
 * {@code scope_key = rootId + ':' + group}; {@code resource:<expr>} mode uses {@code scope_key = 'res:' + value}
 * across all instances of the tenant. The row is written in the <b>acquisition</b> transaction (L1, before any L2;
 * acquisition never takes L2), so a candidate is skipped while a live lease exists; it is released on complete, fail,
 * release or expiry. Its {@code lock_version} is the {@code resourceFence} handed to the worker.
 */
public final class ExclusiveLeaseTable {

    private final EngineDialect dialect;
    private final Duration lockWait;

    public ExclusiveLeaseTable(EngineDialect dialect, Duration lockWait) {
        this.dialect = dialect;
        this.lockWait = lockWait;
    }

    private record Held(String holder, long version, long expiresAtMs) {
    }

    /**
     * Tries to take the scope for {@code holderTaskId}. Returns the new fence version, or empty when another task
     * holds a live lease on the scope (the caller skips the candidate).
     */
    public Optional<Long> acquire(Connection c, String tenant, String scopeKey, String holderTaskId, long expiresAtMs,
                                  long nowMs) {
        Optional<Held> cur = Jdbc.one(c, dialect.lockingSelect("holder_task_id, lock_version, expires_at_ms",
                        "EXCLUSIVE_LEASE", "tenant_id = ? AND scope_key = ?", lockWait),
                rs -> new Held(rs.getString(1), rs.getLong(2), rs.getLong(3)), tenant, scopeKey);
        if (cur.isEmpty()) {
            Jdbc.update(c, "INSERT INTO EXCLUSIVE_LEASE (tenant_id, scope_key, holder_task_id, lock_version, expires_at_ms, "
                    + "created_at) VALUES (?,?,?,1,?,?)", tenant, scopeKey, holderTaskId, expiresAtMs, Instant.ofEpochMilli(nowMs));
            return Optional.of(1L);
        }
        Held h = cur.get();
        if (!h.holder().equals(holderTaskId) && h.expiresAtMs() > nowMs) {
            return Optional.empty();
        }
        long next = h.version() + 1;
        Jdbc.update(c, "UPDATE EXCLUSIVE_LEASE SET holder_task_id = ?, lock_version = ?, expires_at_ms = ? "
                + "WHERE tenant_id = ? AND scope_key = ?", holderTaskId, next, expiresAtMs, tenant, scopeKey);
        return Optional.of(next);
    }

    /** Heartbeat of the holder. */
    public void extend(Connection c, String tenant, String scopeKey, String holderTaskId, long expiresAtMs) {
        Jdbc.update(c, "UPDATE EXCLUSIVE_LEASE SET expires_at_ms = ? WHERE tenant_id = ? AND scope_key = ? "
                + "AND holder_task_id = ?", expiresAtMs, tenant, scopeKey, holderTaskId);
    }

    /** Release on complete, fail, release or expiry; only the holder's row is removed. */
    public void release(Connection c, String tenant, String scopeKey, String holderTaskId) {
        Jdbc.update(c, "DELETE FROM EXCLUSIVE_LEASE WHERE tenant_id = ? AND scope_key = ? AND holder_task_id = ?",
                tenant, scopeKey, holderTaskId);
    }

    /** {@code tenant|scope} as stored in {@code SANGAM_LEASE.exclusive_scope}. */
    public static String encode(String tenant, String scopeKey) {
        return tenant + "|" + scopeKey;
    }

    /** Inverse of {@link #encode}: {tenant, scope}. */
    public static String[] decode(String stored) {
        int i = stored.indexOf('|');
        return new String[]{stored.substring(0, i), stored.substring(i + 1)};
    }
}
