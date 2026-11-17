// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.relay;

import org.sangam.core.api.ReadWork;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Outbox;
import org.sangam.core.layer.Schema;
import org.sangam.core.layer.Transactions;

/**
 * R40 / VI.2.4 outbox capacity on H2 (PostgreSQL mode). A backlog that does not drain (service rate not above the
 * arrival rate, which is also every first sample after a start) must still be measured: {@code drainSeconds} is null
 * and the level follows the used bytes, so {@link BackpressureGate} refuses new starts. The drain expression used to mix
 * a primitive and a null {@code Double} in one conditional, so the null was unboxed (NullPointerException): the sample
 * failed every time the backlog did not drain, {@code last()} kept the old level and backpressure never engaged.
 */
class OutboxCapacityMonitorTest {

    static final Dialect D = Dialect.POSTGRESQL;

    String url;
    Connection keepAlive;

    @BeforeEach
    void schema() throws Exception {
        url = "jdbc:h2:mem:cap" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        keepAlive = DriverManager.getConnection(url, "sa", "");
        Schema.create(keepAlive, D);
    }

    @AfterEach
    void close() throws Exception {
        keepAlive.close();
    }

    Transactions tx() {
        return new Transactions() {
            @Override
            public <T> T inTransaction(Function<Connection, T> work) {
                try (Connection c = DriverManager.getConnection(url, "sa", "")) {
                    c.setAutoCommit(false);
                    try {
                        T r = work.apply(c);
                        c.commit();
                        return r;
                    } catch (RuntimeException e) {
                        c.rollback();
                        throw e;
                    }
                } catch (java.sql.SQLException e) {
                    throw new IllegalStateException(e);
                }
            }

            @Override
            public <T> T read(ReadWork<T> work) {
                try (Connection c = DriverManager.getConnection(url, "sa", "")) {
                    c.setAutoCommit(false);
                    try {
                        T r = work.read(c);
                        c.commit();
                        return r;
                    } catch (RuntimeException e) {
                        c.rollback();
                        throw e;
                    }
                } catch (java.sql.SQLException e) {
                    throw new IllegalStateException(e);
                }
            }
        };
    }

    private void append(String root) {
        tx().inTransaction(c -> new Outbox(D).append(c, "T", root, "t.event", "{}", List.of(Outbox.Destination.ordered("dest"))));
    }

    @Test
    void aBacklogThatDoesNotDrainIsMeasuredAndEngagesBackpressure() {
        append("r1");
        // H2 has no catalog size function: the monitor estimates 2048 bytes per delivery, so one open delivery is 100%
        OutboxCapacityMonitor monitor = new OutboxCapacityMonitor(D, tx(), 2048, Duration.ofSeconds(60));
        OutboxCapacityMonitor.Sample first = monitor.sample();   // first sample: no previous count, service = arrival rate
        assertEquals(1, first.openDeliveries());
        assertNull(first.drainSeconds(), "the backlog does not drain");
        assertEquals(OutboxCapacityMonitor.Level.CRITICAL, first.level());
        assertSame(first, monitor.last());
        BackpressureGate.BackpressureException refused = assertThrows(BackpressureGate.BackpressureException.class,
                () -> new BackpressureGate(monitor).check(BackpressureGate.Category.START));
        assertEquals(Duration.ofSeconds(30), refused.retryAfter(), "no drain estimate: the default Retry-After");

        append("r2");   // the backlog grows: service rate 0, below the arrival rate
        OutboxCapacityMonitor.Sample growing = monitor.sample();
        assertEquals(2, growing.openDeliveries());
        assertNull(growing.drainSeconds());
        assertEquals(0.0, growing.servicePerSecond());
        assertSame(growing, monitor.last());
    }

    @Test
    void anEmptyOrDrainedOutboxReportsZeroDrainTime() throws Exception {
        OutboxCapacityMonitor monitor = new OutboxCapacityMonitor(D, tx(), 1L << 30, Duration.ofSeconds(60));
        assertEquals(0.0, monitor.sample().drainSeconds(), "nothing open");
        append("r1");
        monitor.sample();
        tx().inTransaction(c -> Jdbc.update(c, "UPDATE OUTBOX_DELIVERY SET state='DONE'"));
        Thread.sleep(20);
        OutboxCapacityMonitor.Sample drained = monitor.sample();   // service rate above the arrival rate
        assertEquals(0, drained.openDeliveries());
        assertEquals(0.0, drained.drainSeconds());
        assertEquals(OutboxCapacityMonitor.Level.NORMAL, drained.level());
    }
}
