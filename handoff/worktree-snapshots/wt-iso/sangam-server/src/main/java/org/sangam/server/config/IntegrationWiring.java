// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import javax.sql.DataSource;

import org.operaton.bpm.engine.ProcessEngine;
import org.sangam.core.api.EngineAdapter;
import org.sangam.core.api.ExpiryPolicy;
import org.sangam.core.engine.EngineEventSink;
import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.OperationLedger;
import org.sangam.core.layer.Outbox;
import org.sangam.core.layer.ReconciliationQueue;
import org.sangam.core.layer.Tombstones;
import org.sangam.core.layer.Transactions;
import org.sangam.core.ports.UniqueStartPort;
import org.sangam.integration.IntegrationMaintenance;
import org.sangam.integration.bridge.ConnectorExternalTaskWorker;
import org.sangam.integration.bridge.TopicBinding;
import org.sangam.integration.bridge.WorkContextResolver;
import org.sangam.integration.bridge.operaton.IntegrationBridge;
import org.sangam.integration.bridge.operaton.OperatonHeldWorkReleaser;
import org.sangam.integration.bridge.operaton.OperatonLifecyclePorts;
import org.sangam.integration.bridge.operaton.OperatonMessageCorrelationPort;
import org.sangam.integration.bridge.operaton.OutboxHistoryEventHandler;
import org.sangam.integration.bridge.operaton.SangamIntegrationEnginePlugin;
import org.sangam.integration.connector.ConnectorCallJournal;
import org.sangam.integration.connector.ConnectorInvoker;
import org.sangam.integration.connector.ConnectorRegistry;
import org.sangam.integration.connector.Connectors;
import org.sangam.integration.connector.gov.DltRegistry;
import org.sangam.integration.inbox.CorrelatingInboxHandler;
import org.sangam.integration.inbox.EventDefinitionPolicy;
import org.sangam.integration.inbox.InboxService;
import org.sangam.integration.inbox.UniqueStartGuard;
import org.sangam.integration.ledger.LedgerExpiryPolicy;
import org.sangam.integration.ledger.OperationLedgerService;
import org.sangam.integration.lifecycle.ErasureService;
import org.sangam.integration.lifecycle.InstanceLifecycleService;
import org.sangam.integration.lifecycle.LegalHoldStore;
import org.sangam.integration.lifecycle.LifecyclePorts;
import org.sangam.integration.packs.IntegrationConnectorGateway;
import org.sangam.integration.packs.PackConnectorBindings;
import org.sangam.integration.packs.PackDispatchPublisher;
import org.sangam.integration.packs.PackMessages;
import org.sangam.integration.packs.PackOperationStore;
import org.sangam.integration.packs.PackReconciliationHooks;
import org.sangam.integration.packs.PacksIntegration;
import org.sangam.integration.reconciliation.ReconciliationEngine;
import org.sangam.integration.reconciliation.ReconciliationTaskPort;
import org.sangam.integration.relay.BackpressureGate;
import org.sangam.integration.relay.CircuitBreaker;
import org.sangam.integration.relay.DeadLetterStore;
import org.sangam.integration.relay.LeaderElection;
import org.sangam.integration.relay.OutboxCapacityMonitor;
import org.sangam.integration.relay.OutboxRelayService;
import org.sangam.integration.relay.OutboxRetention;
import org.sangam.integration.relay.OutboxWriter;
import org.sangam.integration.relay.PublisherRegistry;
import org.sangam.integration.relay.WakeupNotifier;
import org.sangam.integration.sim.SimulatorProfile;
import org.sangam.rest.support.RestProperties;
import org.sangam.rest.support.TxSupport;
import org.sangam.security.SangamSecurity;
import org.sangam.server.bridge.IntegrationBackpressureFilter;
import org.sangam.server.bridge.IntegrationLifecycleAuthorizer;
import org.sangam.server.bridge.IntegrationServices;
import org.sangam.server.bridge.IntegrationUniqueStartPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * sangam-integration wiring (sangam-integration README §4 "Wiring for sangam-server"; deploy README §10).
 *
 * <p><b>Transactions.</b> The inbox, lifecycle services and the packs' connector gateway run in the engine
 * transaction ({@code Transactions.of(engineAdapter)}; without sangam-engine, a Spring transaction that the engine
 * joins), so correlations, cancels and product rows commit together. Relay, capacity monitor, dead letters, ledger
 * service, maintenance and the delegate invoker use their own short transactions on the pool
 * ({@code Transactions.of(dataSource)}), so an intent commits before the remote call.
 *
 * <p><b>History to outbox.</b> {@code sangam.history.outbox-handler} = {@code integration} (default: integration's
 * {@code OutboxHistoryEventHandler} is the only writer of history events to the outbox), {@code engine}
 * (sangam-engine's {@code EngineEventSink} → outbox writer instead) or {@code none}. sangam-flowable-ports'
 * {@code PortsHistoryEventHandler} feeds only the read-model projection ({@code sangam.history.read-model-projection},
 * read by the ports wiring), so no event is published twice.
 *
 * <p><b>Not registered:</b> integration's {@code IdempotencyServletFilter} (sangam-rest's {@code IdempotencyFilter}
 * owns R41 on the same core store) and therefore {@code IdempotencyService}/{@code ReplayAuthorizer}.
 * Disable the module with {@code sangam.modules.integration.enabled=false}.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "sangam.modules.integration.enabled", havingValue = "true", matchIfMissing = true)
