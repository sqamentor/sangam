// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.adapter.jdbc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Schema;
import org.sangam.rest.port.IdempotencyPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.RestProperties;
import org.sangam.rest.support.TxSupport;

/**
 * E-28, SANGAM.md X.2.2.4 step 3: of all 409 results only the version precondition (If-Match, codes
 * {@code REVISION_CONFLICT} and {@code DRAFT_CONFLICT}) is stored as FAILED_FINAL and replayed. A state-dependent 409
 * such as {@code UNKNOWN_LEVEL}, or a 409 without a code, is not stored: a retry with the same key runs the command
 * again. The 2xx replay (24 h with body, then compacted up to 30 d) is unchanged.
 */
class IdempotencyOutcomeClassificationH2Test {

    private static final RequestContext CTX = new RequestContext("T1", "p1", null, null, null, null, null, null, null);
    private static final byte[] FP = "fingerprint".getBytes(StandardCharsets.UTF_8);

    private JdbcDataSource ds;
    private JdbcIdempotencyAdapter adapter;

    /** Plain JDBC transactions over one H2 database; the current transaction's connection is thread-bound. */
    static final class H2Tx extends TxSupport {
        private final JdbcDataSource ds;
        private final ThreadLocal<Connection> current = new ThreadLocal<>();

        H2Tx(JdbcDataSource ds) {
            super(ds, Dialect.POSTGRESQL);
            this.ds = ds;
        }

        @Override
        public <T> T inTxWithStatus(Function<TxStatus, T> work) {
            if (current.get() != null) {
                return work.apply(null);
            }
            return run(work);
        }

        @Override
        public <T> T inNewTx(Supplier<T> work) {
            Connection outer = current.get();
            current.remove();
            try {
                return run(status -> work.get());
            } finally {
                if (outer != null) {
                    current.set(outer);
                }
            }
        }

        private <T> T run(Function<TxStatus, T> work) {
            try (Connection c = ds.getConnection()) {
                c.setAutoCommit(false);
                current.set(c);
                boolean[] rollback = {false};
                TxStatus status = new TxStatus() {
                    @Override
                    public void setRollbackOnly() {
                        rollback[0] = true;
                    }

                    @Override
                    public boolean isRollbackOnly() {
                        return rollback[0];
                    }
                };
                try {
                    T r = work.apply(status);
                    if (rollback[0]) {
                        c.rollback();
                    } else {
                        c.commit();
                    }
                    return r;
                } catch (RuntimeException | Error e) {
                    c.rollback();
                    throw e;
                } finally {
                    current.remove();
                }
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        }

        /** Read work joins the current transaction, or runs in one of its own (as inTxWithStatus). */
        @Override
        public <T> T withReadConnection(org.sangam.core.api.ReadWork<T> work) {
            return inTxWithStatus(status -> work.read(current.get()));
        }

        @Override
        public <T> T inReadTx(org.sangam.core.api.ReadSupplier<T> work) {
            return inTxWithStatus(status -> work.read());
        }

        /** Read work in a new transaction on its own connection, the outer one suspended (as inNewTx). */
        @Override
        public <T> T inNewReadTx(org.sangam.core.api.ReadWork<T> work) {
            return inNewTx(() -> work.read(current.get()));
        }

        @Override
        public Connection connection() {
            return current.get();
        }

        @Override
        public void afterCommit(Runnable work) {
            work.run();
        }
    }

    @BeforeEach
    void setUp() throws SQLException {
        ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        try (Connection c = ds.getConnection()) {
            Schema.create(c, Dialect.POSTGRESQL);
        }
        adapter = new JdbcIdempotencyAdapter(new H2Tx(ds), new RestProperties());
    }

    private static byte[] problem(String code) {
        String json = code == null ? "{\"status\":409,\"title\":\"Conflict\"}"
                : "{\"status\":409,\"code\":\"" + code + "\"}";
        return json.getBytes(StandardCharsets.UTF_8);
    }

    /** Runs the command under {@code key} if the key is claimed; returns the Begin seen first. */
    private IdempotencyPort.Begin call(String key, AtomicInteger runs, int status, byte[] body) throws Exception {
        IdempotencyPort.Begin b = adapter.begin(CTX, "level.assign", key, FP);
        if (b instanceof IdempotencyPort.Claimed claimed) {
            adapter.execute(claimed, () -> {
                runs.incrementAndGet();
                return new IdempotencyPort.Outcome(status, body, status < 300 ? "/levels/1" : null);
            });
        }
        return b;
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNKNOWN_LEVEL", "INVALID_STATE", "ALREADY_ASSIGNED"})
    void aStateDependent409IsNotStoredSoTheRetryRunsAgain(String code) throws Exception {
        AtomicInteger runs = new AtomicInteger();
        assertInstanceOf(IdempotencyPort.Claimed.class, call("k-" + code, runs, 409, problem(code)));
        IdempotencyPort.Begin retry = call("k-" + code, runs, 201, "{\"id\":1}".getBytes(StandardCharsets.UTF_8));
        assertInstanceOf(IdempotencyPort.Claimed.class, retry, "409 " + code + " must not be replayed");
        assertEquals(2, runs.get(), "the retry with the same key runs the command again");
        IdempotencyPort.Replay r = assertInstanceOf(IdempotencyPort.Replay.class,
                adapter.begin(CTX, "level.assign", "k-" + code, FP));
        assertEquals(201, r.status(), "after the state changed, the key holds the success");
    }

    @Test
    void a409WithoutACodeIsNotStored() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        call("k-nocode", runs, 409, problem(null));
        assertInstanceOf(IdempotencyPort.Claimed.class, adapter.begin(CTX, "level.assign", "k-nocode", FP));
    }

