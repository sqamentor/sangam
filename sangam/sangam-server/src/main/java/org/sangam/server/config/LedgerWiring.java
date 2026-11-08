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
import org.sangam.core.layer.CanonicalJson;
import org.sangam.core.layer.Outbox;
import org.sangam.integration.connector.ConnectorInvoker;
import org.sangam.integration.connector.ConnectorRegistry;
import org.sangam.integration.packs.PackConnectorBindings;
import org.sangam.integration.relay.OutboxWriter;
import org.sangam.integration.relay.PublisherRegistry;
import org.sangam.ledger.JdbcLedgerStore;
import org.sangam.ledger.LedgerConnector;
import org.sangam.ledger.LedgerControlStore;
import org.sangam.ledger.LedgerPorts;
import org.sangam.ledger.LedgerSchema;
import org.sangam.ledger.LedgerService;
import org.sangam.rest.modules.ledger.LedgerTenantDefinitions;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.RequestContext;
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
 *       ({@code sangam.ledger.rate-scope}, default {@code IN}); {@code DecisionPort} over Operaton DMN (the tenant's
 *       decision first, then the shared one); {@code AuditLink} to the class-B audit chain ({@code LEDGER_POSTED});
 *       {@code AccountingSink} to the general-ledger connector through the outbox ({@value LedgerGlPublisher#DESTINATION},
 *       only when {@code sangam.ledger.gl-connector} is set; the {@code LDG_ACCOUNTING} table always holds the
 *       entries). {@code ExternalLedgerPort} (SHADOW mode) is not bound: no FMIS connector exists yet.</li>
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

    @Bean
    public JdbcLedgerStore sangamLedgerStore(TxSupport tx, Environment env) {
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
        return new JdbcLedgerStore(tx.dataSource(), tx.dialect());
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

    @Bean
    public LedgerService sangamLedgerService(JdbcLedgerStore store, LedgerControlStore control,
            LedgerPorts.DecisionPort decisions, ObjectProvider<ProfileResolver> profiles, ObjectProvider<AuditPort> audit,
            ObjectProvider<OutboxWriter> outbox, TxSupport tx, Environment env) {
        LedgerService service = new LedgerService(store, control, ConfigWiring.engineClock()).withDecisions(decisions)
                .withBusinessClock(new org.sangam.config.locale.ProfileBusinessClock(profiles::getIfAvailable,
                        ConfigWiring.engineClock()));
        String rateScope = env.getProperty("sangam.ledger.rate-scope", "IN");
        service.withRates((from, to, rateType, asOf) -> {
            if (from.equals(to)) {
                return Optional.of(BigDecimal.ONE);
            }
            ProfileResolver r = profiles.getIfAvailable();
            if (r == null) {
                return Optional.empty();
            }
            try {
                // A missing as-of is "today" of the rate scope (its own profile zone).
                LocalDate d = asOf != null ? asOf : service.today(rateScope);
                return r.resolve(rateScope, d).locale().exchangeRates().rate(from, to, d);
            } catch (RuntimeException e) {
                return Optional.empty();
            }
        });
        service.withAudit((tenant, reference, operation, auditRef, actor, summary) -> {
            AuditPort a = audit.getIfAvailable();
            if (a == null) {
                return;
            }
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("reference", reference);
            p.put("operation", operation);
            p.put("summary", summary == null ? Map.of() : summary);
            a.record(RequestContext.system(tenant, actor), auditRef != null ? auditRef : "ledger:" + tenant,
                    "LEDGER_POSTED", p);
        });
        String gl = env.getProperty("sangam.ledger.gl-connector");
        if (gl != null && !gl.isBlank()) {
            service.withAccounting(entries -> {
                OutboxWriter w = outbox.getIfAvailable();
                if (w == null || entries.isEmpty()) {
                    return;
                }
                tx.withConnection(c -> {
                    for (LedgerPorts.AccountingEntry en : entries) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("reference", en.reference());
                        m.put("event", en.event());
                        m.put("basis", en.basis());
                        m.put("debit", en.debit());
                        m.put("credit", en.credit());
                        m.put("amount", en.amount() == null ? null : en.amount().toPlainString());
                        m.put("currency", en.currency());
                        m.put("functionalAmount", en.functionalAmount() == null ? null
                                : en.functionalAmount().toPlainString());
                        w.append(c, en.tenant(), ComplianceConnectors.id64("ledger:", en.reference()),
                                LedgerGlPublisher.EVENT_TYPE, CanonicalJson.write(m),
                                List.of(Outbox.Destination.unordered(LedgerGlPublisher.DESTINATION)));
                    }
                    return null;
                });
            });
        }
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
            ConfigConnectorBinder binder = new ConfigConnectorBinder(registry, bindings);
            LedgerConnector connector = new LedgerConnector(definitions.ledger(), definitions.periodResolver(),
                    ConfigWiring.engineClock());
            if (binder.register(new LedgerDefiningConnector(connector, definitions))) {
                Map<String, String> ids = new LinkedHashMap<>();
                OPERATIONS.forEach(op -> ids.put("ledger." + op, op));
                LOG.info("ledger connector registered; {} pack connector ids bound", binder.bind("ledger", ids));
            }
            String gl = env.getProperty("sangam.ledger.gl-connector");
            PublisherRegistry p = publishers.getIfAvailable();
            ConnectorInvoker inv = invoker.getIfAvailable();
            if (gl != null && !gl.isBlank() && p != null && inv != null) {
                p.register(LedgerGlPublisher.DESTINATION, new LedgerGlPublisher(new ComplianceConnectors(inv,
                        Duration.ofMinutes(2)), gl, env.getProperty("sangam.ledger.gl-operation", "post")).publisher());
                LOG.info("ledger accounting entries go to the GL connector {}", gl);
            }
        };
    }
}
