// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.util.DriverDataSource;

import org.operaton.bpm.engine.ProcessEngine;
import org.sangam.core.api.EngineAdapter;
import org.sangam.core.api.ExpiryPolicy;
import org.sangam.core.engine.EngineEventSink;
import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.OperationLedger;
import org.sangam.core.layer.Outbox;
import org.sangam.core.layer.PendingAudit;
import org.sangam.core.layer.PendingAuditDrainer;
import org.sangam.core.layer.ReconciliationQueue;
import org.sangam.core.layer.Tombstones;
import org.sangam.core.layer.Transactions;
import org.sangam.core.ports.UniqueStartPort;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.integration.IntegrationMaintenance;
import org.sangam.integration.bridge.ConnectorExternalTaskWorker;
import org.sangam.integration.broker.BrokerWakeupBus;
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
import org.sangam.integration.connector.gov.DltRegistrationStore;
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
import org.sangam.integration.relay.WakeupListener;
import org.sangam.integration.relay.WakeupNotifier;
import org.sangam.integration.sim.SimulatorProfile;
import org.sangam.rest.support.RestProperties;
import org.sangam.rest.support.TxSupport;
import org.sangam.rest.support.WorkLongPoll;
import org.sangam.security.SangamSecurity;
import org.sangam.security.tenant.TenantContext;
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
        return AttributionTenant.resolve(env); // SL-86: the one attribution-tenant resolver
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
        // M-057: the configured external history projection sinks (HistoryProjectionWiring) receive sangam.history.* only
        return new OutboxWriter(tx.dialect(), subscriptions(fixed, rollup, HistoryProjectionWiring.destinations(env)));
    }

    /**
     * Every event goes to {@code fixed}; history, cross-cell and roll-up events also to the roll-up stream when
     * {@code rollup}; history events also to the external projection sinks.
     */
    static OutboxWriter.SubscriptionResolver subscriptions(List<Outbox.Destination> fixed, boolean rollup,
                                                           List<Outbox.Destination> projection) {
        if (!rollup && projection.isEmpty()) {
            return OutboxWriter.SubscriptionResolver.fixed(fixed);
        }
        List<Outbox.Destination> rollupSet = rollup
                ? merge(fixed, List.of(Outbox.Destination.unordered(org.sangam.cell.rollup.RollupPublisher.DESTINATION)))
                : fixed;
        List<Outbox.Destination> historySet = merge(rollupSet, projection);
        return (tenant, eventType) -> eventType == null ? fixed
                : eventType.startsWith(HistoryProjectionWiring.HISTORY_TYPES) ? historySet
                : eventType.startsWith("sangam.xcell.") || eventType.startsWith("sangam.rollup.") ? rollupSet
                : fixed;
    }

    /** {@code a} then the destinations of {@code b} not already named in {@code a}. */
    static List<Outbox.Destination> merge(List<Outbox.Destination> a, List<Outbox.Destination> b) {
        List<Outbox.Destination> out = new ArrayList<>(a);
        for (Outbox.Destination d : b) {
            if (out.stream().noneMatch(x -> x.name().equals(d.name()))) {
                out.add(d);
            }
        }
        return List.copyOf(out);
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
     * Decision 305: the DLT SMS connector validates against the stored per-tenant registration ({@link DltWiring}).
     */
    @Bean
    public ConnectorRegistry sangamConnectorRegistry(ObjectProvider<SimulatorProfile> sims, Environment env,
                                                     DltRegistrationStore sangamDltRegistrationStore) {
        SimulatorProfile profile = sims.getIfAvailable();
        if (profile != null) {
            LOG.warn("Connector catalogue: SIMULATORS (development)");
            return Connectors.simulatorRegistry(profile, sangamDltRegistrationStore.registry(), defaultTenant(env));
        }
        return new ConnectorRegistry();
    }

    /** The invoker used by delegates: own transaction, so the intent commits before the remote call. */
    @Bean
    public ConnectorInvoker sangamConnectorInvoker(DataSource ds, OperationLedger ledger, ConnectorCallJournal journal,
                                                   ConnectorRegistry registry, AuditChain sangamIntegrationAuditChain) {
        return new ConnectorInvoker(productTx(ds), ledger, journal, registry, sangamIntegrationAuditChain);
    }

    /**
     * SL-133 (M-003): the durable pending audit ({@code AUDIT_PENDING}) of callers that hold the root's
     * {@code ROOT_SEQ} lock. Plain product transactions, never the engine transaction.
     */
    @Bean
    public PendingAudit sangamPendingAudit(TxSupport tx, DataSource ds, AuditChain sangamIntegrationAuditChain) {
        return new PendingAudit(productTx(ds), tx.dialect(), sangamIntegrationAuditChain);
    }

    /**
     * SL-133: appends the pending rows to the root chains on its own threads, after the job transaction that holds the
     * root lock has ended, plus a periodic sweep. {@code sangam.audit.pending.workers} (2),
     * {@code .sweep-interval} (30s; 0 = no sweep), {@code .sweep-min-age} (10s), {@code .sweep-batch} (200).
     */
    @Bean(destroyMethod = "close")
    public PendingAuditDrainer sangamPendingAuditDrainer(PendingAudit sangamPendingAudit, Environment env) {
        return new PendingAuditDrainer(sangamPendingAudit, pendingAuditSettings(env)).start();
    }

    static PendingAuditDrainer.Settings pendingAuditSettings(Environment env) {
        PendingAuditDrainer.Settings d = PendingAuditDrainer.Settings.defaults();
        return new PendingAuditDrainer.Settings(
                env.getProperty("sangam.audit.pending.workers", Integer.class, d.workers()),
                env.getProperty("sangam.audit.pending.sweep-interval", Duration.class, d.sweepInterval()),
                env.getProperty("sangam.audit.pending.sweep-min-age", Duration.class, d.sweepMinAge()),
                env.getProperty("sangam.audit.pending.sweep-batch", Integer.class, d.sweepBatch()));
    }

    /**
     * The JavaDelegate path ({@code SangamConnectorDelegate} through {@link IntegrationBridge#delegateInvoker()}) runs
     * inside the job transaction that holds the root's lock, so its invoker defers the audit (SL-133): pending rows
     * written with the ledger, appended by the drainer. The other users of {@link ConnectorInvoker} keep direct audit.
     */
    @Bean
    public IntegrationBridge sangamIntegrationBridge(ConnectorInvoker invoker, PendingAuditDrainer sangamPendingAuditDrainer,
                                                     OutboxWriter writer, Environment env) {
        IntegrationBridge bridge = new IntegrationBridge(invoker.deferringAudit(sangamPendingAuditDrainer), writer,
                defaultTenant(env));
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
            LifecyclePorts.Authorizer authorizer, Environment env) {
        Dialect d = tx.dialect();
        // decisions 143, 152: never the no-op document eraser outside a development environment (start refused)
        return new ErasureService(d, engineTx(adapter, tx), sangamIntegrationAuditChain, new LegalHoldStore(d), journal,
                deadLetters, ports, ports, DocumentEraserRequirement.require(documents.getIfAvailable(), env), authorizer,
                denialChains());
    }

    /**
     * SL-132: an erased instance's denial audits sit on its denial chain ({@code sec:d:...}, the one formula of
     * sangam-core's {@link AuditChain#denialChain}), so erasure covers that chain with the instance's own.
     * {@code cmp:d:} chains are per principal and hold no personal data of the instance.
     */
    static LifecyclePorts.RelatedAuditChains denialChains() {
        return (tenant, chain) -> List.of(AuditChain.denialChain(tenant, chain));
    }

    // ---------------------------------------------------------------- relay, reconciliation, maintenance, worker

    @Bean(destroyMethod = "")
    public OutboxRelayService sangamOutboxRelayService(TxSupport tx, DataSource ds, PublisherRegistry publishers,
            AuditChain sangamIntegrationAuditChain, DeadLetterStore deadLetters, RestProperties rest,
            WakeupNotifier sangamWakeupNotifier) {
        return new OutboxRelayService(tx.dialect(), productTx(ds), publishers, OutboxRelayService.Settings.defaults(rest.getNode()),
                sangamIntegrationAuditChain, deadLetters, CircuitBreaker.defaults(), sangamWakeupNotifier);
    }

    /**
     * The one wake-up hint sender: the relay after a cycle that delivered, and the M-066 work announcer
     * ({@code WorkAnnounceWiring}) after a command that created external work. Never throws.
     */
    @Bean
    public WakeupNotifier sangamWakeupNotifier(TxSupport tx, DataSource ds, WakeupListener sangamWakeupListener,
            ObjectProvider<BrokerWakeupBus> brokerWakeup) {
        // M-005: PostgreSQL notifies every node (LISTEN in sangamWakeupListener); the other dialects wake this node only
        WakeupNotifier wakeup = sangamWakeupListener.usesListen()
                ? WakeupNotifier.postgres(tx.dialect(), productTx(ds), WakeupNotifier.CHANNEL)
                : sangamWakeupListener.inJvmNotifier();
        // M-058: opt-in broker bus (BrokerWakeupWiring) wakes the other nodes where the database has no LISTEN
        BrokerWakeupBus bus = brokerWakeup.getIfAvailable();
        return bus == null ? wakeup : wakeup.andThen(bus);
    }

    // ---------------------------------------------------------------- wake-up and worker long-poll (M-005, M-053)

    /**
     * M-005: the receiving side of the relay wake-up. On PostgreSQL a {@code LISTEN} on its own physical connection,
     * opened with the pool's driver settings but outside the pool (a pooled connection would be held forever and would
     * carry the LISTEN session state to its next borrower); reconnects with back-off. Other dialects, or a pool that is
     * not HikariCP: in-JVM only. Always a hint: the subscribers keep their 1 s poll (R1).
     */
    @Bean(initMethod = "start", destroyMethod = "close")
    public WakeupListener sangamWakeupListener(TxSupport tx, DataSource ds) {
        WakeupListener.ConnectionSource source = tx.dialect() == Dialect.POSTGRESQL ? listenerConnections(ds) : null;
        if (tx.dialect() == Dialect.POSTGRESQL && source == null) {
            LOG.warn("M-005: the data source is not a HikariCP pool with a JDBC URL; the relay wake-up is in-JVM only "
                    + "(other nodes' long-polls use their 1 s poll)");
        }
        return WakeupListener.forDialect(tx.dialect(), source, WakeupNotifier.CHANNEL);
    }

    /** A source of physical connections with the pool's own URL, driver, credentials and driver properties. */
    static WakeupListener.ConnectionSource listenerConnections(DataSource ds) {
        HikariDataSource pool;
        try {
            pool = ds instanceof HikariDataSource h ? h
                    : ds.isWrapperFor(HikariDataSource.class) ? ds.unwrap(HikariDataSource.class) : null;
        } catch (java.sql.SQLException e) {
            pool = null;
        }
        if (pool == null || pool.getJdbcUrl() == null || pool.getJdbcUrl().isBlank()) {
            return null;
        }
        java.util.Properties props = new java.util.Properties();
        props.putAll(pool.getDataSourceProperties());
        DriverDataSource physical = new DriverDataSource(pool.getJdbcUrl(), pool.getDriverClassName(), props,
                pool.getUsername(), pool.getPassword());
        return physical::getConnection;
    }

    /**
     * M-053: the worker long-poll without a parked request thread ({@code /api/v1/work/fetch-and-lock}), woken by
     * {@link #sangamWakeupListener}. {@code sangam.work.long-poll.max-waiters} bounds the waiting requests per node
     * (above it a request waits in its thread, as before), {@code poll-threads} the threads that run the polls. The
     * request's tenant identity is carried to the poll thread explicitly. {@code enabled=false} keeps the blocking wait.
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "sangam.work.long-poll.enabled", havingValue = "true", matchIfMissing = true)
    public WorkLongPoll sangamWorkLongPoll(Environment env, WakeupListener sangamWakeupListener) {
        WorkLongPoll.Settings d = WorkLongPoll.Settings.defaults();
        WorkLongPoll.Settings s = new WorkLongPoll.Settings(
                env.getProperty("sangam.work.long-poll.max-waiters", Integer.class, d.maxWaiters()),
                env.getProperty("sangam.work.long-poll.poll-threads", Integer.class, d.pollThreads()),
                d.firstInterval(), d.maxInterval());
        WorkLongPoll poll = new WorkLongPoll(s, List.of(tenantIdentityCarrier()));
        sangamWakeupListener.subscribe(poll::wakeUp);
        return poll;
    }

    /**
     * The request's {@link TenantContext} identity (bound by the security filter on the request thread), bound around
     * every long-poll attempt on the poll thread, and exactly that: a request without one polls without one.
     */
    static WorkLongPoll.ContextCarrier tenantIdentityCarrier() {
        return () -> {
            SecurityIdentity identity = TenantContext.current().orElse(null);
            return new WorkLongPoll.ContextCarrier.Scope() {
                @Override
                public <T> T call(java.util.function.Supplier<T> work) {
                    return TenantContext.runAs(identity, work);
                }
            };
        };
    }

    /**
     * A reconciliation {@code CONFIRM_SUCCESS} (the engine's lookup or an officer) writes the connector's catalogued
     * events through the direct-audit invoker, in the resolving transaction, once whichever path confirms.
     */
    @Bean(destroyMethod = "")
    public ReconciliationEngine sangamReconciliationEngine(TxSupport tx, DataSource ds, ReconciliationQueue queue,
            OperationLedger ledger, ConnectorCallJournal journal, ConnectorRegistry registry, ProcessEngine engine,
            ObjectProvider<ReconciliationTaskPort> humans, RestProperties rest, ConnectorInvoker sangamConnectorInvoker) {
        return new ReconciliationEngine(tx.dialect(), productTx(ds), queue, ledger, journal, registry,
                humans.getIfAvailable(), new OperatonHeldWorkReleaser(engine),
                ReconciliationEngine.Settings.defaults(rest.getNode())).confirmations(sangamConnectorInvoker);
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
     * Pack connector → integration connector bindings: the defaults and capability classes the discovered packs
     * declare in their manifests ({@code connectors[].simulatorBinding}; SL-104: pack data, no system name in product
     * code), then the site's {@code sangam.packs.bindings.*} (with payload mappers), which win.
     */
    @Bean
    public PackConnectorBindings sangamPackConnectorBindings(
            @org.springframework.beans.factory.annotation.Qualifier("sangamPackBindingsProperties")
            PackBindingsProperties properties,
            ObjectProvider<org.sangam.packs.spi.PackRegistry> packs) {
        org.sangam.packs.spi.PackRegistry registry = packs.getIfAvailable();
        PackConnectorBindings bindings = registry != null
                ? PackConnectorBindings.fromPackData(registry.all())
                : new PackConnectorBindings();
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
     * true ({@link #simulatorFallback}); otherwise they fail with {@code CONNECTOR_NOT_CONFIGURED}.
     */
    /**
     * {@code sangam.packs.simulator-fallback}: the configured value, else the default of the h2 profile. That default is
     * a development default (decision 143): it holds only in a validated development environment. In production with a
     * development profile next to it the server starts as production with the fallback off (decision 198); a
     * configured {@code true} in production is refused by the callers.
     */
    static boolean simulatorFallback(Boolean configured, Environment env) {
        if (configured != null) {
            return configured;
        }
        return java.util.Arrays.asList(env.getActiveProfiles()).contains("h2")
                && ProductionEnvironment.developmentDefaultsAllowed(env);
    }

    @Bean
    public IntegrationConnectorGateway sangamIntegrationConnectorGateway(TxSupport tx, ObjectProvider<EngineAdapter> adapter,
            ProcessEngine engine, PackOperationStore store, PackConnectorBindings bindings, ConnectorRegistry registry,
            ConnectorCallJournal journal, ReconciliationQueue queue, OutboxWriter writer, BackpressureGate gate,
            @org.springframework.beans.factory.annotation.Qualifier("sangamPackBindingsProperties")
            PackBindingsProperties properties, Environment env) {
        boolean fallback = simulatorFallback(properties.getSimulatorFallback(), env);
        if (fallback && ProductionEnvironment.isProduction(env)) { // SL-109: only an explicit true reaches this
            throw new IllegalStateException("sangam.packs.simulator-fallback must not be enabled on a production profile");
        }
        return new IntegrationConnectorGateway(engineTx(adapter, tx), engine, store, bindings, registry, journal, queue,
                // SL-115: the demo's external checks answer from simulator DATA (demo-answers.properties), never from a
                // pack default; a case's own sim_* directive still wins
                writer, gate, fallback ? org.sangam.packs.sim.DemoAnswersGateway.withShippedAnswers() : null);
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

    /**
     * Decision 290: automatic re-dispatches of an operation with no process instance are bounded by
     * {@value #MAX_REDISPATCHES_PROPERTY} (default {@link PackReconciliationHooks#DEFAULT_MAX_REDISPATCHES}); past the
     * bound a human decides.
     */
    @Bean
    public PackReconciliationHooks sangamPackReconciliationHooks(DataSource ds, PackOperationStore store,
            IntegrationConnectorGateway gateway, PackMessages messages, Environment env) {
        return new PackReconciliationHooks(productTx(ds), store, gateway, messages, maxRedispatches(env));
    }

    /** The bound of automatic re-dispatches (decision 290). */
    public static final String MAX_REDISPATCHES_PROPERTY = "sangam.integration.reconciliation.max-redispatches";

    /**
     * {@value #MAX_REDISPATCHES_PROPERTY}: unset gives {@link PackReconciliationHooks#DEFAULT_MAX_REDISPATCHES}; any
     * value that is not a positive integer refuses the start, naming the property (fail closed, never a silent
     * default).
     */
    static int maxRedispatches(Environment env) {
        String raw = env.getProperty(MAX_REDISPATCHES_PROPERTY);
        if (raw == null) {
            return PackReconciliationHooks.DEFAULT_MAX_REDISPATCHES;
        }
        int value;
        try {
            value = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            value = 0;
        }
        if (value < 1) {
            throw new IllegalStateException(MAX_REDISPATCHES_PROPERTY + " must be a positive integer (found '" + raw
                    + "'); decision 290");
        }
        return value;
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
