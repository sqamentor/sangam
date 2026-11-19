// SPDX-License-Identifier: Apache-2.0
package org.sangam.channels.messenger;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.sangam.channels.ChannelsSchema;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Schema;
import org.sangam.core.layer.Transactions;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * On Oracle the claim's skip-locked select can return a media row a peer completed (media_state='DONE') and committed
 * after the select's snapshot. The claim UPDATE is fenced on the claim predicate; a row it does not move is not this
 * node's claim, so it is not returned and stays DONE (its media is not delivered again). H2 cannot reproduce the
 * snapshot, so the peer's committed write is injected right before the claim UPDATE.
 */
class InboundMediaQueueSkipLockedFenceH2Test {

    @Test
    void aRowAPeerCompletedAfterTheSnapshotIsNotClaimedAgain() throws Exception {
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        try (Connection c = h2.getConnection()) {
            Schema.create(c, Dialect.POSTGRESQL);
            ChannelsSchema.create(c, Dialect.POSTGRESQL);
        }
        Transactions plain = Transactions.of(h2);
        boolean enqueued = plain.inTransaction(c -> {
            Jdbc.update(c, "INSERT INTO CH_INBOUND_SEEN(tenant_id, channel, message_id, seen_at) VALUES ('t','wa','m1',"
                    + Dialect.POSTGRESQL.now() + ")");
            return InboundMediaQueue.enqueue(c, Dialect.POSTGRESQL, "t", "wa", "m1", "x1", "p1", "media-1");
        });
        assertTrue(enqueued);

        boolean[] raced = {false};
        InboundMediaQueue queue = new InboundMediaQueue(Dialect.POSTGRESQL, Transactions.of(racingDataSource(h2, raced)), "n1",
                Duration.ofMinutes(5));

        List<InboundMediaQueue.Pending> claimed = queue.claim(10);

        assertTrue(raced[0], "the race was injected");
        assertEquals(List.of(), claimed, "a row the peer completed is not returned");
        String state = plain.inTransaction(c -> Jdbc.one(c, "SELECT media_state FROM CH_INBOUND_SEEN WHERE message_id='m1'",
                rs -> rs.getString(1)).orElseThrow());
        assertEquals("DONE", state, "the row stays DONE");
    }

    /** Before the first claim UPDATE, completes every media row on the same connection (as a committed peer would). */
    private static DataSource racingDataSource(DataSource real, boolean[] raced) {
        return (DataSource) Proxy.newProxyInstance(InboundMediaQueueSkipLockedFenceH2Test.class.getClassLoader(),
                new Class<?>[]{DataSource.class}, (p, m, args) -> {
                    Object out = invoke(real, m, args);
                    if (out instanceof Connection conn) {
                        return Proxy.newProxyInstance(InboundMediaQueueSkipLockedFenceH2Test.class.getClassLoader(),
                                new Class<?>[]{Connection.class}, (p2, m2, a2) -> {
                                    if (m2.getName().equals("prepareStatement") && !raced[0]
                                            && String.valueOf(a2[0]).startsWith("UPDATE CH_INBOUND_SEEN SET media_state='CLAIMED'")) {
                                        raced[0] = true;
                                        Jdbc.update(conn, "UPDATE CH_INBOUND_SEEN SET media_state='DONE', media_owner=NULL, media_next_at=NULL");
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
