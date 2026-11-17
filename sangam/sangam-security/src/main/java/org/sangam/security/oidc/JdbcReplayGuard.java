// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.oidc;

import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Hashes;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Cluster-wide {@link JtiReplayGuard} (SL-138, M-006; owner decision 2026-11-12): one row per used
 * (issuer, {@code jti}) in {@code SEC_CLIENT_JTI}, shared by every node, kept until the token's expiry plus skew.
 *
 * <ul>
 *   <li><b>Key.</b> {@code replay_key = SHA-256(issuer || NUL || jti)}, so an issuer or id of any length fits the
 *       32-byte primary key; the issuer is kept for operators.</li>
 *   <li><b>First use</b> is the insert that wins the primary key, committed in its own short transaction before the
 *       call is served: a second node that sees the same token loses the key and refuses it. On PostgreSQL the insert
 *       is {@code ON CONFLICT DO NOTHING}; elsewhere a duplicate key is the refusal.</li>
 *   <li><b>Expiry.</b> A row whose {@code expires_at} has passed no longer counts: it is deleted and the id may be used
 *       again (the verifier has already refused an expired token, so only a new token can reuse an old id). Expired
 *       rows are also purged in one statement at most once per {@code purgeInterval} on each node.</li>
 *   <li><b>Time.</b> Expiry comparisons use the same {@link Clock} as the verifier, bound as a parameter, so a node's
 *       verdict does not depend on a second clock.</li>
 * </ul>
 * The table is created by the product changelog (changeset 0038-02).
 */
public final class JdbcReplayGuard implements JtiReplayGuard {

    static final int MAX_ISSUER = 1000;

    private final Transactions tx;
    private final Dialect dialect;
    private final Clock clock;
    private final Duration purgeInterval;
    private final AtomicLong nextPurgeMs = new AtomicLong(Long.MIN_VALUE);

    /**
     * @param tx            plain short transactions on the product pool
     * @param dialect       the product database dialect
     * @param clock         the verifier's clock (null: UTC system clock)
     * @param purgeInterval how often a node deletes expired rows (null: 5 minutes; zero: on every call)
     */
    public JdbcReplayGuard(Transactions tx, Dialect dialect, Clock clock, Duration purgeInterval) {
        this.tx = Objects.requireNonNull(tx, "tx");
        this.dialect = Objects.requireNonNull(dialect, "dialect");
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.purgeInterval = purgeInterval == null ? Duration.ofMinutes(5) : purgeInterval;
    }

    public JdbcReplayGuard(Transactions tx, Dialect dialect, Clock clock) {
        this(tx, dialect, clock, null);
    }

    /** SHA-256 of the issuer, a NUL byte and the id. */
    static byte[] key(String issuer, String jti) {
        return Hashes.sha256((issuer + "\u0000" + jti).getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public boolean firstUse(String issuer, String jti, Instant expiresAt) {
        Objects.requireNonNull(issuer, "issuer");
        Objects.requireNonNull(jti, "jti");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Instant now = clock.instant();
        purgeIfDue(now);
        byte[] key = key(issuer, jti);
        String storedIssuer = issuer.length() > MAX_ISSUER ? issuer.substring(0, MAX_ISSUER) : issuer;
        if (claim(key, storedIssuer, expiresAt, now)) {
            return true;
        }
        // taken: still valid means a replay; an expired row is released and the insert tried once more (two nodes
        // reusing one expired id race on that insert, and only one wins)
        int released = tx.read(c -> Jdbc.update(c,
                "DELETE FROM SEC_CLIENT_JTI WHERE replay_key=? AND expires_at <= ?", key, now));
        return released == 1 && claim(key, storedIssuer, expiresAt, now);
    }

    /** The key-winning insert in its own committed transaction; a concurrent duplicate is a lost race, not an error. */
    private boolean claim(byte[] key, String issuer, Instant expiresAt, Instant now) {
        String insert = "INSERT INTO SEC_CLIENT_JTI(replay_key, issuer, expires_at, first_used_at) VALUES (?,?,?,?)";
        try {
            return tx.inTransaction(c -> Jdbc.insertIfAbsent(c, dialect, insert, key, issuer, expiresAt, now));
        } catch (Jdbc.LayerSqlException e) {
            if (e.getCause() instanceof java.sql.SQLException sql && dialect.isUniqueViolation(sql)) {
                return false;   // a concurrent first use committed the same key
            }
            throw e;
        }
    }

    /** Deletes every expired row now; returns how many. */
    public int purgeExpired() {
        Instant now = clock.instant();
        return tx.inTransaction(c -> Jdbc.update(c, "DELETE FROM SEC_CLIENT_JTI WHERE expires_at <= ?", now));
    }

    private void purgeIfDue(Instant now) {
        long ms = now.toEpochMilli();
        long due = nextPurgeMs.get();
        if (ms >= due && nextPurgeMs.compareAndSet(due, ms + purgeInterval.toMillis())) {
            try {
                tx.inTransaction(c -> Jdbc.update(c, "DELETE FROM SEC_CLIENT_JTI WHERE expires_at <= ?", now));
            } catch (RuntimeException ignored) {
                // housekeeping only: a failed purge never decides a token; the next interval retries
            }
        }
    }
}
