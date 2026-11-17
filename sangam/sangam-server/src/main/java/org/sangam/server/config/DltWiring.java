// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Transactions;
import org.sangam.integration.connector.ConnectorCallJournal;
import org.sangam.integration.connector.ConnectorRegistry;
import org.sangam.integration.connector.SecretResolver;
import org.sangam.integration.connector.gov.DltDeliveryReports;
import org.sangam.integration.connector.gov.DltDeliveryStatusPoller;
import org.sangam.integration.connector.gov.DltRegistrationStore;
import org.sangam.integration.connector.gov.DltRegistry;
import org.sangam.integration.sim.SimulatorProfile;
import org.sangam.integration.sim.dlt.DltSmsSimulator;
import org.sangam.rest.port.DltPort;
import org.sangam.rest.support.TxSupport;
import org.sangam.server.bridge.IntegrationDltBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * India DLT SMS wiring (owner decisions 304 and 305; signatures as agreed with sangam-integration, W4-H
 * 2026-11-13T08:07:02Z).
 * <ul>
 *   <li><b>Stored registration (305).</b> {@link DltRegistrationStore} on the product pool is the source of the
 *       {@link DltRegistry} the DLT SMS connector validates against ({@code IntegrationWiring.sangamConnectorRegistry}
 *       takes {@link DltRegistrationStore#registry()}). Start-up configuration only seeds it
 *       ({@code sangam.integration.dlt.registration.seed.<tenant>.*}; an entry the checks refuse stops the start);
 *       every node loads the stored registrations at start and then every {@code registration.refresh-interval}
 *       (1m), which is how a change made on another node arrives. The admin routes are the {@link DltPort}.</li>
 *   <li><b>Delivery reports (304).</b> {@link DltDeliveryReports} behind the public, signed route
 *       {@code POST /api/v1/hooks/sms-dlt/{tenant}/by-connector-id/delivery-reports?connectorId=}; the signing secret of a connector is
 *       a {@code secret://} reference ({@code delivery-reports.secrets.*}); a connector without one refuses every
 *       report. {@link DltDeliveryStatusPoller#runOnce} is the fallback, every {@code delivery-status.interval} (5m)
 *       on every node (its claim is skip-locked), unless {@code delivery-status.enabled=false}.</li>
 * </ul>
 * The schedule runs on one daemon thread and only with {@code sangam.integration.background.enabled} (default true),
 * as the other integration background services. Both tasks start after their first interval, so the schema is in
 * place; a failed pass is logged and the next pass retries.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "sangam.modules.integration.enabled", havingValue = "true", matchIfMissing = true)
public class DltWiring {

    private static final Logger LOG = LoggerFactory.getLogger(DltWiring.class);
    static final String PREFIX = "sangam.integration.dlt.";

    @Bean
    public DltProperties sangamDltProperties() {
        return new DltProperties();
    }

    @Bean
    public DltRegistrationStore sangamDltRegistrationStore(TxSupport tx, DataSource ds,
                                                          AuditChain sangamIntegrationAuditChain) {
        return new DltRegistrationStore(tx.dialect(), Transactions.of(ds), sangamIntegrationAuditChain);
    }

    @Bean
    public DltDeliveryReports sangamDltDeliveryReports(TxSupport tx, DataSource ds, ConnectorRegistry registry,
            ConnectorCallJournal journal, AuditChain sangamIntegrationAuditChain, ObjectProvider<SecretResolver> secrets,
            DltProperties props) {
        return new DltDeliveryReports(tx.dialect(), Transactions.of(ds), registry, journal, sangamIntegrationAuditChain,
                secrets.getIfAvailable(SecretResolver::fromEnvironment),
                new DltDeliveryReports.Settings(secretRefs(props), positive(props.getDeliveryReports().getTolerance(),
                        "delivery-reports.tolerance"), 0, 0), Clock.systemUTC());
    }

    @Bean
    public DltDeliveryStatusPoller sangamDltDeliveryStatusPoller(TxSupport tx, DataSource ds, ConnectorRegistry registry,
            ConnectorCallJournal journal, DltDeliveryReports reports, DltProperties props) {
        return new DltDeliveryStatusPoller(tx.dialect(), Transactions.of(ds), registry, journal, reports,
                pollerSettings(props));
    }

    /** The REST port of both decisions; a registration change reaches the development simulator gateway at once. */
    @Bean
    public DltPort sangamDltPort(DltDeliveryReports reports, DltRegistrationStore store,
                                 ObjectProvider<SimulatorProfile> sims) {
        return new IntegrationDltBridge(reports, store, () -> syncSimulator(sims.getIfAvailable(), store.registry()));
    }

    /**
     * Decision 305: start-up configuration seeds the stored registration, then this node loads every tenant's stored
     * registration. Runs after the schema is in place; an entry the store's checks refuse
     * ({@link DltRegistrationStore.InvalidRegistration}) stops the start.
     */
    @Bean
    public ApplicationRunner sangamDltRegistrationSeed(DltRegistrationStore store, DltProperties props,
                                                       ObjectProvider<SimulatorProfile> sims) {
        return args -> {
            List<DltRegistrationStore.Seed> seeds = seeds(props);
            int added = store.seed(seeds);
            int loaded = store.refresh();
            syncSimulator(sims.getIfAvailable(), store.registry());
            if (!seeds.isEmpty() || loaded > 0) {
                LOG.info("DLT registration: {} seeded entr(ies) added from configuration, {} tenant(s) loaded", added, loaded);
            }
        };
    }

    @Bean(destroyMethod = "close")
    public Scheduler sangamDltScheduler(DltRegistrationStore store, DltDeliveryStatusPoller poller, DltProperties props,
                                        ObjectProvider<SimulatorProfile> sims, Environment env) {
        Duration refresh = positive(props.getRegistration().getRefreshInterval(), "registration.refresh-interval");
        Duration poll = positive(props.getDeliveryStatus().getInterval(), "delivery-status.interval");
        Scheduler s = new Scheduler(store, props.getDeliveryStatus().isEnabled() ? poller : null,
                () -> syncSimulator(sims.getIfAvailable(), store.registry()));
        if (env.getProperty("sangam.integration.background.enabled", Boolean.class, true)) {
            s.start(refresh, poll);
        } else {
            LOG.info("DLT registration refresh and delivery-status lookups not scheduled"
                    + " (sangam.integration.background.enabled=false)");
        }
        return s;
    }

    // ---------------------------------------------------------------- settings from configuration

    /**
     * Signing secret references by {@code connectorId} or {@code tenant:connectorId} (which wins). A reference must be
     * {@code secret://...}: a literal secret in the configuration is refused at start, by property.
     */
    static Map<String, String> secretRefs(DltProperties props) {
        Map<String, String> out = new LinkedHashMap<>();
        props.getDeliveryReports().getSecrets().forEach((name, s) -> {
            String p = PREFIX + "delivery-reports.secrets." + name;
            if (s.getConnector() == null || s.getConnector().isBlank()) {
                throw new IllegalStateException(p + ".connector is required");
            }
            if (s.getSecretRef() == null || !s.getSecretRef().trim().startsWith("secret://")) {
                throw new IllegalStateException(p + ".secret-ref must be a secret reference (secret://...), never the secret");
            }
            String tenant = s.getTenant() == null || s.getTenant().isBlank() ? null : s.getTenant().trim();
            String key = tenant == null ? s.getConnector().trim() : tenant + ":" + s.getConnector().trim();
            if (out.putIfAbsent(key, s.getSecretRef().trim()) != null) {
                throw new IllegalStateException(p + ": a second signing secret for " + key);
            }
        });
        return out;
    }

    static DltDeliveryStatusPoller.Settings pollerSettings(DltProperties props) {
        DltProperties.DeliveryStatus s = props.getDeliveryStatus();
        if (s.getMaxLookups() < 1 || s.getBatchSize() < 1) {
            throw new IllegalStateException(PREFIX + "delivery-status.max-lookups and .batch-size must be at least 1");
        }
        return new DltDeliveryStatusPoller.Settings(positive(s.getFirstLookupAfter(), "delivery-status.first-lookup-after"),
                positive(s.getLookupEvery(), "delivery-status.lookup-every"), positive(s.getWindow(), "delivery-status.window"),
                s.getMaxLookups(), s.getBatchSize());
    }

    static List<DltRegistrationStore.Seed> seeds(DltProperties props) {
        List<DltRegistrationStore.Seed> out = new ArrayList<>();
        props.getRegistration().getSeed().forEach((tenant, s) -> {
            List<DltRegistrationStore.TemplateEntry> templates = new ArrayList<>();
            for (DltProperties.Template t : s.getTemplates()) {
                templates.add(new DltRegistrationStore.TemplateEntry(t.getTemplateId(), t.getHeader(), t.getBody()));
            }
            out.add(new DltRegistrationStore.Seed(tenant, s.getEntityId() == null || s.getEntityId().isBlank() ? null
                    : s.getEntityId().trim(), s.getHeaders(), templates, s.getUrls()));
        });
        return out;
    }

    /** A duration that is set and greater than zero; anything else refuses the start, naming the property. */
    static Duration positive(Duration value, String property) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalStateException(PREFIX + property + " must be a positive duration (found " + value + ")");
        }
        return value;
    }

    /**
     * Development only: the in-process DLT simulator gateway scrubs with its own copy of the templates and URL
     * prefixes, taken when the simulator connector is built; stored registrations arrive later (seed, admin route,
     * another node), so they are handed to it again. No simulator profile (every production server): nothing to do.
     */
    static void syncSimulator(SimulatorProfile sims, DltRegistry registry) {
        if (sims == null) {
            return;
        }
        DltSmsSimulator gateway = sims.dlt();
        for (String tenant : registry.tenantIds()) {
            registry.find(tenant).ifPresent(t -> {
                t.templates().forEach(tpl -> gateway.registerTemplate(tpl.id(), tpl.header(), tpl.body()));
                t.urlWhitelist().forEach(gateway::whitelistUrl);
            });
        }
    }

    /** One daemon thread: the registration refresh and the delivery-status lookups, each at its interval. */
    public static final class Scheduler implements AutoCloseable {
        private final DltRegistrationStore store;
        private final DltDeliveryStatusPoller pollerOrNull;
        private final Runnable afterRefresh;
        private ScheduledExecutorService executor;

        Scheduler(DltRegistrationStore store, DltDeliveryStatusPoller pollerOrNull, Runnable afterRefresh) {
            this.store = Objects.requireNonNull(store, "store");
            this.pollerOrNull = pollerOrNull;
            this.afterRefresh = Objects.requireNonNull(afterRefresh, "afterRefresh");
        }

        Scheduler start(Duration refreshEvery, Duration pollEvery) {
            executor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "sangam-dlt");
                t.setDaemon(true);
                return t;
            });
            executor.scheduleWithFixedDelay(this::refreshOnce, refreshEvery.toMillis(), refreshEvery.toMillis(),
                    TimeUnit.MILLISECONDS);
            if (pollerOrNull != null) {
                executor.scheduleWithFixedDelay(this::pollOnce, pollEvery.toMillis(), pollEvery.toMillis(),
                        TimeUnit.MILLISECONDS);
            }
            return this;
        }

        /** Whether the schedule is running on this node. */
        boolean started() {
            return executor != null;
        }

        /** Whether the status-lookup fallback is part of the schedule. */
        boolean polls() {
            return pollerOrNull != null;
        }

        /** Loads the registrations whose revision moved; never throws. Returns the tenants loaded. */
        int refreshOnce() {
            try {
                int loaded = store.refresh();
                if (loaded > 0) {
                    afterRefresh.run();
                    LOG.info("DLT registration of {} tenant(s) reloaded", loaded);
                }
                return loaded;
            } catch (RuntimeException e) {
                LOG.warn("DLT registration refresh failed; retried on the next pass: {}", e.toString());
                return 0;
            }
        }

        /** One status-lookup pass; never throws. Returns the messages looked up. */
        int pollOnce() {
            if (pollerOrNull == null) {
                return 0;
            }
            try {
                return pollerOrNull.runOnce();
            } catch (RuntimeException e) {
                LOG.warn("DLT delivery-status pass failed; retried on the next pass: {}", e.toString());
                return 0;
            }
        }

        @Override
        public void close() {
            if (executor != null) {
                executor.shutdownNow();
            }
        }
    }
}
