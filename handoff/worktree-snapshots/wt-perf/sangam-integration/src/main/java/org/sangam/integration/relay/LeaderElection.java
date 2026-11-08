// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.relay;

import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;

import java.time.Duration;

/**
 * Database lease for leader-elected maintenance services (R29 "cleanup runs as a leader-elected service"): outbox
 * retention, idempotency cleanup, inbox expiry, tombstone purge, reconciliation scan. Table
 * {@code INTEGRATION_LEADER(name PK, holder, epoch, expires_at)}; the database clock decides expiry (A-1).
 *
 * <p>{@link #tryAcquire} takes or renews the lease; a different node can take it only after it expired. The
 * {@code epoch} increases on every take-over, so work done under a lost lease can be fenced by the caller.
 */
public final class LeaderElection {

    private final Dialect dialect;
    private final Transactions tx;
    private final String node;
    private final Duration ttl;

    public LeaderElection(Dialect dialect, Transactions tx, String node, Duration ttl) {
        this.dialect = dialect;
        this.tx = tx;
        this.node = node;
        this.ttl = ttl;
    }

    /** Takes or renews leadership of {@code name}; returns the epoch held, or -1 when another node leads. */
    public long tryAcquire(String name) {
        return tx.inTransaction(c -> {
            Jdbc.insertIfAbsent(c, dialect, "INSERT INTO INTEGRATION_LEADER(name, holder, epoch, expires_at) VALUES (?,NULL,0,"
                    + dialect.now() + ")", name);
            int renewed = Jdbc.update(c, "UPDATE INTEGRATION_LEADER SET expires_at=" + dialect.nowPlusMillisParam()
                    + " WHERE name=? AND holder=? AND expires_at > " + dialect.now(), ttl.toMillis(), name, node);
            if (renewed == 0) {
                int taken = Jdbc.update(c, "UPDATE INTEGRATION_LEADER SET holder=?, epoch=epoch+1, expires_at="
                        + dialect.nowPlusMillisParam() + " WHERE name=? AND (holder IS NULL OR expires_at <= " + dialect.now() + ")",
                        node, ttl.toMillis(), name);
                if (taken == 0) {
                    return -1L;
                }
            }
            return Jdbc.one(c, "SELECT epoch FROM INTEGRATION_LEADER WHERE name=? AND holder=?", rs -> rs.getLong(1), name, node)
                    .orElse(-1L);
        });
    }

    public boolean isLeader(String name) {
        return tryAcquire(name) >= 0;
    }

    public void release(String name) {
        tx.inTransaction(c -> Jdbc.update(c, "UPDATE INTEGRATION_LEADER SET holder=NULL, expires_at=" + dialect.now()
                + " WHERE name=? AND holder=?", name, node));
    }
}
