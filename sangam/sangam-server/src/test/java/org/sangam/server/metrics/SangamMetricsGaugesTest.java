// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.RuntimeService;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Outbox;
import org.sangam.core.layer.Schema;
import org.sangam.core.layer.Transactions;
import org.sangam.integration.relay.OutboxCapacityMonitor;
import org.sangam.integration.relay.OutboxRelayService;
import org.sangam.integration.relay.PublisherRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * M-091: the three gauges the alert rules need. {@code sangam.outbox.parked} counts PARKED deliveries only (the
 * undelivered gauge still includes them); {@code sangam.outbox.capacity.used.ratio} is the last capacity sample's
 * used ratio; {@code sangam.relay.last.cycle.age} is the seconds since this pod's relay last completed a cycle. Each is
 * NaN when its source is absent.
 */
class SangamMetricsGaugesTest {

    static final Dialect D = Dialect.POSTGRESQL;

    JdbcDataSource ds;
    Connection keepAlive;

    @BeforeEach
    void setUp() throws Exception {
        ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:metrics" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
        keepAlive = ds.getConnection();
        Schema.create(keepAlive, D);
    }

    @AfterEach
    void tearDown() throws Exception {
        keepAlive.close();
    }

    Transactions tx() {
        return new Transactions() {
            @Override
            public <T> T inTransaction(Function<Connection, T> work) {
                try (Connection c = ds.getConnection()) {
                    c.setAutoCommit(false);
                    try {
                        T r = work.apply(c);
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

    static <T> ObjectProvider<T> provider(Class<T> type, T beanOrNull) {
        StaticListableBeanFactory f = beanOrNull == null ? new StaticListableBeanFactory()
                : new StaticListableBeanFactory(Map.<String, Object>of("bean", beanOrNull));
        return f.getBeanProvider(type);
    }

    MeterRegistry bind(OutboxCapacityMonitor capacity, OutboxRelayService relay) {
        MeterRegistry registry = new SimpleMeterRegistry();
        new SangamMetrics(ds, provider(RuntimeService.class, null), provider(OutboxCapacityMonitor.class, capacity),
                provider(OutboxRelayService.class, relay)).bindTo(registry);
        return registry;
    }

    static double gauge(MeterRegistry r, String name) {
        return r.get(name).gauge().value();
    }

    /** Four deliveries: two PENDING, one PARKED, one DONE. */
    void seed() {
        for (String root : List.of("r1", "r2", "r3", "r4")) {
            tx().inTransaction(c -> new Outbox(D).append(c, "T", root, "t.event", "{}", List.of(Outbox.Destination.ordered("dest"))));
        }
        tx().inTransaction(c -> Jdbc.update(c, "UPDATE OUTBOX_DELIVERY SET state='PARKED' WHERE outbox_id IN"
                + " (SELECT id FROM OUTBOX WHERE root_id='r3')"));
        tx().inTransaction(c -> Jdbc.update(c, "UPDATE OUTBOX_DELIVERY SET state='DONE' WHERE outbox_id IN"
                + " (SELECT id FROM OUTBOX WHERE root_id='r4')"));
    }

    @Test
    void parkedCountsOnlyParkedDeliveriesAndPendingStillIncludesThem() {
        seed();
        MeterRegistry r = bind(null, null);
        assertEquals(1.0, gauge(r, "sangam.outbox.parked"));
        assertEquals(3.0, gauge(r, "sangam.outbox.pending"), "the undelivered gauge is unchanged: PENDING + CLAIMED + PARKED");
    }

    @Test
    void capacityRatioIsTheLastSamplesUsedRatio() {
        seed();
        // H2 has no catalog size function: the monitor estimates 2048 bytes per OUTBOX_DELIVERY row (4 rows here)
        OutboxCapacityMonitor monitor = new OutboxCapacityMonitor(D, tx(), 4 * 2048 * 2, Duration.ofSeconds(60));
        MeterRegistry r = bind(monitor, null);
        assertEquals(0.0, gauge(r, "sangam.outbox.capacity.used.ratio"), "before the first sample");
        OutboxCapacityMonitor.Sample s = monitor.sample();
        assertEquals(0.5, s.usedRatio());
        assertEquals(OutboxCapacityMonitor.Level.ALERT, s.level(), "R40: 50% is the alert level");
        assertEquals(0.5, gauge(r, "sangam.outbox.capacity.used.ratio"), "read live from the monitor, not the 15 s snapshot");
    }

    @Test
    void relayAgeFollowsTheLastCompletedCycle() throws Exception {
        try (OutboxRelayService relay = new OutboxRelayService(D, tx(), new PublisherRegistry(),
                OutboxRelayService.Settings.defaults("node-1"), null, null, null, null)) {
            MeterRegistry r = bind(null, relay);
            assertTrue(Double.isNaN(gauge(r, "sangam.relay.last.cycle.age")), "not started, no cycle: no heartbeat");
            relay.cycle();
            Thread.sleep(1100);
            double age = gauge(r, "sangam.relay.last.cycle.age");
            assertTrue(age >= 1.0 && age < 30.0, "seconds since the completed cycle: " + age);
            relay.cycle();
            assertTrue(gauge(r, "sangam.relay.last.cycle.age") < age, "a new cycle resets the age");
        }
    }

    @Test
    void eachGaugeIsNaNWithoutItsSource() throws Exception {
        MeterRegistry r = bind(null, null);
        assertTrue(Double.isNaN(gauge(r, "sangam.outbox.capacity.used.ratio")), "no capacity monitor");
        assertTrue(Double.isNaN(gauge(r, "sangam.relay.last.cycle.age")), "no relay");

        // a database without the outbox tables (schema not migrated yet): NaN, not an error
        JdbcDataSource empty = new JdbcDataSource();
        empty.setURL("jdbc:h2:mem:empty" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        empty.setUser("sa");
        empty.setPassword("");
        MeterRegistry unmigrated = new SimpleMeterRegistry();
        new SangamMetrics(empty, provider(RuntimeService.class, null), provider(OutboxCapacityMonitor.class, null),
                provider(OutboxRelayService.class, null)).bindTo(unmigrated);
        assertTrue(Double.isNaN(gauge(unmigrated, "sangam.outbox.parked")));
    }
}
