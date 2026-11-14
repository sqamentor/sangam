// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import io.micrometer.core.instrument.MeterRegistry;

import org.operaton.bpm.engine.ProcessEngine;
import org.sangam.assembly.engine.EngineAssembly;
import org.sangam.assembly.host.SangamProperties;
import org.sangam.core.engine.SangamClock;
import org.sangam.engine.OperatonEngineAdapter;
import org.sangam.engine.SangamEngineSettings;
import org.sangam.engine.deploy.OperationRefPinning;
import org.sangam.integration.connector.contract.ApiContractStore;
import org.sangam.integration.connector.contract.ContractOperations;
import org.sangam.integration.connector.contract.OperationRef;
import org.sangam.rest.port.PackagePort;
import org.sangam.rest.port.Page;
import org.sangam.rest.port.RequestContext;
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
            ObjectProvider<io.opentelemetry.api.OpenTelemetry> otel, ObjectProvider<ApiContractStore> contracts,
            ObjectProvider<PackagePort> packages) {
        SangamEngineSettings s = new SangamEngineSettings();
        s.setApplyProductSchema(false);
        s.setApplyEnginePatches(true);
        // M-141b: job executor, SQL Server RCSI check, WS-PORTS' CMMN event listeners (FLOWABLE-INTO-OPERATON FC-55, note
        // 12) and the M-021 test-mode switch with the host's profiles, read by the host-neutral assembly (the engine
        // refuses test mode on any database but H2; the assembly refuses it in production)
        EngineAssembly.applyConfiguration(s, properties(env), List.of(env.getActiveProfiles()));
        s.setDefaultTenantId(AttributionTenant.resolve(env)); // SL-86: the one attribution-tenant resolver
        // M-044: the starting trace id of each new process instance is the current OpenTelemetry span's (the request's
        // or the engine command's); no valid span, no trace id
        s.setTraceIdSource(EngineWiring::currentTraceId);
        // M-068: sangam:operationRef is pinned at deploy against the package's stored contracts. Both beans are looked up
        // at deploy time: PackagePort depends on DeploymentPort and so on the engine, which this bean configures.
        s.setOperationRefPinner(operationRefPinner(contracts::getIfAvailable, packages::getIfAvailable));
        meters.ifAvailable(r -> s.setMetrics(new EngineMicrometerMetrics(r)));
        // Note 16 (VI.4 rule 7): jobs, timers and async continuations run with the tenant's system identity bound in
        // sangam-security's TenantContext. Without the security module the engine binds JobIdentityBinder.current() only.
        if (securityModuleEnabled(env)) {
            s.setJobIdentityBinder(org.sangam.security.tenant.TenantContext::runAs);
        }
        return finish(s, env, otel);
    }

    /** M-044: the W3C trace id of the current OpenTelemetry span, or {@code null} when no valid span is current. */
    static String currentTraceId() {
        io.opentelemetry.api.trace.SpanContext sc = io.opentelemetry.api.trace.Span.current().getSpanContext();
        return sc.isValid() ? sc.getTraceId() : null;
    }

    /**
     * M-068 (integrator rulings 14:38Z and on the forged package deployment): the engine's
     * {@code OperationRefPinning.Pinner}. A contract belongs to a package, so the engine calls it only for a package's own
     * deploy, with the proof that deploy path bound ({@code PackageDeploy}: tenant, package key, package hash); a
     * deployment name is never read. The package is the one package of that tenant with that key and hash in state
     * APPROVED, the state it has while its own deploy transition runs (found through {@link PackagePort}, no second
     * mapping). The ref names a contract stored for that package and an operation of it; without a version the newest
     * stored version is pinned (decision 115: versions are stored only while the package is DRAFT, so it is one that went
     * through review). Every refusal is {@code 422 MODEL_INVALID} with the reason: no proof, an unknown or ambiguous
     * package, an unknown contract, version or operation, a malformed ref, a missing store.
     */
    static OperationRefPinning.Pinner operationRefPinner(Supplier<ApiContractStore> contracts,
                                                         Supplier<PackagePort> packages) {
        return (proof, value) -> {
            try {
                if (proof == null) {
                    throw refused(value, "only a package's own deploy (/packages) can bind contract operations");
                }
                OperationRef ref = OperationRef.parse(value);
                String packageId = packageOf(packages.get(), proof);
                ApiContractStore store = contracts.get();
                if (store == null) {
                    throw refused(value, "no API contract store is bound");
                }
                ApiContractStore.ApiContract c = store.find(proof.tenantId(), packageId, ref.contractId(), ref.version())
                        .orElseThrow(() -> refused(value, "contract " + ref.contractId()
                                + (ref.version() == null ? "" : "@" + ref.version()) + " is not stored for package "
                                + packageId));
                if (ContractOperations.find(c, ref.operationId()).isEmpty()) {
                    throw refused(value, "operation " + ref.operationId() + " is not in " + ref.contractId() + "@"
                            + c.version());
                }
                return ref.pinned(c.version()).toString();
            } catch (IllegalArgumentException e) {
                throw e.getMessage() != null && e.getMessage().startsWith(REFUSED) ? e : refused(value, e.getMessage());
            }
        };
    }

    private static final String REFUSED = "422 MODEL_INVALID: ";

    /** Read by the deploy path's error mapping ({@code OperatonSupport}: "<status> <CODE>: <detail>"). */
    private static IllegalArgumentException refused(String value, String reason) {
        return new IllegalArgumentException(REFUSED + "sangam:operationRef '" + value + "': " + reason);
    }

    /** The id of the APPROVED package a package deploy deploys, or a refusal. */
    static String packageOf(PackagePort packages, OperationRefPinning.PackageDeploy proof) {
        if (packages == null) {
            throw new IllegalArgumentException("no package store is bound");
        }
        String key = proof.packageKey();
        String hash = proof.packageHash();
        RequestContext ctx = RequestContext.system(proof.tenantId(), null);
        List<String> ids = new ArrayList<>();
        for (int page = 0; ; page++) {
            Page<PackagePort.Package> p = packages.list(ctx, key, "APPROVED",
                    new Page.PageRequest(page, Page.PageRequest.MAX_SIZE));
            for (PackagePort.Package pkg : p.items()) {
                if (key.equals(pkg.key()) && hash.equals(pkg.packageHash())) {
                    ids.add(pkg.id());
                }
            }
            if (p.items().size() < Page.PageRequest.MAX_SIZE) {
                break;
            }
        }
        if (ids.size() != 1) {
            throw new IllegalArgumentException(ids.isEmpty()
                    ? "no APPROVED package " + key + " with hash " + hash + " is being deployed in this tenant"
                    : "package " + key + " with hash " + hash + " is ambiguous (" + ids.size() + " APPROVED packages)");
        }
        return ids.get(0);
    }

    static final String TEST_MODE_PROPERTY = EngineAssembly.TEST_MODE_PROPERTY;

    /** M-141b: the Spring {@link Environment} as the assembly's config source (relaxed binding and placeholders kept). */
    static SangamProperties properties(Environment env) {
        return env::getProperty;
    }

    /**
     * M-021: {@value #TEST_MODE_PROPERTY} (default off). On together with a production environment (SL-109:
     * {@code sangam.security.production=true} or a {@code prod} profile) the start is refused.
     */
    static boolean testMode(Environment env) {
        return EngineAssembly.testMode(properties(env), List.of(env.getActiveProfiles()));
    }

    /**
     * M-021: the clock behind {@code /api/v1/test-mode/clock}, only with {@value #TEST_MODE_PROPERTY}. Built after the
     * engine (it takes the adapter), and re-checks H2 and the profiles itself rather than trusting the settings bean.
     */
    @Bean
    @ConditionalOnProperty(name = TEST_MODE_PROPERTY, havingValue = "true")
    public org.sangam.rest.port.TestClockPort sangamTestClockPort(SangamEngineSettings sangamEngineSettings,
                                                                  OperatonEngineAdapter sangamEngineAdapter,
                                                                  ProcessEngine engine, Environment env) {
        testMode(env);
        String db = ((org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl) engine.getProcessEngineConfiguration())
                .getDatabaseType();
        return new org.sangam.server.bridge.TestModeClockBridge(org.sangam.engine.clock.TestModeClock.forEngine(
                sangamEngineSettings, "h2".equalsIgnoreCase(db), java.util.List.of(env.getActiveProfiles()),
                org.sangam.server.bridge.TestModeClockBridge::log));
    }

    /**
     * Owner decision 75 (fail closed): without the security module the engine runs jobs, timers and async continuations
     * without the tenant's system identity. That is refused unless development is explicit, and logged at every start.
     */
    static boolean securityModuleEnabled(Environment env) {
        if (env.getProperty("sangam.modules.security.enabled", Boolean.class, true)) {
            return true;
        }
        org.sangam.rest.config.SangamRestAutoConfiguration.requireDevelopmentWithoutSecurity(env, "engine jobs, timers and"
                + " async continuations run without the tenant's system identity (no TenantContext job identity binding)");
        return false;
    }

    private SangamEngineSettings finish(SangamEngineSettings s, Environment env,
                                        ObjectProvider<io.opentelemetry.api.OpenTelemetry> otel) {
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