    @Test
    void a409WithAnEmptyOrUnreadableBodyIsNotStored() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        call("k-empty", runs, 409, new byte[0]);
        assertInstanceOf(IdempotencyPort.Claimed.class, adapter.begin(CTX, "level.assign", "k-empty", FP));
        call("k-junk", runs, 409, "not json".getBytes(StandardCharsets.UTF_8));
        assertInstanceOf(IdempotencyPort.Claimed.class, adapter.begin(CTX, "level.assign", "k-junk", FP));
    }

    @ParameterizedTest
    @ValueSource(strings = {"REVISION_CONFLICT", "DRAFT_CONFLICT"})
    void theIfMatchPrecondition409IsStoredAndReplayed(String code) throws Exception {
        AtomicInteger runs = new AtomicInteger();
        byte[] body = problem(code);
        call("k-" + code, runs, 409, body);
        IdempotencyPort.Replay r = assertInstanceOf(IdempotencyPort.Replay.class,
                call("k-" + code, runs, 201, "{}".getBytes(StandardCharsets.UTF_8)));
        assertEquals(409, r.status());
        assertArrayEquals(body, r.body());
        assertEquals(1, runs.get(), "a replay never re-executes the command");
    }

    @ParameterizedTest
    @ValueSource(strings = {"CONFLICT_RETRY_EXHAUSTED", "IDEMPOTENCY_IN_PROGRESS"})
    void transient409sStayUnstored(String code) throws Exception {
        AtomicInteger runs = new AtomicInteger();
        call("k-" + code, runs, 409, problem(code));
        assertInstanceOf(IdempotencyPort.Claimed.class, adapter.begin(CTX, "level.assign", "k-" + code, FP));
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 404, 410, 422})
    void otherDeterministic4xxAreStillStored(int status) throws Exception {
        AtomicInteger runs = new AtomicInteger();
        call("k-" + status, runs, status, "{\"code\":\"X\"}".getBytes(StandardCharsets.UTF_8));
        assertEquals(status, assertInstanceOf(IdempotencyPort.Replay.class,
                adapter.begin(CTX, "level.assign", "k-" + status, FP)).status());
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 429, 500, 503})
    void authAndTransientStatusesAreNotStored(int status) throws Exception {
        AtomicInteger runs = new AtomicInteger();
        call("k-" + status, runs, status, "{\"code\":\"X\"}".getBytes(StandardCharsets.UTF_8));
        assertInstanceOf(IdempotencyPort.Claimed.class, adapter.begin(CTX, "level.assign", "k-" + status, FP));
    }

    @Test
    void a2xxIsReplayedWithItsBodyThenCompactedThenForgotten() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        byte[] body = "{\"id\":1}".getBytes(StandardCharsets.UTF_8);
        call("k-ok", runs, 201, body);
        IdempotencyPort.Replay full = assertInstanceOf(IdempotencyPort.Replay.class,
                adapter.begin(CTX, "level.assign", "k-ok", FP));
        assertEquals(201, full.status());
        assertArrayEquals(body, full.body());
        assertEquals(false, full.compacted());

        age("response_expires_at");   // past 24 h response retention
        assertTrue(adapter.cleanup() >= 1);
        IdempotencyPort.Replay compact = assertInstanceOf(IdempotencyPort.Replay.class,
                adapter.begin(CTX, "level.assign", "k-ok", FP));
        assertTrue(compact.compacted());
        assertEquals(201, compact.status());
        assertEquals("/levels/1", compact.resourceRef());
        assertNull(compact.body());

        age("key_expires_at");        // past 30 d key retention
        adapter.cleanup();
        assertInstanceOf(IdempotencyPort.Claimed.class, adapter.begin(CTX, "level.assign", "k-ok", FP));
        assertEquals(1, runs.get());
    }

    private void age(String column) throws SQLException {
        try (Connection c = ds.getConnection(); var s = c.createStatement()) {
            s.executeUpdate("UPDATE IDEMPOTENCY_RECORD SET " + column + " = TIMESTAMP '2000-01-01 00:00:00'");
        }
    }
}
