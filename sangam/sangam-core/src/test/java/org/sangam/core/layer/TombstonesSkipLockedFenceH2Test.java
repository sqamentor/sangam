// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.layer;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * On Oracle the skip-locked select in {@link Tombstones#sweepExpiredLeases} can return a tombstone a worker acknowledged
 * (CANCELLED) and committed after the select's snapshot. The ack is injected on the same connection right before the
 * follow-up UPDATE: the tombstone stays CANCELLED and no UNKNOWN_OUTCOME reconciliation item is opened for it.
 */
class TombstonesSkipLockedFenceH2Test {

    @Test
    void aTombstoneAckedAfterTheSnapshotStaysCancelledAndOpensNoItem() throws Exception {
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        try (Connection c = h2.getConnection()) {
            Schema.create(c, Dialect.POSTGRESQL);
        }
        Transactions plain = Transactions.of(h2);
        Tombstones tombstones = new Tombstones(Dialect.POSTGRESQL, new ReconciliationQueue(Dialect.POSTGRESQL, null),
                new AuditChain(Dialect.POSTGRESQL));
        plain.inTransaction(c -> {
            tombstones.write(c, "t1", "task1", UUID.randomUUID(), "root1", 1, Duration.ZERO);
            tombstones.write(c, "t1", "task2", UUID.randomUUID(), "root1", 1, Duration.ZERO);
            return Jdbc.update(c, "UPDATE TOMBSTONE SET lease_expires_at = TIMESTAMP '2000-01-01 00:00:00'");
        });

        boolean[] raced = {false};
        int swept = plain.inTransaction(real -> {
            Connection racing = (Connection) Proxy.newProxyInstance(TombstonesSkipLockedFenceH2Test.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, (p, m, a) -> {
                        if (m.getName().equals("prepareStatement") && !raced[0]
                                && String.valueOf(a[0]).startsWith("UPDATE TOMBSTONE SET state='UNKNOWN_OUTCOME'")) {
                            raced[0] = true;
                            tombstones.ackCancel(real, "t1", "task1", 1);   // the worker's committed cancel ack
                        }
                        try {
                            return m.invoke(real, a);
                        } catch (InvocationTargetException ex) {
                            throw ex.getCause();
                        }
                    });
            return tombstones.sweepExpiredLeases(racing);
        });

        assertTrue(raced[0], "the race was injected");
        assertEquals(1, swept, "the count excludes the acknowledged tombstone");
        assertEquals(Tombstones.State.CANCELLED,
                plain.inTransaction(c -> tombstones.find(c, "t1", "task1")).orElseThrow().state());
        assertEquals(Tombstones.State.UNKNOWN_OUTCOME,
                plain.inTransaction(c -> tombstones.find(c, "t1", "task2")).orElseThrow().state());
        assertEquals(0L, (long) plain.inTransaction(c -> Jdbc.one(c,
                        "SELECT COUNT(*) FROM RECONCILIATION_ITEM WHERE task_id='task1'", rs -> rs.getLong(1)).orElseThrow()),
                "no reconciliation item for the acknowledged tombstone");
        assertEquals(1L, (long) plain.inTransaction(c -> Jdbc.one(c,
                "SELECT COUNT(*) FROM RECONCILIATION_ITEM WHERE task_id='task2'", rs -> rs.getLong(1)).orElseThrow()));
    }
}
