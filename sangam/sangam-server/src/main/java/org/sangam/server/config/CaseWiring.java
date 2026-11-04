// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import java.sql.Connection;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.sql.DataSource;

import org.operaton.bpm.engine.ProcessEngine;
import org.sangam.casefile.CaseFileSchema;
import org.sangam.casefile.CaseModule;
import org.sangam.casefile.cmmn.CaseFileEnginePlugin;
import org.sangam.casefile.common.CaseEvents;
import org.sangam.casefile.rest.CaseRestAdapter;
import org.sangam.core.engine.WorkCalendar;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Outbox;
import org.sangam.core.layer.Transactions;
import org.sangam.integration.connector.ConnectorInvoker;
import org.sangam.integration.relay.LeaderElection;
import org.sangam.integration.relay.OutboxWriter;
import org.sangam.integration.relay.PublisherRegistry;
import org.sangam.ports.content.ContentService;
import org.sangam.ports.history.HistoryQueryService;
import org.sangam.ports.links.InstanceLinkService;
import org.sangam.ports.support.EngineTx;
import org.sangam.registry.RegistryModule;
import org.sangam.resources.SangamResources;
import org.sangam.rest.port.AdminPort;
import org.sangam.rest.port.Page;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.RestProperties;
import org.sangam.rest.support.TxSupport;
import org.sangam.security.SangamSecurity;
import org.sangam.server.bridge.CaseEventForwarder;
import org.sangam.server.bridge.CaseFieldScheduler;
import org.sangam.server.bridge.CaseModuleDirectories;
import org.sangam.server.bridge.CaseModuleNotifier;
import org.sangam.server.bridge.CaseModuleProcessStarter;
import org.sangam.server.bridge.ComplianceConnectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Wires sangam-case (N04 case files, N16 committees, meetings, action items, references; sangam-case README
 * "Integration notes"):
 * <ul>
 *   <li>{@code CaseModule} on the engine transactions ({@code EngineTx}, Q1), installed for the BPMN delegates and the
 *       CMMN listener; ports: outbox (core {@code Outbox}, destination {@code case-events}), entity links and content
 *       (sangam-flowable-ports), business calendars ({@code WorkCalendar} beans by name), escalation seats
 *       (sangam-security postings), engine history for the timeline (ports read model), process starts (engine
 *       {@code RuntimeService} in the same command), venue booking (sangam-resources), party registry
 *       (sangam-registry) and notifications (integration outbox + DLT SMS / e-mail connectors);</li>
 *   <li>{@code CaseFileEnginePlugin} as bean {@code sangamCasePlugin} (order: after SangamProcessEnginePlugin and
 *       SangamPortsPlugin);</li>
 *   <li>the REST ports ({@code CaseRestAdapter} with sangam-security's VI.4 {@code AuthorizationService});</li>
 *   <li>the deadline sweep every minute, leader-elected.</li>
 * </ul>
 * Disable with {@code sangam.modules.case.enabled=false}.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "sangam.modules.case.enabled", havingValue = "true", matchIfMissing = true)
public class CaseWiring {

    private static final Logger LOG = LoggerFactory.getLogger(CaseWiring.class);

    /** CMMN transform listener: case files bound to CMMN cases (no engine dependency: built while the engine is configured). */
    @Bean
    public CaseFileEnginePlugin sangamCasePlugin(Environment env) {
        return new CaseFileEnginePlugin(null, env.getProperty("sangam.case.close-file-with-case", Boolean.class, true));
    }

    @Bean
    public CaseModule sangamCaseModule(ProcessEngine engine, TxSupport tx, Environment env, ListableBeanFactory beans,
            ObjectProvider<ContentService> content, ObjectProvider<SangamSecurity> security,
            ObjectProvider<HistoryQueryService> history, ObjectProvider<SangamResources> resources,
            ObjectProvider<RegistryModule> registry, ObjectProvider<OutboxWriter> outboxWriter,
            ObjectProvider<ConnectorInvoker> invoker, ObjectProvider<PublisherRegistry> publishers) {
        Dialect dialect = tx.dialect();
        devSchema(env, tx, "CF_CASE", c -> CaseFileSchema.create(c, dialect));
        Transactions engineTx = EngineTx.transactions(engine);
        CaseModule.Settings s = new CaseModule.Settings(dialect, engineTx);
        s.outbox = new Outbox(dialect);
        s.links = new InstanceLinkService(dialect, engineTx);
        s.content = content.getIfAvailable();
        s.zone = ZoneId.of(env.getProperty("sangam.case.zone", "Asia/Kolkata"));
        s.calendars = CaseModuleDirectories.calendars(beans.getBeansOfType(WorkCalendar.class));
        s.processes = new CaseModuleProcessStarter(engine.getRuntimeService(), engine.getRepositoryService());
        SangamSecurity sec = security.getIfAvailable();
        if (sec != null) {
            s.escalationSeats = CaseModuleDirectories.escalation(sec.postings());
        }
        HistoryQueryService queries = history.getIfAvailable();
        if (queries != null) {
            s.history = CaseModuleDirectories.history(queries);
        }
        SangamResources res = resources.getIfAvailable();
        if (res != null) {
            s.venues = CaseModuleDirectories.venues(res);
        }
        RegistryModule reg = registry.getIfAvailable();
        if (reg != null) {
            s.partyDirectory = CaseModuleDirectories.parties(reg);
        }
        PublisherRegistry pubs = publishers.getIfAvailable();
        CaseModuleNotifier notifier = notifier("case", env, outboxWriter, invoker, pubs);
        if (notifier != null) {
            s.notifier = notifier;
        }
        CaseModule module = new CaseModule(s);
        CaseModule.install(module);
        if (pubs != null) {
            pubs.register(CaseEvents.DESTINATION, new CaseEventForwarder(pubs, CaseEvents.DESTINATION,
                    list(env, "sangam.case.events.forward-to"), null));
        }
        LOG.info("sangam-case installed (content: {}, links: yes, security seats: {}, history: {}, venues: {}, parties: {},"
                        + " notifier: {})", s.content != null, sec != null, queries != null, res != null, reg != null,
                notifier != null);
        return module;
    }

