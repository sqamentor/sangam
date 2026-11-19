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
import org.sangam.packs.spi.ProfileTableDeployer;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.support.TxSupport;
import org.sangam.security.SangamSecurity;
import org.sangam.server.bridge.PacksAuditSink;
import org.sangam.server.bridge.PacksSeatDirectory;
import org.sangam.server.bridge.PacksTenantDeployer;
import org.sangam.server.bridge.ProfileTenantTables;
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

    /**
     * SL-116: deploy and rollback of one pack in one tenant scope are serialised by a row lock on its
     * {@code EVT_DEFINITION_SEQ} mark (kind {@code pack}) in the Spring transaction the engine joins, so the lock and
     * the deployment commit together. The wait for a held lock is bounded by {@code sangam.packs.operation.lock-timeout}
     * (default 10 s; Oracle: the product layer's {@code FOR UPDATE WAIT 2}); a timeout is 409
     * {@code PACK_OPERATION_IN_PROGRESS}.
     */
    @Bean
    public org.sangam.packs.spi.PackOperationLock sangamPackOperationLock(TxSupport tx, Environment env) {
        Duration wait = env.getProperty("sangam.packs.operation.lock-timeout", Duration.class, Duration.ofSeconds(10));
        return org.sangam.server.bridge.PackOperationTxLock.of(tx, wait);
    }

    /** The one deployer of the server (onboarding, tenant provisioning, the pack lifecycle API), with the SL-116 lock. */
    @Bean
    public PackDeployer sangamPackDeployer(ProcessEngine engine, PackRegistry registry,
            org.sangam.packs.spi.PackOperationLock operationLock) {
        return new PackDeployer(engine, registry, operationLock);
    }

    /**
     * With sangam-config: each tenant's own delegation tables from its profile (DEC-225-4, {@code profiles/} on the
     * class path), deployed with its packs and re-deployed for every tenant after a profile change (activation,
     * retirement, import). Without a profile resolver the packs alone.
     */
    @Bean
    public PacksTenantDeployer sangamPacksTenantDeployer(PackDeployer deployer, PackRegistry registry, AuditPort audit,
            ProcessEngine engine, ObjectProvider<org.sangam.config.profile.ProfileResolver> profiles,
            ObjectProvider<org.sangam.core.time.BusinessClock> businessClocks,
            ObjectProvider<org.sangam.config.admin.ConfigAdminService> configAdmin) {
        PacksTenantDeployer d = new PacksTenantDeployer(deployer, registry, audit);
        org.sangam.config.profile.ProfileResolver resolver = profiles.getIfAvailable();
        if (resolver != null) {
            d.useProfileTables(new ProfileTableDeployer(engine, ProfileTableDeployer.classpath(
                    PacksWiring.class.getClassLoader(), "profiles/")),
                    new ProfileTenantTables(resolver, ConfigWiring.businessClock(businessClocks)));
            configAdmin.ifAvailable(a -> a.onActiveChange(changes -> syncProfileTables(d)));
        } else {
            LOG.warn("No profile resolver: tenants are deployed without their own delegation tables (DEC-225-4)");
        }
        return d;
    }

    /** Re-deploys every tenant's own tables; one tenant's refusal (audited by the deployer) never stops the others. */
    static void syncProfileTables(PacksTenantDeployer d) {
        for (String tenant : d.tenantsToSync()) {
            try {
                d.syncTables(tenant);
            } catch (RuntimeException e) {
                LOG.error("Profile tables of tenant {} not re-deployed after a profile change: {}", tenant, e.toString());
            }
        }
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
            ObjectProvider<org.sangam.core.engine.SangamClock> sangamClock,
            ObjectProvider<org.sangam.core.time.BusinessClock> businessClocks,
            ObjectProvider<org.sangam.config.profile.ProfileResolver> profiles,
            ObjectProvider<org.sangam.integration.dsc.DscSigningFlow> dscFlows,
            ObjectProvider<ProcessEngine> engines, PackRegistry registry) {
        return () -> {
            Dialect dialect = tx.dialect();
            // Seat validity days are business dates in each tenant's profile zone; sangam.packs.zone, if set,
            // forces one zone for every tenant (legacy override).
            String forced = env.getProperty("sangam.packs.zone");
            org.sangam.core.time.BusinessClock seatClock = forced != null && !forced.isBlank()
                    ? org.sangam.core.time.BusinessClock.fixedZone(ConfigWiring.engineClock(), ZoneId.of(forced))
                    : org.sangam.core.time.BusinessClock.of(ConfigWiring.engineClock(),
                            t -> ConfigWiring.businessClock(businessClocks).zone(t));
            if (env.getProperty("sangam.persistence.dev-schema", Boolean.class, false)) {
                createPackOperationTable(tx, dialect);
            }
            PackRuntime.Ports ports = PackRuntime.ports()
                    .withAudit(new PacksAuditSink(new AuditChain(dialect), tx)) // RM-49: no default tenant
                    .withClock(new EngineClock(ZoneOffset.UTC));
            SangamSecurity sec = security.getIfAvailable();
            if (sec != null) {
                ports = ports.withSeats(new PacksSeatDirectory(sec, tx, seatClock));
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
            org.sangam.config.profile.ProfileResolver pr = profiles.getIfAvailable();
            String calendarName = env.getProperty("sangam.packs.calendar", "government");
            if (pr != null) {
                // SL-34: pack SLAs count the tenant's own working days (profile calendar: zone, weekend, holidays)
                ports = ports.withCalendar(packCalendar(pr, businessClocks, calendarName));
                // owner decision M1: a money-out step takes the tenant / jurisdiction profile's currency first
                org.sangam.packs.port.TenantCurrencies.install(tenant -> {
                    java.util.Currency c = pr.resolve(tenant, ConfigWiring.businessClock(businessClocks).today(tenant))
                            .locale().money().currency();
                    return c == null ? null : c.getCurrencyCode();
                });
            } else {
                LOG.warn("No ProfileResolver bean: pack SLAs use the default working-day calendar (no tenant holidays)");
            }
            // decisions 236 and 237: the DSC completion gate asks the server's DSC flow; refuse-all without it
            org.sangam.integration.dsc.DscSigningFlow dsc = dscFlows.getIfAvailable();
            ports = ports.withSignatures(signatures(dsc));
            // decision 385 (2), layer 1: the gate binds a slot only to a document the document route bound to the case
            ports = withDocumentRoute(ports, engines.getIfAvailable(), tx);
            // decision 385 (2), layer 2: every caller write path refuses the slot names the installed packs declare
            // (decision 387 (2): the installer is plain Java in sangam-assembly, for any host)
            org.sangam.assembly.packs.PackDocumentSlotNames.install(registry);
            // decision 292: in production every pack rule row not permitted for PRODUCTION refuses
            ports = withProductionMode(ports, env);
            // owner option (b) 2026-11-19: demo sandbox tenants (payee + payment simulators for them only)
            installDemoSandbox(env);
            PackRuntime.install(ports);
            LOG.info("PackRuntime DSC signature check: {}", dsc != null ? "server DSC flow" : "refuse all (DSC off)");
            LOG.info("PackRuntime DSC slot binding: {}", ports.slotBindings() != org.sangam.packs.port.DocumentSlotBindings.NONE
                    ? "document route audit" : "none (every slot is empty)");
            LOG.info("PackRuntime production mode: {}", ports.production()
                    ? "on (rule rows not permitted for PRODUCTION refuse)" : "off (demonstration rows usable)");
            LOG.info("PackRuntime installed: audit chain, seats={}, ledgers={}, calendar={}",
                    sec != null ? "sangam-security" : "in-memory", clock != null ? "JDBC" : "in-memory",
                    pr != null ? "profile calendar '" + calendarName + "'" : "default working days");
        };
    }

    /**
     * Owner decision 292: the packs' production mode is the server's fail-closed production predicate of decision 143
     * ({@link ProductionEnvironment#isProduction}), so a missing, invalid or contradictory
     * {@code sangam.security.production} counts as production. In production, sangam-packs refuses every rule row whose
     * permittedUse is not PRODUCTION ({@code <PREFIX>_NOT_PERMITTED_IN_PRODUCTION}); outside it, demonstration rows stay
     * usable.
     */
    /**
     * Owner option (b) 2026-11-19: {@code GET /me} says {@code sandbox=true} for a demo sandbox tenant (the UI's
     * "Sandbox" badge), read from the same {@link org.sangam.packs.sim.DemoSandbox} set the packs use.
     */
    @Bean
    public InitializingBean sangamDemoSandboxMe(
            ObjectProvider<org.sangam.rest.port.WorkspacePort> workspace) {
        return () -> {
            if (workspace.getIfAvailable() instanceof org.sangam.rest.adapter.operaton.OperatonWorkspaceAdapter w) {
                w.setSandboxTenants(org.sangam.packs.sim.DemoSandbox::isSandbox);
            }
        };
    }

    /** {@value #SANDBOX_TENANTS_PROPERTY}: tenant ids, comma-separated; empty (the default) switches it off. */
    public static final String SANDBOX_TENANTS_PROPERTY = "sangam.demo.sandbox-tenants";

    /**
     * Owner option (b) 2026-11-19: installs {@code sangam.demo.sandbox-tenants} into the packs
     * ({@link org.sangam.packs.sim.DemoSandbox}). Allowed in production; it relaxes no production guard. A non-empty
     * list logs a WARN naming every sandbox tenant at each start.
     */
    static java.util.Set<String> installDemoSandbox(Environment env) {
        String[] raw = env.getProperty(SANDBOX_TENANTS_PROPERTY, String[].class, new String[0]);
        java.util.List<String> ids = new java.util.ArrayList<>();
        for (String r : raw) {
            if (r != null && !r.isBlank()) {
                ids.add(r.trim());
            }
        }
        org.sangam.packs.sim.DemoSandbox.install(ids);
        java.util.Set<String> t = org.sangam.packs.sim.DemoSandbox.tenants();
        if (!t.isEmpty()) {
            LOG.warn("DEMO SANDBOX tenants {} ({}): their verified payees and payments ({}) are SIMULATED and audited "
                    + "sandbox=true; production mode {}; every other tenant is unchanged", t, SANDBOX_TENANTS_PROPERTY,
                    org.sangam.packs.sim.DemoSandbox.PAYMENT_CONNECTORS.stream().sorted().toList(),
                    ProductionEnvironment.isProduction(env) ? "stays on" : "is off");
        }
        return t;
    }

    static PackRuntime.Ports withProductionMode(PackRuntime.Ports ports, Environment env) {
        return ports.withProduction(ProductionEnvironment.isProduction(env));
    }

    /**
     * Owner decision 385 (2), layer 1: the DSC completion gate asks whether the document route
     * ({@link org.sangam.server.bridge.OperatonDocumentSlots}) bound the slot's document to this case, read from the
     * route's own audit events ({@link org.sangam.assembly.packs.AuditDocumentSlotBindings}, plain Java in
     * sangam-assembly, decision 387 (2)); a gate that signs consumes the binding on the same audit chain (decision 390). Without an engine there is
     * no route, so nothing is bound and every slot is empty (fail closed).
     */
    /** The connection of the Operaton command on this thread, the seam {@code AuditDocumentSlotBindings} writes on. */
    public static java.util.Optional<java.sql.Connection> engineCommand() {
        return org.sangam.ports.support.EngineTx.inCommand()
                ? java.util.Optional.of(org.sangam.ports.support.EngineTx.connection()) : java.util.Optional.empty();
    }

    static PackRuntime.Ports withDocumentRoute(PackRuntime.Ports ports, ProcessEngine engine, TxSupport tx) {
        return ports.withSlotBindings(engine == null ? org.sangam.packs.port.DocumentSlotBindings.NONE
                : new org.sangam.assembly.packs.AuditDocumentSlotBindings(tx::withConnection,
                        PacksWiring::engineCommand, new AuditChain(tx.dialect())));
    }

    /**
     * Decisions 236 and 237: the packs' signature port over the server's DSC flow, or
     * {@link org.sangam.packs.port.SignatureVerifications#DENY} when there is none ({@code sangam.dsc.enabled} off;
     * with it on, start-up already fails without sangam-esign).
     */
    public static org.sangam.packs.port.SignatureVerifications signatures(org.sangam.integration.dsc.DscSigningFlow flow) {
        return flow == null ? org.sangam.packs.port.SignatureVerifications.DENY
                : new org.sangam.server.bridge.DscSignatureVerificationsAdapter(flow);
    }

    /** The packs' business calendar over the tenant profile calendar {@code calendarName} in force today. */
    static org.sangam.packs.port.BusinessCalendar packCalendar(org.sangam.config.profile.ProfileResolver profiles,
            ObjectProvider<org.sangam.core.time.BusinessClock> clocks, String calendarName) {
        // SL-104: a tenant without a profile calendar falls back to its business zone (BusinessClock: UTC, logged once)
        return new org.sangam.packs.calendar.WorkingDayCalendar(
                new org.sangam.server.bridge.ProfileWorkingDays(tenant -> profiles.resolve(tenant,
                        ConfigWiring.businessClock(clocks).today(tenant)).locale().calendar(calendarName)));
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

    /** {@link Clock} over the engine clock (one clock per JVM, FLOWABLE-INTO-OPERATON D-10). */
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
