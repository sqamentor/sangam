// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.sql.DataSource;

import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.bpm.engine.impl.cfg.ProcessEnginePlugin;
import org.sangam.assembly.engine.EngineAssembly;
import org.sangam.assembly.host.Capabilities;
import org.sangam.core.api.ExpiryPolicy;
import org.sangam.core.engine.AuditSink;
import org.sangam.core.engine.EngineEventSink;
import org.sangam.core.engine.IncidentSink;
import org.sangam.core.engine.WorkCalendar;
import org.sangam.engine.SangamEngineConfigurer;
import org.sangam.engine.SangamEngineSettings;
import org.sangam.engine.SangamProcessEnginePlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;

/**
 * Applies sangam-engine to the ONE Operaton engine that the Operaton Spring Boot starter builds (sangam-engine README
 * integration notes 1, 2, 4, 11). It runs on the starter's {@link ProcessEngineConfigurationImpl} bean before the
 * {@code ProcessEngineFactoryBean} builds the engine. The host-neutral part (module ports, plugin order, capability
 * check) is {@link EngineAssembly} (M-141b); this class only finds the Spring beans it needs.
 *
 * <p><b>DataSource (P-1).</b> The engine keeps the <em>raw</em> Spring {@link DataSource}: the Spring engine
 * configuration wraps it in a {@code TransactionAwareDataSourceProxy}, so engine commands use the very connection that
 * {@code DataSourceUtils.getConnection(dataSource)} returns inside a Spring transaction. sangam-rest's {@code TxSupport}
 * relies on that: product rows (audit chain, ledger, idempotency) commit together with engine state. Handing the engine
 * {@code IsolationGuardDataSource(raw)} would bind a different connection, so the guard runs only as a check
 * ({@link SangamEngineConfigurer#prepare}) and the server's {@code IsolationGuard} forces READ COMMITTED on the pool.
 *
 * <p><b>Plugin order</b> ({@link EngineAssembly#orderPlugins}). The starter's composite (its {@code Default*Configuration}
 * plugins: datasource, history level, job executor, id generator, serialization, generic properties) runs first, so
 * the Sangam plugins can rely on and override those defaults. Then {@link SangamProcessEnginePlugin}, then the
 * {@link EngineAssembly#PLUGIN_ORDER} plugins (README note 11), then every other {@code ProcessEnginePlugin} bean, and
 * last a step that re-applies the pinned database type (FLOWABLE-INTO-OPERATON D-05), which the starter's datasource
 * plugin would otherwise overwrite with {@code operaton.bpm.database.type}.
 *
 * <p><b>Capabilities</b> (M-144). While the security module is on, the start is refused with
 * {@code CAPABILITY_MISSING} unless sangam-security's engine plugin is in the final list.
 *
 * <p>Liquibase ({@code SchemaBootstrap}) is forced to run before the engine is configured, so product tables exist
 * when the engine and its plugins start.
 *
 * <p><b>Engine checks</b> (owner decisions 276, 286 and 287, phase 1b): before {@code prepare}, the engine area's own
 * checks of {@code EngineAssembly} run on this host too: utc-jvm, jackson-feel-compatibility, schema-before-engine
 * (the server's Liquibase or dev schema bean ran, or the engine applies the product schema), job-leases only with
 * executor partitioning (decision 286), and the database guard on the raw pool the engine is handed (decision 287 c).
 * One {@code CAPABILITY_MISSING} refusal names every missing capability with each failed check's own exception.
 * The server always carries sangam-persistence's changelog, so schema-before-engine fails only when
 * {@code sangam.persistence.changelog} points at a missing resource while {@code sangam.persistence.dev-schema} is
 * off, where the engine would otherwise start on no product schema.
 */
public final class EngineConfigurationCustomizer implements BeanPostProcessor, BeanFactoryAware, EnvironmentAware {

    private static final Logger LOG = LoggerFactory.getLogger(EngineConfigurationCustomizer.class);

    /** The starter's own default plugins, found inside its composite. */
    private static final String STARTER_PACKAGE = "org.operaton.bpm.spring.boot.starter";

    private ListableBeanFactory beans;
    private Environment environment;
    private boolean done;

