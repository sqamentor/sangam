package org.sangam.spike.operaton;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Locale;

import org.junit.jupiter.api.Assumptions;

import org.sangam.spike.sim.db.Databases;

/**
 * The adapter tests on a Testcontainers database started through {@link Databases} (one container at a time; it is
 * stopped after the class). Select databases with {@code -Doperaton.dbs=postgres,oracle,sqlserver,mysql} (default:
 * all four).
 *
 * <p>MySQL ({@link #mysqlUrl}): own database {@code operaton_it} (utf8mb4 / utf8mb4_bin); per session
 * {@code transaction_isolation} = {@code -Doperaton.mysql.isolation} (default {@code READ-COMMITTED}, Operaton's MySQL
 * requirement; the server default of the {@link Databases} container is READ-COMMITTED too, so the session variable
 * is what a REPEATABLE-READ comparison changes). After the engine created its schema, the datetime(3) patch
 * {@link MysqlDatetimePrecisionPatch} is applied unless {@code -Doperaton.mysql.datetimePatch=false}. The driver is
 * chosen with {@code -Dspike.mysql.driver=mysql|mariadb} (both test scope).
 */
abstract class AbstractContainerDbTest extends AbstractOperatonAdapterTest {

    static final String DEFAULT_DBS = "postgres,oracle,sqlserver,mysql";

    private Databases.Running db;

    /** {@code postgres}, {@code oracle}, {@code sqlserver} or {@code mysql}. */
    protected abstract String database();

    static boolean selected(String database) {
        String selected = System.getProperty("operaton.dbs", DEFAULT_DBS);
        return Arrays.stream(selected.split(",")).map(s -> s.trim().toLowerCase(Locale.ROOT)).anyMatch(database::equals);
    }

    @Override
    protected OperatonEngineAdapter newAdapter() {
        Assumptions.assumeTrue(selected(database()),
                database() + " not selected by -Doperaton.dbs=" + System.getProperty("operaton.dbs", DEFAULT_DBS));
        db = Databases.start(database());
        return switch (db.dialect()) {
            case "sqlserver" -> {
                // Own database with READ_COMMITTED_SNAPSHOT ON (VI.1.6; both engines' SQL Server guides).
                try (Connection c = db.dataSource().getConnection(); Statement s = c.createStatement()) {
                    s.execute("create database operaton_it");
                    s.execute("alter database operaton_it set read_committed_snapshot on");
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
                yield new OperatonEngineAdapter(db.jdbcUrl() + ";databaseName=operaton_it", db.username(), db.password());
            }
            case "mysql" -> {
                String url = createMysqlDatabase(db, "operaton_it");
                OperatonEngineAdapter a = new OperatonEngineAdapter(url, db.username(), db.password());
                if (!"false".equalsIgnoreCase(System.getProperty("operaton.mysql.datetimePatch", "true"))) {
                    try (Connection c = DriverManager.getConnection(url, db.username(), db.password())) {
                        int n = MysqlDatetimePrecisionPatch.apply(c).size();
                        System.out.printf("[%s] MySQL datetime(3) patch P-1: %d columns altered%n",
                                getClass().getSimpleName(), n);
                    } catch (SQLException e) {
                        throw new IllegalStateException("datetime(3) patch failed", e);
                    }
                }
                yield a;
            }
            default -> new OperatonEngineAdapter(db.jdbcUrl(), db.username(), db.password());
        };
    }

    /** Creates {@code name} (utf8mb4_bin) on the MySQL server and returns the engine URL for it. */
    static String createMysqlDatabase(Databases.Running db, String name) {
        try (Connection c = db.dataSource().getConnection(); Statement s = c.createStatement()) {
            s.execute("create database " + name + " character set utf8mb4 collate utf8mb4_bin");
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return mysqlUrl(db, name);
    }

    /**
     * The {@link Databases.MySqlContainer} URL of {@code name} plus the session isolation. The MariaDB Connector/J also
     * gets {@code timezone=UTC} (its counterpart of Connector/J {@code connectionTimeZone=UTC}, which the harness sets),
     * so both drivers write and read DATETIME values as UTC.
     */
    static String mysqlUrl(Databases.Running db, String name) {
        Databases.MySqlContainer c = (Databases.MySqlContainer) db.container();
        String isolation = System.getProperty("operaton.mysql.isolation", "READ-COMMITTED");
        String url = c.jdbcUrl(name) + "&sessionVariables=transaction_isolation='" + isolation + "'";
        return c.mariadbDriver() ? url + "&timezone=UTC" : url;
    }

    @Override
    protected void afterEngineStopped() {
        if (db != null) {
            db.close();
            db = null;
        }
    }
}
