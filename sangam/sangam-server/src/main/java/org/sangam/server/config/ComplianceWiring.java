// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import javax.sql.DataSource;

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
import org.sangam.rest.port.Page;
import org.sangam.rest.port.RequestContext;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Wires sangam-compliance (its README "Integration notes" §2–§6):
 * <ul>
 *   <li>{@link SangamCompliance} on the engine transaction ({@code Transactions.of(engineAdapter)}; Spring-joined
 *       transactions when sangam-engine is disabled) with {@link ComplianceConfigProvider} (jurisdiction packs from
 *       {@code sangam.compliance.jurisdictions}, no default), then {@code ComplianceRuntime.install(...)};</li>
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

    @Bean
    public SangamCompliance sangamCompliance(TxSupport tx, ObjectProvider<EngineAdapter> engineAdapter,
            ComplianceConfigProvider config, ProcessEngine engine, ObjectProvider<ErasureService> erasure,
            ObjectProvider<OutboxWriter> outbox, ObjectProvider<ConnectorInvoker> invoker,
            ObjectProvider<PublisherRegistry> publishers, Environment env) {
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
        SangamCompliance compliance = new SangamCompliance(tx.dialect(), ctx, config, ports);
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
            RestProperties rest, Environment env, ObjectProvider<AdminPort> admin, ComplianceConfigProvider provider) {
        LeaderElection leader = new LeaderElection(tx.dialect(), Transactions.of(ds), rest.getNode(),
                Duration.ofMinutes(3));
        return new ComplianceScheduler(compliance, leader, () -> tenants(env, admin).stream().filter(t -> provider.effectiveJurisdiction(t) != null).toList(),
                env.getProperty("sangam.compliance.schedule.retention-batch", Integer.class, 500),
                env.getProperty("sangam.compliance.schedule.integrity-max-chains", Integer.class, 10_000));
    }

    /** Active tenants from the tenant directory (AdminPort, as the system principal), plus {@code sangam.compliance.tenants}. */
    static List<String> tenants(Environment env, ObjectProvider<AdminPort> admin) {
        Set<String> out = new LinkedHashSet<>(Binder.get(env)
                .bind("sangam.compliance.tenants", Bindable.listOf(String.class)).orElse(List.of()));
        AdminPort port = admin.getIfAvailable();
        if (port != null) {
            try {
                String home = env.getProperty("sangam.tenant.default", "dev");
                RequestContext sys = new RequestContext(home, "system:compliance",
                        Set.of("sangam-system", "sangam-admin"), null, null, null, null, null, "SYSTEM");
                Page<AdminPort.Tenant> page = port.tenants(sys, new AdminPort.AdminFilter(null, null, null, null, null,
                        null, null, false), Page.PageRequest.of(0, 500));
                page.items().stream().filter(t -> !"SUSPENDED".equals(t.status())).map(AdminPort.Tenant::id)
                        .forEach(out::add);
            } catch (RuntimeException e) {
                LOG.debug("tenant directory unavailable for the compliance schedule: {}", e.toString());
            }
        }
        out.removeIf(t -> t == null || t.isBlank());
        return new ArrayList<>(out);
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
