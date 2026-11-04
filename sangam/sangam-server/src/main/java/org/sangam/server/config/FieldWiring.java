// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Function;

import javax.sql.DataSource;

import org.operaton.bpm.engine.ProcessEngine;
import org.sangam.core.engine.SangamClockHolder;
import org.sangam.core.layer.Transactions;
import org.sangam.field.FieldConfig;
import org.sangam.field.FieldModule;
import org.sangam.field.FieldSchema;
import org.sangam.field.adapters.GeoServicePort;
import org.sangam.field.adapters.OperatonDecisionAdapter;
import org.sangam.field.evidence.EvidenceBlobStore;
import org.sangam.field.rest.FieldRestService;
import org.sangam.field.sync.QueuePolicy;
import org.sangam.geo.GeoService;
import org.sangam.integration.relay.LeaderElection;
import org.sangam.integration.relay.OutboxWriter;
import org.sangam.integration.relay.PublisherRegistry;
import org.sangam.rest.port.AdminPort;
import org.sangam.rest.support.RestProperties;
import org.sangam.rest.support.TxSupport;
import org.sangam.server.bridge.CaseEventForwarder;
import org.sangam.server.bridge.CaseFieldScheduler;
import org.sangam.server.bridge.FieldEngineEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Wires sangam-field (N13 field work, N17 dispatch; sangam-field README "Integration notes"): {@code FieldModule} with
 * geo (sangam-geo {@code GeoService}), decisions (Operaton DMN, tenant-scoped), events (integration outbox,
 * destination {@code field-events}, with engine message correlation for submitted visits and closed jobs), evidence
 * blobs (file store {@code sangam.field.evidence.root}), the audit chain and the engine clock; the module's REST port;
 * and the leader-elected per-tenant schedule ({@code runDue}, {@code sweepOverdue}, {@code expireOffers}).
 * Disable with {@code sangam.modules.field.enabled=false}.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "sangam.modules.field.enabled", havingValue = "true", matchIfMissing = true)
public class FieldWiring {

    private static final Logger LOG = LoggerFactory.getLogger(FieldWiring.class);

    @Bean
    public FieldEngineEvents sangamFieldEvents(ProcessEngine engine, TxSupport tx, ObjectProvider<OutboxWriter> outboxWriter) {
        return new FieldEngineEvents(outboxWriter.getIfAvailable(), tx.dialect(), engine.getRuntimeService());
    }

    @Bean
    public FieldModule sangamFieldModule(ProcessEngine engine, TxSupport tx, Environment env, FieldEngineEvents events,
            ObjectProvider<GeoService> geo, ObjectProvider<PublisherRegistry> publishers) {
        CaseWiring.devSchema(env, tx, "FLD_TEMPLATE", c -> FieldSchema.create(c, tx.dialect()));
        GeoService geoService = geo.getIfAvailable();
        Path evidenceRoot = Path.of(env.getProperty("sangam.field.evidence.root", "data/field-evidence"));
        var ports = new FieldModule.Ports(
                geoService == null ? null : new GeoServicePort(geoService),
                new OperatonDecisionAdapter(engine, env.getProperty("sangam.field.decisions.tenant-scoped", Boolean.class, true)),
                events.events(),
                new EvidenceBlobStore.FileSystem(evidenceRoot),
                null /* audit: FieldAudit.chain(dialect), the core audit chain */,
                QueuePolicy.defaults(), FieldConfig.defaults(), new EngineClock(ZoneOffset.UTC));
        FieldModule module = new FieldModule(joined(tx), tx.dialect(), ports);
        events.module(module);
        PublisherRegistry pubs = publishers.getIfAvailable();
        if (pubs != null) {
            pubs.register(FieldEngineEvents.DESTINATION, new CaseEventForwarder(pubs, FieldEngineEvents.DESTINATION,
                    CaseWiring.list(env, "sangam.field.events.forward-to"), events.correlator()));
        }
        LOG.info("sangam-field installed (geo: {}, evidence store: {}, events: {})",
                geoService != null ? "sangam-geo" : "pure geometry", evidenceRoot.toAbsolutePath(),
                pubs != null ? "outbox + engine correlation" : "outbox only");
        return module;
    }

    /** The field module's REST port (FieldRestPort). */
    @Bean
    public FieldRestService sangamFieldRestPort(FieldModule module) {
        return module.rest;
    }

    /** Per tenant: due schedules hourly, overdue sweep every 15 minutes, offer expiry every minute (leader-elected). */
    @Bean
    @ConditionalOnProperty(name = "sangam.field.schedule.enabled", havingValue = "true", matchIfMissing = true)
    public CaseFieldScheduler sangamFieldScheduler(FieldModule module, TxSupport tx, DataSource ds, RestProperties rest,
                                                   Environment env, ObjectProvider<AdminPort> admin) {
        long grace = env.getProperty("sangam.field.schedule.overdue-grace", Duration.class, Duration.ofHours(1)).toSeconds();
        LeaderElection leader = new LeaderElection(tx.dialect(), Transactions.of(ds), rest.getNode(), Duration.ofMinutes(3));
        return new CaseFieldScheduler("sangam-field-scheduler", leader,
                () -> CaseWiring.tenants(env, admin, "sangam.field.tenants"),
                List.of(new CaseFieldScheduler.Task("run-due", env.getProperty("sangam.field.schedule.run-due-every",
                                Duration.class, Duration.ofHours(1)), true, t -> module.schedules.runDue(t)),
                        new CaseFieldScheduler.Task("sweep-overdue", env.getProperty("sangam.field.schedule.overdue-every",
                                Duration.class, Duration.ofMinutes(15)), true, t -> module.assignments.sweepOverdue(t, grace)),
                        new CaseFieldScheduler.Task("expire-offers", env.getProperty("sangam.field.schedule.offers-every",
                                Duration.class, Duration.ofMinutes(1)), true, t -> module.dispatch.expireOffers(t))),
                Duration.ofSeconds(30));
    }

    private static Transactions joined(TxSupport tx) {
        return new Transactions() {
            @Override
            public <T> T inTransaction(Function<java.sql.Connection, T> work) {
                return tx.withConnection(work);
            }
        };
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
