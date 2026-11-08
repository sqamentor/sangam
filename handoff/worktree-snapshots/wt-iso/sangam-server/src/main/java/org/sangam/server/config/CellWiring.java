// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import java.net.URI;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;

import javax.sql.DataSource;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.operaton.bpm.engine.ProcessEngine;
import org.sangam.cell.id.CellId;
import org.sangam.cell.registration.CellDescriptor;
import org.sangam.cell.registration.ControlPlaneClient;
import org.sangam.cell.rollup.CrossCellPublisher;
import org.sangam.cell.rollup.RollupPublisher;
import org.sangam.core.layer.Jdbc;
import org.sangam.integration.inbox.InboxService;
import org.sangam.integration.relay.LeaderElection;
import org.sangam.integration.relay.OutboxCapacityMonitor;
import org.sangam.integration.relay.PublisherRegistry;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.support.DocumentStore;
import org.sangam.rest.support.RestProperties;
import org.sangam.rest.support.TxSupport;
import org.sangam.server.bridge.CellCrossCellInboxFilter;
import org.sangam.server.bridge.CellCrossCellInboxHandler;
import org.sangam.server.bridge.CellLoadSampler;
import org.sangam.server.bridge.CellPoisonPublisher;
import org.sangam.server.bridge.CellRegistrationService;
import org.sangam.server.bridge.CellTenantMover;
import org.sangam.server.bridge.CellTenantStateFilter;
import org.sangam.server.bridge.CellTenantStateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationListener;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * SCALE cell wiring (sangam-cell README IR-2, IR-3, IR-4). Active only when {@code sangam.cell.id}
 * ({@code SANGAM_CELL_ID}) is set; a server without it is a single deployment and none of this runs.
 * <ul>
 *   <li><b>IR-2</b> registration and heartbeat with the control plane ({@link CellRegistrationService}: every node
 *       registers, the {@code sangam-cell-heartbeat} leader reports load; DRAINING on shutdown);</li>
 *   <li><b>IR-3</b> publishers {@value RollupPublisher#DESTINATION} and {@code xcell:*} wrapped by
 *       {@link CellPoisonPublisher}; the {@code rollup:national} subscription for history events is added to the
 *       outbox writer in {@code IntegrationWiring}; cross-cell messages are received through
 *       {@link CellCrossCellInboxFilter} into the inbox and correlated by {@link CellCrossCellInboxHandler};</li>
 *   <li><b>IR-4</b> tenant move state ({@link CellTenantStateStore}, {@link CellTenantStateFilter}) and the row mover
 *       ({@link CellTenantMover}) behind {@code web/CellTenantAdminController}.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "sangam.cell.id")
public class CellWiring {

    private static final Logger LOG = LoggerFactory.getLogger(CellWiring.class);

    /** Order after Spring Security (-100), before back-pressure (50) and the Idempotency-Key filter (100). */
    public static final int STATE_FILTER_ORDER = 40;
    public static final int XCELL_FILTER_ORDER = 45;

    /** The control-plane client, or none when {@code SANGAM_CONTROL_PLANE_URL} is not configured. */
    public record ControlPlane(ControlPlaneClient client) {
    }

    @Bean
    public ControlPlane sangamControlPlane(Environment env) {
        String url = env.getProperty("sangam.cell.control-plane.url", "");
        if (url.isBlank()) {
            LOG.warn("sangam.cell.id is set but sangam.cell.control-plane.url is not: the cell does not register");
            return new ControlPlane(null);
        }
        return new ControlPlane(new ControlPlaneClient(URI.create(url), env.getProperty("sangam.cell.control-plane.token"),
                env.getProperty("sangam.cell.control-plane.path-ttl", Duration.class, Duration.ofMinutes(5))));
    }

    // ---------------------------------------------------------------- IR-4 tenant move

    @Bean
    public CellTenantStateStore sangamCellTenantStates(DocumentStore documents) {
        return new CellTenantStateStore(documents);
    }

