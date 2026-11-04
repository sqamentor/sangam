package org.sangam.spike.load;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import org.sangam.spike.sim.db.Databases;

/**
 * Gives each engine its own empty database (PostgreSQL, SQL Server) or schema/user (Oracle) inside the ONE running
 * container, so Flowable and Operaton (both use ACT_* table names) never share tables.
 */
public final class DbSetup {

    /** Rewritten spike_lease statements on SQL Server (see {@link #sqlServerLeaseShim}). */
    public static final AtomicLong SHIM_REWRITES = new AtomicLong();

    private DbSetup() {
    }

    /** A pool on an isolated database; {@link #dataSource()} may be the SQL Server shim around {@link #pool()}. */
    public record Isolated(String dialect, String jdbcUrl, String user, HikariDataSource pool, DataSource dataSource,
                           String notes) implements AutoCloseable {
        @Override
        public void close() {
            pool.close();
        }
    }

    public static Isolated create(Databases.Running db, String name, int poolSize, boolean autoCommit) {
        String dialect = db.dialect();
        String n = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        String url;
        String user;
        String password;
        String notes;
        try {
            switch (dialect) {
                case "postgresql" -> {
                    try (Connection c = db.dataSource().getConnection(); Statement s = c.createStatement()) {
                        s.execute("create database " + n);
                    }
                    String base = db.jdbcUrl();
                    int q = base.indexOf('?');
                    String tail = q < 0 ? "" : base.substring(q);
                    String head = q < 0 ? base : base.substring(0, q);
                    url = head.substring(0, head.lastIndexOf('/') + 1) + n + tail;
                    user = db.username();
                    password = db.password();
                    notes = "PostgreSQL database " + n + " (default isolation READ COMMITTED)";
                }
                case "oracle" -> {
                    // gvenzl/oracle-free: SYSTEM has the same password as the app user (ORACLE_PASSWORD).
                    user = n.toUpperCase(Locale.ROOT);
                    password = "spike";
                    try (Connection c = DriverManager.getConnection(db.jdbcUrl(), "system", db.password());
                         Statement s = c.createStatement()) {
                        s.execute("create user " + user + " identified by " + password
                                + " default tablespace users quota unlimited on users");
                        s.execute("grant create session, create table, create sequence, create view, "
                                + "create procedure, create trigger to " + user);
                    }
                    url = db.jdbcUrl();
                    notes = "Oracle user/schema " + user + " in FREEPDB1 (default isolation READ COMMITTED)";
                }
                case "sqlserver" -> {
                    try (Connection c = db.dataSource().getConnection(); Statement s = c.createStatement()) {
                        s.execute("create database " + n);
                        // Required by VI.1.6 (and recommended by both engines' SQL Server guides).
                        s.execute("alter database " + n + " set read_committed_snapshot on");
                    }
                    url = db.jdbcUrl() + ";databaseName=" + n;
                    user = db.username();
                    password = db.password();
                    notes = "SQL Server database " + n + " with READ_COMMITTED_SNAPSHOT ON";
                }
                default -> throw new IllegalArgumentException(dialect);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("cannot create isolated database " + n + " on " + dialect, e);
        }
        HikariConfig hc = new HikariConfig();
        hc.setPoolName("load-" + n);
        hc.setJdbcUrl(url);
        hc.setUsername(user);
        hc.setPassword(password);
        hc.setMaximumPoolSize(poolSize);
        hc.setMinimumIdle(Math.min(poolSize, 8));
        hc.setAutoCommit(autoCommit);
        hc.setConnectionTimeout(60_000);
        HikariDataSource pool = new HikariDataSource(hc);
        DataSource ds = "postgresql".equals(dialect) ? pool : leaseShim(pool, dialect);
        if (!"postgresql".equals(dialect)) {
            notes += "; spike_lease SQL rewritten by the test shim (product-api LeaseTable is PostgreSQL-only: "
                    + "BIGINT DDL fails on Oracle, IF NOT EXISTS / FOR UPDATE fail on SQL Server)";
        }
        return new Isolated(dialect, url, user, pool, ds, notes);
    }

    // ------------------------------------------------------------------ SQL Server shim (test-side workaround)

    /**
     * product-api {@code LeaseTable} uses {@code create table if not exists} and {@code select ... for update}, which
     * SQL Server does not accept, and both adapters call {@code LeaseTable.createIfMissing} in their constructors. This
     * proxy rewrites exactly those spike_lease statements to T-SQL; every other statement passes through unchanged.
     * It is a test-harness workaround, reported as a finding, not a fix.
     */
    static DataSource leaseShim(DataSource target, String dialect) {
        return (DataSource) Proxy.newProxyInstance(DbSetup.class.getClassLoader(), new Class<?>[]{DataSource.class},
                (p, m, a) -> {
                    Object r = invoke(m, target, a);
                    return r instanceof Connection c ? wrap(c, Connection.class, dialect) : r;
                });
    }

    static String rewrite(String sql) {
        return rewrite(sql, "sqlserver");
    }

    static String rewrite(String sql, String dialect) {
        if (sql == null) {
            return null;
        }
        String lower = sql.trim().toLowerCase(Locale.ROOT);
        if ("oracle".equals(dialect)) {
            // Oracle has no BIGINT type (ORA-00902); 23ai accepts IF NOT EXISTS and FOR UPDATE.
            if (lower.startsWith("create table if not exists spike_lease")) {
                SHIM_REWRITES.incrementAndGet();
                return sql.replaceAll("(?i)\\bbigint\\b", "number(19)");
            }
            return sql;
        }
        if (lower.startsWith("create table if not exists spike_lease")) {
            SHIM_REWRITES.incrementAndGet();
            return "if object_id('spike_lease','U') is null "
                    + sql.trim().replaceFirst("(?i)create table if not exists", "create table");
        }
        if (lower.contains("from spike_lease where work_id = ? for update")) {
            SHIM_REWRITES.incrementAndGet();
            return sql.replaceFirst("(?i)from spike_lease where work_id = \\? for update",
                    "from spike_lease with (updlock, rowlock) where work_id = ?");
        }
        return sql;
    }

    @SuppressWarnings("unchecked")
    private static <T> T wrap(T target, Class<T> iface, String dialect) {
        InvocationHandler h = (p, m, a) -> {
            String name = m.getName();
            if (a != null && a.length > 0 && a[0] instanceof String sql
                    && (name.startsWith("prepare") || name.startsWith("execute") || name.equals("addBatch"))) {
                a = a.clone();
                a[0] = rewrite(sql, dialect);
            }
            Object r = invoke(m, target, a);
            if (iface == Connection.class && name.equals("createStatement") && r instanceof Statement s) {
                return wrap(s, Statement.class, dialect);
            }
            return r;
        };
        return (T) Proxy.newProxyInstance(DbSetup.class.getClassLoader(), new Class<?>[]{iface}, h);
    }

    private static Object invoke(Method m, Object target, Object[] a) throws Throwable {
        try {
            return m.invoke(target, a);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
