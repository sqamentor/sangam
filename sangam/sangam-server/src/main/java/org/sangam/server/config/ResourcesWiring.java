// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import javax.sql.DataSource;

import io.micrometer.core.instrument.MeterRegistry;

import org.operaton.bpm.engine.ProcessEngine;
import org.sangam.config.profile.ProfileResolver;
import org.sangam.core.api.EngineAdapter;
import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Transactions;
import org.sangam.integration.connector.ConnectorRegistry;
import org.sangam.integration.relay.LeaderElection;
import org.sangam.integration.relay.PublisherRegistry;
import org.sangam.resources.ResourcesContext;
import org.sangam.resources.ResourcesSchema;
import org.sangam.resources.ResourcesSweepers;
import org.sangam.resources.SangamResources;
import org.sangam.resources.bpmn.OperatonApprovalPort;
import org.sangam.resources.bpmn.ResourcesProcesses;
import org.sangam.resources.dialect.ResourcesDialect;
import org.sangam.resources.port.ResourcesOutboxPublisher;
import org.sangam.resources.port.ResourcesPorts;
import org.sangam.resources.rest.QueueRestPort;
import org.sangam.resources.rest.ResourcesRestPort;
import org.sangam.resources.rest.RosterRestPort;
import org.sangam.resources.security.SecurityDutySeatLink;
import org.sangam.resources.security.SecurityLeaveCalendar;
import org.sangam.rest.modules.resources.ResourcesIdentity;
import org.sangam.rest.port.WorkspacePort;
import org.sangam.rest.support.RestProperties;
import org.sangam.rest.support.TxSupport;
import org.sangam.security.SangamSecurity;
import org.sangam.server.bridge.ResourcesPortBridges;
import org.sangam.server.bridge.ResourcesSweepScheduler;
import org.sangam.server.bridge.SecurityIdentities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Wires sangam-resources (resources README "Entry point", "After-commit delivery", "Default approval models",
 * "Scheduled sweepers"): the {@link ResourcesContext} with every port bound, {@link SangamResources} registered for
 * BPMN, the default approval models deployed at start-up, the outbox destinations
 * {@value ResourcesOutboxPublisher#DEST_NOTIFICATION} (unordered) and {@value ResourcesOutboxPublisher#DEST_DISPLAY_FEED}
 * (ordered per service point) routed to {@code ctx.outboxPublisher}, the three REST ports, and the sweepers as a
 * leader-elected job.
 *
 * <p>Bindings: authorization and seat directory → sangam-security (VI.4 predicate + object guard; PostingService);
 * duty-seat link → security postings; leave → security leave records; approvals → Operaton
 * ({@link OperatonApprovalPort}); decisions → engine DMN deployments; business calendars → sangam-config profiles;
 * notifications → in-app notifications; display feed → an outbound event channel; audit → core audit chain; metrics →
 * Micrometer; attendance → a configured connector; billing → a {@code ResourcesPorts.BillingPort} bean when one exists
 * (else free).
 *
 * <p>Properties: {@code sangam.modules.resources.enabled}; {@code sangam.resources.settings.*} (module settings, e.g.
 * {@code bookingApproverSeat}); {@code sangam.resources.display-feed.channel}; {@code sangam.resources.attendance.connector};
 * {@code sangam.resources.schedule.enabled} (default true), {@code .interval} (default PT1M), {@code .tenants},
 * {@code .lease-ttl} (default PT5M), {@code .batch} (default 200); {@code sangam.resources.deploy-default-processes}
 * (default true).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "sangam.modules.resources.enabled", havingValue = "true", matchIfMissing = true)
public class ResourcesWiring {

    private static final Logger LOG = LoggerFactory.getLogger(ResourcesWiring.class);

    @Bean
    public SangamResources sangamResources(TxSupport tx, Environment env, ProcessEngine engine,
            ObjectProvider<SangamSecurity> security, ObjectProvider<ResourcesPorts.BillingPort> billing,
            ObjectProvider<WorkspacePort> workspace, ObjectProvider<ConnectorRegistry> connectors,
            ObjectProvider<MeterRegistry> meters, ObjectProvider<ProfileResolver> profiles) {
        ResourcesDialect dialect = tx.withConnection(ResourcesDialect::detect);
        if (env.getProperty("sangam.persistence.dev-schema", Boolean.class, false)) {
            tx.withConnection(c -> {
                if (!RegistryWiring.exists(c, "RES_RESOURCE")) {
                    try {
                        ResourcesSchema.create(c, dialect);
                    } catch (java.sql.SQLException e) {
                        throw new IllegalStateException("cannot create the sangam-resources development tables", e);
                    }
                    LOG.info("Created the sangam-resources development tables");
                }
                return null;
            });
        }
        Map<String, Object> settings = Binder.get(env).bind("sangam.resources.settings",
                Bindable.mapOf(String.class, Object.class)).orElse(Map.of());
        ResourcesContext.Builder b = ResourcesContext.builder(dialect)
                .decisions(ResourcesPortBridges.decisions(engine.getRepositoryService()))
                .approvals(new OperatonApprovalPort(engine.getRuntimeService()))
                .audit(ResourcesPortBridges.audit(new AuditChain(tx.dialect())))
                .displayFeed(ResourcesPortBridges.displayFeed(tx, env.getProperty("sangam.resources.display-feed.channel")))
                .settings(settings);
        SangamSecurity sec = security.getIfAvailable();
        if (sec != null) {
            b.authorization(sec.authorization())
                    .seats(sec.postings())
                    .seatLink(new SecurityDutySeatLink(sec.postings()))
                    .leave(new SecurityLeaveCalendar());
        } else {
            LOG.warn("sangam-security off: resources run without VI.4 visibility, seat directory or leave calendar");
        }
        ResourcesPorts.BillingPort bp = billing.getIfAvailable();
        if (bp != null) {
            b.billing(bp);
        }
        WorkspacePort ws = workspace.getIfAvailable();
        if (ws != null) {
            b.notifications(ResourcesPortBridges.notifications(ws));
        }
        String attendance = env.getProperty("sangam.resources.attendance.connector");
        ConnectorRegistry conns = connectors.getIfAvailable();
        if (attendance != null && !attendance.isBlank() && conns != null) {
            b.attendance(ResourcesPortBridges.attendance(conns, attendance));
        }
        MeterRegistry m = meters.getIfAvailable();
        if (m != null) {
            b.metrics(ResourcesPortBridges.metrics(m));
        }
        ProfileResolver pr = profiles.getIfAvailable();
        if (pr != null) {
            // The tenant is the configuration scope; calendars come from the resolved profile's locale settings.
            b.businessCalendars((tenant, id) -> {
                try {
                    return Optional.of(pr.resolve(tenant, LocalDate.now()).locale().calendar(id));
                } catch (RuntimeException e) {
                    LOG.debug("no business calendar {} for tenant {}: {}", id, tenant, e.toString());
                    return Optional.empty();
                }
            });
        }
        return new SangamResources(b.build()).registerForBpmn();
    }

