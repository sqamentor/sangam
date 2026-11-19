// SPDX-License-Identifier: Apache-2.0
package org.sangam.casefile.deadline;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.sangam.casefile.CaseFileSchema;
import org.sangam.casefile.common.CaseEvents;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * On Oracle the sweep's skip-locked select can return a deadline a peer sweeper (or a meet) processed and committed
 * after the select's snapshot. The sweep re-reads the locked row and works it only while it is still due; a deadline
 * the peer settled gets no second DEADLINE_BREACHED event or notification and keeps the state the peer set. H2 cannot
 * reproduce the snapshot, so the peer's committed write is injected right after the skip-locked select.
 */
class DeadlineServiceSkipLockedFenceH2Test {

    @Test
    void aDeadlineAPeerSettledAfterTheSnapshotIsNotProcessedAgain() throws Exception {
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        try (Connection c = h2.getConnection()) {
            CaseFileSchema.create(c, Dialect.POSTGRESQL);
        }
        CaseEvents events = new CaseEvents(Dialect.POSTGRESQL, null);
        Transactions plain = Transactions.of(h2);
        DeadlineService setup = new DeadlineService(Dialect.POSTGRESQL, plain, events, null, null, null, null);
        String id = plain.inTransaction(c -> setup.startAt(c, "T1", "CASE", "C-1", "REPLY", "SEAT-1", null,
                Instant.parse("2020-01-01T00:00:00Z"), null, null, List.of()).id());

        boolean[] raced = {false};
        List<String> notified = new ArrayList<>();
        DeadlineService sweeper = new DeadlineService(Dialect.POSTGRESQL, Transactions.of(racingDataSource(h2, raced)), events,
                null, (c, m) -> {
                    notified.add(m.kind());
                    return null;
                }, null, null);

        sweeper.sweep(10);

        assertTrue(raced[0], "the race was injected");
        assertEquals(List.of(), notified, "no notification for a deadline the peer settled");
        int breached = plain.inTransaction(c -> Jdbc.one(c, "SELECT COUNT(*) FROM CF_EVENT WHERE event_type='DEADLINE_BREACHED'",
                rs -> rs.getInt(1)).orElseThrow());
        assertEquals(0, breached, "no DEADLINE_BREACHED event for a deadline the peer settled");
        DeadlineService.State state = plain.inTransaction(c -> setup.get(c, "T1", id).orElseThrow().state());
        assertEquals(DeadlineService.State.MET, state, "the row keeps the state the peer set");
    }

    /** Right after the skip-locked select, meets every deadline on the same connection (as a committed peer would). */
    private static DataSource racingDataSource(DataSource real, boolean[] raced) {
        boolean[] selected = {false};
        return (DataSource) Proxy.newProxyInstance(DeadlineServiceSkipLockedFenceH2Test.class.getClassLoader(),
                new Class<?>[]{DataSource.class}, (p, m, args) -> {
                    Object out = invoke(real, m, args);
                    if (out instanceof Connection conn) {
                        return Proxy.newProxyInstance(DeadlineServiceSkipLockedFenceH2Test.class.getClassLoader(),
                                new Class<?>[]{Connection.class}, (p2, m2, a2) -> {
                                    if (m2.getName().equals("prepareStatement")) {
                                        if (selected[0] && !raced[0]) {
                                            raced[0] = true;
                                            Jdbc.update(conn, "UPDATE CF_DEADLINE SET state='MET', met_at=due_at, next_check_at=NULL,"
                                                    + " row_version=row_version+1");
                                        }
                                        if (String.valueOf(a2[0]).contains("SKIP LOCKED")) {
                                            selected[0] = true;
                                        }
                                    }
                                    return invoke(conn, m2, a2);
                                });
                    }
                    return out;
                });
    }

    private static Object invoke(Object target, java.lang.reflect.Method m, Object[] args) throws Throwable {
        try {
            return m.invoke(target, args);
        } catch (InvocationTargetException ex) {
            throw ex.getCause();
        }
    }
}
