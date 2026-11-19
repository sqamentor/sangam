// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.layer;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

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
 * On Oracle the skip-locked select in {@link Inbox#claim} and {@link Inbox#expire} can return a row a peer claimed or
 * processed, and committed, after the select's snapshot. H2 cannot reproduce that snapshot, so the peer's committed
 * change is injected on the same connection right before the follow-up UPDATE; the fenced UPDATE must not move it.
 */
class InboxSkipLockedFenceH2Test {

    @Test
    void aMessageAPeerProcessedAfterTheSnapshotIsNotClaimedAgain() throws Exception {
        JdbcDataSource h2 = h2();
        Transactions plain = Transactions.of(h2);
        Inbox seed = new Inbox(Dialect.POSTGRESQL, plain, "seed", Duration.ofSeconds(30), Duration.ZERO, null);
        seed.receive("t1", "src", "m1", "k1", "{}", Duration.ofHours(1));
        seed.receive("t1", "src", "m2", "k2", "{}", Duration.ofHours(1));

        boolean[] raced = {false};
        Transactions racing = Transactions.of(racingDataSource(h2, raced, "UPDATE INBOX SET state='CLAIMED'",
                "UPDATE INBOX SET state='PROCESSED' WHERE message_id='m1'"));
        Inbox inbox = new Inbox(Dialect.POSTGRESQL, racing, "n1", Duration.ofSeconds(30), Duration.ZERO, null);

        List<Inbox.Message> claimed = inbox.claim(10);

        assertTrue(raced[0], "the race was injected");
        assertEquals(List.of("m2"), claimed.stream().map(Inbox.Message::messageId).toList(),
                "a message a peer processed is not claimed again");
        assertEquals(Inbox.State.PROCESSED, plain.inTransaction(c -> Inbox.state(c, "t1", "src", "m1")));
        assertEquals(Inbox.State.CLAIMED, plain.inTransaction(c -> Inbox.state(c, "t1", "src", "m2")));
    }

    @Test
    void aMessageAPeerClaimedAfterTheSnapshotIsNotExpiredNorAudited() throws Exception {
        assertNotExpiredWhenPeerSets("CLAIMED");
    }

    @Test
    void aMessageAPeerProcessedAfterTheSnapshotIsNotExpiredNorAudited() throws Exception {
        assertNotExpiredWhenPeerSets("PROCESSED");
    }

    private static void assertNotExpiredWhenPeerSets(String peerState) throws Exception {
        JdbcDataSource h2 = h2();
        Transactions plain = Transactions.of(h2);
        Inbox seed = new Inbox(Dialect.POSTGRESQL, plain, "seed", Duration.ofSeconds(30), Duration.ZERO, null);
        seed.receive("t1", "src", "m1", "k1", "{}", Duration.ofHours(1));
        seed.receive("t1", "src", "m2", "k2", "{}", Duration.ofHours(1));
        plain.inTransaction(c -> Jdbc.update(c, "UPDATE INBOX SET expires_at = TIMESTAMP '2000-01-01 00:00:00'"));

        boolean[] raced = {false};
        Transactions racing = Transactions.of(racingDataSource(h2, raced, "UPDATE INBOX SET state='EXPIRED'",
                "UPDATE INBOX SET state='" + peerState + "' WHERE message_id='m1'"));
        Inbox inbox = new Inbox(Dialect.POSTGRESQL, racing, "n1", Duration.ofSeconds(30), Duration.ZERO,
                new AuditChain(Dialect.POSTGRESQL));

        int expired = inbox.expire();

        assertTrue(raced[0], "the race was injected");
        assertEquals(1, expired, "the count excludes the message the peer took");
        assertEquals(Inbox.State.valueOf(peerState), plain.inTransaction(c -> Inbox.state(c, "t1", "src", "m1")));
        assertEquals(Inbox.State.EXPIRED, plain.inTransaction(c -> Inbox.state(c, "t1", "src", "m2")));
        assertEquals(1L, (long) plain.inTransaction(c -> Jdbc.one(c,
                        "SELECT COUNT(*) FROM AUDIT_EVENT WHERE event_type='INBOX_MESSAGE_EXPIRED'", rs -> rs.getLong(1)).orElseThrow()),
                "only the message actually expired is audited");
    }

    private static JdbcDataSource h2() throws Exception {
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        try (Connection c = h2.getConnection()) {
            Schema.create(c, Dialect.POSTGRESQL);
        }
        return h2;
    }

    /** Before the first statement starting with {@code prefix} is prepared, runs {@code peerSql} on the same connection. */
    private static DataSource racingDataSource(DataSource real, boolean[] raced, String prefix, String peerSql) {
        return (DataSource) Proxy.newProxyInstance(InboxSkipLockedFenceH2Test.class.getClassLoader(),
                new Class<?>[]{DataSource.class}, (p, m, args) -> {
                    Object out = invoke(real, m, args);
                    if (out instanceof Connection conn) {
                        return Proxy.newProxyInstance(InboxSkipLockedFenceH2Test.class.getClassLoader(),
                                new Class<?>[]{Connection.class}, (p2, m2, a2) -> {
                                    if (m2.getName().equals("prepareStatement") && !raced[0]
                                            && String.valueOf(a2[0]).startsWith(prefix)) {
                                        raced[0] = true;
                                        Jdbc.update(conn, peerSql);
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
