// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.Map;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Isolation guard (ARCHITECTURE section 1.5 and 1.6 "REPEATABLE READ rejected"; R84 MySQL Tier-1 conditions):
 * <ol>
 *   <li>every Hikari pool is forced to {@code TRANSACTION_READ_COMMITTED} before it starts; on MySQL/MariaDB the
 *       session isolation is also set by {@code connectionInitSql};</li>
 *   <li>at startup every {@link DataSource} bean is checked with a real connection; anything but READ COMMITTED fails
 *       startup;</li>
 *   <li>SQL Server: READ_COMMITTED_SNAPSHOT (RCSI) must be on for the database ({@code sangam.db.require-rcsi},
 *       default true);</li>
 *   <li>MySQL: {@code @@transaction_isolation = READ-COMMITTED}, {@code @@character_set_database = utf8mb4*}; a server
 *       other than 8.4 LTS is logged as a warning.</li>
 * </ol>
 */
@Configuration(proxyBeanMethods = false)
public class IsolationGuard {

    private static final Logger LOG = LoggerFactory.getLogger(IsolationGuard.class);
    static final String MYSQL_INIT_SQL = "SET SESSION TRANSACTION ISOLATION LEVEL READ COMMITTED";

    /** Forces READ COMMITTED on every Hikari pool before initialisation. */
    @Bean
    public static BeanPostProcessor sangamReadCommittedPoolEnforcer() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessBeforeInitialization(Object bean, String beanName) {
                if (bean instanceof HikariDataSource hikari && !hikari.isRunning()) {
                    hikari.setTransactionIsolation("TRANSACTION_READ_COMMITTED");
                    String url = hikari.getJdbcUrl() == null ? "" : hikari.getJdbcUrl().toLowerCase(Locale.ROOT);
                    if ((url.contains(":mysql:") || url.contains(":mariadb:")) && hikari.getConnectionInitSql() == null) {
                        hikari.setConnectionInitSql(MYSQL_INIT_SQL);
                    }
                    LOG.debug("Pool '{}' forced to READ COMMITTED", beanName);
                }
                return bean;
            }
        };
    }

    /** Verifies every pool once all singletons exist; throws (startup fails) on a violation. */
    @Bean
    public SmartInitializingSingleton sangamIsolationVerifier(ListableBeanFactory beans, Environment env) {
        boolean requireRcsi = env.getProperty("sangam.db.require-rcsi", Boolean.class, Boolean.TRUE);
        return () -> {
            Map<String, DataSource> pools = beans.getBeansOfType(DataSource.class);
            for (Map.Entry<String, DataSource> e : pools.entrySet()) {
                verify(e.getKey(), e.getValue(), requireRcsi);
            }
        };
    }

    static void verify(String name, DataSource ds, boolean requireRcsi) {
        try (Connection c = ds.getConnection()) {
            String product = c.getMetaData().getDatabaseProductName();
            String p = product.toLowerCase(Locale.ROOT);
            int iso = c.getTransactionIsolation();
            if (iso != Connection.TRANSACTION_READ_COMMITTED) {
                throw new IllegalStateException("DataSource '" + name + "' (" + product + ") runs at isolation "
                        + isolationName(iso) + "; Sangam requires READ COMMITTED on every pool (ARCHITECTURE 1.5)");
            }
            if (p.contains("sql server")) {
                checkRcsi(name, c, requireRcsi);
            } else if (p.contains("mysql") || p.contains("mariadb")) {
                checkMysql(name, c);
            }
            LOG.info("Isolation guard: DataSource '{}' on {} {} verified at READ COMMITTED", name, product,
                    c.getMetaData().getDatabaseProductVersion());
        } catch (SQLException ex) {
            throw new IllegalStateException("Isolation guard cannot check DataSource '" + name + "': " + ex.getMessage(), ex);
        }
    }

    private static void checkRcsi(String name, Connection c, boolean requireRcsi) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT is_read_committed_snapshot_on FROM sys.databases WHERE name = DB_NAME()")) {
            boolean on = rs.next() && rs.getInt(1) == 1;
            if (!on && requireRcsi) {
                throw new IllegalStateException("DataSource '" + name + "': SQL Server READ_COMMITTED_SNAPSHOT is OFF. Run "
                        + "ALTER DATABASE <db> SET READ_COMMITTED_SNAPSHOT ON (or set sangam.db.require-rcsi=false for development)");
            }
            if (!on) {
                LOG.warn("DataSource '{}': SQL Server RCSI is OFF (allowed by sangam.db.require-rcsi=false)", name);
            }
        }
    }

    private static void checkMysql(String name, Connection c) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT @@transaction_isolation, @@character_set_database, @@version")) {
            if (!rs.next()) {
                throw new IllegalStateException("DataSource '" + name + "': cannot read MySQL session settings");
            }
            String iso = rs.getString(1);
            String charset = rs.getString(2);
            String version = rs.getString(3);
            if (!"READ-COMMITTED".equalsIgnoreCase(iso)) {
                throw new IllegalStateException("DataSource '" + name + "': MySQL @@transaction_isolation is " + iso
                        + "; READ-COMMITTED is required (R84)");
            }
            if (charset == null || !charset.toLowerCase(Locale.ROOT).startsWith("utf8mb4")) {
                throw new IllegalStateException("DataSource '" + name + "': MySQL @@character_set_database is " + charset
                        + "; utf8mb4 is required (R84, P-1)");
            }
            if (version == null || !version.startsWith("8.4")) {
                LOG.warn("DataSource '{}': MySQL server {} is not the pinned 8.4 LTS line (R84)", name, version);
            }
        }
    }

    private static String isolationName(int iso) {
        return switch (iso) {
            case Connection.TRANSACTION_NONE -> "NONE";
            case Connection.TRANSACTION_READ_UNCOMMITTED -> "READ UNCOMMITTED";
            case Connection.TRANSACTION_READ_COMMITTED -> "READ COMMITTED";
            case Connection.TRANSACTION_REPEATABLE_READ -> "REPEATABLE READ";
            case Connection.TRANSACTION_SERIALIZABLE -> "SERIALIZABLE";
            default -> "UNKNOWN(" + iso + ")";
        };
    }
}