public class IntegrationWiring {

    private static final Logger LOG = LoggerFactory.getLogger(IntegrationWiring.class);

    // ---------------------------------------------------------------- shared primitives

    private static Transactions productTx(DataSource ds) {
        return Transactions.of(ds);
    }

    /** The engine transaction: sangam-engine's adapter (reuses an active command context, note 10) or Spring's. */
    private static Transactions engineTx(ObjectProvider<EngineAdapter> adapter, TxSupport tx) {
        EngineAdapter a = adapter.getIfAvailable();
        if (a != null) {
            return Transactions.of(a);
        }
        return new Transactions() {
            @Override
            public <T> T inTransaction(Function<java.sql.Connection, T> work) {
                return tx.withConnection(work);
            }
        };
    }

    private static String defaultTenant(Environment env) {
        String t = env.getProperty("sangam.tenant.default");
        return t == null || t.isBlank() ? "default" : t;
    }

    @Bean
    public AuditChain sangamIntegrationAuditChain(TxSupport tx) {
        return new AuditChain(tx.dialect());
    }

    @Bean
    public ReconciliationQueue sangamReconciliationQueue(TxSupport tx, AuditChain sangamIntegrationAuditChain) {
        return new ReconciliationQueue(tx.dialect(), sangamIntegrationAuditChain);
    }

    @Bean
    public ConnectorCallJournal sangamConnectorCallJournal(TxSupport tx) {
        return new ConnectorCallJournal(tx.dialect());
    }

    @Bean
    public OperationLedger sangamOperationLedger(TxSupport tx, ReconciliationQueue queue) {
        return OperationLedgerService.create(tx.dialect(), queue);
    }

    @Bean
    public OperationLedgerService sangamOperationLedgerService(TxSupport tx, DataSource ds, OperationLedger ledger,
                                                               ConnectorCallJournal journal) {
        return new OperationLedgerService(tx.dialect(), productTx(ds), ledger, journal);
    }

    /** A-10: lease expiry decided against the ledger (consumed by sangam-engine's settings before the engine starts). */
    @Bean
    public ExpiryPolicy sangamLedgerExpiryPolicy(OperationLedgerService ledgerService) {
        return new LedgerExpiryPolicy(ledgerService);
    }

    @Bean
    public DeadLetterStore sangamDeadLetterStore(TxSupport tx, DataSource ds) {
        return new DeadLetterStore(tx.dialect(), productTx(ds));
    }

