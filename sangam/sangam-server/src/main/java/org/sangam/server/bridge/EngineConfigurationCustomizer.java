// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.operaton.bpm.engine.impl.cfg.CompositeProcessEnginePlugin;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.bpm.engine.impl.cfg.ProcessEnginePlugin;
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

/**
 * Applies sangam-engine to the ONE Operaton engine that the Operaton Spring Boot starter builds (sangam-engine README
 * integration notes 1, 2, 4, 11). It runs on the starter's {@link ProcessEngineConfigurationImpl} bean before the
 * {@code ProcessEngineFactoryBean} builds the engine.
 *
 * <p><b>DataSource (P-1).</b> The engine keeps the <em>raw</em> Spring {@link DataSource}: the Spring engine
 * configuration wraps it in a {@code TransactionAwareDataSourceProxy}, so engine commands use the very connection that
 * {@code DataSourceUtils.getConnection(dataSource)} returns inside a Spring transaction. sangam-rest's {@code TxSupport}
 * relies on that: product rows (audit chain, ledger, idempotency) commit together with engine state. Handing the engine
 * {@code IsolationGuardDataSource(raw)} would bind a different connection, so the guard runs only as a check
 * ({@link SangamEngineConfigurer#prepare}) and the server's {@code IsolationGuard} forces READ COMMITTED on the pool.
 *
 * <p><b>Plugin order.</b> The starter's composite (its {@code Default*Configuration} plugins: datasource, history
 * level, job executor, id generator, serialization, generic properties) runs first, so the Sangam plugins can rely on
 * and override those defaults. Then {@link SangamProcessEnginePlugin}, then sangam-security's, sangam-integration's and
 * sangam-flowable-ports' plugins in that order (README note 11), then every other {@code ProcessEnginePlugin} bean,
 * and last a step that re-applies the pinned database type (FLOWABLE-INTO-OPERATON D-05), which the starter's datasource plugin would
 * otherwise overwrite with {@code operaton.bpm.database.type}.
 *
 * <p>Liquibase ({@code SchemaBootstrap}) is forced to run before the engine is configured, so product tables exist
 * when the engine and its plugins start.
 */
public final class EngineConfigurationCustomizer implements BeanPostProcessor, BeanFactoryAware {

    private static final Logger LOG = LoggerFactory.getLogger(EngineConfigurationCustomizer.class);

    /** Plugin class names in their required order after {@link SangamProcessEnginePlugin} (README note 11). */
    private static final List<String> ORDER = List.of(
            "org.sangam.security.assignment.SangamSecurityEnginePlugin",
            "org.sangam.integration.bridge.operaton.SangamIntegrationEnginePlugin",
            "org.sangam.ports.SangamPortsPlugin",
            // generic modules (final integration wave): case files (CMMN listener, after ports), geo FEEL functions
            "org.sangam.casefile.cmmn.CaseFileEnginePlugin",
            "org.sangam.geo.feel.GeoEnginePlugin",
            // compliance hooks (purpose binding, national-id guard, retention tracking) run after the module plugins
            "org.sangam.server.bridge.CompliancePurposePlugin",
            // profile binding of a case (R43: profileVersion + CFG_CASE_BINDING) after compliance accepted the start
            "org.sangam.server.bridge.ConfigCaseBindingPlugin",
            // global task listeners (sangam-ops) attach last, seeing every task the earlier plugins shaped
            "org.sangam.server.bridge.OpsTaskListenerPlugin");

    private ListableBeanFactory beans;
    private boolean done;

