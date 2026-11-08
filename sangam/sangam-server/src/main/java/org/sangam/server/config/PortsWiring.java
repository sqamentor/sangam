// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import javax.sql.DataSource;

import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.cfg.ProcessEnginePlugin;
import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Inbox;
import org.sangam.core.layer.Outbox;
import org.sangam.core.layer.Transactions;
import org.sangam.core.ports.IdentityExtrasPort;
import org.sangam.core.ports.UniqueStartPort;
import org.sangam.integration.inbox.InboxService;
import org.sangam.integration.ports.EventRegistryWiring;
import org.sangam.integration.relay.PublisherRegistry;
import org.sangam.ports.PortsSchema;
import org.sangam.ports.PortsSettings;
import org.sangam.ports.SangamPortsPlugin;
import org.sangam.ports.adhoc.AdHocSubProcessService;
import org.sangam.ports.app.AppDeploymentService;
import org.sangam.ports.bpmn.SangamModelValidator;
import org.sangam.ports.cmmn.CaseLifecycleService;
import org.sangam.ports.cmmn.CmmnEventListenerService;
import org.sangam.ports.content.ContentService;
import org.sangam.ports.content.FileSystemDocumentStore;
import org.sangam.ports.content.OutputTemplateRenderer;
import org.sangam.ports.debug.DebuggerService;
import org.sangam.ports.dynamic.CrossInstanceStateChangeService;
import org.sangam.ports.dynamic.DynamicInjectionService;
import org.sangam.ports.events.EventDispatcher;
import org.sangam.ports.events.EventLog;
import org.sangam.ports.events.EventRegistry;
import org.sangam.ports.events.InboundEventProcessor;
import org.sangam.ports.events.channel.ChannelAdapters;
import org.sangam.ports.events.channel.ChannelPublisher;
import org.sangam.ports.events.channel.InboundChannelManager;
import org.sangam.ports.events.channel.WebhookInboundEndpoint;
import org.sangam.ports.forms.SangamFormService;
import org.sangam.ports.history.HistoryProjector;
import org.sangam.ports.idm.IdmFacade;
import org.sangam.ports.support.EngineTx;
import org.sangam.rest.adapter.operaton.OperatonCaseAdapter;
import org.sangam.rest.adapter.operaton.OperatonDeploymentAdapter;
import org.sangam.rest.adapter.operaton.OperatonFormAdapter;
import org.sangam.rest.adapter.operaton.OperatonInstanceControlAdapter;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.CasePort;
import org.sangam.rest.port.DebugPort;
import org.sangam.rest.port.DeploymentPort;
import org.sangam.rest.port.DocumentExtrasPort;
import org.sangam.rest.port.DocumentPort;
import org.sangam.rest.port.DynamicPort;
import org.sangam.rest.port.EventPort;
import org.sangam.rest.port.FormPort;
import org.sangam.rest.port.InstanceControlPort;
import org.sangam.rest.port.LinkPort;
import org.sangam.rest.port.ProcessPort;
import org.sangam.rest.port.RegistryPort;
import org.sangam.rest.support.RestProperties;
import org.sangam.rest.support.TxSupport;
import org.sangam.server.bridge.PortsCaseBridge;
import org.sangam.server.bridge.PortsDebugBridge;
import org.sangam.server.bridge.PortsDeploymentBridge;
import org.sangam.server.bridge.PortsDocumentBridge;
import org.sangam.server.bridge.PortsDocumentExtrasBridge;
import org.sangam.server.bridge.PortsDynamicBridge;
import org.sangam.server.bridge.PortsEventBridge;
import org.sangam.server.bridge.PortsFormBridge;
import org.sangam.server.bridge.PortsInstanceControlBridge;
import org.sangam.server.bridge.PortsLinkBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;