    /** The case module's REST ports (CaseFileRestPort, MeetingRestPort, ReferenceRestPort); VI.4 via sangam-security. */
    @Bean
    public CaseRestAdapter sangamCaseRestPort(CaseModule module, ObjectProvider<SangamSecurity> security) {
        SangamSecurity sec = security.getIfAvailable();
        if (sec == null) {
            LOG.warn("sangam-security is off: case-module object checks are tenant-only (development)");
        }
        return new CaseRestAdapter(module, sec == null ? CaseModuleDirectories.tenantOnly() : sec.authorization());
    }

    /** {@code DeadlineService.sweep(batch)} every minute on the leader (safe on several nodes; leader-elected anyway). */
    @Bean
    @ConditionalOnProperty(name = "sangam.case.schedule.enabled", havingValue = "true", matchIfMissing = true)
    public CaseFieldScheduler sangamCaseScheduler(CaseModule module, TxSupport tx, DataSource ds, RestProperties rest,
                                                  Environment env) {
        int batch = env.getProperty("sangam.case.schedule.deadline-batch", Integer.class, 200);
        Duration every = env.getProperty("sangam.case.schedule.deadline-every", Duration.class, Duration.ofMinutes(1));
        LeaderElection leader = new LeaderElection(tx.dialect(), Transactions.of(ds), rest.getNode(), Duration.ofMinutes(3));
        return new CaseFieldScheduler("sangam-case-scheduler", leader, List::of,
                List.of(new CaseFieldScheduler.Task("deadline-sweep", every, false, t -> module.deadlines().sweep(batch))),
                Duration.ofSeconds(30));
    }

    // ---------------------------------------------------------------- shared helpers (case, programme, field wiring)

    /** The outbox notifier of a module, registered on the relay; null when integration is off. */
    static CaseModuleNotifier notifier(String module, Environment env, ObjectProvider<OutboxWriter> outboxWriter,
                                       ObjectProvider<ConnectorInvoker> invoker, PublisherRegistry publishers) {
        OutboxWriter writer = outboxWriter.getIfAvailable();
        ConnectorInvoker inv = invoker.getIfAvailable();
        if (writer == null || inv == null || publishers == null) {
            return null;
        }
        ComplianceConnectors connectors = new ComplianceConnectors(inv,
                env.getProperty("sangam." + module + ".connector-timeout", Duration.class, Duration.ofMinutes(2)));
        CaseModuleNotifier n = new CaseModuleNotifier(module, writer, connectors,
                env.getProperty("sangam." + module + ".notify.sms-connector", "sms-dlt-sim"),
                env.getProperty("sangam." + module + ".notify.email-connector", "smtp"));
        publishers.register(n.destination(), n.publisher());
        return n;
    }

    /** Development only: create the module tables when {@code sangam.persistence.dev-schema=true} and they are missing. */
    static void devSchema(Environment env, TxSupport tx, String probeTable, java.util.function.Consumer<Connection> create) {
        if (!env.getProperty("sangam.persistence.dev-schema", Boolean.class, false)) {
            return;
        }
        tx.withConnection(c -> {
            try {
                for (String name : new String[]{probeTable, probeTable.toLowerCase(java.util.Locale.ROOT)}) {
                    try (var rs = c.getMetaData().getTables(null, null, name, new String[]{"TABLE"})) {
                        if (rs.next()) {
                            return null;
                        }
                    }
                }
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException("cannot inspect the schema for " + probeTable, e);
            }
            create.accept(c);
            LOG.info("Created the development tables of {}", probeTable);
            return null;
        });
    }

    static List<String> list(Environment env, String property) {
        return Binder.get(env).bind(property, Bindable.listOf(String.class)).orElse(List.of());
    }

    /** Tenants for per-tenant schedules: {@code <property>} plus the active tenants of the tenant directory. */
    static List<String> tenants(Environment env, ObjectProvider<AdminPort> admin, String property) {
        Set<String> out = new LinkedHashSet<>(list(env, property));
        AdminPort port = admin.getIfAvailable();
        if (port != null) {
            try {
                String home = env.getProperty("sangam.tenant.default", "dev");
                RequestContext sys = new RequestContext(home, "system:scheduler", Set.of("sangam-system", "sangam-admin"),
                        null, null, null, null, null, "SYSTEM");
                Page<AdminPort.Tenant> page = port.tenants(sys, new AdminPort.AdminFilter(null, null, null, null, null,
                        null, null, false), Page.PageRequest.of(0, 500));
                page.items().stream().filter(t -> !"SUSPENDED".equals(t.status())).map(AdminPort.Tenant::id)
                        .forEach(out::add);
            } catch (RuntimeException e) {
                LOG.debug("tenant directory unavailable for a module schedule: {}", e.toString());
            }
        }
        out.removeIf(t -> t == null || t.isBlank());
        return new ArrayList<>(out);
    }
}