    @Override
    public void setBeanFactory(BeanFactory beanFactory) {
        this.beans = (ListableBeanFactory) beanFactory;
    }

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
        if (!(bean instanceof ProcessEngineConfigurationImpl cfg) || done) {
            return bean;
        }
        done = true;
        runSchemaFirst();
        SangamEngineSettings settings = beans.getBean(SangamEngineSettings.class);
        applyModulePorts(settings);
        DataSource raw = beans.getBean(DataSource.class);
        SangamEngineConfigurer configurer = new SangamEngineConfigurer(settings);
        DataSource prepared = configurer.prepare(raw);
        configurer.configure(cfg, raw, List.of());
        cfg.setProcessEnginePlugins(order(cfg.getProcessEnginePlugins(), configurer.plugin(),
                configurer.findings().operatonDatabaseType()));
        beans.getBean(EngineConfigurerHolder.class).set(configurer, prepared, jdbcUrl(raw));
        LOG.info("sangam-engine applied to the Operaton starter engine (dialect {}, plugins {})",
                configurer.findings().dialect(), cfg.getProcessEnginePlugins());
        return bean;
    }

    /** Liquibase / dev schema beans first: the product tables must exist before the engine starts. */
    private void runSchemaFirst() {
        for (String name : List.of("sangamLiquibase", "sangamLiquibaseTask", "sangamDevSchema")) {
            if (beans.containsBean(name)) {
                beans.getBean(name);
            }
        }
    }

    /** Ports other modules supply as beans (README note 4): event sink, expiry policy, sinks, work calendars. */
    private void applyModulePorts(SangamEngineSettings settings) {
        beans.getBeanProvider(EngineEventSink.class).ifAvailable(settings::setEventSink);
        beans.getBeanProvider(ExpiryPolicy.class).ifAvailable(settings::setExpiryPolicy);
        beans.getBeanProvider(AuditSink.class).ifAvailable(settings::setAuditSink);
        beans.getBeanProvider(IncidentSink.class).ifAvailable(settings::setIncidentSink);
        Map<String, WorkCalendar> calendars = beans.getBeansOfType(WorkCalendar.class);
        calendars.forEach((name, calendar) -> settings.getWorkCalendars().putIfAbsent(name, calendar));
    }

    private List<ProcessEnginePlugin> order(List<ProcessEnginePlugin> current, ProcessEnginePlugin sangam,
                                            String databaseType) {
        List<ProcessEnginePlugin> starter = new ArrayList<>();
        Map<String, List<ProcessEnginePlugin>> ordered = new java.util.LinkedHashMap<>();
        ORDER.forEach(n -> ordered.put(n, new ArrayList<>()));
        List<ProcessEnginePlugin> others = new ArrayList<>();
        Map<ProcessEnginePlugin, Boolean> seen = new IdentityHashMap<>();
        for (ProcessEnginePlugin p : current == null ? List.<ProcessEnginePlugin>of() : current) {
            if (p instanceof CompositeProcessEnginePlugin composite) {
                for (ProcessEnginePlugin inner : composite.getPlugins()) {
                    classify(inner, sangam, starter, ordered, others, seen, true);
                }
            } else {
                classify(p, sangam, starter, ordered, others, seen, false);
            }
        }
        // Plugin beans that the starter did not collect (or that are created lazily) are added by type.
        for (String className : ORDER) {
            for (ProcessEnginePlugin p : beansOfType(className)) {
                if (seen.put(p, Boolean.TRUE) == null) {
                    ordered.get(className).add(p);
                }
            }
        }
        List<ProcessEnginePlugin> result = new ArrayList<>();
        if (!starter.isEmpty()) {
            result.add(new CompositeProcessEnginePlugin(starter));
        }
        result.add(sangam);
        ordered.values().forEach(result::addAll);
        result.addAll(others);
        result.add(new EngineSettingsReapplyPlugin(databaseType));
        return result;
    }

    private void classify(ProcessEnginePlugin p, ProcessEnginePlugin sangam, List<ProcessEnginePlugin> starter,
                          Map<String, List<ProcessEnginePlugin>> ordered, List<ProcessEnginePlugin> others,
                          Map<ProcessEnginePlugin, Boolean> seen, boolean fromStarterComposite) {
        if (p == sangam || p instanceof SangamProcessEnginePlugin || seen.put(p, Boolean.TRUE) != null) {
            return;
        }
        String name = p.getClass().getName();
        if (ordered.containsKey(name)) {
            ordered.get(name).add(p);
        } else if (fromStarterComposite && name.startsWith("org.operaton.bpm.spring.boot.starter")) {
            starter.add(p);
        } else {
            others.add(p);
        }
    }

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

    /** Last plugin: re-pins the database type detected by {@code DatabaseGuard} (FLOWABLE-INTO-OPERATON D-05: never inferred from the driver). */
    static final class EngineSettingsReapplyPlugin extends org.operaton.bpm.engine.impl.cfg.AbstractProcessEnginePlugin {
        private final String databaseType;

        EngineSettingsReapplyPlugin(String databaseType) {
            this.databaseType = databaseType;
        }

        @Override
        public void preInit(ProcessEngineConfigurationImpl cfg) {
            if (databaseType != null) {
                cfg.setDatabaseType(databaseType);
            }
            cfg.setAuthorizationEnabled(false); // VI.4: product authorization, Operaton's stays off
        }

        @Override
        public String toString() {
            return "SangamEngineSettingsReapply[" + databaseType + "]";
        }
    }
}