    @Bean
    public CellTenantMover sangamCellTenantMover(TxSupport tx, DocumentStore documents, AuditPort audit,
                                                 Environment env) {
        return new CellTenantMover(tx, documents, audit,
                Path.of(env.getProperty("sangam.cell.export-dir", "data/cell-exports")),
                env.getProperty("sangam.cell.admin-url", ""),
                env.getProperty("sangam.cell.export-ttl", Duration.class, Duration.ofHours(2)));
    }

    @Bean
    public FilterRegistrationBean<CellTenantStateFilter> sangamCellTenantStateFilter(CellTenantStateStore states) {
        FilterRegistrationBean<CellTenantStateFilter> reg = new FilterRegistrationBean<>(new CellTenantStateFilter(states));
        reg.addUrlPatterns("/api/*", "/engine-rest/*");
        reg.setOrder(STATE_FILTER_ORDER);
        reg.setName("sangamCellTenantStateFilter");
        return reg;
    }

    // ---------------------------------------------------------------- IR-3 publishers and cross-cell inbox

    @Bean
    public InitializingBean sangamCellPublishers(Environment env, ObjectProvider<PublisherRegistry> registry,
                                                 ControlPlane controlPlane) {
        return () -> {
            PublisherRegistry publishers = registry.getIfAvailable();
            if (publishers == null) {
                LOG.warn("sangam-integration is disabled: roll-up and cross-cell publishers are not registered");
                return;
            }
            String cellId = env.getRequiredProperty("sangam.cell.id");
            String ingest = env.getProperty("sangam.cell.rollup.ingest-url", "");
            if (!ingest.isBlank()) {
                ControlPlaneClient cp = controlPlane.client();
                publishers.register(RollupPublisher.DESTINATION, new CellPoisonPublisher(new RollupPublisher(
                        CellId.of(cellId), URI.create(ingest),
                        cp == null ? tenant -> java.util.Optional.empty() : cp::tenantPath,
                        env.getProperty("sangam.cell.rollup.token"), null)));
            }
            String gateway = env.getProperty("sangam.cell.gateway-url", "");
            if (!gateway.isBlank()) {
                publishers.register(CrossCellPublisher.PREFIX + "*", new CellPoisonPublisher(new CrossCellPublisher(
                        URI.create(gateway), env.getProperty("sangam.cell.service-token"), cellId)));
            }
            LOG.info("Cell {} publishers: rollup={}, xcell={}", cellId, !ingest.isBlank(), !gateway.isBlank());
        };
    }

    /** Cross-cell inbound messages: registered before the inbox starts (integration lifecycle phase MAX-100). */
    @Bean
    public InitializingBean sangamCellCrossCellHandler(ObjectProvider<InboxService> inbox, ProcessEngine engine) {
        return () -> {
            InboxService i = inbox.getIfAvailable();
            if (i != null) {
                i.registerHandler(CellCrossCellInboxFilter.EVENT_TYPE, new CellCrossCellInboxHandler(engine.getRuntimeService()));
            }
        };
    }

    @Bean
    @ConditionalOnProperty(name = "sangam.modules.integration.enabled", havingValue = "true", matchIfMissing = true)
    public FilterRegistrationBean<CellCrossCellInboxFilter> sangamCellCrossCellFilter(InboxService inbox) {
        FilterRegistrationBean<CellCrossCellInboxFilter> reg =
                new FilterRegistrationBean<>(new CellCrossCellInboxFilter(inbox));
        reg.addUrlPatterns("/api/v1/messages");
        reg.setOrder(XCELL_FILTER_ORDER);
        reg.setName("sangamCellCrossCellFilter");
        return reg;
    }

    // ---------------------------------------------------------------- IR-2 registration and heartbeat

