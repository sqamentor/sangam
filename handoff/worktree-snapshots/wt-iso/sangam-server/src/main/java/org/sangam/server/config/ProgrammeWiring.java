// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import org.operaton.bpm.engine.ProcessEngine;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Outbox;
import org.sangam.integration.connector.ConnectorInvoker;
import org.sangam.integration.relay.OutboxWriter;
import org.sangam.integration.relay.PublisherRegistry;
import org.sangam.ports.support.EngineTx;
import org.sangam.programme.ProgrammeModule;
import org.sangam.programme.ProgrammeSchema;
import org.sangam.programme.ports.ProgrammePorts;
import org.sangam.programme.rest.ProgrammeRestAdapter;
import org.sangam.rest.support.TxSupport;
import org.sangam.security.SangamSecurity;
import org.sangam.server.bridge.CaseEventForwarder;
import org.sangam.server.bridge.CaseModuleDirectories;
import org.sangam.server.bridge.CaseModuleNotifier;
import org.sangam.server.bridge.CaseModuleProcessStarter;
import org.sangam.server.bridge.ProgrammeBindings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Wires sangam-programme (N15; sangam-programme README "Integration notes"): {@code ProgrammeModule} on the engine
 * transactions (Q1) with the hierarchy on sangam-security org units, campaigns started on the engine in the same
 * command, return validation on Operaton DMN, reminders through the integration outbox and connectors, and the outbox
 * destinations {@code programme-events} and {@code programme-dashboard}; plus the REST port. The BPMN delegates use
 * the installed module; the periodic work (campaigns, reminders, delays) runs as timer-started pack processes.
 * Disable with {@code sangam.modules.programme.enabled=false}.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "sangam.modules.programme.enabled", havingValue = "true", matchIfMissing = true)
public class ProgrammeWiring {

    public static final String EVENTS = "programme-events";
    public static final String DASHBOARD = "programme-dashboard";

    private static final Logger LOG = LoggerFactory.getLogger(ProgrammeWiring.class);

    @Bean
    public ProgrammeModule sangamProgrammeModule(ProcessEngine engine, TxSupport tx, Environment env,
            ObjectProvider<SangamSecurity> security, ObjectProvider<OutboxWriter> outboxWriter,
            ObjectProvider<ConnectorInvoker> invoker, ObjectProvider<PublisherRegistry> publishers) {
        Dialect dialect = tx.dialect();
        CaseWiring.devSchema(env, tx, "PG_PROGRAMME", c -> ProgrammeSchema.create(c, dialect));
        SangamSecurity sec = security.getIfAvailable();
        PublisherRegistry pubs = publishers.getIfAvailable();
        CaseModuleNotifier notifier = CaseWiring.notifier("programme", env, outboxWriter, invoker, pubs);
        ProgrammePorts.HierarchyPort hierarchy = sec == null ? null : ProgrammeBindings.orgHierarchy(sec);
        var ports = new ProgrammeModule.Ports(hierarchy,
                new CaseModuleProcessStarter(engine.getRuntimeService(), engine.getRepositoryService()),
                ProgrammeBindings.dmnValidator(engine.getDecisionService(), engine.getRepositoryService()),
                notifier, null /* dashboard: the programme-dashboard outbox destination is the feed */,
                null /* financial source: sangam-ledger consumption is not bound (see the server README) */);
        ProgrammeModule module = new ProgrammeModule(dialect, EngineTx.transactions(engine), new Outbox(dialect), ports);
        ProgrammeModule.install(module);
        if (pubs != null) {
            pubs.register(EVENTS, new CaseEventForwarder(pubs, EVENTS, CaseWiring.list(env, "sangam.programme.events.forward-to"),
                    null));
            pubs.register(DASHBOARD, new CaseEventForwarder(pubs, DASHBOARD,
                    CaseWiring.list(env, "sangam.programme.dashboard.forward-to"), null));
        }
        LOG.info("sangam-programme installed (hierarchy: {}, notifier: {})", hierarchy != null ? "sangam-security" : "FLAT",
                notifier != null);
        return module;
    }

    /** The programme REST port; VI.4 via sangam-security. */
    @Bean
    public ProgrammeRestAdapter sangamProgrammeRestPort(ProgrammeModule module, ObjectProvider<SangamSecurity> security) {
        SangamSecurity sec = security.getIfAvailable();
        if (sec == null) {
            LOG.warn("sangam-security is off: programme object checks are tenant-only (development)");
        }
        return new ProgrammeRestAdapter(module, sec == null ? CaseModuleDirectories.tenantOnly() : sec.authorization());
    }
}
