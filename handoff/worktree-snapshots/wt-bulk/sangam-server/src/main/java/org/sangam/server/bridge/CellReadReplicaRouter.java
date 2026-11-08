// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.function.Supplier;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;

import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

/**
 * IR-6 read-replica routing. When {@code sangam.datasource.replica.url} is configured, {@link #routing()} is a
 * routing {@link DataSource} that answers from the replica pool while the current thread carries the read-replica hint
 * (set by {@code ReadReplicaWiring}'s interceptor for the GET routes of dashboards/analytics, audit timeline and
 * search, history and exports), and from the primary otherwise.
 *
 * <p>Deliberately <b>not</b> a {@code DataSource} bean: the primary pool stays the only DataSource bean, so the
 * Operaton engine, Liquibase, {@code TxSupport} and every command keep the primary (P-1: product rows commit with
 * engine state; reads that also write, such as {@code AUDIT_VIEWED} on an audit read, stay correct). Read-only query
 * code adopts the replica explicitly through {@link #routing()} or {@link #onReplica}.
 *
 * <p>The replica pool runs READ COMMITTED and read-only, like every Sangam pool (ARCHITECTURE §1.5); the isolation is
 * checked when the pool starts.
 */
public final class CellReadReplicaRouter implements AutoCloseable {

    private static final ThreadLocal<Boolean> HINT = new ThreadLocal<>();

    private final DataSource primary;
    private final HikariDataSource replica;
    private final AbstractRoutingDataSource routing;

    public CellReadReplicaRouter(DataSource primary, HikariDataSource replicaOrNull) {
        this.primary = primary;
        this.replica = replicaOrNull;
        if (replicaOrNull == null) {
            this.routing = null;
            return;
        }
        AbstractRoutingDataSource r = new AbstractRoutingDataSource() {
            @Override
            protected Object determineCurrentLookupKey() {
                return Boolean.TRUE.equals(HINT.get()) ? "replica" : "primary";
            }
        };
        r.setTargetDataSources(Map.of("primary", primary, "replica", replicaOrNull));
        r.setDefaultTargetDataSource(primary);
        r.afterPropertiesSet();
        this.routing = r;
        verifyIsolation(replicaOrNull);
    }

    public boolean replicaConfigured() {
        return replica != null;
    }

    /** The routing source (the primary itself when no replica is configured). */
    public DataSource routing() {
        return routing == null ? primary : routing;
    }

    /** Runs {@code work} with the read-replica hint set (no effect without a replica). */
    public <T> T onReplica(Supplier<T> work) {
        Boolean previous = HINT.get();
        HINT.set(Boolean.TRUE);
        try {
            return work.get();
        } finally {
            if (previous == null) {
                HINT.remove();
            } else {
                HINT.set(previous);
            }
        }
    }

    public static void hint(boolean replicaRead) {
        if (replicaRead) {
            HINT.set(Boolean.TRUE);
        } else {
            HINT.remove();
        }
    }

    public static boolean hinted() {
        return Boolean.TRUE.equals(HINT.get());
    }

    private static void verifyIsolation(DataSource ds) {
        try (Connection c = ds.getConnection()) {
            if (c.getTransactionIsolation() != Connection.TRANSACTION_READ_COMMITTED) {
                throw new IllegalStateException("the read replica pool must run READ COMMITTED (ARCHITECTURE §1.5), not "
                        + c.getTransactionIsolation());
            }
        } catch (SQLException e) {
            throw new IllegalStateException("cannot open the read replica: " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        if (replica != null) {
            replica.close();
        }
    }
}
