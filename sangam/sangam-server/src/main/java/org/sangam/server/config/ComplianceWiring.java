// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.Function;

import javax.sql.DataSource;

import io.micrometer.core.instrument.MeterRegistry;
import org.operaton.bpm.engine.ProcessEngine;
import org.sangam.compliance.SangamCompliance;
import org.sangam.compliance.adapters.IntegrationErasureAdapter;
import org.sangam.compliance.adapters.IntegrationLegalHoldAdapter;
import org.sangam.compliance.bpmn.ComplianceRuntime;
import org.sangam.compliance.jurisdiction.JurisdictionRegistry;
import org.sangam.compliance.ports.CompliancePorts;
import org.sangam.core.api.EngineAdapter;
import org.sangam.core.layer.Transactions;
import org.sangam.integration.connector.ConnectorInvoker;
import org.sangam.integration.lifecycle.ErasureService;
import org.sangam.integration.lifecycle.LegalHoldStore;
import org.sangam.integration.relay.LeaderElection;
import org.sangam.integration.relay.OutboxWriter;
import org.sangam.integration.relay.PublisherRegistry;
import org.sangam.rest.port.AdminPort;
import org.sangam.rest.support.RestProperties;
import org.sangam.rest.support.TxSupport;
import org.sangam.server.bridge.ComplianceAuthorityGateway;
import org.sangam.server.bridge.ComplianceConfigProvider;
import org.sangam.server.bridge.ComplianceConnectors;
import org.sangam.server.bridge.ComplianceLogArchiveStore;
import org.sangam.server.bridge.ComplianceNotifier;
import org.sangam.server.bridge.CompliancePurposePlugin;
import org.sangam.server.bridge.ComplianceScheduler;
import org.sangam.server.bridge.ComplianceWorkflowStarter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Wires sangam-compliance (its README "Integration notes" §2–§6):
 * <ul>
 *   <li>{@link SangamCompliance} on the engine transaction ({@code Transactions.of(engineAdapter)}; Spring-joined
 *       transactions when sangam-engine is disabled) with {@link ComplianceConfigProvider} (jurisdiction packs from
 *       {@code sangam.compliance.jurisdictions}, no default), then {@code ComplianceRuntime.install(...)};</li>
 *   <li>M-009: denial audits (purpose binding, child safeguards) commit on their own through the leaf pool
 *       {@code sangam-compliance-audit} ({@link ComplianceAuditPool}), so they survive the refused operation's
 *       rollback without taking a second connection from the pool the caller holds (SL-43);</li>
 *   <li>ports: legal holds → integration {@code LegalHoldStore}; erasure → integration {@code ErasureService};
 *       principal messages → outbox + DLT SMS / e-mail connectors ({@link ComplianceNotifier}); supervisory and incident
 *       authorities → e-mail / HTTP connectors; workflow starts → the engine; log archive → a home-country filesystem
 *       store. The other ports stay at the module's "not configured" defaults (reported as gaps by the evidence report);</li>
 *   <li>{@link ComplianceScheduler}: leader-elected schedule ({@code sangam.compliance.schedule.enabled});</li>
 *   <li>{@link CompliancePurposePlugin}: purpose binding, national-id guard and retention tracking at process start/end.</li>
 * </ul>
 * The {@code ERASE_DATA} grant for {@code system:retention} is in {@code IntegrationWiring#sangamLifecycleAuthorizer}.
 * Disable with {@code sangam.modules.compliance.enabled=false}.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "sangam.modules.compliance.enabled", havingValue = "true", matchIfMissing = true)
public class ComplianceWiring {

    private static final Logger LOG = LoggerFactory.getLogger(ComplianceWiring.class);

    @Bean
    public ComplianceConfigProvider sangamComplianceConfig(Environment env,
            ObjectProvider<org.sangam.server.plugins.PluginManager> plugins) {
        JurisdictionRegistry registry = JurisdictionRegistry.discover();
        plugins.ifAvailable(m -> m.jurisdictions().forEach(registry::add));
        LOG.info("Compliance jurisdiction packs installed: {}", registry.all().size());
        return new ComplianceConfigProvider(registry, env);
    }

