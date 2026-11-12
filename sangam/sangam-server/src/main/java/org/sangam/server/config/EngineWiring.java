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
    public SangamEngineSettings sangamEngineSettings(Environment env, ObjectProvider<MeterRegistry> meters,
            ObjectProvider<io.opentelemetry.api.OpenTelemetry> otel) {
        SangamEngineSettings s = new SangamEngineSettings();
        s.setApplyProductSchema(false);
        s.setApplyEnginePatches(true);
        s.setJobExecutorActivate(env.getProperty("operaton.bpm.job-execution.enabled", Boolean.class, true));
        s.setRequireSqlServerRcsi(env.getProperty("sangam.db.require-rcsi", Boolean.class, true));
        s.setDefaultTenantId(AttributionTenant.resolve(env)); // SL-86: the one attribution-tenant resolver
        // FLOWABLE-INTO-OPERATON FC-55: WS-PORTS' CMMN event listeners are on when the ports module is (note 12)
        s.setCmmnEventListenersEnabled(env.getProperty("sangam.ports.cmmn-event-listeners", Boolean.class, true));
        meters.ifAvailable(r -> s.setMetrics(new EngineMicrometerMetrics(r)));
        // Note 16 (VI.4 rule 7): jobs, timers and async continuations run with the tenant's system identity bound in
        // sangam-security's TenantContext. Without the security module the engine binds JobIdentityBinder.current() only.
        if (env.getProperty("sangam.modules.security.enabled", Boolean.class, true)) {
            s.setJobIdentityBinder(org.sangam.security.tenant.TenantContext::runAs);
        }
        s.setTracing(tracing(env, otel.getIfAvailable()));
        return s;
    }

    /**
     * M-062 (R13, R56): engine spans over OpenTelemetry only with {@code sangam.tracing.engine.enabled=true}
     * (default off: the engine installs no tracing interceptor). Enabled without an {@code OpenTelemetry} bean fails
     * the start-up rather than tracing nothing silently.
     */
    static org.sangam.engine.trace.EngineTracing tracing(Environment env, io.opentelemetry.api.OpenTelemetry otelOrNull) {
        if (!env.getProperty(TRACING_PROPERTY, Boolean.class, false)) {
            return org.sangam.engine.trace.EngineTracing.NONE;
        }
        if (otelOrNull == null) {
            throw new IllegalStateException(TRACING_PROPERTY + "=true needs an OpenTelemetry bean"
                    + " (spring-boot-starter-opentelemetry on the class path)");
        }
        return new OpenTelemetryEngineTracing(otelOrNull);
    }

    // not under sangam.engine.*: that prefix binds onto SangamEngineSettings, whose "tracing" is the port itself
    static final String TRACING_PROPERTY = "sangam.tracing.engine.enabled";

    /**
     * M-054: with {@code sangam.engine.job-virtual-threads} (or env {@code SANGAM_JOB_VIRTUAL_THREADS}) or any
     * {@code sangam.engine.job-bulkheads.<type>} set, the job executor must be Sangam's: this bean replaces the
     * Operaton starter's {@code JobExecutor} (its bean is {@code @ConditionalOnMissingBean(JobExecutor.class)}), sized
     * from the same settings as {@code SangamProcessEnginePlugin} sizes its own. With both options off there is no such
     * bean and the starter's executor stays exactly as before.
     */
    @Bean
    @org.springframework.context.annotation.Conditional(JobExecutorOptionsCondition.class)
    public org.operaton.bpm.engine.impl.jobexecutor.JobExecutor sangamJobExecutor(SangamEngineSettings sangamEngineSettings) {
        if (sangamEngineSettings.isApplyEnvironment() && !sangamEngineSettings.isFrozen()) {
            // the same SANGAM_JOB_* values the configurer applies again at bootstrap (idempotent)
            sangamEngineSettings.applyEnvironment(System.getenv());
        }
        return jobExecutor(sangamEngineSettings);
    }

    /** A {@code SangamJobExecutor} with the options and the IR-7 sizing of {@code settings}. */
    static org.sangam.engine.job.SangamJobExecutor jobExecutor(SangamEngineSettings s) {
        org.sangam.engine.job.SangamJobExecutor je = new org.sangam.engine.job.SangamJobExecutor(s.isJobVirtualThreads(),
                s.getJobBulkheads(), s.getJobBulkheadWait());
        je.setCorePoolSize(s.getJobCorePoolSize());
        je.setMaxPoolSize(s.getJobMaxPoolSize());
        je.setQueueSize(s.getJobQueueCapacity());
        je.setMaxJobsPerAcquisition(s.getJobMaxJobsPerAcquisition());
        je.setWaitTimeInMillis(s.getJobWaitTimeInMillis());
        je.setWaitIncreaseFactor(s.getJobWaitIncreaseFactor());
        je.setMaxWait(s.getJobMaxWait());
        je.setLockTimeInMillis(s.getJobLockTimeInMillis());
        je.setBackoffTimeInMillis(s.getJobBackoffTimeInMillis());
        je.setMaxBackoff(s.getJobMaxBackoff());
        if (s.getJobLockOwner() != null) {
            je.setLockOwner(s.getJobLockOwner());
        }
        return je;
    }

    /** True when an M-054 executor option is set, read as the engine settings read it (properties, then env). */
    static final class JobExecutorOptionsCondition implements org.springframework.context.annotation.Condition {
        @Override
        public boolean matches(org.springframework.context.annotation.ConditionContext context,
                               org.springframework.core.type.AnnotatedTypeMetadata metadata) {
            return optionsOn(context.getEnvironment(), System.getenv());
        }

        static boolean optionsOn(Environment env, java.util.Map<String, String> osEnv) {
            org.springframework.boot.context.properties.bind.Binder binder =
                    org.springframework.boot.context.properties.bind.Binder.get(env);
            boolean virtual = binder.bind("sangam.engine.job-virtual-threads", Boolean.class).orElse(false);
            boolean applyEnv = binder.bind("sangam.engine.apply-environment", Boolean.class).orElse(true);
            String v = osEnv.get("SANGAM_JOB_VIRTUAL_THREADS");
            if (applyEnv && v != null && !v.isBlank()) {
                virtual = Boolean.parseBoolean(v.trim());
            }
            boolean bulkheads = !binder.bind("sangam.engine.job-bulkheads",
                    org.springframework.boot.context.properties.bind.Bindable.mapOf(String.class, Integer.class))
                    .orElse(java.util.Map.of()).isEmpty();
            return virtual || bulkheads;
        }
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
