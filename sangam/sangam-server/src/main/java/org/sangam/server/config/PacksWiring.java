// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.operaton.bpm.engine.ProcessEngine;
import org.sangam.core.api.EngineAdapter;
import org.sangam.core.engine.SangamClockHolder;
import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.ReconciliationQueue;
import org.sangam.core.layer.Transactions;
import org.sangam.integration.connector.ConnectorCallJournal;
import org.sangam.integration.connector.ConnectorInvoker;
import org.sangam.integration.connector.ConnectorRegistry;
import org.sangam.integration.inbox.InboxService;
import org.sangam.integration.ledger.OperationLedgerService;
import org.sangam.integration.packs.IntegrationConnectorGateway;
import org.sangam.integration.packs.PackConnectorBindings;
import org.sangam.integration.packs.PackDispatchPublisher;
import org.sangam.integration.packs.PackMessages;
import org.sangam.integration.packs.PackOperationStore;
import org.sangam.integration.packs.PackReconciliationHooks;
import org.sangam.integration.packs.PacksIntegration;
import org.sangam.integration.reconciliation.ReconciliationEngine;
import org.sangam.integration.relay.BackpressureGate;
import org.sangam.integration.relay.OutboxWriter;
import org.sangam.integration.relay.PublisherRegistry;
import org.sangam.packs.port.ConnectorGateway;
import org.sangam.packs.port.PackRuntime;
import org.sangam.packs.sim.SimulatorConnectorGateway;
import org.sangam.packs.spi.PackDeployer;
import org.sangam.packs.spi.PackRegistry;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.support.TxSupport;
import org.sangam.security.SangamSecurity;
import org.sangam.server.bridge.PacksAuditSink;
import org.sangam.server.bridge.PacksSeatDirectory;
import org.sangam.server.bridge.PacksTenantDeployer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Wires sangam-packs (WS-PACKS README §2 and §8):
 * <ul>
 *   <li>{@link PackRuntime#install}: seats → sangam-security's posting/seat model ({@link PacksSeatDirectory}),
 *       audit → core audit chain in the engine command ({@link PacksAuditSink}), clock → the engine clock
 *       ({@code SangamClockHolder}); then the connector gateway → sangam-integration
 *       ({@link IntegrationConnectorGateway}, outbox in the caller's transaction, {@code sangam.operationResult}
 *       correlation) through {@link PacksIntegration#wire}. Ports whose production module is absent keep the packs'
 *       simulator / in-memory defaults (logged).</li>
 *   <li>{@link PackRegistry#discover()} + {@link PackDeployer}, and the {@link PacksTenantDeployer} hook; packs are
 *       deployed at start-up for {@code sangam.packs.tenants} when {@code sangam.packs.deploy-on-start} is true
 *       (default: true on the h2 profile, false elsewhere).</li>
 * </ul>
 * Disable with {@code sangam.modules.packs.enabled=false}.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "sangam.modules.packs.enabled", havingValue = "true", matchIfMissing = true)
public class PacksWiring {

    private static final Logger LOG = LoggerFactory.getLogger(PacksWiring.class);

    @Bean
    public PackRegistry sangamPackRegistry(ObjectProvider<org.sangam.server.plugins.PluginManager> plugins) {
        PackRegistry r = PackRegistry.discover();
        plugins.ifAvailable(m -> m.packs().forEach(r::add));
        LOG.info("Discovered {} packs", r.all().size());
        return r;
    }

    @Bean
    public PackDeployer sangamPackDeployer(ProcessEngine engine, PackRegistry registry) {
        return new PackDeployer(engine, registry);
    }

    @Bean
    public PacksTenantDeployer sangamPacksTenantDeployer(PackDeployer deployer, PackRegistry registry, AuditPort audit) {
        return new PacksTenantDeployer(deployer, registry, audit);
    }

    /**
     * Installs the pack ports owned outside sangam-integration: audit (core chain in the engine command), seats
     * (sangam-security), clock (engine clock), ledgers and batch ledger (sangam-engine {@code JdbcLedgerStore} /
     * {@code JdbcBatchLedger}, engine README note 15). The connector gateway belongs to sangam-integration:
     * {@code IntegrationWiring#sangamPacksIntegration} calls {@code PacksIntegration.wire}, which installs it. Both sides
     * start from the current {@link PackRuntime#ports()} and replace only their own ports, so the result is the same in
     * either initialisation order.
     */
    @Bean
    public InitializingBean sangamPackRuntimeInstaller(TxSupport tx, Environment env,
            ObjectProvider<SangamSecurity> security,
            ObjectProvider<org.sangam.core.engine.SangamClock> sangamClock) {
        return () -> {
            Dialect dialect = tx.dialect();
            String defaultTenant = env.getProperty("sangam.tenant.default",
                    env.getProperty("sangam.security.dev-token.tenant", "dev"));
            ZoneId zone = ZoneId.of(env.getProperty("sangam.packs.zone", "Asia/Kolkata"));
            if (env.getProperty("sangam.persistence.dev-schema", Boolean.class, false)) {
                createPackOperationTable(tx, dialect);
            }
            PackRuntime.Ports ports = PackRuntime.ports()
                    .withAudit(new PacksAuditSink(new AuditChain(dialect), tx, defaultTenant))
                    .withClock(new EngineClock(ZoneOffset.UTC));
            SangamSecurity sec = security.getIfAvailable();
            if (sec != null) {
                ports = ports.withSeats(new PacksSeatDirectory(sec, tx, zone));
            } else {
                LOG.warn("No SangamSecurity bean: packs use the in-memory seat directory");
            }
            org.sangam.core.engine.SangamClock clock = sangamClock.getIfAvailable();
            if (clock != null) {
                Duration lockWait = env.getProperty("sangam.packs.ledger.lock-timeout", Duration.class,
                        Duration.ofSeconds(5));
                ports = ports.withLedgers(new org.sangam.engine.packs.JdbcLedgerStore(tx.dataSource(), clock, lockWait))
                        .withBatches(new org.sangam.engine.packs.JdbcBatchLedger(tx.dataSource(), clock));
            } else {
                LOG.warn("No SangamClock bean (sangam-engine disabled): packs keep the in-memory ledger and batch ledger");
            }
            PackRuntime.install(ports);
            LOG.info("PackRuntime installed: audit chain, seats={}, ledgers={}, calendar=packs WorkingDayCalendar",
                    sec != null ? "sangam-security" : "in-memory", clock != null ? "JDBC" : "in-memory");
        };
    }

    /**
     * Per-tenant pack provisioning through the REST API: after {@code AdminPort.createTenant} /
     * {@code AdminPort.updateTenant} (status ACTIVE), and before {@code PackagePort.transition} with action
     * {@code deploy}, the packs are ensured for that tenant ({@link PacksTenantDeployer#ensureDeployed}). Implemented as
     * a decorator around whichever AdminPort / PackagePort bean is active (sangam-rest default or a module bridge).
     */
    @Bean
    public static org.springframework.beans.factory.config.BeanPostProcessor sangamPacksProvisioningDecorator(
            ObjectProvider<PacksTenantDeployer> deployer) {
        return new org.springframework.beans.factory.config.BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof org.sangam.rest.port.AdminPort || bean instanceof org.sangam.rest.port.PackagePort) {
                    return provisioning(bean, deployer);
                }
                return bean;
            }
        };
    }

    private static Object provisioning(Object target, ObjectProvider<PacksTenantDeployer> deployer) {
        Class<?> port = target instanceof org.sangam.rest.port.AdminPort ? org.sangam.rest.port.AdminPort.class
                : org.sangam.rest.port.PackagePort.class;
        return java.lang.reflect.Proxy.newProxyInstance(port.getClassLoader(), new Class<?>[]{port}, (proxy, m, args) -> {
            String name = m.getName();
            if (port == org.sangam.rest.port.PackagePort.class && name.equals("transition") && args != null
                    && args[2] instanceof org.sangam.rest.port.PackagePort.TransitionRequest t
                    && "deploy".equalsIgnoreCase(t.action())) {
                ensure(deployer, ((org.sangam.rest.port.RequestContext) args[0]).tenantId());
            }
            Object result;
            try {
                result = m.invoke(target, args);
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getCause();
            }
            if (port == org.sangam.rest.port.AdminPort.class && result instanceof org.sangam.rest.port.AdminPort.Tenant t
                    && (name.equals("createTenant") || name.equals("updateTenant")) && "ACTIVE".equals(t.status())) {
                ensure(deployer, t.id());
            }
            return result;
        });
    }

    private static void ensure(ObjectProvider<PacksTenantDeployer> deployer, String tenant) {
        PacksTenantDeployer d = deployer.getIfAvailable();
        if (d != null && tenant != null) {
            d.ensureDeployed(tenant);
        }
    }

    /** Start-up deployment of the packs for the configured tenants (dev: on by default). */
    @Bean
    public ApplicationRunner sangamPacksDeployOnStart(PacksTenantDeployer deployer, Environment env) {
        return args -> {
            boolean h2 = Arrays.asList(env.getActiveProfiles()).contains("h2");
            if (!env.getProperty("sangam.packs.deploy-on-start", Boolean.class, h2)) {
                return;
            }
            List<String> tenants = new ArrayList<>();
            for (String t : env.getProperty("sangam.packs.tenants",
                    env.getProperty("sangam.security.dev-token.tenant", "dev")).split(",")) {
                if (!t.isBlank()) {
                    tenants.add(t.trim());
                }
            }
            for (String t : tenants) {
                try {
                    deployer.deploy(t);
                } catch (RuntimeException e) {
                    LOG.error("Pack deployment for tenant {} failed: {}", t, e.toString());
                }
            }
        };
    }

    /** Development only: PACK_OPERATION from the reference DDL (production: sangam-persistence Liquibase). */
    private static void createPackOperationTable(TxSupport tx, Dialect dialect) {
        tx.withConnection(c -> {
            try {
                try (var rs = c.getMetaData().getTables(null, null, "PACK_OPERATION", new String[]{"TABLE"})) {
                    if (rs.next()) {
                        return null;
                    }
                }
                try (var rs = c.getMetaData().getTables(null, null, "pack_operation", new String[]{"TABLE"})) {
                    if (rs.next()) {
                        return null;
                    }
                }
                try (var st = c.createStatement()) {
                    for (String sql : PackOperationStore.DDL) {
                        String s = dialect.expand(sql);
                        st.execute(s.startsWith("CREATE TABLE") ? s + dialect.createTableSuffix() : s);
                    }
                }
                LOG.info("Created the PACK_OPERATION development table");
                return null;
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException("cannot create PACK_OPERATION", e);
            }
        });
    }

    /** {@link Clock} over the engine clock (one clock per JVM, D-10). */
    static final class EngineClock extends Clock {
        private final ZoneId zone;

        EngineClock(ZoneId zone) {
            this.zone = zone;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId z) {
            return new EngineClock(z);
        }

        @Override
        public Instant instant() {
            return SangamClockHolder.now();
        }
    }
}