    /** {@code sangam.resources.notification} (unordered) and {@code .display-feed} (ordered) → ctx.outboxPublisher. */
    @Bean
    public InitializingBean sangamResourcesOutboxDestinations(SangamResources resources, DataSource ds,
                                                              ObjectProvider<PublisherRegistry> publishers) {
        return () -> {
            PublisherRegistry registry = publishers.getIfAvailable();
            if (registry == null) {
                LOG.warn("sangam-integration off: resources notifications and display feed stay in the outbox");
                return;
            }
            ResourcesOutboxPublisher publisher = resources.context().outboxPublisher(Transactions.of(ds));
            registry.register(ResourcesOutboxPublisher.DEST_NOTIFICATION, publisher);
            registry.register(ResourcesOutboxPublisher.DEST_DISPLAY_FEED, publisher);
        };
    }

    /** Default generic approval models (duplicate filtering: safe on every start and node). */
    @Bean
    public ApplicationRunner sangamResourcesProcesses(ProcessEngine engine, Environment env) {
        return args -> {
            if (env.getProperty("sangam.resources.deploy-default-processes", Boolean.class, true)) {
                ResourcesProcesses.deploy(engine.getRepositoryService());
            }
        };
    }

    // ---------------------------------------------------------------- REST ports (engine transaction)

    @Bean
    public ResourcesRestPort sangamResourcesRestPort(SangamResources resources, TxSupport tx,
                                                     ObjectProvider<EngineAdapter> engine) {
        return resources.rest(engineTx(tx, engine));
    }

    @Bean
    public QueueRestPort sangamQueueRestPort(SangamResources resources, TxSupport tx, ObjectProvider<EngineAdapter> engine) {
        return resources.queueRest(engineTx(tx, engine));
    }

    @Bean
    public RosterRestPort sangamRosterRestPort(SangamResources resources, TxSupport tx, ObjectProvider<EngineAdapter> engine) {
        return resources.rosterRest(engineTx(tx, engine));
    }

    /** The organisation-model identity of the request (seats and roles resolved by sangam-security). */
    @Bean
    public ResourcesIdentity sangamResourcesIdentity() {
        return SecurityIdentities::of;
    }

    // ---------------------------------------------------------------- sweepers (leader-elected)

    @Bean
    @ConditionalOnProperty(name = "sangam.resources.schedule.enabled", havingValue = "true", matchIfMissing = true)
    public ResourcesSweepScheduler sangamResourcesSweepers(SangamResources resources, TxSupport tx, DataSource ds,
                                                           RestProperties rest, Environment env) {
        ResourcesSweepers sweepers = resources.sweepers(Transactions.of(ds), rest.getNode(),
                env.getProperty("sangam.resources.schedule.lease-ttl", Duration.class, Duration.ofMinutes(5)),
                env.getProperty("sangam.resources.schedule.batch", Integer.class, 200));
        LeaderElection leader = new LeaderElection(tx.dialect(), Transactions.of(ds), rest.getNode(), Duration.ofMinutes(3));
        return new ResourcesSweepScheduler(sweepers, leader, () -> configuredTenants(env),
                env.getProperty("sangam.resources.schedule.interval", Duration.class, Duration.ofMinutes(1)));
    }

    private static List<String> configuredTenants(Environment env) {
        Set<String> out = new LinkedHashSet<>(Binder.get(env)
                .bind("sangam.resources.schedule.tenants", Bindable.listOf(String.class)).orElse(List.of()));
        out.removeIf(t -> t == null || t.isBlank());
        return new ArrayList<>(out);
    }

    private static Transactions engineTx(TxSupport tx, ObjectProvider<EngineAdapter> engine) {
        EngineAdapter adapter = engine.getIfAvailable();
        if (adapter != null) {
            return Transactions.of(adapter);
        }
        return new Transactions() {
            @Override
            public <T> T inTransaction(Function<java.sql.Connection, T> work) {
                return tx.withConnection(work);
            }
        };
    }
}
