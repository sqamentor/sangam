// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.relay;

import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;

/**
 * Outbox retention (SANGAM.md VI.2.5, R4, R29).
 *
 * <p>A day of outbox events may be removed only when <b>every</b> delivery of it is {@code DONE} or {@code SKIPPED}.
 * Where the persistence layer partitions {@code OUTBOX} by day (PostgreSQL/Oracle range partitions), it drops whole
 * partitions with the same test; this class is the portable path that works on every dialect: it deletes eligible
 * events older than {@code keep} in bounded batches (deliveries first, then the event), and reports days that are
 * older than {@code keep} but still hold open deliveries (the alert of VI.2.5).
 *
 * <p>Runs under a {@link LeaderElection} lease.
 */
public final class OutboxRetention {

    private static final Logger LOG = LoggerFactory.getLogger(OutboxRetention.class);

    public record Result(int eventsDeleted, int deliveriesDeleted, long overdueOpenDeliveries) {
    }

    private final Dialect dialect;
    private final Transactions tx;
    private final Duration keep;
    private final int batch;

    public OutboxRetention(Dialect dialect, Transactions tx, Duration keep, int batch) {
        this.dialect = dialect;
        this.tx = tx;
        this.keep = keep;
        this.batch = batch;
    }

    public Result run() {
        int events = 0;
        int deliveries = 0;
        while (true) {
            int[] r = tx.inTransaction(c -> {
                List<Long> ids = Jdbc.query(c, dialect.skipLockedSelect("o.id", "OUTBOX", "o",
                                "o.created_at < " + dialect.nowPlusMillisParam()
                                        + " AND NOT EXISTS (SELECT 1 FROM OUTBOX_DELIVERY d WHERE d.outbox_id = o.id"
                                        + " AND d.state NOT IN ('DONE','SKIPPED'))", "o.id", batch),
                        rs -> rs.getLong(1), -keep.toMillis());
                int d = 0;
                for (Long id : ids) {
                    d += Jdbc.update(c, "DELETE FROM OUTBOX_DELIVERY WHERE outbox_id=?", id);
                    Jdbc.update(c, "DELETE FROM OUTBOX WHERE id=?", id);
                }
                return new int[]{ids.size(), d};
            });
            events += r[0];
            deliveries += r[1];
            if (r[0] < batch) {
                break;
            }
        }
        long overdue = tx.inTransaction(c -> Jdbc.one(c, "SELECT COUNT(*) FROM OUTBOX_DELIVERY d JOIN OUTBOX o ON o.id = d.outbox_id"
                        + " WHERE d.state NOT IN ('DONE','SKIPPED') AND o.created_at < " + dialect.nowPlusMillisParam(),
                rs -> rs.getLong(1), -keep.toMillis()).orElse(0L));
        if (overdue > 0) {
            LOG.warn("outbox retention: {} open deliveries are older than {} and keep their day (VI.2.5 alert)", overdue, keep);
        }
        return new Result(events, deliveries, overdue);
    }
}