    @Override
    public void setBeanFactory(BeanFactory beanFactory) {
        this.beans = (ListableBeanFactory) beanFactory;
    }

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
        if (!(bean instanceof ProcessEngineConfigurationImpl cfg) || done) {
            return bean;
        }
        done = true;
        boolean ranSchema = runSchemaFirst(beans);
        SangamEngineSettings settings = beans.getBean(SangamEngineSettings.class);
        EngineAssembly.applyModulePorts(settings,
                Optional.ofNullable(beans.getBeanProvider(EngineEventSink.class).getIfAvailable()),
                Optional.ofNullable(beans.getBeanProvider(ExpiryPolicy.class).getIfAvailable()),
                Optional.ofNullable(beans.getBeanProvider(AuditSink.class).getIfAvailable()),
                Optional.ofNullable(beans.getBeanProvider(IncidentSink.class).getIfAvailable()),
                beans.getBeansOfType(WorkCalendar.class));
        DataSource raw = beans.getBean(DataSource.class);
        EngineAssembly.EngineChecks engine = engineChecks(settings, ranSchema, raw);
        SangamEngineConfigurer configurer = new SangamEngineConfigurer(settings);
        DataSource prepared = configurer.prepare(raw);
        // the holder owns the engine slot from here on and releases it when the context closes
        beans.getBean(EngineConfigurerHolder.class).set(configurer, prepared, jdbcUrl(raw));
        configurer.configure(cfg, raw, List.of());
        List<ProcessEnginePlugin> plugins = EngineAssembly.orderPlugins(cfg.getProcessEnginePlugins(),
                configurer.plugin(), name -> name.startsWith(STARTER_PACKAGE), this::beansOfType,
                configurer.findings().operatonDatabaseType());
        // the Spring host supplies the config source (Environment) and the shared transaction (P-1, above)
        EngineAssembly.requireCapabilities(securityModuleOn(), plugins,
                List.of(Capabilities.CONFIG_SOURCE, Capabilities.SHARED_TRANSACTION), engine);
        cfg.setProcessEnginePlugins(plugins);
        LOG.info("sangam-engine applied to the Operaton starter engine (dialect {}, plugins {})",
                configurer.findings().dialect(), cfg.getProcessEnginePlugins());
        return bean;
    }

    /**
     * The security module switch exactly as {@code SecurityWiring}'s {@code @ConditionalOnProperty} reads it
     * ({@code havingValue = "true"}, {@code matchIfMissing = true}), so the check expects the plugin precisely when
     * that wiring registers it.
     */
    private boolean securityModuleOn() {
        String value = environment == null ? null : environment.getProperty("sangam.modules.security.enabled");
        return value == null || "true".equalsIgnoreCase(value.trim());
    }

    /** The schema beans of {@code SchemaBootstrap}, in the order they run. */
    static final List<String> SCHEMA_BEANS = List.of("sangamLiquibase", "sangamLiquibaseTask", "sangamDevSchema");

    /**
     * Liquibase / dev schema beans first: the product tables must exist before the engine starts. True when at least
     * one of them exists and was created (it runs while it is initialised), the host schema step of
     * {@link EngineAssembly#checkBeforeDatabase}.
     */
    static boolean runSchemaFirst(ListableBeanFactory beans) {
        boolean ran = false;
        for (String name : SCHEMA_BEANS) {
            if (beans.containsBean(name)) {
                beans.getBean(name);
                ran = true;
            }
        }
        return ran;
    }

    /** The engine area's checks for this host: before the database, then the guard on the raw engine pool. */
    static EngineAssembly.EngineChecks engineChecks(SangamEngineSettings settings, boolean ranSchema, DataSource raw) {
        return EngineAssembly.checkBeforeDatabase(settings, ranSchema).and(EngineAssembly.checkDatabase(settings, raw));
    }

    /** Plugin beans of one ordered class, for plugins the starter did not collect (or that are created lazily). */
    @SuppressWarnings("unchecked")
    private List<ProcessEnginePlugin> beansOfType(String className) {
        try {
            Class<? extends ProcessEnginePlugin> type = (Class<? extends ProcessEnginePlugin>) Class.forName(className,
                    false, getClass().getClassLoader());
            return new ArrayList<>(beans.getBeansOfType(type).values());
        } catch (ClassNotFoundException e) {
            return List.of();
        }
    }

    private static String jdbcUrl(DataSource ds) {
        try (Connection c = ds.getConnection()) {
            return c.getMetaData().getURL();
        } catch (SQLException e) {
            return null;
        }
    }
}