    /** One source of go-live evidence: effective jurisdictions and pack items (plugin packs included). */
    @Bean
    public org.springframework.beans.factory.InitializingBean sangamComplianceEvidence(ComplianceConfigProvider provider,
            ObjectProvider<org.sangam.security.SangamSecurity> security) {
        return () -> security.ifAvailable(s -> s.tenants().useJurisdictions(
                code -> provider.registry().find(code).map(org.sangam.compliance.jurisdiction.JurisdictionPack::goLiveEvidence),
                tenant -> {
                    String code = provider.effectiveJurisdiction(tenant);
                    return code == null ? java.util.List.<String>of() : java.util.List.of(code);
                }));
    }

    /**
     * M-009 / SL-43: the leaf pool for denial audits. A denial is recorded while the caller holds a connection of the
     * main pool (engine command, REST transaction) and must commit on its own, because the refusal rolls the caller
     * back. A second connection from the main pool there is the SL-43 pool-locking deadlock once every slot is held by a
     * caller waiting for its second one. A connection of this pool is never held while another connection is
     * requested (the only work on it is one {@code AuditChain.append} on a denial-only chain), so waits for it cannot
     * form a cycle. Same database and settings as the main pool; {@code sangam.compliance.denial-audit.pool.maximum-size}
     * (default 2) connections.
     */
    static final class ComplianceAuditPool implements DisposableBean {
        final HikariDataSource pool;

        ComplianceAuditPool(HikariDataSource pool) {
            this.pool = pool;
        }

        @Override
        public void destroy() {
            pool.close();
        }
    }

    public static final String AUDIT_POOL_NAME = "sangam-compliance-audit";

    @Bean
    public ComplianceAuditPool sangamComplianceAuditPool(DataSource ds, Environment env) {
        return new ComplianceAuditPool(leafPool(ds,
                env.getProperty("sangam.compliance.denial-audit.pool.maximum-size", Integer.class, 2)));
    }

    /** A pool on the main pool's database and settings, separate from it (never the pool the callers hold). */
    static HikariDataSource leafPool(DataSource ds, int maximumSize) {
        HikariConfig cfg = new HikariConfig();
        try {
            if (!ds.isWrapperFor(HikariDataSource.class)) {
                throw new IllegalStateException("M-009 compliance denial audits need a HikariCP main pool to derive their"
                        + " own pool from (found " + ds.getClass().getName() + "); or set"
                        + " sangam.modules.compliance.enabled=false");
            }
            ds.unwrap(HikariDataSource.class).copyStateTo(cfg);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("cannot derive the compliance audit pool from the main DataSource", e);
        }
        cfg.setPoolName(AUDIT_POOL_NAME);
        int size = Math.max(1, maximumSize);
        cfg.setMaximumPoolSize(size);
        cfg.setMinimumIdle(Math.min(1, size));
        cfg.setAutoCommit(true);
        return new HikariDataSource(cfg);
    }