    @Bean(destroyMethod = "close")
    public CellRegistrationService sangamCellRegistration(Environment env, ControlPlane controlPlane, TxSupport tx,
            DataSource dataSource, DocumentStore documents, RestProperties rest, ProcessEngine engine,
            ObjectProvider<MeterRegistry> meters, ObjectProvider<OutboxCapacityMonitor> capacity) {
        String cellId = env.getRequiredProperty("sangam.cell.id");
        String version = env.getProperty("sangam.version", CellWiring.class.getPackage().getImplementationVersion() == null
                ? "0.1.0-SNAPSHOT" : CellWiring.class.getPackage().getImplementationVersion());
        CellDescriptor descriptor = new CellDescriptor(cellId,
                env.getProperty("sangam.cell.region", ""),
                env.getProperty("sangam.cell.jurisdiction", "IN"),
                env.getProperty("sangam.cell.residency", env.getProperty("sangam.cell.jurisdiction", "IN")),
                env.getProperty("sangam.cell.public-url", ""),
                env.getProperty("sangam.cell.admin-url", ""),
                dbVendor(dataSource),
                env.getProperty("sangam.cell.capacity.starts-per-sec", Integer.class, 500),
                env.getProperty("sangam.cell.capacity.tenants", Integer.class, 2000),
                version);
        LeaderElection leader = new LeaderElection(tx.dialect(), tx.newTransactions(), rest.getNode(),
                Duration.ofSeconds(30));
        CellRegistrationService[] self = new CellRegistrationService[1];
        CellLoadSampler sampler = new CellLoadSampler(cellId, version, meters.getIfAvailable(SimpleMeterRegistry::new),
                engine.getHistoryService(), capacity.getIfAvailable(),
                () -> self[0] == null ? 1 : self[0].activeNodes(),
                () -> tenantCount(tx));
        ControlPlaneClient client = controlPlane.client();
        CellRegistrationService service = new CellRegistrationService(client, descriptor, sampler, leader, documents,
                rest.getNode(), env.getProperty("sangam.cell.heartbeat-interval", Duration.class, Duration.ofSeconds(10))
                .toMillis());
        self[0] = service;
        return service;
    }

    /** Starts registration when the application is ready; DRAINING heartbeat at shutdown (before services stop). */
    @Bean
    public CellLifecycle sangamCellLifecycle(CellRegistrationService registration, ControlPlane controlPlane) {
        return new CellLifecycle(registration, controlPlane.client() != null);
    }

    public static final class CellLifecycle implements ApplicationListener<ApplicationReadyEvent>, SmartLifecycle {
        private final CellRegistrationService registration;
        private final boolean enabled;
        private volatile boolean running;

        CellLifecycle(CellRegistrationService registration, boolean enabled) {
            this.registration = registration;
            this.enabled = enabled;
        }

        @Override
        public void onApplicationEvent(ApplicationReadyEvent event) {
            if (enabled && !running) {
                registration.start();
                running = true;
            }
        }

        @Override
        public void start() {
            // started on ApplicationReadyEvent (IR-2), not with the context refresh
        }

        @Override
        public void stop() {
            if (running) {
                registration.drain();
                running = false;
            }
        }

        @Override
        public boolean isRunning() {
            return running;
        }

        /** Stopped first (highest phase), so DRAINING is reported while the node still serves. */
        @Override
        public int getPhase() {
            return Integer.MAX_VALUE;
        }
    }

    private static long tenantCount(TxSupport tx) {
        return tx.withConnection(c -> Jdbc.one(c,
                "SELECT COUNT(DISTINCT TENANT_ID_) FROM ACT_RE_DEPLOYMENT WHERE TENANT_ID_ IS NOT NULL",
                rs -> rs.getLong(1)).orElse(0L));
    }

    private static String dbVendor(DataSource ds) {
        try (Connection c = ds.getConnection()) {
            return c.getMetaData().getDatabaseProductName().toLowerCase(java.util.Locale.ROOT);
        } catch (java.sql.SQLException e) {
            return "unknown";
        }
    }
}
