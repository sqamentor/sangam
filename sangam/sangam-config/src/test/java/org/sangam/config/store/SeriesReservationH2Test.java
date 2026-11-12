// SPDX-License-Identifier: Apache-2.0
package org.sangam.config.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.sangam.config.api.ConfigConnector;
import org.sangam.config.locale.ProfileBusinessClock;
import org.sangam.config.master.ClassificationRegistry;
import org.sangam.config.master.MasterStore;
import org.sangam.config.profile.InMemoryProfileRepository;
import org.sangam.config.profile.LayerModel;
import org.sangam.config.profile.ProfileDocument;
import org.sangam.config.profile.ProfileRepository;
import org.sangam.config.profile.ProfileResolver;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.ConnectorResult;
import org.sangam.core.integration.LookupResult;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;

/**
 * SL-130 follow-up: {@code series.next} reserves its number durably, keyed on the caller's {@code ref}, else the
 * operationId. Before, the dedupe was a node-local map: lost on restart and not shared, so a retry on another node
 * (or after a restart) drew a second number and burned the first.
 */
class SeriesReservationH2Test {

    static final LocalDate AS_OF = LocalDate.of(2026, 11, 5);

    static JdbcConfigStore store() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        try (Connection c = ds.getConnection()) {
            ConfigSchema.create(c, Dialect.POSTGRESQL);
        }
        return new JdbcConfigStore(ds, Dialect.POSTGRESQL);
    }

    static long counter(JdbcConfigStore s) {
        return s.inTx(c -> Jdbc.one(c, "SELECT last_no FROM CFG_SERIES WHERE scope_id='ZZ' AND series_id='TOKEN'",
                rs -> rs.getLong(1)).orElse(0L));
    }

    @Test
    void aKeyIsReservedOnceAndARepeatDrawsNothing() throws Exception {
        JdbcConfigStore s = store();
        ConfigConnector.SeriesReservations.Reserved a = s.reserve("ZZ", "TOKEN", "U1", "2026-11-05", "ref:doc-1", n -> "T/" + n);
        assertEquals(1, a.number());
        assertEquals("T/1", a.display());
        assertFalse(a.replayed());
        ConfigConnector.SeriesReservations.Reserved again = s.reserve("ZZ", "TOKEN", "U1", "2026-11-06", "ref:doc-1", n -> "X/" + n);
        assertEquals(1, again.number(), "the stored number, whatever the retry's period or format");
        assertEquals("T/1", again.display());
        assertTrue(again.replayed());
        assertEquals(1, counter(s), "the counter moved once");

        ConfigConnector.SeriesReservations.Reserved b = s.reserve("ZZ", "TOKEN", "U1", "2026-11-05", "op:x", null);
        assertEquals(2, b.number());
        assertNull(b.display());
        assertEquals(2L, b.value());
        assertEquals(b, s.reserved("ZZ", "TOKEN", "op:x").map(r -> new ConfigConnector.SeriesReservations.Reserved(
                r.number(), r.display(), false)).orElseThrow());
        assertTrue(s.reserved("ZZ", "OTHER", "op:x").isEmpty(), "reservations are per series");
        assertEquals(3, s.next("ZZ", "TOKEN", "U1", "2026-11-05"), "plain next() is unchanged");
    }

    @Test
    void concurrentFirstCallsWithOneKeyGetOneNumberAndBurnNone() throws Exception {
        JdbcConfigStore s = store();
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Callable<Long>> calls = new ArrayList<>();
            for (int i = 0; i < 18; i++) {
                calls.add(() -> s.reserve("ZZ", "TOKEN", "U1", "2026-11-05", "ref:same", null).number());
            }
            Set<Long> numbers = new HashSet<>();
            for (Future<Long> f : pool.invokeAll(calls)) {
                numbers.add(f.get());
            }
            assertEquals(Set.of(1L), numbers);
            assertEquals(1, counter(s), "a lost race rolled its counter step back");
        } finally {
            pool.shutdownNow();
        }
    }

    static ConfigConnector connector(JdbcConfigStore s) {
        InMemoryProfileRepository repo = new InMemoryProfileRepository();
        repo.save(new ProfileDocument("ZZ", "government", "1", LocalDate.of(2026, 4, 1), null, List.of(), List.of(), Map.of(),
                "test", Map.of(), List.of(), Map.of()), ProfileRepository.Status.ACTIVE);
        ProfileResolver resolver = new ProfileResolver(repo, LayerModel.open());
        return new ConfigConnector(resolver, new ClassificationRegistry(new MasterStore.InMemory()), s,
                new ProfileBusinessClock(resolver, Clock.systemUTC()));
    }

    static ConnectorRequest next(UUID op, String ref) {
        Map<String, Object> in = new LinkedHashMap<>(Map.of("series", "TOKEN", "unit", "U1", "format", "{unit}/{n:3}",
                "asOf", AS_OF.toString()));
        if (ref != null) {
            in.put("ref", ref);
        }
        return new ConnectorRequest("ZZ", op, "a", 1, null, null, "series.next", in, null);
    }

    @Test
    void theConnectorKeysOnRefThenOperationIdAndSurvivesARestart() throws Exception {
        JdbcConfigStore s = store();
        UUID op1 = UUID.randomUUID();
        ConnectorResult.Success first = assertInstanceOf(ConnectorResult.Success.class, connector(s).invoke(next(op1, null)));
        assertEquals("U1/001", first.output().get("number"));
        assertFalse(first.duplicate());

        // "restart": a new connector (empty memory) on the same store; same operationId → same number, as a duplicate
        ConnectorResult.Success retry = assertInstanceOf(ConnectorResult.Success.class, connector(s).invoke(next(op1, null)));
        assertEquals("U1/001", retry.output().get("number"));
        assertTrue(retry.duplicate());
        LookupResult.Found found = assertInstanceOf(LookupResult.Found.class, connector(s).lookup(next(op1, null)));
        assertEquals("U1/001", found.output().get("number"));
        assertInstanceOf(LookupResult.NotFound.class, connector(s).lookup(next(UUID.randomUUID(), null)));

        // a ref is the document identity: two operations naming it get one number
        ConnectorResult.Success refA = assertInstanceOf(ConnectorResult.Success.class,
                connector(s).invoke(next(UUID.randomUUID(), "bill-42")));
        ConnectorResult.Success refB = assertInstanceOf(ConnectorResult.Success.class,
                connector(s).invoke(next(UUID.randomUUID(), "bill-42")));
        assertEquals("U1/002", refA.output().get("number"));
        assertEquals("U1/002", refB.output().get("number"));
        assertTrue(refB.duplicate());
        assertEquals(2, counter(s), "two documents, two numbers, none burned");
    }
}