/**
 * Wires sangam-flowable-ports (WS-PORTS) into the server and binds the sangam-rest ports to its implementations
 * (ports README "Integration notes"):
 * <ul>
 *   <li>{@code sangamPortsPlugin}: {@link SangamPortsPlugin} as a {@link ProcessEnginePlugin} bean; WS-ENGINE's
 *       {@code SangamProcessEnginePlugin} must run first (EngineWiring orders the plugins);</li>
 *   <li>event registry: {@link EventDispatcher} (with WS-INTEGRATION's {@link UniqueStartPort}) on the inbox,
 *       {@link ChannelPublisher} and (when {@code sangam.history.read-model-projection=true}) {@link HistoryProjector}
 *       on the relay, through {@link EventRegistryWiring#wire};</li>
 *   <li>REST ports: {@link EventPort}, {@link DynamicPort} (FLOWABLE-INTO-OPERATON FC-01, FLOWABLE-INTO-OPERATON FC-15: the 501 defaults are gone), {@link CasePort},
 *       {@link FormPort}, {@link DocumentPort}, {@link DocumentExtrasPort}, {@link DeploymentPort} (app bundles FLOWABLE-INTO-OPERATON FC-41),
 *       {@link LinkPort}, {@link InstanceControlPort} (FLOWABLE-INTO-OPERATON FC-17) and, in sandbox profiles, {@link DebugPort};</li>
 *   <li>{@link IdmFacade} over WS-SECURITY's {@link IdentityExtrasPort} (FLOWABLE-INTO-OPERATON FC-42).</li>
 * </ul>
 * Beans declared here replace the {@code @ConditionalOnMissingBean} defaults of {@code SangamRestAutoConfiguration}.
 * Disable the whole module with {@code sangam.modules.ports.enabled=false}.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "sangam.modules.ports.enabled", havingValue = "true", matchIfMissing = true)
public class PortsWiring {

    private static final Logger LOG = LoggerFactory.getLogger(PortsWiring.class);

    /** Marker that the ports tables exist (dev schema) before the engine and its handlers use them. */
    public record PortsSchemaReady(Dialect dialect) {
    }

    @Bean
    public PortsSchemaReady sangamPortsSchema(DataSource dataSource, Environment env) {
        Dialect dialect = TxSupport.detectDialect(dataSource);
        if (env.getProperty("sangam.persistence.dev-schema", Boolean.class, false)) {
            try (Connection c = dataSource.getConnection()) {
                if (!tableExists(c, PortsSchema.tables().get(0))) {
                    boolean auto = c.getAutoCommit();
                    c.setAutoCommit(false);
                    PortsSchema.create(c, dialect);
                    c.commit();
                    c.setAutoCommit(auto);
                    LOG.info("Created the sangam-flowable-ports development schema ({} tables)", PortsSchema.tables().size());
                }
            } catch (SQLException e) {
                throw new IllegalStateException("cannot create the ports development schema", e);
            }
        }
        return new PortsSchemaReady(dialect);
    }

    @Bean
    @ConfigurationProperties("sangam.ports")
    public PortsSettings sangamPortsSettings(PortsSchemaReady schema, Environment env) {
        PortsSettings s = new PortsSettings();
        s.setDialect(schema.dialect());
        s.setOutbox(new Outbox(schema.dialect()));
        // One history-to-outbox stream only: integration's OutboxHistoryEventHandler feeds the outbox; the ports
        // handler publishes only the read-model projection (FLOWABLE-INTO-OPERATON FC-25) when this is on.
        s.setReadModelFeed(env.getProperty("sangam.history.read-model-projection", Boolean.class, true));
        return s;
    }

    /** Debugger (FLOWABLE-INTO-OPERATON FC-44, R80): only with {@code sangam.debug.enabled=true} on a profile that is not production. */
    @Bean
    @ConditionalOnExpression("${sangam.debug.enabled:false} and !'${spring.profiles.active:}'.contains('prod')")
    public DebuggerService sangamPortsDebugger(PortsSettings settings, ObjectProvider<ProcessEngine> engine, Environment env) {
        if (Arrays.stream(env.getActiveProfiles()).anyMatch(p -> p.contains("prod"))) {
            throw new IllegalStateException("sangam.debug.enabled must not be set on a production profile (R80)");
        }
        ProcessEngine lazy = lazyEngine(engine);
        Transactions tx = new Transactions() {
            @Override
            public <T> T inTransaction(Function<Connection, T> work) {
                return EngineTx.transactions(engine.getObject()).inTransaction(work);
            }
        };
        return new DebuggerService(lazy, settings.getDialect(), tx, sandboxTenants(env), true);
    }

    @Bean("sangamPortsPlugin")
    @Order(400)
    public SangamPortsPlugin sangamPortsPlugin(PortsSettings settings, ObjectProvider<DebuggerService> debugger) {
        return new SangamPortsPlugin(settings, debugger.getIfAvailable());
    }

    // ---------------------------------------------------------------- engine-side services (after the engine is built)

    @Bean
    public EventRegistry sangamEventRegistry(SangamPortsPlugin plugin, ProcessEngine engine) {
        EventRegistry r = plugin.eventRegistry();
        if (r == null) {
            throw new IllegalStateException("sangam.ports.event-registry is off, or SangamPortsPlugin was not registered"
                    + " with the process engine (EngineWiring must append the sangamPortsPlugin bean)");
        }
        return r;
    }

    @Bean
    public EventLog sangamEventLog(PortsSettings settings) {
        return new EventLog(settings.getDialect(), settings.isStoreEventPayloads());
    }

    @Bean
    public ChannelAdapters.Environment sangamChannelEnvironment(Environment env) {
        Map<String, String> secrets = new LinkedHashMap<>();
        String refs = env.getProperty("sangam.ports.secret-refs", "");
        for (String ref : refs.split(",")) {
            String r = ref.trim();
            if (!r.isEmpty()) {
                String v = env.getProperty("sangam.ports.secrets." + r);
                if (v != null) {
                    secrets.put(r, v);
                }
            }
        }
        Set<String> egress = new HashSet<>();
        for (String h : env.getProperty("sangam.ports.egress-hosts", "").split(",")) {
            if (!h.isBlank()) {
                egress.add(h.trim());
            }
        }
        // Air-gapped by default (ARCHITECTURE §1.9): no egress host unless configured.
        return ChannelAdapters.Environment.of(secrets, egress);
    }

    @Bean
    public InboundEventProcessor sangamInboundEventProcessor(EventRegistry registry, EventLog log, ProcessEngine engine,
                                                             PortsSettings settings, RestProperties props, Environment env) {
        Transactions tx = EngineTx.transactions(engine);
        Inbox inbox = new Inbox(settings.getDialect(), tx, props.getNode(), Duration.ofSeconds(30), Duration.ofSeconds(30),
                new AuditChain(settings.getDialect()));
        // Audit PR-5 (FW-02): bounded event payload; larger events are REJECTED with PAYLOAD_TOO_LARGE (413 on REST).
        int maxPayload = env.getProperty("sangam.ports.events.max-payload-bytes", Integer.class, 1024 * 1024);
        return new InboundEventProcessor(registry, inbox, log, tx, maxPayload);
    }

    @Bean
    public WebhookInboundEndpoint sangamWebhookInboundEndpoint(EventRegistry registry, InboundEventProcessor processor,
                                                               ChannelAdapters.Environment env) {
        return new WebhookInboundEndpoint(registry, processor, env);
    }

    @Bean(destroyMethod = "close")
    public ChannelPublisher sangamChannelPublisher(EventRegistry registry, ChannelAdapters.Environment env) {
        return new ChannelPublisher(registry, env);
    }

    @Bean(destroyMethod = "close")
    public InboundChannelManager sangamInboundChannelManager(EventRegistry registry, InboundEventProcessor processor,
                                                            ChannelAdapters.Environment env) {
        return new InboundChannelManager(registry, processor, env);
    }

    @Bean
    public EventDispatcher sangamEventDispatcher(ProcessEngine engine, EventRegistry registry, EventLog log,
                                                 SangamPortsPlugin plugin, ObjectProvider<UniqueStartPort> uniqueStart) {
        UniqueStartPort guard = uniqueStart.getIfAvailable();
        if (guard == null) {
            LOG.warn("No UniqueStartPort bean: event definitions with startUniqueReference will be refused (FLOWABLE-INTO-OPERATON FC-04)");
        }
        return new EventDispatcher(engine, registry, log, plugin.links(), guard);
    }

    /**
     * Hands the inbox rows {@code evt:*} to the dispatcher and registers the relay publishers ({@code channel:*},
     * read-model projector) before WS-INTEGRATION's inbox and relay start (SmartLifecycle phase MAX-100).
     */
    @Bean
    public InitializingBean sangamEventRegistryWiring(ObjectProvider<InboxService> inbox,
            ObjectProvider<PublisherRegistry> publishers, EventDispatcher dispatcher, ChannelPublisher channels,
            PortsSettings settings, ProcessEngine engine, InboundChannelManager inbound) {
        return () -> {
            HistoryProjector projector = settings.isReadModelFeed()
                    ? new HistoryProjector(settings.getDialect(), EngineTx.transactions(engine)) : null;
            InboxService in = inbox.getIfAvailable();
            PublisherRegistry pub = publishers.getIfAvailable();
            if (in == null || pub == null) {
                LOG.warn("sangam-integration inbox/relay beans missing: inbound events are stored but not dispatched,"
                        + " outbound channel events are not published");
            }
            EventRegistryWiring.wire(in, pub, dispatcher, channels, projector);
            try {
                inbound.refresh();
            } catch (RuntimeException e) {
                LOG.warn("Inbound channel adapters could not start: {}", e.toString());
            }
        };
    }

    @Bean
    public AdHocSubProcessService sangamAdHocSubProcessService(ProcessEngine engine) {
        return new AdHocSubProcessService(engine);
    }

    @Bean
    public DynamicInjectionService sangamDynamicInjectionService(ProcessEngine engine, PortsSettings settings,
                                                                 SangamPortsPlugin plugin) {
        // Governance (R43/R49): the REST layer requires PROCESS_MODIFY and a reason, and audits INSTANCE_MODIFIED.
        DynamicInjectionService.InjectionPolicy policy = (tenant, pi, kind, spec, by) -> {
            if (by == null || by.isBlank()) {
                throw new SecurityException("dynamic injection needs an identified requester");
            }
        };
        return new DynamicInjectionService(engine, settings.getDialect(), plugin.links(), policy);
    }

    @Bean
    public CrossInstanceStateChangeService sangamCrossInstanceStateChangeService(ProcessEngine engine) {
        return new CrossInstanceStateChangeService(engine);
    }

    @Bean
    public CaseLifecycleService sangamCaseLifecycleService(ProcessEngine engine, SangamPortsPlugin plugin) {
        return new CaseLifecycleService(engine, plugin.links());
    }

    @Bean
    public CmmnEventListenerService sangamCmmnEventListenerService(ProcessEngine engine) {
        return new CmmnEventListenerService(engine);
    }

    @Bean
    public SangamFormService sangamFormService(ProcessEngine engine, PortsSettings settings) {
        return new SangamFormService(engine, settings.getDialect(), EngineTx.transactions(engine));
    }

    @Bean
    public ContentService sangamContentService(ProcessEngine engine, PortsSettings settings, Environment env) {
        Path root = Path.of(env.getProperty("sangam.content.root", "data/content"));
        try {
            // Audit PR-4 (FW-39): bounded uploads; above the limit 413 PAYLOAD_TOO_LARGE and the partial object is removed.
            return new ContentService(settings.getDialect(), EngineTx.transactions(engine), new FileSystemDocumentStore(root),
                    null, env.getProperty("sangam.content.require-clean", Boolean.class, false), contentMaxBytes(env));
        } catch (IOException e) {
            throw new IllegalStateException("cannot open the document store at " + root.toAbsolutePath(), e);
        }
    }

    @Bean
    public OutputTemplateRenderer sangamOutputTemplateRenderer(ContentService content) {
        return new OutputTemplateRenderer(content);
    }

    @Bean
    public AppDeploymentService sangamAppDeploymentService(ProcessEngine engine, PortsSettings settings,
            SangamFormService forms, EventRegistry events, InboundChannelManager channels) {
        // App bundles reach this service through the R43 package lifecycle (review + approval already recorded) or
        // through POST /deployments (DEPLOYMENT_WRITE permission); the requester is recorded as the approver.
        AppDeploymentService.ApprovalPolicy approval = (tenant, bundle, requestedBy) -> requestedBy;
        return new AppDeploymentService(engine, settings.getDialect(),
                new SangamModelValidator(new SangamModelValidator.Options(settings.isAdHocSubProcess())), approval, forms,
                events, channels);
    }

    /** FLOWABLE-INTO-OPERATON FC-42 privileges and API tokens; {@code null} (no bean) when sangam-security's IdentityExtrasPort is absent. */
    @Bean
    public IdmFacade sangamIdmFacade(ObjectProvider<IdentityExtrasPort> identity, ProcessEngine engine) {
        IdentityExtrasPort port = identity.getIfAvailable();
        if (port == null) {
            LOG.info("No IdentityExtrasPort bean (sangam-security): IdmFacade privileges/tokens are unavailable");
            return null;
        }
        return new IdmFacade(port, EngineTx.transactions(engine));
    }

    // ---------------------------------------------------------------- REST ports bound to the ports implementations

    @Bean
    public EventPort sangamPortsEventPort(EventRegistry registry, WebhookInboundEndpoint webhook,
                                          InboundChannelManager channels, AuditPort audit, RestProperties props,
                                          Environment env) {
        return new PortsEventBridge(registry, webhook, channels, audit, props,
                env.getProperty("sangam.ports.events.allow-unsigned-webhooks", Boolean.class, false));
    }

    /** {@code sangam.content.max-bytes} (default 256 MiB): upload limit and the bound of every download read. */
    static long contentMaxBytes(Environment env) {
        return env.getProperty("sangam.content.max-bytes", Long.class, ContentService.DEFAULT_MAX_BYTES);
    }

    @Bean
    public DynamicPort sangamPortsDynamicPort(ProcessEngine engine, AdHocSubProcessService adHoc,
                                              DynamicInjectionService injection, ProcessPort processes, TxSupport tx,
                                              AuditPort audit) {
        return new PortsDynamicBridge(engine, adHoc, injection, processes, tx, audit);
    }

    @Bean
    public CasePort sangamPortsCasePort(ProcessEngine engine, CaseLifecycleService lifecycle,
                                        CmmnEventListenerService listeners, TxSupport tx, AuditPort audit,
                                        RestProperties props) {
        CasePort base = new OperatonCaseAdapter(engine.getCaseService(), engine.getRepositoryService(),
                engine.getHistoryService(), tx, audit, props);
        return new PortsCaseBridge(base, lifecycle, listeners, tx, audit);
    }

    @Bean
    public FormPort sangamPortsFormPort(ProcessEngine engine, SangamFormService forms, AuditPort audit,
                                        RestProperties props, TxSupport tx,
                                        ObjectProvider<org.sangam.core.security.FieldEncryptor> encryptor) {
        OperatonFormAdapter base = new OperatonFormAdapter(engine.getRepositoryService(), engine.getTaskService(),
                audit, props, tx);
        base.setEncryptor(encryptor.getIfAvailable()); // drafts encrypted at rest (X.2.5.8 rule 1)
        return new PortsFormBridge(base, forms);
    }

    @Bean
    public PortsDocumentBridge sangamPortsDocumentPort(ContentService content, ProcessEngine engine, AuditPort audit,
                                                       RestProperties props, TxSupport tx, Environment env) {
        return new PortsDocumentBridge(content, engine.getTaskService(), engine.getHistoryService(), audit, props, tx,
                contentMaxBytes(env));
    }

    @Bean
    public DocumentExtrasPort sangamPortsDocumentExtrasPort(@Qualifier("sangamPortsDocumentPort") PortsDocumentBridge documents,
            OutputTemplateRenderer renderer, ProcessEngine engine, RegistryPort registry, AuditPort audit) {
        return new PortsDocumentExtrasBridge(documents, renderer, engine.getRuntimeService(), engine.getHistoryService(),
                registry, audit);
    }

    @Bean
    public DeploymentPort sangamPortsDeploymentPort(ProcessEngine engine, AppDeploymentService apps, TxSupport tx,
                                                    AuditPort audit) {
        DeploymentPort base = new OperatonDeploymentAdapter(engine.getRepositoryService(), tx, audit);
        return new PortsDeploymentBridge(base, apps, audit);
    }

    @Bean
    public LinkPort sangamPortsLinkPort(SangamPortsPlugin plugin, ProcessEngine engine, AuditPort audit) {
        if (plugin.links() == null) {
            throw new IllegalStateException("SangamPortsPlugin was not registered with the engine (no link service)");
        }
        return new PortsLinkBridge(plugin.links(), engine, audit);
    }

    @Bean
    public InstanceControlPort sangamPortsInstanceControlPort(ProcessEngine engine, ProcessPort processes,
            CrossInstanceStateChangeService moves, TxSupport tx, AuditPort audit, RestProperties props,
            org.sangam.rest.adapter.operaton.OperatonOperationIds operationIds) {
        InstanceControlPort base = new OperatonInstanceControlAdapter(engine.getRuntimeService(),
                engine.getRepositoryService(), engine.getHistoryService(), engine.getExternalTaskService(),
                engine.getTaskService(), processes, tx, audit, props, operationIds);
        return new PortsInstanceControlBridge(base, moves, processes, tx, audit);
    }

    @Bean
    @ConditionalOnExpression("${sangam.debug.enabled:false} and !'${spring.profiles.active:}'.contains('prod')")
    public DebugPort sangamPortsDebugPort(DebuggerService debugger, ProcessEngine engine, ProcessPort processes,
                                          AuditPort audit, Environment env) {
        return new PortsDebugBridge(debugger, engine, processes, audit, String.join(",", env.getActiveProfiles()),
                sandboxTenants(env));
    }

    // ---------------------------------------------------------------- history read model, SLA, product batches

    /** History query API over the read model (FLOWABLE-INTO-OPERATON FC-25); needs the projection ({@code sangam.history.read-model-projection}). */
    @Bean
    @ConditionalOnProperty(name = "sangam.history.read-model-projection", havingValue = "true", matchIfMissing = true)
    public org.sangam.ports.history.HistoryQueryService sangamHistoryQueryService(ProcessEngine engine, TxSupport tx) {
        return new org.sangam.ports.history.HistoryQueryService(engine, tx.dialect(), tx.newTransactions());
    }

    /** HistoryPort on the read model; incidents, job log and operation log stay on Operaton history. */
    @Bean
    @ConditionalOnProperty(name = "sangam.history.read-model-projection", havingValue = "true", matchIfMissing = true)
    public org.sangam.rest.port.HistoryPort sangamPortsHistoryPort(org.sangam.ports.history.HistoryQueryService queries,
            ProcessEngine engine, ObjectProvider<org.sangam.security.SangamSecurity> security, TxSupport tx,
            RestProperties props) {
        return new org.sangam.server.bridge.PortsHistoryBridge(queries,
                new org.sangam.rest.adapter.operaton.OperatonHistoryAdapter(engine.getHistoryService(),
                        engine.getRepositoryService(), props),
                security.getIfAvailable(), tx, props.getCountCap());
    }

    /** SLA clocks and aggregates (EC-08, R79); working calendars are the engine's {@code WorkCalendar} beans by name. */
    @Bean
    public org.sangam.ports.sla.SlaService sangamSlaService(TxSupport tx,
            org.springframework.beans.factory.ListableBeanFactory beans) {
        Map<String, org.sangam.core.engine.WorkCalendar> calendars =
                beans.getBeansOfType(org.sangam.core.engine.WorkCalendar.class);
        return new org.sangam.ports.sla.SlaService(tx.dialect(), tx.newTransactions(), new Outbox(tx.dialect()),
                calendars::get);
    }

    @Bean
    public org.sangam.rest.port.InsightsPort sangamPortsInsightsPort(org.sangam.ports.sla.SlaService sla,
            ProcessEngine engine, AuditPort audit, RestProperties props, org.sangam.rest.support.DocumentStore store) {
        return new org.sangam.server.bridge.PortsInsightsBridge(sla,
                new org.sangam.rest.adapter.operaton.OperatonInsightsAdapter(engine.getHistoryService(),
                        engine.getRepositoryService(), engine.getTaskService(), audit, props, store));
    }

    /**
     * Product batch service (R64, FLOWABLE-INTO-OPERATON D-34): four eyes for DELETE / MIGRATE / RESTART and above
     * {@code sangam.rest.batch-four-eyes-threshold} items; up to {@code sangam.ports.batch.max-attempts} attempts.
     */
    @Bean
    public org.sangam.ports.batch.ProductBatchService sangamProductBatchService(ProcessEngine engine, TxSupport tx,
            RestProperties props, Environment env) {
        var fourEyes = org.sangam.ports.batch.ProductBatchService.FourEyesPolicy.of(
                Set.of("ops:DELETE", "ops:MIGRATE", "ops:RESTART", "REST:DELETE", "REST:MIGRATE", "REST:RESTART"),
                (int) Math.min(Integer.MAX_VALUE, props.getBatchFourEyesThreshold()));
        var service = new org.sangam.ports.batch.ProductBatchService(tx.dialect(), tx.newTransactions(),
                env.getProperty("sangam.ports.batch.max-attempts", Integer.class, 3), fourEyes);
        org.sangam.ports.batch.StandardBatchHandlers.registerAll(service, engine);
        return service;
    }

    /** OpsPort: batches on ProductBatchService, everything else on the default OperatonOpsAdapter. */
    @Bean(destroyMethod = "close")
    public org.sangam.rest.port.OpsPort sangamPortsOpsPort(org.sangam.ports.batch.ProductBatchService batches,
            ProcessEngine engine, ProcessPort processes, TxSupport tx, AuditPort audit, RestProperties props,
            ObjectProvider<org.sangam.core.layer.ReconciliationResolver> resolver) {
        var defaults = new org.sangam.rest.adapter.operaton.OperatonOpsAdapter(engine.getRuntimeService(),
                engine.getManagementService(), engine.getHistoryService(), engine.getRepositoryService(),
                engine.getExternalTaskService(), tx, audit, props);
        defaults.setProcessPort(processes);
        defaults.setReconciliationResolver(resolver::getIfAvailable);   // release held work on every resolve
        return new org.sangam.server.bridge.PortsOpsBridge(batches, defaults, engine, processes, audit, props);
    }

    /**
     * The one batch runner on {@code PORT_BATCH}: works pending items of running batches in chunks (SKIP LOCKED, so
     * several nodes share the work) and re-queues items left RUNNING by a crashed node. It also continues
     * {@code REST:} batches written by the default adapter before this wiring was bound (the bridge registers
     * handlers for them), which is why the default adapter's own runner is not started here: two runners would claim
     * the same items with different state vocabularies. Disable with {@code sangam.ports.batch-runner.enabled=false}.
     */
    @Bean
    @ConditionalOnProperty(name = "sangam.ports.batch-runner.enabled", havingValue = "true", matchIfMissing = true)
    public BatchRunner sangamPortsBatchRunner(org.sangam.ports.batch.ProductBatchService batches, Environment env) {
        return new BatchRunner(batches, env.getProperty("sangam.ports.batch.chunk", Integer.class, 50),
                env.getProperty("sangam.ports.batch.poll", Duration.class, Duration.ofSeconds(1)),
                env.getProperty("sangam.ports.batch.stale-after", Duration.class, Duration.ofMinutes(5)));
    }

    /** Lifecycle of the product batch runner (starts after the context, stops before the engine). */
    public static final class BatchRunner implements org.springframework.context.SmartLifecycle {
        private final org.sangam.ports.batch.ProductBatchService batches;
        private final int chunk;
        private final Duration poll;
        private final Duration staleAfter;
        private volatile java.util.concurrent.ScheduledExecutorService executor;

        BatchRunner(org.sangam.ports.batch.ProductBatchService batches, int chunk, Duration poll, Duration staleAfter) {
            this.batches = batches;
            this.chunk = chunk;
            this.poll = poll;
            this.staleAfter = staleAfter;
        }

        @Override
        public void start() {
            executor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "sangam-product-batches");
                t.setDaemon(true);
                return t;
            });
            executor.scheduleWithFixedDelay(this::tick, poll.toMillis(), poll.toMillis(),
                    java.util.concurrent.TimeUnit.MILLISECONDS);
            executor.scheduleWithFixedDelay(this::recover, 0, Math.max(staleAfter.toMillis() / 2, 1000),
                    java.util.concurrent.TimeUnit.MILLISECONDS);
            LOG.info("Product batch runner started (chunk {}, poll {})", chunk, poll);
        }

        private void tick() {
            try {
                while (batches.runOnce(chunk) >= chunk) {
                    if (Thread.currentThread().isInterrupted()) {
                        return;
                    }
                }
            } catch (RuntimeException e) {
                LOG.warn("Product batch run failed: {}", e.toString());
            }
        }

        private void recover() {
            try {
                int n = batches.recoverStale(staleAfter.toMillis());
                if (n > 0) {
                    LOG.info("Re-queued {} batch items left running by a stopped node", n);
                }
            } catch (RuntimeException e) {
                LOG.warn("Stale batch item recovery failed: {}", e.toString());
            }
        }

        @Override
        public void stop() {
            java.util.concurrent.ScheduledExecutorService e = executor;
            executor = null;
            if (e != null) {
                e.shutdownNow();
            }
        }

        @Override
        public boolean isRunning() {
            return executor != null;
        }

        @Override
        public int getPhase() {
            return Integer.MAX_VALUE - 200;
        }
    }

    // ---------------------------------------------------------------- helpers

    private static Set<String> sandboxTenants(Environment env) {
        String configured = env.getProperty("sangam.debug.sandbox-tenants",
                env.getProperty("sangam.debug.sandbox-tenant", env.getProperty("sangam.security.dev-token.tenant", "dev")));
        Set<String> out = new HashSet<>();
        for (String t : configured.split(",")) {
            if (!t.isBlank()) {
                out.add(t.trim());
            }
        }
        return out;
    }

    /** A {@link ProcessEngine} that resolves the real engine on first use (breaks the plugin/engine cycle). */
    private static ProcessEngine lazyEngine(ObjectProvider<ProcessEngine> provider) {
        return (ProcessEngine) Proxy.newProxyInstance(ProcessEngine.class.getClassLoader(), new Class<?>[]{ProcessEngine.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("toString") && method.getParameterCount() == 0) {
                        return "lazy ProcessEngine";
                    }
                    try {
                        return method.invoke(provider.getObject(), args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private static boolean tableExists(Connection c, String table) throws SQLException {
        for (String name : List.of(table, table.toLowerCase(java.util.Locale.ROOT), table.toUpperCase(java.util.Locale.ROOT))) {
            try (ResultSet rs = c.getMetaData().getTables(null, null, name, new String[]{"TABLE"})) {
                if (rs.next()) {
                    return true;
                }
            }
        }
        return false;
    }
}
