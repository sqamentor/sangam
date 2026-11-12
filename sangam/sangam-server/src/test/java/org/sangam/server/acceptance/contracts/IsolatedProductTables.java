// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.acceptance.contracts;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Schema;
import org.sangam.core.layer.Transactions;
import org.sangam.persistence.SangamSchema;
import org.sangam.server.acceptance.AcceptanceDatabase;

/**
 * A private copy of the product tables in a per-run namespace of the acceptance database ({@link IsolationPlan}: a
 * PostgreSQL schema, a SQL Server or MySQL database, an Oracle user), populated by the product's <b>own</b> schema path
 * as the server runs it at start-up: {@link SangamSchema#apply} (the Liquibase master changelog) and then
 * {@link SangamSchema#applyEnginePatches}. The copy is therefore the production schema of that database, with no
 * hand-written DDL.
 *
 * <p>Why: the core relay, inbox, tombstone sweep and idempotency cleanup claim rows <b>globally</b> (no tenant filter, by
 * design). The running server's own background services (sangam-integration relay, inbox, maintenance) would claim a
 * test's rows in the shared tables at any moment and break the deterministic interleavings the Stage B specs need
 * (claim, crash, sweep). The copy gives the specs exclusive tables, truncated per test exactly as Stage B did, without
 * stopping or reconfiguring the shared server.
 *
 * <p>The pool ({@link #tx()}, {@link #ds}) has the namespace as its only default: an unqualified table name resolves
 * inside it or fails, never silently in the server's tables. {@link #get()} checks that the pool's current
 * schema/database is the namespace and that every product table exists there. The namespace is created with the
 * administrator account Testcontainers gives the lane ({@link AcceptanceDatabase#adminUsername()}), in the test harness
 * only; the pool itself uses the server's account (Oracle: the namespace user, with the server user's privileges).
 */
final class IsolatedProductTables {

    /** The product tables of {@link Schema#tables()} plus the ones core components touch through them. */
    private static final List<String> EXTRA = List.of("AUDIT_CHECKPOINT");

    private static IsolatedProductTables instance;

    final Dialect dialect;
    final String namespace;
    final HikariDataSource ds;
    private final Transactions tx;
    private final List<String> tables;

    private IsolatedProductTables(Dialect dialect, String namespace, HikariDataSource ds, List<String> tables) {
        this.dialect = dialect;
        this.namespace = namespace;
        this.ds = ds;
        this.tx = Transactions.of(ds);
        this.tables = tables;
    }

    static synchronized IsolatedProductTables get() {
        if (instance != null) {
            return instance;
        }
        Dialect dialect = IsolationPlan.dialectOf(AcceptanceDatabase.name());
        String ns = IsolationPlan.namespace(dialect, ContractSupport.RUN);
        String user = AcceptanceDatabase.username();
        String password = AcceptanceDatabase.password();
        try (Connection admin = DriverManager.getConnection(AcceptanceDatabase.jdbcUrl(),
                AcceptanceDatabase.adminUsername(), AcceptanceDatabase.adminPassword());
             Statement st = admin.createStatement()) {
            List<String> ddl = dialect == Dialect.ORACLE
                    ? oracleUser(admin, ns, user, password)
                    : IsolationPlan.createNamespace(dialect, ns, user);
            for (String sql : ddl) {
                st.execute(sql);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("cannot create the isolated namespace " + ns, e);
        }
        HikariConfig cfg = new HikariConfig();
        switch (dialect) {
            case POSTGRESQL -> cfg.setJdbcUrl(IsolationPlan.withCurrentSchema(AcceptanceDatabase.jdbcUrl(), ns));
            case SQLSERVER, MYSQL -> cfg.setJdbcUrl(AcceptanceDatabase.jdbcUrl(ns));
            case ORACLE -> {
                cfg.setJdbcUrl(AcceptanceDatabase.jdbcUrl());
                user = ns;
            }
        }
        cfg.setUsername(user);
        cfg.setPassword(password);
        cfg.setMaximumPoolSize(24);
        cfg.setAutoCommit(true);
        cfg.setPoolName("acc-contracts-iso");
        HikariDataSource ds = new HikariDataSource(cfg);
        // the product's own schema path, in the server's start-up order (product changelog, then engine patches)
        SangamSchema.apply(ds);
        SangamSchema.applyEnginePatches(ds);
        verifyNamespace(ds, dialect, ns);
        List<String> tables = new ArrayList<>(Schema.tables());
        tables.addAll(EXTRA);
        instance = new IsolatedProductTables(dialect, ns, ds, tables);
        return instance;
    }

    /** Oracle: reads the server user's tablespace, system privileges and roles, and creates the namespace user with them. */
    private static List<String> oracleUser(Connection admin, String ns, String appUser, String password) {
        String grantee = appUser.toUpperCase(Locale.ROOT);
        String tablespace = Jdbc.one(admin, "SELECT default_tablespace FROM dba_users WHERE username = ?",
                rs -> rs.getString(1), grantee).orElseThrow(() -> new IllegalStateException("no Oracle user " + grantee));
        List<String> privileges = Jdbc.query(admin, "SELECT privilege FROM dba_sys_privs WHERE grantee = ? ORDER BY privilege",
                rs -> rs.getString(1), grantee);
        List<String> roles = Jdbc.query(admin, "SELECT granted_role FROM dba_role_privs WHERE grantee = ? ORDER BY granted_role",
                rs -> rs.getString(1), grantee);
        return IsolationPlan.createOracleUser(ns, password, tablespace, privileges, roles);
    }

    /**
     * The pool's default namespace is {@code ns} (PostgreSQL and Oracle: current schema; SQL Server and MySQL: current
     * database) and every table of {@link SangamSchema#PRODUCT_TABLES} exists in it.
     */
    private static void verifyNamespace(HikariDataSource ds, Dialect dialect, String ns) {
        try (Connection c = ds.getConnection()) {
            String current = dialect == Dialect.SQLSERVER || dialect == Dialect.MYSQL ? c.getCatalog() : c.getSchema();
            if (!ns.equalsIgnoreCase(current)) {
                throw new IllegalStateException("isolated pool's default namespace is " + current + ", not " + ns);
            }
            Set<String> present = new HashSet<>();
            try (ResultSet rs = c.getMetaData().getTables(c.getCatalog(), c.getSchema(), "%", new String[]{"TABLE"})) {
                while (rs.next()) {
                    present.add(rs.getString("TABLE_NAME").toUpperCase(Locale.ROOT));
                }
            }
            List<String> missing = SangamSchema.PRODUCT_TABLES.stream().filter(t -> !present.contains(t)).toList();
            if (!missing.isEmpty()) {
                throw new IllegalStateException("product tables missing from " + ns + ": " + missing);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("cannot verify the isolated namespace " + ns, e);
        }
    }

    Transactions tx() {
        return tx;
    }

    /** Per-test reset of the copied tables, identities restarted where TRUNCATE does it ({@link IsolationPlan#truncate}). */
    void truncate() {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            for (String sql : IsolationPlan.truncate(dialect, tables)) {
                st.execute(sql);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
