// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;

import javax.sql.DataSource;

import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Transactions;
import org.sangam.core.quota.TenantQuotas;
import org.sangam.rest.support.TxSupport;
import org.sangam.security.SangamSecurity;
import org.sangam.security.quota.JdbcTenantQuotas;
import org.sangam.server.bridge.TenantQuotaEnginePlugin;
import org.sangam.server.bridge.TenantQuotaFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * SL-43 per-tenant quotas (R28). Configuration only:
 * <ul>
 *   <li>{@code sangam.quotas.enabled} (default true);</li>
 *   <li>{@code sangam.quotas.defaults.<key>} for every tenant, keys {@code startsPerSecond}, {@code apiCallsPerMinute},
 *       {@code maxBatchSize}, {@code maxVariableBytes}, {@code maxDocumentBytes}, {@code maxStorageBytes} (negative =
 *       unlimited; unset = the built-in safe default of {@code QuotaLimit});</li>
 *   <li>per-tenant overrides: the same keys in the tenant's {@code quotas} (onboarding {@code tenant.quotas},
 *       {@code TenantAdminService.configure}), re-read every {@code sangam.quotas.override-ttl} (default 30 s).</li>
 * </ul>
 * Entry points: {@link TenantQuotaFilter} (REST; order 45, after the tenant state filter at 40 and before
 * back-pressure at 50 and the Idempotency-Key filter at 100), {@link TenantQuotaEnginePlugin} (engine commands), and
 * through {@link TenantQuotas#current()} the batch and content services.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "sangam.quotas.enabled", havingValue = "true", matchIfMissing = true)
public class QuotaWiring {

    private static final Logger LOG = LoggerFactory.getLogger(QuotaWiring.class);
    public static final int QUOTA_FILTER_ORDER = 45;

    /** Installed as {@link TenantQuotas#current()} for components without dependency injection. */
    static final class Installed implements DisposableBean {
        final JdbcTenantQuotas quotas;

        Installed(JdbcTenantQuotas quotas) {
            this.quotas = quotas;
            TenantQuotas.install(quotas);
        }

        @Override
        public void destroy() {
            if (TenantQuotas.current() == quotas) {
                TenantQuotas.install(null);
            }
        }
    }

    /**
     * SL-43-DEADLOCK: the leaf pool for the quota's own short transactions (counting UPDATE, refusal audit, override
     * reads of callers without a transaction). Quota checks run while the caller holds a connection of the main pool
     * (engine command, REST transaction); a second connection from that same pool is a pool-locking deadlock once all
     * slots are held by callers waiting for their second one. A quota connection is never held while another
     * connection is requested, so waits for it cannot form a cycle. Same database and settings as the main pool;
     * {@code sangam.quotas.pool.maximum-size} (default 4) connections.
     */
    static final class QuotaPool implements DisposableBean {
        final HikariDataSource pool;

        QuotaPool(HikariDataSource pool) {
            this.pool = pool;
        }

        @Override
        public void destroy() {
            pool.close();
        }
    }

    @Bean
    public QuotaPool sangamQuotaPool(DataSource ds, Environment env) {
        HikariConfig cfg = new HikariConfig();
        try {
            if (!ds.isWrapperFor(HikariDataSource.class)) {
                throw new IllegalStateException("SL-43 quotas need a HikariCP main pool to derive the quota pool from"
                        + " (found " + ds.getClass().getName() + "); or set sangam.quotas.enabled=false");
            }
            ds.unwrap(HikariDataSource.class).copyStateTo(cfg);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("cannot derive the quota pool from the main DataSource", e);
        }
        cfg.setPoolName("sangam-quota");
        int size = Math.max(1, env.getProperty("sangam.quotas.pool.maximum-size", Integer.class, 4));
        cfg.setMaximumPoolSize(size);
        cfg.setMinimumIdle(Math.min(1, size));
        cfg.setAutoCommit(true);
        return new QuotaPool(new HikariDataSource(cfg));
    }

    @Bean
    public JdbcTenantQuotas sangamTenantQuotas(TxSupport tx, DataSource ds, QuotaPool pool,
                                               ObjectProvider<SangamSecurity> security, Environment env) {
        Transactions leaf = Transactions.of(pool.pool);
        SangamSecurity s = security.getIfAvailable();
        JdbcTenantQuotas.ConnectionOverrideSource source = s == null ? (c, t) -> Map.of()
                : (c, t) -> s.tenants().find(c, t).map(tenant -> tenant.quotas()).orElse(Map.of());
        JdbcTenantQuotas q = new JdbcTenantQuotas(tx.dialect(), leaf, source, ambient(ds),
                JdbcTenantQuotas.defaultsFrom(k -> env.getProperty("sangam.quotas.defaults." + k)),
                new AuditChain(tx.dialect()), Clock.systemUTC(),
                env.getProperty("sangam.quotas.override-ttl", Duration.class, Duration.ofSeconds(30)));
        LOG.info("SL-43 per-tenant quotas enabled (database-counted windows, own pool of {})",
                pool.pool.getMaximumPoolSize());
        return q;
    }

    /** The connection bound to the current Spring transaction on {@code ds} (engine commands share it), else null. */
    static JdbcTenantQuotas.AmbientConnection ambient(DataSource ds) {
        return () -> TransactionSynchronizationManager.getResource(ds) instanceof ConnectionHolder h
                && h.hasConnection() ? h.getConnection() : null;
    }

    /**
     * Drops the cached quota overrides of a tenant after its configuration commits (at once when no transaction is
     * active; again after the surrounding transaction completes when one is). Other nodes: {@code override-ttl}.
     */
    public static void invalidateAfterCommit(String tenantId) {
        TenantQuotas.current().invalidate(tenantId);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    TenantQuotas.current().invalidate(tenantId);
                }
            });
        }
    }

    @Bean
    public Installed sangamTenantQuotasInstalled(JdbcTenantQuotas quotas) {
        return new Installed(quotas);
    }

    @Bean
    public FilterRegistrationBean<TenantQuotaFilter> sangamTenantQuotaFilter(JdbcTenantQuotas quotas) {
        FilterRegistrationBean<TenantQuotaFilter> reg = new FilterRegistrationBean<>(new TenantQuotaFilter(() -> quotas));
        reg.addUrlPatterns("/api/*", "/engine-rest/*");
        reg.setOrder(QUOTA_FILTER_ORDER);
        reg.setName("sangamTenantQuotaFilter");
        return reg;
    }

    @Bean
    public TenantQuotaEnginePlugin sangamTenantQuotaEnginePlugin() {
        // reads the installed quotas at command time: the engine is built before or after this configuration
        return new TenantQuotaEnginePlugin(TenantQuotas::current);
    }
}
