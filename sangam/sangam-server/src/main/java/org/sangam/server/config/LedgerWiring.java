// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.dmn.DecisionsEvaluationBuilder;
import org.sangam.config.profile.ProfileResolver;
import org.sangam.core.layer.PendingAuditDrainer;
import org.sangam.integration.connector.ConnectorInvoker;
import org.sangam.integration.connector.ConnectorRegistry;
import org.sangam.integration.packs.PackConnectorBindings;
import org.sangam.integration.relay.OutboxWriter;
import org.sangam.integration.relay.PublisherRegistry;
import org.sangam.ledger.JdbcLedgerStore;
import org.sangam.ledger.LedgerConnector;
import org.sangam.ledger.LedgerControlStore;
import org.sangam.ledger.LedgerJournal;
import org.sangam.ledger.LedgerPorts;
import org.sangam.ledger.LedgerSchema;
import org.sangam.ledger.LedgerService;
import org.sangam.rest.modules.ledger.LedgerTenantDefinitions;
import org.sangam.rest.support.TxSupport;
import org.sangam.server.bridge.ComplianceConnectors;
import org.sangam.server.bridge.ConfigConnectorBinder;
import org.sangam.server.bridge.LedgerDefiningConnector;
import org.sangam.server.bridge.LedgerGlPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Wires sangam-ledger (README "Integration notes"):
 * <ul>
 *   <li>{@link LedgerService} on the durable {@link JdbcLedgerStore} and {@link LedgerControlStore.JdbcStore}
 *       ({@code LDG_*} tables), with the engine clock.</li>
 *   <li>Ports: {@code RatePort} from the configuration profile's {@code locale.exchangeRates}
 *       ({@code sangam.ledger.rate-scope}, no default (SL-104): a cross-currency conversion with it unset fails
 *       naming the property); {@code DecisionPort} over Operaton DMN (the tenant's
 *       decision first, then the shared one). {@code ExternalLedgerPort} (SHADOW mode) is not bound: no FMIS connector
 *       exists yet.</li>
 *   <li>RM-04: the store's {@link LedgerJournal} writes each posting's class-B {@code LEDGER_POSTED} audit row (through
 *       the SL-133 pending audit, drained to the chain) and its general-ledger outbox rows
 *       ({@value LedgerGlPublisher#DESTINATION}) in the posting's own transaction, atomically with {@code LDG_ENTRY}.</li>
 *   <li>Start-up refusals: RM-05, a GL connector with no outbox writer, relay publisher registry or connector invoker
 *       (integration off) would drop every accounting entry; owner decision 126, in production the GL export is
 *       required wherever the ledger module is enabled; and the ledger needs the pending-audit drainer of the
 *       integration module for its audit rows.</li>
 *   <li>The ledger connector adapter ({@link LedgerDefiningConnector}, registry id {@code ledger}) for the pack ids
 *       {@code ledger.available} … {@code ledger.closePeriod}; the operation is the id suffix, the reference the pack
 *       {@code operationId}.</li>
 *   <li>{@link LedgerTenantDefinitions} (shared with the REST endpoints): tenant ledger definitions from the profile.</li>
 * </ul>
 * Properties: {@code sangam.modules.ledger.enabled}, {@code sangam.ledger.rate-scope}, {@code sangam.ledger.gl-connector},
 * {@code sangam.ledger.gl-operation} (default {@code post}).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "sangam.modules.ledger.enabled", havingValue = "true", matchIfMissing = true)
public class LedgerWiring {

    private static final Logger LOG = LoggerFactory.getLogger(LedgerWiring.class);

    /** The pack connector ids of the {@code gov.ifms.*} packs; the operation is the id suffix. */
    static final List<String> OPERATIONS = List.of("available", "allocate", "distribute", "withdraw", "commit",
            "encumber", "release", "consume", "transfer", "adjust", "releaseHolds", "closePeriod");

    /**
     * The start-up rules of the GL export (RM-05, owner decision 126); package-private for the guard test.
     *
     * @return the GL connector id, or null when none is configured (allowed outside production only)
     */
    static String requireGlExport(String glConnector, boolean production, boolean outboxWriter, boolean publishers,
                                  boolean invoker) {
        boolean set = glConnector != null && !glConnector.isBlank();
        if (!set) {
            if (production) {
                throw new IllegalStateException("sangam.ledger.gl-connector must be set in production while the ledger"
                        + " module is enabled (owner decision 126: the GL export is required where the ledger is enabled);"
                        + " set it, or disable the ledger with sangam.modules.ledger.enabled=false");
            }
            return null;
        }
        if (!outboxWriter || !publishers || !invoker) {
            String missing = !outboxWriter ? "OutboxWriter" : !publishers ? "PublisherRegistry" : "ConnectorInvoker";
            throw new IllegalStateException("sangam.ledger.gl-connector=" + glConnector + " is set but the integration"
                    + " module provides no " + missing + ": every accounting entry would be dropped (RM-05); enable"
                    + " sangam.modules.integration.enabled or unset the GL connector");
        }
        return glConnector.trim();
    }

    /** The ledger's audit rows need the SL-133 pending-audit drainer (integration module). */
    static PendingAuditDrainer requirePendingAudit(PendingAuditDrainer drainer) {
        if (drainer == null) {
            throw new IllegalStateException("the ledger writes LEDGER_POSTED through the pending audit (SL-133), which"
                    + " the integration module provides: enable sangam.modules.integration.enabled or disable the ledger"
                    + " with sangam.modules.ledger.enabled=false");
        }
        return drainer;
    }

    /** The store's posting journal: audit always, GL rows when a GL connector is configured. */
    static LedgerJournal journal(PendingAuditDrainer drainer, String gl, OutboxWriter writer) {
        return new LedgerJournal(requirePendingAudit(drainer), gl == null ? null
                : new LedgerJournal.GlExport(LedgerGlPublisher.DESTINATION, LedgerGlPublisher.EVENT_TYPE,
                        reference -> ComplianceConnectors.id64("ledger:", reference), writer::append));
    }

    @Bean
    public JdbcLedgerStore sangamLedgerStore(TxSupport tx, Environment env, ObjectProvider<PendingAuditDrainer> drainer,
            ObjectProvider<OutboxWriter> outbox, ObjectProvider<PublisherRegistry> publishers,
            ObjectProvider<ConnectorInvoker> invoker) {
        OutboxWriter writer = outbox.getIfAvailable();
        String gl = requireGlExport(env.getProperty("sangam.ledger.gl-connector"), ProductionEnvironment.isProduction(env),
                writer != null, publishers.getIfAvailable() != null, invoker.getIfAvailable() != null);
        LedgerJournal journal = journal(drainer.getIfAvailable(), gl, writer);
        if (env.getProperty("sangam.persistence.dev-schema", Boolean.class, false)) {
            ConfigWiring.devSchema(tx, "LDG_BALANCE", () -> tx.withConnection(c -> {
                try {
                    LedgerSchema.create(c, tx.dialect());
                    return null;
                } catch (java.sql.SQLException e) {
                    throw new IllegalStateException("cannot create the LDG_* dev tables", e);
                }
            }));
        }
        return new JdbcLedgerStore(tx.dataSource(), tx.dialect(), journal);
    }

    @Bean
    public LedgerControlStore sangamLedgerControl(TxSupport tx) {
        return new LedgerControlStore.JdbcStore(tx.dataSource(), tx.dialect());
    }

    /** DMN through Operaton: the tenant's decision when deployed, else the shared (tenant-less) one. */
    @Bean
    public LedgerPorts.DecisionPort sangamLedgerDecisions(ObjectProvider<ProcessEngine> engine) {
        return (tenant, key, variables) -> {
            ProcessEngine e = engine.getObject();
            Map<String, Object> vars = variables == null ? Map.of() : variables;
            boolean tenantHas = tenant != null && e.getRepositoryService().createDecisionDefinitionQuery()
                    .decisionDefinitionKey(key).tenantIdIn(tenant).latestVersion().count() > 0;
            DecisionsEvaluationBuilder b = e.getDecisionService().evaluateDecisionByKey(key).variables(vars);
            b = tenantHas ? b.decisionDefinitionTenantId(tenant) : b.decisionDefinitionWithoutTenantId();
            return b.evaluate().getResultList();
        };
    }

    /**
     * Exchange rates from the profile named by {@code sangam.ledger.rate-scope}: its {@code locale.exchangeRates} on the
     * as-of date ("today" of that scope when none is given). SL-104 (ADR-D-20): there is no default scope. Same-currency
     * conversion needs none; a cross-currency rate with the scope unset fails naming the property, instead of reading a
     * country profile nobody configured.
     */
    static LedgerPorts.RatePort profileRates(String rateScope, java.util.function.Supplier<ProfileResolver> profiles,
                                             java.util.function.Function<String, LocalDate> todayOf) {
        boolean configured = rateScope != null && !rateScope.isBlank();
        return (from, to, rateType, asOf) -> {
            if (from.equals(to)) {
                return Optional.of(BigDecimal.ONE);
            }
            if (!configured) {
                throw new IllegalStateException("sangam.ledger.rate-scope is not configured (SANGAM_LEDGER_RATE_SCOPE):"
                        + " no exchange-rate profile for " + from + "->" + to + " (SL-104)");
            }
            ProfileResolver r = profiles.get();
            if (r == null) {
                return Optional.empty();
            }
            try {
                // A missing as-of is "today" of the rate scope (its own profile zone).
                LocalDate d = asOf != null ? asOf : todayOf.apply(rateScope);
                return r.resolve(rateScope, d).locale().exchangeRates().rate(from, to, d);
            } catch (RuntimeException e) {
                return Optional.empty();
            }
        };
    }

    @Bean
    public LedgerService sangamLedgerService(JdbcLedgerStore store, LedgerControlStore control,
            LedgerPorts.DecisionPort decisions, ObjectProvider<ProfileResolver> profiles, Environment env) {
        LedgerService service = new LedgerService(store, control, ConfigWiring.engineClock()).withDecisions(decisions)
                .withBusinessClock(new org.sangam.config.locale.ProfileBusinessClock(profiles::getIfAvailable,
                        ConfigWiring.engineClock()));
        service.withRates(profileRates(env.getProperty("sangam.ledger.rate-scope"), profiles::getIfAvailable,
                service::today));
        // RM-04: no after-commit AuditLink or AccountingSink: the store's journal writes both with the posting
        return service;
    }

    @Bean
    public LedgerTenantDefinitions sangamLedgerDefinitions(LedgerService service,
                                                           ObjectProvider<ProfileResolver> profiles) {
        return new LedgerTenantDefinitions(service, profiles.getIfAvailable());
    }

    /** The {@code ledger} connector and its 12 pack connector ids; the GL publisher when a GL connector is set. */
    @Bean
    public InitializingBean sangamLedgerConnectorRegistration(LedgerTenantDefinitions definitions,
            ObjectProvider<ConnectorRegistry> registry, ObjectProvider<PackConnectorBindings> bindings,
            ObjectProvider<PublisherRegistry> publishers, ObjectProvider<ConnectorInvoker> invoker, Environment env) {
        return () -> {
            PublisherRegistry p = publishers.getIfAvailable();
            ConnectorInvoker inv = invoker.getIfAvailable();
            String gl = requireGlExport(env.getProperty("sangam.ledger.gl-connector"),
                    ProductionEnvironment.isProduction(env), true, p != null, inv != null);   // RM-05 before anything
            ConfigConnectorBinder binder = new ConfigConnectorBinder(registry, bindings);
            LedgerConnector connector = new LedgerConnector(definitions.ledger(), definitions.periodResolver(),
                    ConfigWiring.engineClock());
            if (binder.register(new LedgerDefiningConnector(connector, definitions))) {
                Map<String, String> ids = new LinkedHashMap<>();
                OPERATIONS.forEach(op -> ids.put("ledger." + op, op));
                LOG.info("ledger connector registered; {} pack connector ids bound", binder.bind("ledger", ids));
            }
            if (gl != null) {
                p.register(LedgerGlPublisher.DESTINATION, new LedgerGlPublisher(new ComplianceConnectors(inv,
                        Duration.ofMinutes(2)), gl, env.getProperty("sangam.ledger.gl-operation", "post")).publisher());
                LOG.info("ledger accounting entries go to the GL connector {}", gl);
            }
        };
    }
}
