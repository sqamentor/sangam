package org.sangam.spike.flowable;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Locale;

import org.junit.jupiter.api.Assumptions;
import org.sangam.spike.sim.db.Databases;

/**
 * The adapter tests on a Testcontainers database started through {@link Databases} (one container at a time; it is
 * stopped after the class). Select databases with {@code -Dflowable.dbs=postgres,oracle,sqlserver,mysql} (falls back to
 * {@code -Dspike.dbs}; default: all), the same way adapter-operaton reads {@code -Doperaton.dbs}.
 */
abstract class AbstractContainerDbTest extends AbstractFlowableAdapterTest {

    private Databases.Running db;

    /** {@code postgres}, {@code oracle}, {@code sqlserver} or {@code mysql}. */
    protected abstract String database();

    @Override
    protected FlowableEngineAdapter newAdapter() {
        String selected = System.getProperty("flowable.dbs",
                System.getProperty("spike.dbs", "postgres,oracle,sqlserver,mysql"));
        Assumptions.assumeTrue(Arrays.stream(selected.split(","))
                        .map(s -> s.trim().toLowerCase(Locale.ROOT)).anyMatch(database()::equals),
                database() + " not selected by -Dflowable.dbs=" + selected);
        db = Databases.start(database());
        switch (db.dialect()) {
            case "sqlserver" -> {
                // Own database with READ_COMMITTED_SNAPSHOT ON (VI.1.6; Flowable's SQL Server guidance), as on Operaton.
                exec("create database flowable_it", "alter database flowable_it set read_committed_snapshot on");
                return FlowableEngineAdapter.create(db.jdbcUrl() + ";databaseName=flowable_it", db.username(), db.password());
            }
            case "mysql" -> {
                // Own database; server: utf8mb4_bin, READ-COMMITTED (Databases.MySqlContainer); URL with
                // nullCatalogMeansCurrent=true, which Flowable's schema check needs.
                exec("create database flowable_it character set utf8mb4 collate utf8mb4_bin");
                var mysql = (Databases.MySqlContainer) db.container();
                return FlowableEngineAdapter.create(mysql.jdbcUrl("flowable_it"), db.username(), db.password());
            }
            default -> {
                return FlowableEngineAdapter.create(db.jdbcUrl(), db.username(), db.password());
            }
        }
    }

    private void exec(String... sql) {
        try (Connection c = db.dataSource().getConnection(); Statement s = c.createStatement()) {
            for (String q : sql) {
                s.execute(q);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    protected void afterEngineStopped() {
        if (db != null) {
            db.close();
            db = null;
        }
    }
}
