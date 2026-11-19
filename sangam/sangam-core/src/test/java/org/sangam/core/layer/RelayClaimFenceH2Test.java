// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.layer;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A claim candidate can stop being PENDING between the candidate select and the claim UPDATE (on Oracle the
 * SKIP LOCKED select locks rows chosen from the statement's snapshot, so another relay may already have claimed and
 * finished the row). The claim UPDATE is fenced on state='PENDING'; a row it does not move to CLAIMED is not this
 * relay's claim and must not be published. Oracle CI showed it as OutboxRelayIT publishing 155 of 150 events.
 */
class RelayClaimFenceH2Test {

    @Test
    void aCandidateThatIsNoLongerPendingWhenClaimedIsNotPublished() throws Exception {
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        try (Connection c = h2.getConnection()) {
            Schema.create(c, Dialect.POSTGRESQL);
        }
        Outbox outbox = new Outbox(Dialect.POSTGRESQL);
        Transactions plain = Transactions.of(h2);
        Outbox.Appended e = plain.inTransaction(c ->
                outbox.append(c, "t1", "root1", "E", "{}", List.of(Outbox.Destination.ordered("history"))));

        // Another relay finishes the row after this relay selected it and before its claim UPDATE runs.
        boolean[] raced = {false};
        Transactions racing = Transactions.of(racingDataSource(h2, raced));
        List<String> published = new ArrayList<>();
        Relay relay = new Relay(Dialect.POSTGRESQL, racing, (d, ev) -> published.add(d + "#" + ev.rootSeq()),
                new Relay.Config("n1", 10, Duration.ofSeconds(30), 10, Duration.ZERO, Duration.ZERO), null);

        Relay.CycleResult r = relay.runOnce();

        assertTrue(raced[0], "the race was injected");
        assertEquals(List.of(), published, "a row this relay did not claim is not published again");
        assertEquals(0, r.claimed());
        assertEquals("DONE", plain.inTransaction(c -> Relay.state(c, e.outboxId(), "history")));
    }

    /** Before the first claim UPDATE, marks every delivery DONE on the same connection (as a committed peer would). */
    private static DataSource racingDataSource(DataSource real, boolean[] raced) {
        return (DataSource) Proxy.newProxyInstance(RelayClaimFenceH2Test.class.getClassLoader(),
                new Class<?>[]{DataSource.class}, (p, m, args) -> {
                    Object out = invoke(real, m, args);
                    if (out instanceof Connection conn) {
                        return Proxy.newProxyInstance(RelayClaimFenceH2Test.class.getClassLoader(),
                                new Class<?>[]{Connection.class}, (p2, m2, a2) -> {
                                    if (m2.getName().equals("prepareStatement") && !raced[0]
                                            && String.valueOf(a2[0]).startsWith("UPDATE OUTBOX_DELIVERY SET state='CLAIMED'")) {
                                        raced[0] = true;
                                        Jdbc.update(conn, "UPDATE OUTBOX_DELIVERY SET state='DONE'");
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
