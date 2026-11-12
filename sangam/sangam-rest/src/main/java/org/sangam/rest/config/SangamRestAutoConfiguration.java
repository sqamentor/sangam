// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.config;

import java.util.List;

import javax.sql.DataSource;

import org.operaton.bpm.engine.ProcessEngine;
import org.sangam.core.api.EngineAdapter;
import org.sangam.rest.adapter.jdbc.JdbcAuditAdapter;
import org.sangam.rest.adapter.jdbc.JdbcIdempotencyAdapter;
import org.sangam.rest.adapter.memory.GroupPermissionAdapter;
import org.sangam.rest.adapter.operaton.EngineAdapterWorkAdapter;
import org.sangam.rest.adapter.operaton.NotInstalledDynamicAdapter;
import org.sangam.rest.adapter.operaton.OperatonAnalyticsAdapter;
import org.sangam.rest.adapter.operaton.OperatonCaseAdapter;
import org.sangam.rest.adapter.operaton.OperatonDecisionAdapter;
import org.sangam.rest.adapter.operaton.OperatonDeploymentAdapter;
import org.sangam.rest.adapter.operaton.OperatonDocumentAdapter;
import org.sangam.rest.adapter.operaton.OperatonFormAdapter;
import org.sangam.rest.adapter.operaton.OperatonHistoryAdapter;
import org.sangam.rest.adapter.operaton.OperatonLinkAdapter;
import org.sangam.rest.adapter.operaton.OperatonOpsAdapter;
import org.sangam.rest.adapter.operaton.OperatonProcessAdapter;
import org.sangam.rest.adapter.operaton.OperatonTaskAdapter;
import org.sangam.rest.adapter.operaton.OperatonWorkAdapter;
import org.sangam.rest.port.AdminPort;
import org.sangam.rest.port.AnalyticsPort;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.CasePort;
import org.sangam.rest.port.DecisionPort;
import org.sangam.rest.port.DeploymentPort;
import org.sangam.rest.port.DocumentPort;
import org.sangam.rest.port.DynamicPort;
import org.sangam.rest.port.EventPort;
import org.sangam.rest.port.FormPort;
import org.sangam.rest.port.HistoryPort;
import org.sangam.rest.port.IdempotencyPort;
import org.sangam.rest.port.LinkPort;
import org.sangam.rest.port.OpsPort;
import org.sangam.rest.port.PackagePort;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.ProcessPort;
import org.sangam.rest.port.TaskPort;
import org.sangam.rest.port.WorkPort;
import org.sangam.rest.support.RestProperties;
import org.sangam.rest.support.TxSupport;
import org.sangam.rest.web.CommandCatalog;
import org.sangam.rest.web.IdempotencyFilter;
import org.sangam.rest.web.RequestContextArgumentResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Default wiring of every service port (ARCHITECTURE §3: "the integrator wires it in sangam-server"). Each bean is
 * {@code @ConditionalOnMissingBean}, and this configuration runs last, so a bean of the same port type declared by
 * sangam-server or by another module's configuration (sangam-engine, -security, -integration, -flowable-ports, -packs)
 * replaces the default without any change here.
 */
@AutoConfiguration
@AutoConfigureOrder(Ordered.LOWEST_PRECEDENCE)
public class SangamRestAutoConfiguration {

    /** After Spring Security (order -100), so the authenticated principal is available. */
    public static final int IDEMPOTENCY_FILTER_ORDER = 100;

    @Bean
    @ConditionalOnMissingBean
    @ConfigurationProperties("sangam.rest")
    public RestProperties sangamRestProperties() {
        return new RestProperties();
    }

    @Bean
    @ConditionalOnMissingBean
    public TxSupport sangamTxSupport(DataSource dataSource, PlatformTransactionManager txManager) {
        return new TxSupport(dataSource, txManager, TxSupport.detectDialect(dataSource));
    }

    /** REST_DOCUMENT: persistence of the default adapters' state (nothing in memory). */
    @Bean
    @ConditionalOnMissingBean
    public org.sangam.rest.support.DocumentStore sangamDocumentStore(TxSupport tx,
            ObjectProvider<org.sangam.core.security.FieldEncryptor> encryptor) {
        org.sangam.rest.support.DocumentStore store = new org.sangam.rest.support.DocumentStore(tx);
        org.sangam.core.security.FieldEncryptor fe = encryptor.getIfAvailable();
        if (fe != null) {
            // Personal data at rest (X.2.3.8, DPDP): portal drafts and grievances, reconciliation evidence metadata,
            // identity-provider and service-principal records, notifications.
            store.setEncryption(fe, ENCRYPTED_DOCUMENT_KINDS);
        }
        return store;
    }

