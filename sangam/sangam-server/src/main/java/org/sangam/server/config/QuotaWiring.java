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

    @Bean
    public JdbcTenantQuotas sangamTenantQuotas(TxSupport tx, DataSource ds, ObjectProvider<SangamSecurity> security,
                                               Environment env) {
        Transactions ptx = Transactions.of(ds);
        SangamSecurity s = security.getIfAvailable();
        JdbcTenantQuotas.OverrideSource source = s == null ? t -> Map.of()
                : t -> ptx.inTransaction(c -> s.tenants().find(c, t).map(tenant -> tenant.quotas()).orElse(Map.of()));
        JdbcTenantQuotas q = new JdbcTenantQuotas(tx.dialect(), ptx, source,
                JdbcTenantQuotas.defaultsFrom(k -> env.getProperty("sangam.quotas.defaults." + k)),
                new AuditChain(tx.dialect()), Clock.systemUTC(),
                env.getProperty("sangam.quotas.override-ttl", Duration.class, Duration.ofSeconds(30)));
        LOG.info("SL-43 per-tenant quotas enabled (database-counted windows)");
        return q;
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
