// SPDX-License-Identifier: Apache-2.0
package org.sangam.modeller.lease;

import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;

import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Canvas locking and takeover: one edit lease per file, kept alive by heartbeat. Others see read-only and may request a takeover. A
 * takeover is audited and keeps the former editor's draft. The increasing {@code fence} goes with a save, so a former holder's late
 * save is refused by {@link #checkFence}. The optimistic version check on save stays in the package store.
 */
public final class EditLeases {

    public record Lease(String fileId, String holder, long fence, Instant expiresAt, String requestedBy) {
    }

    public record Outcome(boolean editable, Lease lease) {
    }

    public static final class StaleEditor extends RuntimeException {
        public StaleEditor() {
            super("EDIT_LEASE_LOST");
        }
    }

    private final Dialect dialect;
    private final Transactions tx;
    private final AuditChain audit;
    private final Duration ttl;

    public EditLeases(Dialect dialect, Transactions tx, AuditChain audit, Duration ttl) {
        this.dialect = dialect;
        this.tx = tx;
        this.audit = audit;
        this.ttl = ttl;
    }

    /** Takes a free or expired lease, or renews one's own; otherwise the caller stays read-only. */
    public Outcome acquire(String tenantId, String fileId, String user) {
        return tx.inTransaction(c -> {
            Instant now = Jdbc.dbNow(c, dialect);
            Jdbc.insertIfAbsent(c, dialect, "INSERT INTO MOD_EDIT_LEASE(tenant_id,file_id,holder,fence,expires_at) VALUES (?,?,?,0,?)", tenantId, fileId, user, now.minusSeconds(1));
            Lease l = lock(c, tenantId, fileId);
            if (l.holder().equals(user) && l.fence() > 0 && l.expiresAt().isAfter(now)) {
                Jdbc.update(c, "UPDATE MOD_EDIT_LEASE SET expires_at=? WHERE tenant_id=? AND file_id=?", now.plus(ttl), tenantId, fileId);
                return new Outcome(true, read(c, tenantId, fileId));
            }
            if (l.expiresAt().isAfter(now)) {
                return new Outcome(false, l);
            }
            Jdbc.update(c, "UPDATE MOD_EDIT_LEASE SET holder=?, fence=fence+1, expires_at=?, requested_by=NULL WHERE tenant_id=? AND file_id=?", user, now.plus(ttl), tenantId, fileId);
            return new Outcome(true, read(c, tenantId, fileId));
        });
    }

    public void heartbeat(String tenantId, String fileId, String user, long fence) {
        tx.inTransaction(c -> {
            Instant now = Jdbc.dbNow(c, dialect);
            if (Jdbc.update(c, "UPDATE MOD_EDIT_LEASE SET expires_at=? WHERE tenant_id=? AND file_id=? AND holder=? AND fence=? AND expires_at>=?", now.plus(ttl), tenantId, fileId, user, fence, now) == 0) {
                throw new StaleEditor();
            }
            return null;
        });
    }

    public void release(String tenantId, String fileId, String user, long fence) {
        tx.inTransaction(c -> Jdbc.update(c, "UPDATE MOD_EDIT_LEASE SET expires_at=? WHERE tenant_id=? AND file_id=? AND holder=? AND fence=?", Jdbc.dbNow(c, dialect).minusSeconds(1), tenantId, fileId, user, fence));
    }

    /** Marks a request the holder's client shows. */
    public void requestTakeover(String tenantId, String fileId, String user) {
        tx.inTransaction(c -> Jdbc.update(c, "UPDATE MOD_EDIT_LEASE SET requested_by=? WHERE tenant_id=? AND file_id=?", user, tenantId, fileId));
    }

    /** Takes the lease from the holder. {@code permitted} is the caller's result of the R19 check. The former draft is kept. */
    public Lease takeover(String tenantId, String fileId, String user, boolean permitted, String formerDraft, String reason) {
        if (!permitted) {
            throw new SecurityException("TAKEOVER_NOT_PERMITTED");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("a reason is required");
        }
        return tx.inTransaction(c -> {
            Instant now = Jdbc.dbNow(c, dialect);
            Lease l = lock(c, tenantId, fileId);
            Jdbc.update(c, "INSERT INTO MOD_TAKEOVER_DRAFT(tenant_id,file_id,former,taken_by,content,created_at) VALUES (?,?,?,?,?,?)", tenantId, fileId, l.holder(), user, formerDraft, now);
            Jdbc.update(c, "UPDATE MOD_EDIT_LEASE SET holder=?, fence=fence+1, expires_at=?, requested_by=NULL WHERE tenant_id=? AND file_id=?", user, now.plus(ttl), tenantId, fileId);
            audit.append(c, tenantId, "modeller:" + fileId, "MODEL_LEASE_TAKEOVER", Map.of("file", fileId, "from", l.holder(), "to", user, "reason", reason));
            return read(c, tenantId, fileId);
        });
    }

    /** Call in the save transaction. Refuses a save from anyone but the live holder with the current fence. */
    public void checkFence(Connection c, String tenantId, String fileId, String user, long fence) {
        Lease l = lock(c, tenantId, fileId);
        if (!l.holder().equals(user) || l.fence() != fence || !l.expiresAt().isAfter(Jdbc.dbNow(c, dialect))) {
            throw new StaleEditor();
        }
    }

    public Optional<Lease> current(String tenantId, String fileId) {
        return tx.inTransaction(c -> Jdbc.one(c, SELECT + " WHERE tenant_id=? AND file_id=?", EditLeases::map, tenantId, fileId));
    }

    private Lease lock(Connection c, String tenantId, String fileId) {
        return Jdbc.one(c, dialect.lockingSelect("file_id,holder,fence,expires_at,requested_by", "MOD_EDIT_LEASE", "tenant_id=? AND file_id=?"), EditLeases::map, tenantId, fileId)
                .orElseThrow(() -> new IllegalStateException("no lease row for " + fileId));
    }

    private static Lease read(Connection c, String tenantId, String fileId) {
        return Jdbc.one(c, SELECT + " WHERE tenant_id=? AND file_id=?", EditLeases::map, tenantId, fileId).orElseThrow();
    }

    private static final String SELECT = "SELECT file_id,holder,fence,expires_at,requested_by FROM MOD_EDIT_LEASE";

    private static Lease map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Lease(rs.getString(1), rs.getString(2), rs.getLong(3), Jdbc.instant(rs, "expires_at"), rs.getString(5));
    }
}
