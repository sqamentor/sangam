package org.sangam.spike.it.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.sangam.spike.api.EngineAdapter;
import org.sangam.spike.corpus.Corpus;
import org.sangam.spike.layer.AuditChain;
import org.sangam.spike.layer.Dialect;
import org.sangam.spike.layer.Jdbc;
import org.sangam.spike.layer.OperationLedger;
import org.sangam.spike.layer.Outbox;
import org.sangam.spike.layer.ReconciliationQueue;
import org.sangam.spike.layer.Schema;
import org.sangam.spike.layer.Tombstones;
import org.sangam.spike.layer.Transactions;

/**
 * Per-experiment environment: one fresh database ({@code -Dspike.db=postgres|oracle|sqlserver}, see {@link Db}) holding
 * the engine's tables and the product tables, one engine adapter on it, and the product-layer components wired to the
 * engine transaction ({@link Transactions#of(EngineAdapter)}), so product rows commit with engine state (Q1). Every
 * product component gets the {@link Dialect} of that database.
 */
public final class Env implements AutoCloseable {

    public final EngineKind kind;
    /** The database of this environment (name, URL, credentials, dialect). */
    public final Db.Target db;
    public final Dialect dialect;
    public final String jdbcUrl;
    public final EngineAdapter engine;
    /** Plain pool on the same database, for read-only SQL (lists, counts, checks) outside engine commands. */
    public final HikariDataSource reads;
    public final Transactions tx;
    public final AuditChain audit;
    public final ReconciliationQueue reconciliation;
    public final OperationLedger ledger;
    public final Tombstones tombstones;
    public final Outbox outbox;

    private Instant clock;

    private Env(EngineKind kind, Db.Target db, EngineAdapter engine, HikariDataSource reads) {
        this.kind = kind;
        this.db = db;
        this.dialect = db.dialect();
        this.jdbcUrl = db.url();
        this.engine = engine;
        this.reads = reads;
        this.tx = Transactions.of(engine);
        this.audit = new AuditChain(dialect);
        this.reconciliation = new ReconciliationQueue(dialect, audit);
        this.ledger = new OperationLedger(dialect, reconciliation);
        this.tombstones = new Tombstones(dialect, reconciliation, audit);
        this.outbox = new Outbox(dialect);
    }

    public static Env create(EngineKind kind, String experiment) {
        Db.dialect();   // fails fast (before a container starts) where product-layer has no dialect, e.g. MySQL
        Db.Target db = Db.create(experiment + "_" + kind.name());
        // Product tables first, on a plain connection (DDL, auto-commit).
        try (Connection c = DriverManager.getConnection(db.url(), db.user(), db.password())) {
            c.setAutoCommit(false);
            Schema.create(c, db.dialect());
            try (Statement s = c.createStatement()) {
                // Test-owned mapping from engine work id to operation id (no such column exists in the product
                // schema; see experiments-integration/README.md, finding F-3).
                s.execute(db.dialect().expand("CREATE TABLE IT_WORK_OPERATION (work_id {str64} PRIMARY KEY,"
                        + " operation_id {str64} NOT NULL, tenant_id {str64} NOT NULL, root_id {str64} NOT NULL)"));
            }
            c.commit();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return open(kind, db);
    }

    /**
     * Opens an engine and the read pool on an existing database whose tables are already there (used by T35 after
     * the database was restored from a snapshot: the engine restarts on the restored state).
     */
    public static Env open(EngineKind kind, Db.Target db) {
        EngineAdapter engine = kind.create(db.url(), db.user(), db.password());
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(db.url());
        hc.setUsername(db.user());
        hc.setPassword(db.password());
        hc.setMaximumPoolSize(12);
        hc.setAutoCommit(true);
        hc.setPoolName("it-reads-" + kind.name().toLowerCase() + "-" + db.name());
        return new Env(kind, db, engine, new HikariDataSource(hc));
    }

    // ------------------------------------------------------------------ engine helpers

    public String deploy(String tenant, String... files) {
        Map<String, byte[]> res = Corpus.resources(kind.corpusName(), files);
        return engine.deploy(tenant, "it-" + UUID.randomUUID(), res);
    }

    public <T> T inTx(Function<Connection, T> work) {
        return engine.inEngineTransaction(work);
    }

    public void run(java.util.function.Consumer<Connection> work) {
        engine.inEngineTransaction(c -> {
            work.accept(c);
            return null;
        });
    }

    /** Fixes the engine clock (lease times use the engine clock in both adapters). */
    public Instant setClock(Instant now) {
        clock = now.truncatedTo(ChronoUnit.MILLIS);
        engine.setClock(clock);
        return clock;
    }

    public Instant clock() {
        if (clock == null) {
            throw new IllegalStateException("engine clock not set");
        }
        return clock;
    }

    public Instant advance(java.time.Duration d) {
        return setClock(clock().plus(d));
    }

    // ------------------------------------------------------------------ plain SQL on the read pool

    public DataSource readPool() {
        return reads;
    }

    public long count(String sql, Object... params) {
        try (Connection c = reads.getConnection()) {
            return Jdbc.one(c, sql, rs -> rs.getLong(1), params).orElse(0L);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public List<String> strings(String sql, Object... params) {
        try (Connection c = reads.getConnection()) {
            return Jdbc.query(c, sql, rs -> rs.getString(1), params);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public List<Object[]> rows(String sql, int columns, Object... params) {
        try (Connection c = reads.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            Jdbc.bind(ps, 1, params);
            List<Object[]> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Object[] r = new Object[columns];
                    for (int i = 0; i < columns; i++) {
                        r[i] = rs.getObject(i + 1);
                    }
                    out.add(r);
                }
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public void exec(String sql, Object... params) {
        try (Connection c = reads.getConnection()) {
            Jdbc.update(c, sql, params);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Process instances recorded with this business key (runtime + history, so a rolled-back start shows 0). */
    public long instancesWithBusinessKey(String businessKey) {
        return count("SELECT count(*) FROM ACT_HI_PROCINST WHERE BUSINESS_KEY_ = ?", businessKey);
    }

    public long runtimeInstancesWithBusinessKey(String businessKey) {
        return count("SELECT count(*) FROM ACT_RU_EXECUTION WHERE BUSINESS_KEY_ = ? AND PARENT_ID_ IS NULL", businessKey);
    }

    @Override
    public void close() {
        try {
            engine.close();
        } finally {
            reads.close();
        }
    }
}