    /** REST_DOCUMENT kinds whose body is encrypted at rest when a FieldEncryptor is bound. */
    public static final java.util.Set<String> ENCRYPTED_DOCUMENT_KINDS = java.util.Set.of("portal:draft",
            "portal:grievance", "ops:evidence", "identity:idp", "identity:service-principal", "notification");

    /** REST_BLOB: export files and evidence. */
    @Bean
    @ConditionalOnMissingBean
    public org.sangam.rest.support.BlobStore sangamBlobStore(TxSupport tx) {
        return new org.sangam.rest.support.BlobStore(tx);
    }

    // ---------------------------------------------------------------- product-layer ports

    @Bean
    @ConditionalOnMissingBean
    public AuditPort sangamAuditPort(TxSupport tx, ProcessEngine engine, RestProperties props) {
        return new JdbcAuditAdapter(tx, engine.getHistoryService(), engine.getRuntimeService(), props);
    }

    /**
     * SL-139 (M-007, X.2.2.3): with sangam-security's {@code FieldEncryptor} bound, stored idempotency response bodies
     * are encrypted at rest under the tenant key and decrypted before a replay; rows written without an encryptor stay
     * readable (no marker). Without an encryptor the bodies are stored as before.
     */
    @Bean
    @ConditionalOnMissingBean
    public IdempotencyPort sangamIdempotencyPort(TxSupport tx, RestProperties props,
            ObjectProvider<org.sangam.core.security.FieldEncryptor> encryptor) {
        return new JdbcIdempotencyAdapter(tx, props, encryptor.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean
    public PermissionPort sangamPermissionPort() {
        return new GroupPermissionAdapter();
    }

    // ---------------------------------------------------------------- engine ports (Operaton Java API)

    /**
     * T35: the one operation-id resolver of every REST path; the Sangam engine adapter is its authority when bound.
     */
    @Bean
    @ConditionalOnMissingBean
    public org.sangam.rest.adapter.operaton.OperatonOperationIds sangamOperationIds(ProcessEngine engine,
            ObjectProvider<EngineAdapter> engineAdapter) {
        var ids = new org.sangam.rest.adapter.operaton.OperatonOperationIds(engine.getRuntimeService(),
                engine.getHistoryService(), engine.getExternalTaskService());
        if (engineAdapter.getIfAvailable() instanceof org.sangam.core.engine.SangamEngineAdapter s) {
            ids.setEngine(s);
        }
        return ids;
    }

    /**
     * The hardened sangam-engine {@link EngineAdapter} is preferred when present; else Operaton directly. SL-158 (R26,
     * owner decision: enforce): with a {@code WorkerTopicGuard} bound (the server binds sangam-security's worker
     * bindings), a worker fetches and settles work only on topics it is bound to.
     */
    @Bean
    @ConditionalOnMissingBean
    public WorkPort sangamWorkPort(ObjectProvider<EngineAdapter> engineAdapter, ProcessEngine engine, TxSupport tx,
                                   AuditPort audit, RestProperties props, org.sangam.rest.support.DocumentStore store,
                                   org.sangam.rest.adapter.operaton.OperatonOperationIds operationIds,
                                   ObjectProvider<org.sangam.rest.adapter.operaton.WorkerTopicGuard> topicGuard,
                                   ObjectProvider<org.sangam.rest.support.WorkLongPoll> longPoll,
                                   org.springframework.core.env.Environment env) {
        EngineAdapter adapter = engineAdapter.getIfAvailable();
        org.sangam.rest.adapter.operaton.WorkerTopicGuard guard = topicGuard.getIfAvailable();
        org.sangam.rest.support.WorkLongPoll poll = longPoll.getIfAvailable();
        WorkPort raw;
        if (adapter != null) {
            EngineAdapterWorkAdapter work = new EngineAdapterWorkAdapter(adapter, tx, audit, props);
            if (guard != null) {
                work.setTopicGuard(guard);
            }
            if (poll != null) {
                work.setLongPollSettings(poll.settings());   // X1-07: one back-off definition
            }
            // M-062: the engine's trace-links setting (SangamEngineSettings binds the same key, default off)
            work.setTraceLinks(traceLinks(env));
            raw = work;
        } else {
            OperatonWorkAdapter work = new OperatonWorkAdapter(engine.getExternalTaskService(), engine.getRuntimeService(),
                    tx, audit, props, operationIds);
            if (guard != null) {
                work.setTopicGuard(guard);   // SL-158 on this path too (engine module off)
            }
            if (poll != null) {
                work.setLongPollSettings(poll.settings());
            }
            raw = work;
        }
        // Worker/topic tracking and persisted topic pause for the ops console (O06).
        return new org.sangam.rest.adapter.operaton.TrackingWorkPort(raw, store);
    }

    /** M-062: {@code sangam.engine.trace-links}, bound as {@code SangamEngineSettings} binds it (default false). */
    static boolean traceLinks(org.springframework.core.env.Environment env) {
        return org.springframework.boot.context.properties.bind.Binder.get(env)
                .bind("sangam.engine.trace-links", Boolean.class).orElse(false);
    }

    /**
     * SG-15: task.list and task.get run their engine reads in one transaction ({@code ReadTransactions}). SL-132: the
     * SoD refusal audit ({@code SOD_VIOLATION_BLOCKED}) commits through the bound {@code DenialAudit} (the server binds
     * one on its {@code sangam-security-audit} leaf pool); without one, the adapter's main-pool fallback.
     */
    @Bean
    @ConditionalOnMissingBean
    public TaskPort sangamTaskPort(ProcessEngine engine, TxSupport tx, AuditPort audit, RestProperties props,
            PlatformTransactionManager txManager,
            ObjectProvider<org.sangam.rest.adapter.jdbc.DenialAudit> denialAudit) {
        OperatonTaskAdapter adapter = new OperatonTaskAdapter(engine.getTaskService(), engine.getRuntimeService(),
                engine.getHistoryService(), engine.getFormService(), tx, audit, props);
        // SL-77: required fields of the task's Sangam form are enforced on completion.
        adapter.setFormRequirements(new org.sangam.rest.adapter.operaton.TaskFormRequirements(
                engine.getRepositoryService()));
        adapter.setReadTransactions(new org.sangam.rest.adapter.operaton.ReadTransactions(txManager));
        org.sangam.rest.adapter.jdbc.DenialAudit denials = denialAudit.getIfAvailable();
        if (denials != null) {
            adapter.setDenialAudit(denials);
        }
        return adapter;
    }

    @Bean
    @ConditionalOnMissingBean
    public ProcessPort sangamProcessPort(ProcessEngine engine, TxSupport tx, AuditPort audit, RestProperties props,
                                         org.sangam.rest.adapter.operaton.OperatonOperationIds operationIds) {
        return new OperatonProcessAdapter(engine.getRuntimeService(), engine.getRepositoryService(),
                engine.getHistoryService(), engine.getExternalTaskService(), tx, audit, props, operationIds);
    }

    @Bean
    @ConditionalOnMissingBean
    public CasePort sangamCasePort(ProcessEngine engine, TxSupport tx, AuditPort audit, RestProperties props) {
        return new OperatonCaseAdapter(engine.getCaseService(), engine.getRepositoryService(), engine.getHistoryService(),
                tx, audit, props);
    }

    @Bean
    @ConditionalOnMissingBean
    public DecisionPort sangamDecisionPort(ProcessEngine engine, TxSupport tx, AuditPort audit) {
        return new OperatonDecisionAdapter(engine.getDecisionService(), engine.getRepositoryService(), tx, audit);
    }

    @Bean
    @ConditionalOnMissingBean
    public DeploymentPort sangamDeploymentPort(ProcessEngine engine, TxSupport tx, AuditPort audit) {
        return new OperatonDeploymentAdapter(engine.getRepositoryService(), tx, audit);
    }

    @Bean
    @ConditionalOnMissingBean
    public DocumentPort sangamDocumentPort(ProcessEngine engine, TxSupport tx, AuditPort audit) {
        return new OperatonDocumentAdapter(engine.getTaskService(), tx, audit);
    }

    @Bean
    @ConditionalOnMissingBean
    public LinkPort sangamLinkPort(ProcessEngine engine, RestProperties props, TxSupport tx) {
        return new OperatonLinkAdapter(engine.getRuntimeService(), engine.getHistoryService(), props, tx);
    }

    @Bean
    @ConditionalOnMissingBean
    public DynamicPort sangamDynamicPort() {
        return new NotInstalledDynamicAdapter();
    }

    @Bean
    @ConditionalOnMissingBean
    public HistoryPort sangamHistoryPort(ProcessEngine engine, RestProperties props) {
        return new OperatonHistoryAdapter(engine.getHistoryService(), engine.getRepositoryService(),
                engine.getManagementService(), props);
    }

    @Bean
    @ConditionalOnMissingBean
    public AnalyticsPort sangamAnalyticsPort(ProcessEngine engine, RestProperties props) {
        return new OperatonAnalyticsAdapter(engine.getHistoryService(), engine.getRuntimeService(),
                engine.getManagementService(), engine.getRepositoryService(), props);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public OpsPort sangamOpsPort(ProcessEngine engine, TxSupport tx, AuditPort audit, RestProperties props,
                                 ProcessPort processes,
                                 ObjectProvider<org.sangam.core.layer.ReconciliationResolver> resolver,
                                 ObjectProvider<org.sangam.core.layer.OutboxRepair> outboxRepair) {
        OperatonOpsAdapter ops = new OperatonOpsAdapter(engine.getRuntimeService(), engine.getManagementService(),
                engine.getHistoryService(), engine.getRepositoryService(), engine.getExternalTaskService(), tx, audit,
                props, outboxRepair::getIfUnique);   // lazily resolved: the relay service may be created after this port
        ops.setProcessPort(processes);
        ops.setReconciliationResolver(resolver::getIfAvailable);
        return ops;
    }

    @Bean
    @ConditionalOnMissingBean
    public FormPort sangamFormPort(ProcessEngine engine, AuditPort audit, RestProperties props, TxSupport tx,
                                   ObjectProvider<org.sangam.core.security.FieldEncryptor> encryptor,
                                   ObjectProvider<org.sangam.rest.extension.TenantCustomFieldRegistry> customFields,
                                   ObjectProvider<org.sangam.core.time.BusinessClock> businessClock) {
        OperatonFormAdapter forms = new OperatonFormAdapter(engine.getRepositoryService(), engine.getTaskService(),
                audit, props, tx);
        forms.setCustomFields(customFields.getIfAvailable(),
                new org.sangam.rest.support.BusinessDates(businessClock::getIfAvailable)); // SL-40: tenant custom fields, when configured
        forms.setEncryptor(encryptor.getIfAvailable()); // X.2.5.8 rule 1: drafts encrypted per tenant
        return forms;
    }

    // ---------------------------------------------------------------- ports owned by other modules
    // JDBC defaults (product tables, nothing in memory); the owning module's bridge replaces them when bound.

    @Bean
    @ConditionalOnMissingBean
    public AdminPort sangamAdminPort(org.sangam.rest.support.DocumentStore store, TxSupport tx, AuditPort audit,
                                     RestProperties props) {
        return new org.sangam.rest.adapter.jdbc.JdbcAdminAdapter(store, tx, audit, props);
    }

    @Bean
    @ConditionalOnMissingBean
    public EventPort sangamEventPort(ProcessPort processes, TxSupport tx, AuditPort audit, RestProperties props) {
        return new org.sangam.rest.adapter.jdbc.JdbcEventAdapter(processes, tx, audit, props);
    }

    @Bean
    @ConditionalOnMissingBean
    public PackagePort sangamPackagePort(TxSupport tx, DeploymentPort deployments, PermissionPort permissions,
                                         AuditPort audit, RestProperties props) {
        return new org.sangam.rest.adapter.jdbc.JdbcPackageAdapter(tx, deployments, permissions, audit, props);
    }

    // ---------------------------------------------------------------- UI-proposal ports (workspace, ops console, admin tools)

    @Bean
    @ConditionalOnMissingBean
    public org.sangam.rest.port.RegistryPort sangamRegistryPort(org.sangam.rest.support.DocumentStore store,
                                                                AuditPort audit, RestProperties props) {
        return new org.sangam.rest.adapter.jdbc.JdbcRegistryAdapter(store, audit, props);
    }

    @Bean
    @ConditionalOnMissingBean
    public org.sangam.rest.port.WorkspacePort sangamWorkspacePort(TaskPort tasks, ProcessEngine engine,
            AdminPort admin, PermissionPort permissions, AuditPort audit, RestProperties props,
            org.sangam.rest.support.DocumentStore store,
            org.springframework.beans.factory.ObjectProvider<org.sangam.core.time.BusinessClock> businessClock,
            org.springframework.beans.factory.BeanFactory beans) {
        org.sangam.rest.adapter.operaton.OperatonWorkspaceAdapter adapter =
                new org.sangam.rest.adapter.operaton.OperatonWorkspaceAdapter(tasks, engine.getTaskService(),
                engine.getRuntimeService(), engine.getHistoryService(), engine.getRepositoryService(),
                engine.getFormService(), admin, permissions, audit, props, store,
                new org.sangam.rest.support.BusinessDates(businessClock::getIfAvailable));
        // M-045: /me carries the tenant's resolved profile locale (currency, zone, grouping) when sangam-config's
        // ProfileResolver is present; without it /me has no locale, as before
        if (org.springframework.util.ClassUtils.isPresent(ProfileLocales.RESOLVER, getClass().getClassLoader())) {
            ProfileLocales.bind(adapter, beans);
        }
        return adapter;
    }

    /**
     * Touches sangam-config's {@code ProfileResolver} only when the class is present (sangam-config is an optional
     * dependency of sangam-rest): this holder is loaded only then.
     */
    static final class ProfileLocales {
        static final String RESOLVER = "org.sangam.config.profile.ProfileResolver";

        private ProfileLocales() {
        }

        static void bind(org.sangam.rest.adapter.operaton.OperatonWorkspaceAdapter adapter,
                         org.springframework.beans.factory.BeanFactory beans) {
            org.sangam.config.profile.ProfileResolver profiles =
                    beans.getBeanProvider(org.sangam.config.profile.ProfileResolver.class).getIfAvailable();
            if (profiles != null) {
                adapter.setProfiles(profiles);
            }
        }
    }

    @Bean
    @ConditionalOnMissingBean
    public org.sangam.rest.port.InstanceControlPort sangamInstanceControlPort(ProcessEngine engine,
            ProcessPort processes, TxSupport tx, AuditPort audit, RestProperties props,
            org.sangam.rest.adapter.operaton.OperatonOperationIds operationIds) {
        return new org.sangam.rest.adapter.operaton.OperatonInstanceControlAdapter(engine.getRuntimeService(),
                engine.getRepositoryService(), engine.getHistoryService(), engine.getExternalTaskService(),
                engine.getTaskService(), processes, tx, audit, props, operationIds);
    }

    @Bean
    @ConditionalOnMissingBean
    public org.sangam.rest.port.PortalPort sangamPortalPort(ProcessPort processes, ProcessEngine engine,
            DocumentPort documents, org.sangam.rest.port.RegistryPort registry, AuditPort audit, RestProperties props,
            TxSupport tx, org.sangam.rest.support.DocumentStore store,
            org.springframework.beans.factory.ObjectProvider<org.sangam.core.time.BusinessClock> businessClock) {
        return new org.sangam.rest.adapter.operaton.OperatonPortalAdapter(processes, engine.getTaskService(),
                engine.getRuntimeService(), engine.getHistoryService(), engine.getRepositoryService(), documents,
                registry, audit, props, tx, store, new org.sangam.rest.support.BusinessDates(businessClock::getIfAvailable));
    }

    @Bean
    @ConditionalOnMissingBean
    public org.sangam.rest.port.OpsConsolePort sangamOpsConsolePort(ProcessEngine engine, OpsPort ops, WorkPort work,
            TxSupport tx, AuditPort audit, RestProperties props, org.sangam.rest.support.DocumentStore store,
            org.sangam.rest.support.BlobStore blobs,
            org.sangam.rest.adapter.operaton.OperatonOperationIds operationIds) {
        // The default WorkPort is already wrapped; a module-provided one is wrapped here for statistics and pauses.
        var tracking = work instanceof org.sangam.rest.adapter.operaton.TrackingWorkPort t ? t
                : new org.sangam.rest.adapter.operaton.TrackingWorkPort(work, store);
        java.util.function.BooleanSupplier jobExecutorActive = () -> {
            var cfg = engine.getProcessEngineConfiguration();
            return cfg instanceof org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl impl
                    && impl.getJobExecutor() != null && impl.getJobExecutor().isActive();
        };
        var console = new org.sangam.rest.adapter.operaton.OperatonOpsConsoleAdapter(engine.getRuntimeService(),
                engine.getManagementService(), engine.getHistoryService(), engine.getExternalTaskService(),
                engine.getTaskService(), engine.getRepositoryService(), ops, tracking, tx, audit, props,
                jobExecutorActive, store, blobs);
        console.setOperationIds(operationIds);
        return console;
    }

    @Bean
    @ConditionalOnMissingBean
    public org.sangam.rest.port.DebugPort sangamDebugPort(ProcessEngine engine, RestProperties props,
            org.springframework.core.env.Environment env, TxSupport tx) {
        // SL-109: never enabled in production (ProductionMode: the production property or a 'prod' profile)
        boolean enabled = env.getProperty("sangam.debug.enabled", Boolean.class, false)
                && !org.sangam.core.security.ProductionMode.isProduction(
                        env.getProperty(org.sangam.core.security.ProductionMode.PROPERTY, Boolean.class, false),
                        java.util.Arrays.asList(env.getActiveProfiles()));
        String profile = String.join(",", env.getActiveProfiles());
        return new org.sangam.rest.adapter.operaton.OperatonDebugAdapter(engine.getRuntimeService(),
                engine.getManagementService(), props, enabled, profile, env.getProperty("sangam.debug.sandbox-tenant"),
                tx);
    }

    @Bean
    @ConditionalOnMissingBean
    public org.sangam.rest.port.InsightsPort sangamInsightsPort(ProcessEngine engine, AuditPort audit,
            RestProperties props, org.sangam.rest.support.DocumentStore store) {
        return new org.sangam.rest.adapter.operaton.OperatonInsightsAdapter(engine.getHistoryService(),
                engine.getRepositoryService(), engine.getTaskService(), audit, props, store);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public org.sangam.rest.port.AuditExportPort sangamAuditExportPort(AuditPort audit, ProcessPort processes,
            DocumentPort documents, TxSupport tx, RestProperties props, org.sangam.rest.support.DocumentStore store,
            org.sangam.rest.support.BlobStore blobs) {
        return new org.sangam.rest.adapter.jdbc.JdbcAuditExportAdapter(audit, processes, documents, tx, props, store,
                blobs);
    }

    /**
     * After start-up, persisted work continues: RUNNING product batches resume from their PENDING items and
     * QUEUED/RUNNING audit export jobs are re-queued (nothing is lost on restart).
     */
    @Bean
    public org.springframework.boot.ApplicationRunner sangamResumePersistedWork(ObjectProvider<OpsPort> ops,
            ObjectProvider<org.sangam.rest.port.AuditExportPort> exports) {
        return args -> {
            if (ops.getIfAvailable() instanceof OperatonOpsAdapter o) {
                o.resume();
            }
            if (exports.getIfAvailable() instanceof org.sangam.rest.adapter.jdbc.JdbcAuditExportAdapter e) {
                e.resume();
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean
    public org.sangam.rest.port.DocumentExtrasPort sangamDocumentExtrasPort(DocumentPort documents, ProcessEngine engine,
            org.sangam.rest.port.RegistryPort registry, AuditPort audit, RestProperties props) {
        return new org.sangam.rest.adapter.operaton.OperatonDocumentExtrasAdapter(documents, engine.getTaskService(),
                engine.getRuntimeService(), engine.getHistoryService(), registry, audit, props);
    }

    @Bean
    @ConditionalOnMissingBean
    public org.sangam.rest.port.PowerTablePort sangamPowerTablePort(TxSupport tx, DeploymentPort deployments,
            PermissionPort permissions, AuditPort audit, RestProperties props) {
        return new org.sangam.rest.adapter.jdbc.JdbcPowerTableAdapter(tx, deployments, permissions, audit, props);
    }

    @Bean
    @ConditionalOnMissingBean
    public org.sangam.rest.port.IdentityAdminPort sangamIdentityAdminPort(org.sangam.rest.support.DocumentStore store,
            AdminPort admin, TaskPort tasks, PermissionPort permissions, AuditPort audit, RestProperties props,
            org.springframework.core.env.Environment env,
            org.springframework.beans.factory.ObjectProvider<org.sangam.core.time.BusinessClock> businessClock) {
        return new org.sangam.rest.adapter.jdbc.JdbcIdentityAdminAdapter(store, admin, tasks, permissions, audit, props,
                env.getProperty("sangam.outbound.enabled", Boolean.class, false),
                new org.sangam.rest.support.BusinessDates(businessClock::getIfAvailable));
    }

    @Bean
    @ConditionalOnMissingBean
    public org.sangam.rest.port.LowCodePort sangamLowCodePort(org.sangam.rest.support.DocumentStore store,
            TaskPort tasks, ProcessPort processes, AuditPort audit, RestProperties props) {
        return new org.sangam.rest.adapter.jdbc.JdbcLowCodeAdapter(store, tasks, processes, audit, props);
    }

    @Bean
    @ConditionalOnMissingBean
    public org.sangam.rest.port.PackageToolsPort sangamPackageToolsPort(PackagePort packages, AuditPort audit,
            RestProperties props, org.sangam.rest.support.DocumentStore store) {
        return new org.sangam.rest.adapter.operaton.OperatonPackageToolsAdapter(packages, audit, props, store);
    }

    // ---------------------------------------------------------------- web layer

    @Bean
    @ConditionalOnMissingBean
    public CommandCatalog sangamCommandCatalog() {
        return new CommandCatalog();
    }

    @Bean
    public FilterRegistrationBean<IdempotencyFilter> sangamIdempotencyFilter(CommandCatalog catalog,
            IdempotencyPort idempotency, PermissionPort permissions, AuditPort audit, RestProperties props) {
        FilterRegistrationBean<IdempotencyFilter> reg = new FilterRegistrationBean<>(
                new IdempotencyFilter(catalog, idempotency, permissions, audit, props));
        reg.addUrlPatterns("/api/v1/*");
        reg.setOrder(IDEMPOTENCY_FILTER_ORDER);
        reg.setName("sangamIdempotencyFilter");
        return reg;
    }

    /** SL-38: the packaged deprecation registry (api/sangam-api-deprecations.yaml), validated at start-up. */
    @Bean
    @ConditionalOnMissingBean
    public org.sangam.rest.lifecycle.ApiDeprecationRegistry sangamApiDeprecationRegistry() {
        return org.sangam.rest.lifecycle.ApiDeprecationRegistry.fromClasspath();
    }

    /** SL-38: Deprecation / Sunset / Link headers and 410 API_SUNSET; runs before idempotency so retired calls never claim a key. */
    @Bean
    public FilterRegistrationBean<org.sangam.rest.lifecycle.DeprecationHeadersFilter> sangamDeprecationHeadersFilter(
            org.sangam.rest.lifecycle.ApiDeprecationRegistry registry) {
        FilterRegistrationBean<org.sangam.rest.lifecycle.DeprecationHeadersFilter> reg = new FilterRegistrationBean<>(
                new org.sangam.rest.lifecycle.DeprecationHeadersFilter(registry, java.time.Clock.systemUTC()));
        reg.addUrlPatterns("/api/v1/*");
        reg.setOrder(IDEMPOTENCY_FILTER_ORDER - 10);
        reg.setName("sangamDeprecationHeadersFilter");
        return reg;
    }

    @Bean
    public WebMvcConfigurer sangamRestWebMvcConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
                resolvers.add(new RequestContextArgumentResolver());
            }
        };
    }

    /**
     * {@code /api/v1/compliance} (sangam-compliance): registered only when the compliance module is on the class path
     * and the server provides a {@code SangamCompliance} bean. The controller and its problem advice live outside the
     * component-scanned {@code org.sangam.rest.web} package, so they are imported here.
     */
    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @org.springframework.boot.autoconfigure.condition.ConditionalOnClass(name = "org.sangam.compliance.SangamCompliance")
    @org.springframework.boot.autoconfigure.condition.ConditionalOnBean(type = "org.sangam.compliance.SangamCompliance")
    @org.springframework.context.annotation.Import({org.sangam.rest.compliance.ComplianceController.class,
            org.sangam.rest.compliance.ComplianceProblemAdvice.class})
    public static class ComplianceRestConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public org.sangam.rest.compliance.ComplianceRestService sangamComplianceApi(
                org.sangam.compliance.SangamCompliance compliance) {
            return new org.sangam.rest.compliance.ComplianceApiImpl(compliance);
        }
    }
}