    @Bean
    public OutboxCapacityMonitor sangamOutboxCapacityMonitor(TxSupport tx, DataSource ds, Environment env) {
        long maxBytes = env.getProperty("sangam.integration.outbox.max-bytes", Long.class, 2L * 1024 * 1024 * 1024);
        return new OutboxCapacityMonitor(tx.dialect(), productTx(ds), maxBytes,
                env.getProperty("sangam.integration.outbox.rate-window", Duration.class, Duration.ofMinutes(5)));
    }

    @Bean
    public BackpressureGate sangamBackpressureGate(OutboxCapacityMonitor monitor) {
        return new BackpressureGate(monitor);
    }

    // ---------------------------------------------------------------- outbox

    /**
     * Destinations every outbox event is delivered to, from {@code sangam.integration.default-destinations}
     * ({@code name} or {@code name:ordered}); module subscriptions (channels, read-model projector) register their own.
     */
    @Bean
    public OutboxWriter sangamOutboxWriter(TxSupport tx, Environment env) {
        List<Outbox.Destination> destinations = new ArrayList<>();
        for (String spec : env.getProperty("sangam.integration.default-destinations", String[].class, new String[0])) {
            if (spec.isBlank()) {
                continue;
            }
            destinations.add(spec.endsWith(":ordered")
                    ? Outbox.Destination.ordered(spec.substring(0, spec.length() - ":ordered".length()))
                    : Outbox.Destination.unordered(spec));
        }
        List<Outbox.Destination> fixed = List.copyOf(destinations);
        // SCALE IR-3: in a cell with a roll-up ingest URL, history, cross-cell and roll-up metric events also go to the
        // national roll-up stream, unordered (the roll-up projector is idempotent per event id; RollupPublisher
        // forwards only its event-type allow-list).
        boolean rollup = !env.getProperty("sangam.cell.id", "").isBlank()
                && !env.getProperty("sangam.cell.rollup.ingest-url", "").isBlank()
                && fixed.stream().noneMatch(d -> d.name().equals(org.sangam.cell.rollup.RollupPublisher.DESTINATION));
        if (!rollup) {
            return new OutboxWriter(tx.dialect(), OutboxWriter.SubscriptionResolver.fixed(fixed));
        }
        List<Outbox.Destination> withRollup = new ArrayList<>(fixed);
        withRollup.add(Outbox.Destination.unordered(org.sangam.cell.rollup.RollupPublisher.DESTINATION));
        List<Outbox.Destination> rollupSet = List.copyOf(withRollup);
        return new OutboxWriter(tx.dialect(), (tenant, eventType) -> eventType != null
                && (eventType.startsWith("sangam.history.") || eventType.startsWith("sangam.xcell.")
                || eventType.startsWith("sangam.rollup.")) ? rollupSet : fixed);
    }

    @Bean
    public PublisherRegistry sangamPublisherRegistry() {
        return new PublisherRegistry();
    }

    /** History events → outbox, through integration's handler (the default). */
    @Bean
    public SangamIntegrationEnginePlugin sangamIntegrationEnginePlugin(OutboxWriter writer, Environment env) {
        String handler = env.getProperty("sangam.history.outbox-handler", "integration");
        return new SangamIntegrationEnginePlugin("integration".equalsIgnoreCase(handler)
                ? new OutboxHistoryEventHandler(writer, defaultTenant(env)) : null);
    }

    /** Only with {@code sangam.history.outbox-handler=engine}: sangam-engine's typed events → outbox. */
    @Bean
    @ConditionalOnProperty(name = "sangam.history.outbox-handler", havingValue = "engine")
    public EngineEventSink sangamEngineEventSink(OutboxWriter writer) {
        return writer::append;
    }

