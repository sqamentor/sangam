// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.acceptance.contracts;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.sangam.core.api.EngineAdapter;
import org.sangam.core.layer.Schema;
import org.sangam.core.layer.Transactions;
import org.sangam.server.acceptance.AcceptanceDatabase;

/**
 * A private copy of the product tables, cloned from the server's <b>Liquibase</b> tables ({@code CREATE TABLE ... (LIKE
 * public.T INCLUDING ALL)}: same columns, types, identity, keys and indexes), in its own PostgreSQL schema.
 *
 * <p>Why: the core relay, inbox, tombstone sweep and idempotency cleanup claim rows <b>globally</b> (no tenant filter, by
 * design). The running server's own background services (sangam-integration relay, inbox, maintenance) would claim a
 * test's rows in the shared tables at any moment and break the deterministic interleavings the Stage B specs need
 * (claim, crash, sweep). The copy gives the specs exclusive tables, truncated per test exactly as Stage B did, without
 * stopping or reconfiguring the shared server.
 *
 * <p>Product-only work uses {@link #tx()} (a pool whose {@code currentSchema} is {@code <copy>,public}). Engine-coupled
 * work uses {@link #inEngineTx}: one engine command whose first statement is {@code SET LOCAL search_path}, so the
 * product rows land in the copy while the engine's ACT_* tables still resolve to {@code public}, in one transaction.
 * PostgreSQL only.
 */
final class IsolatedProductTables {

    /** The product tables of {@link Schema#tables()} plus the ones core components touch through them. */
    private static final List<String> EXTRA = List.of("AUDIT_CHECKPOINT");

    private static IsolatedProductTables instance;

    final String schema;
    final HikariDataSource ds;
    private final Transactions tx;
    private final List<String> tables;

    private IsolatedProductTables(String schema, HikariDataSource ds, List<String> tables) {
        this.schema = schema;
        this.ds = ds;
        this.tx = Transactions.of(ds);
        this.tables = tables;
    }

    static synchronized IsolatedProductTables get(DataSource serverDs) {
        if (instance != null) {
            return instance;
        }
        if (!"postgres".equals(AcceptanceDatabase.name())) {
            throw new IllegalStateException("IsolatedProductTables needs PostgreSQL (search_path)");
        }
        String schema = ("acc_" + ContractSupport.RUN).toLowerCase(Locale.ROOT);
        List<String> tables = new ArrayList<>(Schema.tables());
        tables.addAll(EXTRA);
        try (Connection c = serverDs.getConnection(); Statement st = c.createStatement()) {
            st.execute("CREATE SCHEMA IF NOT EXISTS " + schema);
            for (String t : tables) {
                st.execute("CREATE TABLE IF NOT EXISTS " + schema + "." + t + " (LIKE public." + t + " INCLUDING ALL)");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("cannot clone the product tables", e);
        }
        HikariConfig cfg = new HikariConfig();
        String url = AcceptanceDatabase.jdbcUrl();
        cfg.setJdbcUrl(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema + ",public");
        cfg.setUsername(AcceptanceDatabase.username());
        cfg.setPassword(AcceptanceDatabase.password());
        cfg.setMaximumPoolSize(24);
        cfg.setAutoCommit(true);
        cfg.setPoolName("acc-contracts-iso");
        instance = new IsolatedProductTables(schema, new HikariDataSource(cfg), tables);
        return instance;
    }

    Transactions tx() {
        return tx;
    }

    /** TRUNCATE of every copied table, identities restarted (the Stage B per-test reset). */
    void truncate() {
        StringBuilder sb = new StringBuilder("TRUNCATE ");
        for (int i = 0; i < tables.size(); i++) {
            sb.append(i == 0 ? "" : ", ").append(schema).append('.').append(tables.get(i));
        }
        sb.append(" RESTART IDENTITY");
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute(sb.toString());
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** One engine command whose product statements use the copy (engine tables stay in public). */
    <T> T inEngineTx(EngineAdapter engine, Function<Connection, T> work) {
        return engine.inEngineTransaction(c -> {
            try (Statement st = c.createStatement()) {
                st.execute("SET LOCAL search_path TO " + schema + ", public");
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
            return work.apply(c);
        });
    }

    /** A Transactions view that runs every unit of work as {@link #inEngineTx} (the relay/inbox with engine work). */
    Transactions engineTransactions(EngineAdapter engine) {
        return new Transactions() {
            @Override
            public <T> T inTransaction(Function<Connection, T> work) {
                return inEngineTx(engine, work);
            }
        };
    }
}
