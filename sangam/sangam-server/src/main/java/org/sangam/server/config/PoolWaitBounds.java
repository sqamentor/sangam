// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import org.sangam.engine.db.JdbcWaitBounds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * SL-59 (S-R-5 "never hang", P-419): bounded waits on every HikariCP pool of the server, set once per pool before it
 * starts, so every statement on every connection is covered (engine, product layer, relay, quota pool) without
 * per-call-site timeouts:
 * <ol>
 *   <li><b>Pool acquire.</b> {@code connectionTimeout} must be finite: HikariCP turns {@code 0} into
 *       {@code Integer.MAX_VALUE} (wait for ever for a free connection), so such a pool fails start-up.</li>
 *   <li><b>Socket reads.</b> The driver's read timeout ({@link JdbcWaitBounds#readTimeoutProperty}: pgjdbc
 *       {@code socketTimeout} s, mssql-jdbc / MariaDB {@code socketTimeout} ms, Oracle {@code oracle.jdbc.ReadTimeout}
 *       ms) from {@code sangam.db.network-timeout} (default {@link JdbcWaitBounds#DEFAULT_NETWORK_TIMEOUT}), unless the
 *       URL or {@code spring.datasource.hikari.data-source-properties} already set it. It ends reads on dead
 *       connections, which statement timeouts cannot cancel; {@code 0} disables it (logged as a warning).</li>
 * </ol>
 * The quota pool ({@link QuotaWiring}) copies the main pool's state, so it inherits both; the read-replica pool
 * ({@link ReadReplicaWiring}) is not a bean and calls {@link #apply} itself.
 */
@Configuration(proxyBeanMethods = false)
public class PoolWaitBounds {

    private static final Logger LOG = LoggerFactory.getLogger(PoolWaitBounds.class);
    static final String NETWORK_TIMEOUT = "sangam.db.network-timeout";

    /** Applies the bounds to every Hikari pool bean before it starts. */
    @Bean
    public static BeanPostProcessor sangamPoolWaitBounds(Environment env) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessBeforeInitialization(Object bean, String beanName) {
                if (bean instanceof HikariDataSource hikari && !hikari.isRunning()) {
                    apply(hikari, networkTimeout(env), beanName);
                }
                return bean;
            }
        };
    }

    /** {@code sangam.db.network-timeout}, default {@link JdbcWaitBounds#DEFAULT_NETWORK_TIMEOUT}. */
    public static Duration networkTimeout(Environment env) {
        Duration d = env.getProperty(NETWORK_TIMEOUT, Duration.class, JdbcWaitBounds.DEFAULT_NETWORK_TIMEOUT);
        return d == null ? Duration.ZERO : d;
    }

    /**
     * Applies the SL-59 bounds to a pool that has not started.
     *
     * @throws IllegalStateException when the pool would wait for ever for a free connection
     */
    public static void apply(HikariConfig pool, Duration networkTimeout, String name) {
        long acquire = pool.getConnectionTimeout();
        if (acquire <= 0 || acquire >= Integer.MAX_VALUE) {
            throw new IllegalStateException("Pool '" + name + "': connectionTimeout " + acquire + " ms is unbounded "
                    + "(HikariCP reads 0 as wait for ever); set spring.datasource.hikari.connection-timeout to a finite "
                    + "value (SL-59, S-R-5 never hang)");
        }
        String url = pool.getJdbcUrl();
        if (url == null) {
            LOG.warn("Pool '{}' has no JDBC URL (dataSourceClassName {}); set the driver's socket read timeout in its "
                    + "data source properties (SL-59)", name, pool.getDataSourceClassName());
            return;
        }
        if (JdbcWaitBounds.readTimeoutConfigured(url, pool.getDataSourceProperties())) {
            LOG.info("Pool '{}': socket read timeout set by the operator, kept (SL-59)", name);
            return;
        }
        Optional<Map.Entry<String, String>> p = JdbcWaitBounds.readTimeoutProperty(url, networkTimeout);
        if (p.isPresent()) {
            pool.addDataSourceProperty(p.get().getKey(), p.get().getValue());
            LOG.info("Pool '{}': socket reads bounded by {}={} (SL-59)", name, p.get().getKey(), p.get().getValue());
        } else if (JdbcWaitBounds.Driver.of(url).readTimeoutKey() != null) {
            LOG.warn("Pool '{}': {}={} disables the socket read bound; a dead connection can block a thread for ever "
                    + "(SL-59)", name, NETWORK_TIMEOUT, networkTimeout);
        }
    }
}
