// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.connector.gov;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.integration.ConnectorDescriptor;
import org.sangam.core.integration.ConnectorProfile;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.ConnectorResult;
import org.sangam.core.integration.LookupResult;
import org.sangam.core.integration.SangamConnector;
import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.CapabilityClass;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Schema;
import org.sangam.core.layer.Transactions;
import org.sangam.integration.connector.ConnectorCallJournal;
import org.sangam.integration.connector.ConnectorRegistry;
import org.sangam.integration.connector.SecretResolver;
import org.sangam.integration.schema.IntegrationSchema;

/**
 * On Oracle the skip-locked select of {@code DltDeliveryStatusPoller.claim} locks rows chosen from the statement's
 * snapshot, so it can return a delivery a peer looked up (next lookup moved forward) or settled, and committed, after
 * that snapshot. H2 cannot reproduce the snapshot, so the peer's committed change is injected right before the claim
 * UPDATE: the poller must not count a lookup or call the provider for that delivery.
 */
class DltDeliveryStatusPollerSkipLockedFenceH2Test {

    private static final Dialect D = Dialect.POSTGRESQL;
    private static final String TENANT = "T1";

    private final String url = "jdbc:h2:mem:dltfence" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
    private final Connection keepAlive;
    private final ConnectorCallJournal journal = new ConnectorCallJournal(D);
    private final AtomicInteger lookups = new AtomicInteger();
    private final ConnectorRegistry registry = new ConnectorRegistry().register(new SangamConnector() {
        @Override
        public ConnectorDescriptor descriptor() {
            return new ConnectorDescriptor("sms-1", DltSmsConnector.TYPE, CapabilityClass.B_LOOKUP_ONLY,
                    List.of(DltSmsConnector.OP_SEND), ConnectorProfile.SIMULATOR);
        }

        @Override
        public ConnectorResult invoke(ConnectorRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public LookupResult lookup(ConnectorRequest request) {
            lookups.incrementAndGet();
            return new LookupResult.Unavailable("not yet");
        }
    });

    DltDeliveryStatusPollerSkipLockedFenceH2Test() throws SQLException {
        keepAlive = DriverManager.getConnection(url, "sa", "");
        Schema.create(keepAlive, D);
        IntegrationSchema.create(keepAlive, D);
    }

    @AfterEach
    void close() throws SQLException {
        keepAlive.close();
    }

    @Test
    void aDeliveryAPeerLookedUpAfterTheSnapshotIsNotLookedUpAgain() {
        UUID op = sentAndEnrolled();
        boolean[] raced = {false};
        int n = poller(raced, "UPDATE NOTIFICATION_DELIVERY SET lookups=lookups+1,"
                + " next_lookup_at=CURRENT_TIMESTAMP + INTERVAL '1' HOUR WHERE operation_id='" + op + "'").runOnce();

        assertTrue(raced[0], "the race was injected");
        assertEquals(0, n, "the peer's lookup is not repeated");
        assertEquals(0, lookups.get(), "the provider is not called");
        assertEquals(1, lookupCount(op), "lookups counted once, by the peer");
    }

    @Test
    void aDeliveryAPeerSettledAfterTheSnapshotIsNotLookedUp() {
        UUID op = sentAndEnrolled();
        boolean[] raced = {false};
        int n = poller(raced, "UPDATE NOTIFICATION_DELIVERY SET state='DELIVERED', state_rank=" + DltDeliveryReports.FINAL_RANK
                + " WHERE operation_id='" + op + "'").runOnce();

        assertTrue(raced[0], "the race was injected");
        assertEquals(0, n, "a settled delivery is not looked up");
        assertEquals(0, lookups.get(), "the provider is not called");
        assertEquals(0, lookupCount(op), "no lookup counted");
    }

    private UUID sentAndEnrolled() {
        UUID op = UUID.randomUUID();
        tx(null, null).inTransaction(c -> {
            journal.recordAttempt(c, op, TENANT, "sms-1", DltSmsConnector.OP_SEND, "case-1", "notice:1",
                    "{\"templateId\":\"1107000000000000001\",\"mobile\":\"9876543210\"}");
            journal.recordResult(c, op, ConnectorCallJournal.LastResult.SUCCESS, "msg-1", null);
            return null;
        });
        DltDeliveryStatusPoller enrolOnly = poller(null, null);
        assertEquals(1, enrolOnly.enrol(enrolOnly.lookupConnectors()));
        return op;
    }

    private DltDeliveryStatusPoller poller(boolean[] raced, String peerSql) {
        Transactions tx = tx(raced, peerSql);
        DltDeliveryReports reports = new DltDeliveryReports(D, tx, registry, journal, new AuditChain(D), SecretResolver.of(Map.of()),
                new DltDeliveryReports.Settings(Map.of(), Duration.ofMinutes(5), 0, 0), Clock.systemUTC());
        return new DltDeliveryStatusPoller(D, tx, registry, journal, reports,
                new DltDeliveryStatusPoller.Settings(Duration.ZERO, Duration.ofHours(1), Duration.ofDays(3), 3, 10));
    }

    private int lookupCount(UUID op) {
        return tx(null, null).inTransaction(c -> Jdbc.one(c, "SELECT lookups FROM NOTIFICATION_DELIVERY WHERE operation_id=?",
                rs -> rs.getInt(1), op)).orElseThrow();
    }

    /** Transactions whose connection runs {@code peerSql} (once) right before the claim UPDATE is prepared. */
    private Transactions tx(boolean[] raced, String peerSql) {
        return new Transactions() {
            @Override
            public <T> T inTransaction(Function<Connection, T> work) {
                try (Connection c = DriverManager.getConnection(url, "sa", "")) {
                    c.setAutoCommit(false);
                    try {
                        T r = work.apply(raced == null ? c : racing(c, raced, peerSql));
                        c.commit();
                        return r;
                    } catch (RuntimeException e) {
                        c.rollback();
                        throw e;
                    }
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
            }
        };
    }

    private static Connection racing(Connection conn, boolean[] raced, String peerSql) {
        return (Connection) Proxy.newProxyInstance(DltDeliveryStatusPollerSkipLockedFenceH2Test.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (p, m, a) -> {
                    if (m.getName().equals("prepareStatement") && !raced[0]
                            && String.valueOf(a[0]).startsWith("UPDATE NOTIFICATION_DELIVERY SET lookups=lookups+1")) {
                        raced[0] = true;
                        Jdbc.update(conn, peerSql);
                    }
                    try {
                        return m.invoke(conn, a);
                    } catch (InvocationTargetException ex) {
                        throw ex.getCause();
                    }
                });
    }
}