    // ---------------------------------------------------------------- connectors

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "sangam.integration.simulators.enabled", havingValue = "true")
    public SimulatorProfile sangamSimulatorProfile() {
        return new SimulatorProfile();
    }

    /**
     * Connector catalogue. With {@code sangam.integration.simulators.enabled=true} (dev) the simulator catalogue;
     * otherwise empty until connectors are registered (air-gapped default: no outbound calls unless configured, §1.9).
     */
    @Bean
    public ConnectorRegistry sangamConnectorRegistry(ObjectProvider<SimulatorProfile> sims, Environment env) {
        SimulatorProfile profile = sims.getIfAvailable();
        if (profile != null) {
            LOG.warn("Connector catalogue: SIMULATORS (development)");
            return Connectors.simulatorRegistry(profile, new DltRegistry(), defaultTenant(env));
        }
        return new ConnectorRegistry();
    }

    /** The invoker used by delegates: own transaction, so the intent commits before the remote call. */
    @Bean
    public ConnectorInvoker sangamConnectorInvoker(DataSource ds, OperationLedger ledger, ConnectorCallJournal journal,
                                                   ConnectorRegistry registry, AuditChain sangamIntegrationAuditChain) {
        return new ConnectorInvoker(productTx(ds), ledger, journal, registry, sangamIntegrationAuditChain);
    }

    @Bean
    public IntegrationBridge sangamIntegrationBridge(ConnectorInvoker invoker, OutboxWriter writer, Environment env) {
        IntegrationBridge bridge = new IntegrationBridge(invoker, writer, defaultTenant(env));
        IntegrationBridge.install(bridge);
        return bridge;
    }

    // ---------------------------------------------------------------- inbox

    @Bean
    public UniqueStartGuard sangamUniqueStartGuard(TxSupport tx) {
        return new UniqueStartGuard(tx.dialect());
    }

    /** For sangam-flowable-ports' {@code EventDispatcher} (FLOWABLE-INTO-OPERATON FC-04). */
    @Bean
    public UniqueStartPort sangamUniqueStartPort(UniqueStartGuard guard) {
        return new IntegrationUniqueStartPort(guard);
    }

    @Bean
    public EventDefinitionPolicy.Registry sangamEventDefinitionPolicies() {
        return new EventDefinitionPolicy.Registry();
    }

    @Bean(destroyMethod = "")
    public InboxService sangamInboxService(TxSupport tx, ObjectProvider<EngineAdapter> adapter, ProcessEngine engine,
                                           AuditChain sangamIntegrationAuditChain, EventDefinitionPolicy.Registry policies,
                                           UniqueStartGuard guard, BackpressureGate gate, DeadLetterStore deadLetters,
                                           RestProperties rest) {
        return new InboxService(tx.dialect(), engineTx(adapter, tx), InboxService.Settings.defaults(rest.getNode()),
                sangamIntegrationAuditChain, policies,
                new CorrelatingInboxHandler(new OperatonMessageCorrelationPort(engine), policies, guard), gate,
                deadLetters);
    }

    // ---------------------------------------------------------------- lifecycle (VI.5)

    @Bean
    public LifecyclePorts.Authorizer sangamLifecycleAuthorizer(ObjectProvider<SangamSecurity> security) {
        SangamSecurity s = security.getIfAvailable();
        if (s == null) {
            // Without sangam-security the REST layer's endpoint permissions are the only check.
            return (c, tenant, principal, permission, instanceId) -> {
            };
        }
        LifecyclePorts.Authorizer delegate = new IntegrationLifecycleAuthorizer(s);
        // sangam-compliance's retention scheduler erases as principal "system:retention" (DPDP s.8(7) erasure on
        // purpose completion): it holds ERASE_DATA and nothing else beyond what the delegate already allows.
        return (c, tenant, principal, permission, instanceId) -> {
            if (RETENTION_PRINCIPAL.equals(principal) && "ERASE_DATA".equalsIgnoreCase(permission)) {
                return;
            }
            delegate.require(c, tenant, principal, permission, instanceId);
        };
    }

    /** The compliance retention scheduler's principal (sangam-compliance README §2). */
    public static final String RETENTION_PRINCIPAL = "system:retention";

    @Bean
    public OperatonLifecyclePorts sangamOperatonLifecyclePorts(ProcessEngine engine) {
        return new OperatonLifecyclePorts(engine);
    }

    @Bean
    public InstanceLifecycleService sangamInstanceLifecycleService(TxSupport tx, ObjectProvider<EngineAdapter> adapter,
            ReconciliationQueue queue, AuditChain sangamIntegrationAuditChain, OperationLedgerService ledger,
            OperatonLifecyclePorts ports, LifecyclePorts.Authorizer authorizer, BackpressureGate gate) {
        Dialect d = tx.dialect();
        return new InstanceLifecycleService(d, engineTx(adapter, tx),
                new Tombstones(d, queue, sangamIntegrationAuditChain), sangamIntegrationAuditChain, ledger, ports, ports,
                authorizer, gate);
    }

    @Bean
    public ErasureService sangamErasureService(TxSupport tx, ObjectProvider<EngineAdapter> adapter,
            AuditChain sangamIntegrationAuditChain, ConnectorCallJournal journal, DeadLetterStore deadLetters,
            OperatonLifecyclePorts ports, ObjectProvider<LifecyclePorts.DocumentEraser> documents,
            LifecyclePorts.Authorizer authorizer) {
        Dialect d = tx.dialect();
        return new ErasureService(d, engineTx(adapter, tx), sangamIntegrationAuditChain, new LegalHoldStore(d), journal,
                deadLetters, ports, ports, documents.getIfAvailable(() -> LifecyclePorts.DocumentEraser.NONE), authorizer);
    }

    // ---------------------------------------------------------------- relay, reconciliation, maintenance, worker

    @Bean(destroyMethod = "")
    public OutboxRelayService sangamOutboxRelayService(TxSupport tx, DataSource ds, PublisherRegistry publishers,
            AuditChain sangamIntegrationAuditChain, DeadLetterStore deadLetters, RestProperties rest) {
        Transactions ptx = productTx(ds);
        return new OutboxRelayService(tx.dialect(), ptx, publishers, OutboxRelayService.Settings.defaults(rest.getNode()),
                sangamIntegrationAuditChain, deadLetters, CircuitBreaker.defaults(),
                WakeupNotifier.postgres(tx.dialect(), ptx, "sangam_outbox"));
    }

    @Bean(destroyMethod = "")
    public ReconciliationEngine sangamReconciliationEngine(TxSupport tx, DataSource ds, ReconciliationQueue queue,
            OperationLedger ledger, ConnectorCallJournal journal, ConnectorRegistry registry, ProcessEngine engine,
            ObjectProvider<ReconciliationTaskPort> humans, RestProperties rest) {
        return new ReconciliationEngine(tx.dialect(), productTx(ds), queue, ledger, journal, registry,
                humans.getIfAvailable(), new OperatonHeldWorkReleaser(engine),
                ReconciliationEngine.Settings.defaults(rest.getNode()));
    }

    @Bean(destroyMethod = "")
    public IntegrationMaintenance sangamIntegrationMaintenance(TxSupport tx, DataSource ds, OutboxCapacityMonitor monitor,
            InstanceLifecycleService lifecycle, RestProperties rest, Environment env) {
        Transactions ptx = productTx(ds);
        Duration keep = env.getProperty("sangam.integration.outbox.retention", Duration.class, Duration.ofDays(7));
        return new IntegrationMaintenance(new LeaderElection(tx.dialect(), ptx, rest.getNode(), Duration.ofSeconds(30)),
                monitor, new OutboxRetention(tx.dialect(), ptx, keep, 1000), null, lifecycle);
    }

    /**
     * The connector external-task worker (one per worker node), when sangam-engine's adapter is present and topics are
     * bound with {@code sangam.integration.topics} entries {@code <topic>=<connectorId>:<operation>}.
     */
    @Bean(destroyMethod = "")
    @ConditionalOnProperty(name = "sangam.integration.worker.enabled", havingValue = "true", matchIfMissing = true)
    public IntegrationWorkerHolder sangamConnectorWorker(ObjectProvider<EngineAdapter> adapter,
            ConnectorInvoker invoker, InstanceLifecycleService lifecycle, RestProperties rest, Environment env) {
        EngineAdapter a = adapter.getIfAvailable();
        String[] specs = java.util.Arrays.stream(env.getProperty("sangam.integration.topics", String[].class,
                new String[0])).filter(s -> s != null && !s.isBlank()).toArray(String[]::new);
        if (a == null || specs.length == 0) {
            return new IntegrationWorkerHolder(null);
        }
        ConnectorExternalTaskWorker worker = new ConnectorExternalTaskWorker(a, invoker,
                WorkContextResolver.fromVariables(defaultTenant(env)), lifecycle, rest.getNode() + "-connectors", 10,
                Duration.ofSeconds(2));
        for (String spec : specs) {
            int eq = spec.indexOf('=');
            int colon = spec.indexOf(':', eq + 1);
            if (eq <= 0) {
                throw new IllegalStateException("sangam.integration.topics entry must be <topic>=<connector>:<operation>: "
                        + spec);
            }
            String topic = spec.substring(0, eq).trim();
            String connector = colon < 0 ? spec.substring(eq + 1).trim() : spec.substring(eq + 1, colon).trim();
            String operation = colon < 0 ? null : spec.substring(colon + 1).trim();
            worker.bind(TopicBinding.of(topic, connector, operation));
        }
        return new IntegrationWorkerHolder(worker);
    }

    /** Nullable holder (a {@code @Bean} method may not return null). */
    public record IntegrationWorkerHolder(ConnectorExternalTaskWorker worker) {
    }

    // ---------------------------------------------------------------- packs (sangam-packs README §8)

    @Bean
    public PackOperationStore sangamPackOperationStore(TxSupport tx) {
        return new PackOperationStore(tx.dialect());
    }

    @Bean
    public PackBindingsProperties sangamPackBindingsProperties() {
        return new PackBindingsProperties();
    }

    /**
     * Pack connector → integration connector bindings: the government defaults, the capability classes declared by
     * the discovered packs, then the site's {@code sangam.packs.bindings.*} (with payload mappers), which win.
     */
    @Bean
    public PackConnectorBindings sangamPackConnectorBindings(
            @org.springframework.beans.factory.annotation.Qualifier("sangamPackBindingsProperties")
            PackBindingsProperties properties,
            ObjectProvider<org.sangam.packs.spi.PackRegistry> packs) {
        PackConnectorBindings bindings = PackConnectorBindings.governmentDefaults();
        org.sangam.packs.spi.PackRegistry registry = packs.getIfAvailable();
        if (registry != null) {
            bindings.declareFrom(registry.all());
        }
        properties.getBindings().forEach((packConnector, spec) -> {
            if (spec.getConnector() == null || spec.getConnector().isBlank()) {
                throw new IllegalStateException("sangam.packs.bindings." + packConnector + ".connector is required");
            }
            bindings.bind(packConnector, spec.getConnector(), spec.getOperation(), spec.mapper());
        });
        return bindings;
    }

    /**
     * packs' {@code port.ConnectorGateway}: outbox in the caller's (engine) transaction, relay delivers the result.
     * Unbound pack connectors fall back to the packs' simulator only when {@code sangam.packs.simulator-fallback} is
     * true (default: the h2 profile); otherwise they fail with {@code CONNECTOR_NOT_CONFIGURED}.
     */
    @Bean
    public IntegrationConnectorGateway sangamIntegrationConnectorGateway(TxSupport tx, ObjectProvider<EngineAdapter> adapter,
            ProcessEngine engine, PackOperationStore store, PackConnectorBindings bindings, ConnectorRegistry registry,
            ConnectorCallJournal journal, ReconciliationQueue queue, OutboxWriter writer, BackpressureGate gate,
            @org.springframework.beans.factory.annotation.Qualifier("sangamPackBindingsProperties")
            PackBindingsProperties properties, Environment env) {
        boolean fallback = properties.getSimulatorFallback() != null ? properties.getSimulatorFallback()
                : java.util.Arrays.asList(env.getActiveProfiles()).contains("h2");
        if (fallback && java.util.Arrays.stream(env.getActiveProfiles()).anyMatch(p -> p.contains("prod"))) {
            throw new IllegalStateException("sangam.packs.simulator-fallback must not be enabled on a production profile");
        }
        return new IntegrationConnectorGateway(engineTx(adapter, tx), engine, store, bindings, registry, journal, queue,
                writer, gate, fallback ? new org.sangam.packs.sim.SimulatorConnectorGateway() : null);
    }

    @Bean
    public PackMessages sangamPackMessages(InboxService inbox) {
        return new PackMessages(inbox);
    }

    @Bean
    public PackDispatchPublisher sangamPackDispatchPublisher(DataSource ds, PackOperationStore store,
            PackConnectorBindings bindings, ConnectorInvoker invoker, ConnectorCallJournal journal,
            OperationLedgerService ledger, PackMessages messages) {
        return new PackDispatchPublisher(productTx(ds), store, bindings, invoker, journal, ledger, messages, 5,
                Duration.ofSeconds(30));
    }

    @Bean
    public PackReconciliationHooks sangamPackReconciliationHooks(DataSource ds, PackOperationStore store,
            IntegrationConnectorGateway gateway, PackMessages messages) {
        return new PackReconciliationHooks(productTx(ds), store, gateway, messages);
    }

    /**
     * {@code PacksIntegration.wire}: relay publisher for pack dispatches, inbox handlers for the pack result messages,
     * reconciliation hooks, and {@code PackRuntime.install(PackRuntime.ports().withGateway(gateway))}. The packs wiring
     * (WS-PACKS ports: seats, calendar, audit, batches, ledgers) must install on top of {@code PackRuntime.ports()},
     * after this bean, so the gateway is kept.
     */
    @Bean
    public InitializingBean sangamPacksIntegration(ProcessEngine engine, InboxService inbox, PublisherRegistry publishers,
            ReconciliationEngine reconciliation, IntegrationConnectorGateway gateway, PackDispatchPublisher dispatcher,
            PackReconciliationHooks hooks) {
        return () -> PacksIntegration.wire(engine, inbox, publishers, reconciliation, gateway, dispatcher, hooks);
    }

    // ---------------------------------------------------------------- start / stop, back-pressure, reconcile-on-start

    @Bean
    public IntegrationServices sangamIntegrationServices(InboxService inbox, OutboxRelayService relay,
            ReconciliationEngine reconciliation, IntegrationMaintenance maintenance,
            ObjectProvider<IntegrationWorkerHolder> worker, Environment env) {
        IntegrationWorkerHolder holder = worker.getIfAvailable();
        return new IntegrationServices(inbox, relay, reconciliation, maintenance, holder == null ? null : holder.worker(),
                env.getProperty("sangam.integration.capacity-every", Duration.class, Duration.ofSeconds(15)),
                env.getProperty("sangam.integration.maintenance-every", Duration.class, Duration.ofMinutes(1)),
                env.getProperty("sangam.integration.background.enabled", Boolean.class, true));
    }

    @Bean
    public FilterRegistrationBean<IntegrationBackpressureFilter> sangamBackpressureFilter(BackpressureGate gate) {
        FilterRegistrationBean<IntegrationBackpressureFilter> reg =
                new FilterRegistrationBean<>(new IntegrationBackpressureFilter(gate));
        reg.addUrlPatterns("/api/v1/*");
        // after Spring Security (-100), before sangam-rest's Idempotency-Key filter (100)
        reg.setOrder(50);
        reg.setName("sangamBackpressureFilter");
        return reg;
    }

    /** {@code SANGAM_RECONCILE_ON_START=true}: one reconciliation pass at start-up (deploy README §4.3). */
    @Bean
    @ConditionalOnProperty(name = "sangam.reconcile-on-start", havingValue = "true")
    public ApplicationRunner sangamReconcileOnStart(ReconciliationEngine reconciliation) {
        return args -> LOG.info("Reconciliation pass at start-up: {}", reconciliation.runOnce());
    }
}