    @Bean
    public SangamCompliance sangamCompliance(TxSupport tx, ObjectProvider<EngineAdapter> engineAdapter,
            ComplianceConfigProvider config, ProcessEngine engine, ObjectProvider<ErasureService> erasure,
            ObjectProvider<OutboxWriter> outbox, ObjectProvider<ConnectorInvoker> invoker,
            ObjectProvider<PublisherRegistry> publishers, Environment env, ComplianceAuditPool auditPool) {
        EngineAdapter adapter = engineAdapter.getIfAvailable();
        Transactions ctx = adapter != null ? Transactions.of(adapter) : joined(tx);

        CompliancePorts.LegalHoldPort holds = new IntegrationLegalHoldAdapter(new LegalHoldStore(tx.dialect()));
        ErasureService erasureService = erasure.getIfAvailable();
        CompliancePorts.ErasurePort erasurePort = erasureService == null ? null : new IntegrationErasureAdapter(erasureService);

        ConnectorInvoker inv = invoker.getIfAvailable();
        ComplianceConnectors connectors = inv == null ? null : new ComplianceConnectors(inv,
                env.getProperty("sangam.compliance.connector-timeout", Duration.class, Duration.ofMinutes(2)));
        ComplianceNotifier notifier = null;
        OutboxWriter writer = outbox.getIfAvailable();
        PublisherRegistry registry = publishers.getIfAvailable();
        if (connectors != null && writer != null && registry != null) {
            notifier = new ComplianceNotifier(writer, connectors,
                    env.getProperty("sangam.compliance.notify.sms-connector", "sms-dlt-sim"),
                    env.getProperty("sangam.compliance.notify.email-connector", "smtp"));
            registry.register(ComplianceNotifier.DESTINATION, notifier.publisher());
        }
        ComplianceAuthorityGateway authorities = connectors == null ? null : new ComplianceAuthorityGateway(connectors, config,
                env.getProperty("sangam.compliance.authority.email-connector", "smtp"),
                env.getProperty("sangam.compliance.authority.http-connector", "rest"));
        ComplianceLogArchiveStore archive = new ComplianceLogArchiveStore(
                Path.of(env.getProperty("sangam.compliance.log-archive.root", "data/compliance-logs")),
                env.getProperty("sangam.compliance.log-archive.country", ""));

        SangamCompliance.Ports ports = new SangamCompliance.Ports(holds, erasurePort, notifier,
                null /* principals: owned by packs / sangam-security person directory */,
                null /* authenticator: OTP / SSO against the published identifier */,
                null /* personalData: owned by the packs */,
                null /* adultVerifier: no DigiLocker identity-verification connector yet */,
                null /* guardianshipVerifier */, null /* processors */,
                new ComplianceWorkflowStarter(engine), authorities, authorities, archive,
                null /* geo */, null /* nationalIdVault */, null /* securityEvidence */, null /* sntp: built-in client */);
        SangamCompliance compliance = new SangamCompliance(tx.dialect(), ctx, config, ports,
                Transactions.of(auditPool.pool));
        ComplianceRuntime.install(compliance);
        LOG.info("sangam-compliance installed (erasure: {}, notifier: {}, authorities: {})", erasurePort != null,
                notifier != null, authorities != null);
        return compliance;
    }

    /** Engine hooks: appended after the ports plugin by {@code EngineConfigurationCustomizer}. */
    @Bean
    public CompliancePurposePlugin sangamCompliancePurposePlugin(ObjectProvider<SangamCompliance> compliance) {
        return new CompliancePurposePlugin(compliance);
    }

    @Bean
    @ConditionalOnProperty(name = "sangam.compliance.schedule.enabled", havingValue = "true", matchIfMissing = true)
    public ComplianceScheduler sangamComplianceScheduler(SangamCompliance compliance, TxSupport tx, DataSource ds,
            RestProperties rest, Environment env, ObjectProvider<AdminPort> admin, ComplianceConfigProvider provider,
            ObjectProvider<MeterRegistry> meters) {
        LeaderElection leader = new LeaderElection(tx.dialect(), Transactions.of(ds), rest.getNode(),
                Duration.ofMinutes(3));
        ScheduleTenantsMonitor monitor = ScheduleTenantsMonitor.of("compliance", "retention and DPDP erasure,"
                + " rights/breach/incident escalation, log sealing and audit-integrity verification do not run. Tenants come"
                + " from the tenant directory plus sangam.compliance.tenants (SANGAM_COMPLIANCE_TENANTS); only tenants with"
                + " an effective jurisdiction (sangam.compliance.jurisdictions, sangam.compliance.tenant-jurisdictions or"
                + " onboarding) are scheduled.", meters);
        return new ComplianceScheduler(compliance, leader, monitor.observing(
                () -> tenants(env, admin, monitor).stream().filter(t -> provider.effectiveJurisdiction(t) != null).toList()),
                env.getProperty("sangam.compliance.schedule.retention-batch", Integer.class, 500),
                env.getProperty("sangam.compliance.schedule.integrity-max-chains", Integer.class, 10_000));
    }

    /**
     * Active tenants from the tenant directory (AdminPort, as the system principal of the attribution tenant), plus
     * the optional extra tenants {@code sangam.compliance.tenants}. SL-117: one reader ({@link ScheduleTenants}); a
     * directory failure is logged at WARN and retried on the next cycle.
     */
    static List<String> tenants(Environment env, ObjectProvider<AdminPort> admin, ScheduleTenantsMonitor monitor) {
        return ScheduleTenants.resolve(env, admin, "sangam.compliance.tenants", "system:compliance", monitor);
    }

    /** Transactions that join the current Spring transaction (sangam-engine disabled). */
    private static Transactions joined(TxSupport tx) {
        return new Transactions() {
            @Override
            public <T> T inTransaction(Function<java.sql.Connection, T> work) {
                return tx.withConnection(work);
            }
        };
    }
}
