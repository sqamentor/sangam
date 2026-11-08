// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import io.micrometer.core.instrument.MeterRegistry;

import org.operaton.bpm.engine.ProcessEngine;
import org.sangam.core.engine.SangamClock;
import org.sangam.engine.OperatonEngineAdapter;
import org.sangam.engine.SangamEngineSettings;
import org.sangam.server.bridge.EngineConfigurationCustomizer;
import org.sangam.server.bridge.EngineConfigurerHolder;
import org.sangam.server.bridge.EngineMicrometerMetrics;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * sangam-engine wiring (sangam-engine README "Integration notes"). There is exactly one Operaton engine per JVM: the
 * one the Operaton Spring Boot starter builds on the Spring {@code DataSource} and transaction manager (P-1,
 * {@code /engine-rest}). {@link EngineConfigurationCustomizer} applies {@code SangamEngineConfigurer} to the starter's
 * configuration; the adapter is created over the built engine and exposed as {@code EngineAdapter} and
 * {@code SangamEngineAdapter} (sangam-rest then switches its WorkPort to the fenced engine adapter).
 *
 * <p>Settings bind to {@code sangam.engine.*}. The server runs Liquibase itself ({@code SchemaBootstrap}), so
 * {@code applyProductSchema=false}; {@code applyEnginePatches=true} (P-1/P-2 MySQL patches after the engine created its
 * tables). Disable with {@code sangam.modules.engine.enabled=false}.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "sangam.modules.engine.enabled", havingValue = "true", matchIfMissing = true)
public class EngineWiring {

    @Bean
    @ConfigurationProperties("sangam.engine")
    public SangamEngineSettings sangamEngineSettings(Environment env, ObjectProvider<MeterRegistry> meters) {
        SangamEngineSettings s = new SangamEngineSettings();
        s.setApplyProductSchema(false);
        s.setApplyEnginePatches(true);
        s.setJobExecutorActivate(env.getProperty("operaton.bpm.job-execution.enabled", Boolean.class, true));
        s.setRequireSqlServerRcsi(env.getProperty("sangam.db.require-rcsi", Boolean.class, true));
        String tenant = env.getProperty("sangam.tenant.default");
        if (tenant != null && !tenant.isBlank()) {
            s.setDefaultTenantId(tenant);
        }
        // FLOWABLE-INTO-OPERATON FC-55: WS-PORTS' CMMN event listeners are on when the ports module is (note 12)
        s.setCmmnEventListenersEnabled(env.getProperty("sangam.ports.cmmn-event-listeners", Boolean.class, true));
        meters.ifAvailable(r -> s.setMetrics(new EngineMicrometerMetrics(r)));
        // Note 16 (VI.4 rule 7): jobs, timers and async continuations run with the tenant's system identity bound in
        // sangam-security's TenantContext. Without the security module the engine binds JobIdentityBinder.current() only.
        if (env.getProperty("sangam.modules.security.enabled", Boolean.class, true)) {
            s.setJobIdentityBinder(org.sangam.security.tenant.TenantContext::runAs);
        }
        return s;
    }

    @Bean
    public static EngineConfigurerHolder sangamEngineConfigurerHolder() {
        return new EngineConfigurerHolder();
    }

    @Bean
    public static EngineConfigurationCustomizer sangamEngineConfigurationCustomizer() {
        return new EngineConfigurationCustomizer();
    }

    /**
     * The sangam-engine adapter over the starter's engine; injectable as {@code EngineAdapter} and
     * {@code SangamEngineAdapter}. No destroy method: {@code close()} would close the engine, which the starter owns.
     */
    @Bean(destroyMethod = "")
    public OperatonEngineAdapter sangamEngineAdapter(ProcessEngine engine, EngineConfigurerHolder holder) {
        return holder.configurer().afterBuild(engine, holder.prepared(), holder.jdbcUrl(), null);
    }

    /** The engine clock (note 13); other modules inject this rather than reading {@code ClockUtil}. */
    @Bean
    public SangamClock sangamClock(OperatonEngineAdapter adapter) {
        return adapter.clock();
    }
}
