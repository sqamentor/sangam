// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.sangam.core.layer.Transactions;
import org.sangam.core.ports.IdentityExtrasPort;
import org.sangam.rest.adapter.memory.GroupPermissionAdapter;
import org.sangam.rest.adapter.jdbc.JdbcAdminAdapter;
import org.sangam.rest.adapter.jdbc.JdbcIdentityAdminAdapter;
import org.sangam.rest.port.AdminPort;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.IdentityAdminPort;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.TaskPort;
import org.sangam.rest.support.RestProperties;
import org.sangam.rest.support.TxSupport;
import org.sangam.security.SangamSecurity;
import org.sangam.security.assignment.SangamSecurityEnginePlugin;
import org.sangam.security.audit.LoginEventBatcher;
import org.sangam.security.dialect.SecurityDialect;
import org.sangam.security.oidc.SecurityProperties;
import org.sangam.security.spring.TokenAuthenticationFilters;
import org.sangam.server.bridge.SecurityAdminBridge;
import org.sangam.server.bridge.SecurityBootstrap;
import org.sangam.server.bridge.SecurityChainContributions;
import org.sangam.server.bridge.SecurityIdentityAdminBridge;
import org.sangam.server.bridge.SecurityIdentityExtras;
import org.sangam.server.bridge.SecurityIdentityFilter;
import org.sangam.server.bridge.BulkJobPermissions;
import org.sangam.server.bridge.SecurityPermissionBridge;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * sangam-security wiring (sangam-security README "Integration notes" 3–6, 9, 11).
 *
 * <ul>
 *   <li><b>One filter chain.</b> The server's {@code SecurityConfig} stays the only {@code SecurityFilterChain} (deploy
 *       contract: configurable OIDC claims, dev token, {@code SEAT_NOT_HELD}, problem+json). sangam-security's
 *       {@code SangamResourceServerConfiguration} is not imported; its API-token filter and an identity-resolution
 *       filter ({@link SecurityIdentityFilter}) are added to the server chain through {@link SecurityChainContributions}.</li>
 *   <li><b>Beans:</b> {@link SangamSecurity}, the product {@link Transactions} (independent short JDBC transactions of
 *       the module), the denial-audit transaction on the {@code sangam-security-audit} leaf pool
 *       ({@link SecurityAuditPoolWiring}, SL-132) so {@code AUTH_DENIED}/{@code SOD_VIOLATION_BLOCKED} survive a rollback,
 *       {@link SecurityProperties} bound to {@code sangam.security} (no field clash with the server's
 *       {@code SangamSecurityProperties}), {@link SangamSecurityEnginePlugin} (seat assignment, tenant id provider,
 *       Operaton authorization off), and {@link IdentityExtrasPort} for sangam-flowable-ports.</li>
 *   <li><b>REST ports replaced:</b> {@code PermissionPort}, {@code AdminPort}, {@code IdentityAdminPort} (bridges in
 *       {@code org.sangam.server.bridge}).</li>
 * </ul>
 * Disable with {@code sangam.modules.security.enabled=false}.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "sangam.modules.security.enabled", havingValue = "true", matchIfMissing = true)
public class SecurityWiring {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(SecurityWiring.class);

    @Bean
    @ConfigurationProperties("sangam.security")
    public SecurityProperties sangamSecurityModuleProperties() {
        return new SecurityProperties();
    }

    /** Product-layer transactions on the pool: own, short transactions (not the engine/command transaction). */
    @Bean
    public Transactions sangamProductTransactions(DataSource dataSource) {
        return Transactions.of(dataSource);
    }

    @Bean
    public SangamSecurity sangamSecurity(DataSource dataSource, Transactions sangamProductTransactions,
                                         RestProperties rest, Environment env,
                                         SecurityAuditPoolWiring.SecurityAuditPool sangamSecurityAuditPool,
                                         ObjectProvider<org.operaton.bpm.engine.ProcessEngine> engine) {
        SecurityDialect dialect;
        try (var c = dataSource.getConnection()) {
            dialect = SecurityDialect.detect(c);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("cannot detect the database dialect for sangam-security", e);
        }
        Duration staleness = env.getProperty("sangam.security.max-staleness", Duration.class, Duration.ofSeconds(5));
        SangamSecurity.Options options = new SangamSecurity.Options(staleness, null, rest.getBatchFourEyesThreshold(),
                denialTransactions(sangamSecurityAuditPool, sangamProductTransactions), null);
        SangamSecurity security = new SangamSecurity(dialect, options);
        // decision 368: the platform groups used as token scopes are role rows of every tenant, from the code that grants them
        security.roles().usePlatformRoles(platformRoles());
        // decision 193: the trusted case-type metadata of power checks; unbound, every power check is DENY
        security.powers().useOperationMetadata(operationMetadata(engine));
        // decision 97 / M-016: a dedicated instance creates no tenant but its own (as DATABASE isolation)
        String dedicated = env.getProperty(org.sangam.server.bridge.DedicatedTenant.PROPERTY);
        if (dedicated != null) {
            security.tenants().useDedicatedTenant(org.sangam.server.bridge.DedicatedTenant.of(dedicated).tenant());
        }
        return security;
    }

    /**
     * Decision 368: the platform roles, derived from the code: the groups the server grants permissions to
     * ({@link org.sangam.rest.support.Permissions#DEFAULT_GROUP_PERMISSIONS}). Each is a role row (SEC_ROLE) of every
     * tenant, created at tenant set-up, so a service principal's scope binds through {@code RoleAdminService#bind}.
     * {@code sangam-admin}, the platform and the engine groups are not in the map and so are never token scopes.
     */
    public static java.util.Set<String> platformRoles() {
        return org.sangam.rest.support.Permissions.DEFAULT_GROUP_PERMISSIONS.keySet();
    }

    /**
     * The trusted operation metadata of the delegation-of-powers checks (owner decision 193): the {@code powers}
     * declarations of the deployed pack manifest of the case type and version, read from the engine's deployments
     * ({@link org.sangam.packs.core.PackPowers}); never a request, a process variable or the class path. The engine is
     * looked up at each check (it is created after this module); without an engine, or for a case type no pack
     * deployment defines, nothing is classified and the check is denied.
     */
    static org.sangam.core.power.OperationMetadata operationMetadata(
            ObjectProvider<org.operaton.bpm.engine.ProcessEngine> engine) {
        return new org.sangam.core.power.ManifestOperationMetadata((tenant, caseType, caseTypeVersion) -> {
            org.operaton.bpm.engine.ProcessEngine e = engine.getIfAvailable();
            return e == null ? Optional.empty()
                    : new org.sangam.packs.core.PackPowers(e.getRepositoryService()).deployed(tenant, caseType, caseTypeVersion);
        });
    }

    /**
     * SL-132: denial audits commit on the {@code sangam-security-audit} leaf pool (never a second main-pool connection
     * while the refused caller holds one: SL-43); without a leaf pool (main pool not HikariCP), on the product
     * transactions as before.
     */
    static Transactions denialTransactions(SecurityAuditPoolWiring.SecurityAuditPool pool, Transactions product) {
        Transactions leaf = pool.transactionsOrNull();
        return leaf != null ? leaf : product;
    }

    /** The 403 code of a worker call on a topic the worker is not bound to (SL-158). */
    static final String TOPIC_NOT_BOUND = "WORKER_TOPIC_NOT_BOUND";

    /**
     * SL-158 (R26, owner decision 2026-11-12: enforced in 0.1.0): a worker fetches, completes or fails work only on
     * topics it is bound to (WORK on {@code WORKER_TOPIC/<topic>}: a worker binding, or a grant of an administrator
     * role). The check runs on the caller's transaction connection; a refusal records {@code AUTH_DENIED} on the
     * denial chain (leaf pool) and answers 403 {@value #TOPIC_NOT_BOUND} naming the worker and the topic. Migration:
     * bind every worker principal to its topics (service principals are bound when created with their topics).
     */
    /** Decisions 87 and 98: the field masks of every sangam-rest variable read (VariableMasking resolves it per read). */
    @Bean
    public org.sangam.rest.port.VariableMaskPort sangamVariableMaskPort(SangamSecurity security, TxSupport tx) {
        return new org.sangam.server.bridge.VariableMaskBridge(security, tx);
    }

    @Bean
    public org.sangam.rest.adapter.operaton.WorkerTopicGuard sangamWorkerTopicGuard(SangamSecurity security, TxSupport tx) {
        return workerTopicGuard(security, tx);
    }

    static org.sangam.rest.adapter.operaton.WorkerTopicGuard workerTopicGuard(SangamSecurity security, TxSupport tx) {
        return (ctx, topic) -> {
            try {
                tx.withConnection(c -> {
                    security.workers().requireTopic(c, org.sangam.server.bridge.SecurityIdentities.of(ctx), topic);
                    return null;
                });
            } catch (org.sangam.core.security.SangamSecurityException.AccessDenied e) {
                throw new org.sangam.rest.port.ApiProblem(403, TOPIC_NOT_BOUND, "worker '" + ctx.principalId()
                        + "' is not bound to topic '" + topic + "': bind the worker to the topic (WORK on WORKER_TOPIC/"
                        + topic + "; POST /api/v1/admin/service-principals with topics, or a worker binding)")
                        .with("topic", topic);
            }
        };
    }

    /**
     * Decision 141: unverified payments are authorised on sangam-security (explicit ACCEPT_UNVERIFIED and
     * RESOLVE_UNVERIFIED on BILLING {@code payment-channel:<channel>@<office>}, only for the request's authenticated
     * person). BillingWiring takes it through an ObjectProvider; without sangam-security every acceptance is refused.
     */
    @Bean
    public org.sangam.billing.UnverifiedPaymentAuthority sangamUnverifiedPaymentAuthority(SangamSecurity security,
                                                                                         TxSupport tx) {
        return new org.sangam.server.bridge.UnverifiedPaymentAuthorityBridge(security, tx);
    }

    /**
     * Register E-24: {@code GET /me} (and what the workspace derives from the caller's seats) reads the caller's own
     * seats from sangam-security's seat directory, by the rule the seat context of a request is checked with
     * ({@link org.sangam.server.bridge.OwnSeatHoldings}); not from the administrators' posting directory, which an
     * officer may not read.
     */
    @Bean
    public org.springframework.beans.factory.InitializingBean sangamOwnSeatHoldings(
            ObjectProvider<org.sangam.rest.port.WorkspacePort> workspace, SangamSecurity security, TxSupport tx) {
        return () -> {
            if (workspace.getIfAvailable() instanceof org.sangam.rest.adapter.operaton.OperatonWorkspaceAdapter w) {
                w.setOwnHoldings(new org.sangam.server.bridge.OwnSeatHoldings(security, tx));
                // E-32(c), E-31(a): the officer acted for and the holder of a substitute seat, without the admin directory
                w.setSeatHolders(new org.sangam.server.bridge.SeatHolderDirectory(security, tx));
            }
        };
    }

    @Bean
    public SangamSecurityEnginePlugin sangamSecurityEnginePlugin(SangamSecurity security) {
        return new SangamSecurityEnginePlugin(security);
    }

    @Bean(destroyMethod = "close")
    public LoginEventBatcher sangamLoginEventBatcher(Transactions sangamProductTransactions, SangamSecurity security,
                                                     SecurityProperties props) {
        return new LoginEventBatcher(sangamProductTransactions, security.audit(), props.getLoginBatchMillis());
    }

    @Bean
    public SecurityChainContributions sangamSecurityChainContributions(Transactions sangamProductTransactions,
            SangamSecurity security, SecurityProperties props, LoginEventBatcher logins,
            org.springframework.core.env.Environment env) {
        // decision 152: development identities only in a validated development environment (decision 143)
        return new SecurityChainContributions(
                new TokenAuthenticationFilters.ApiTokenFilter(sangamProductTransactions, security.tokens()),
                new SecurityIdentityFilter(sangamProductTransactions, security, props, logins,
                        ProductionEnvironment.developmentDefaultsAllowed(env)));
    }

    // ---------------------------------------------------------------- sangam-rest ports on sangam-security

    /**
     * Owner decision 273: the replay of a bulk command re-checks the permission of the stored job's sink, resolved by
     * {@link #bulkJobPermissions}; without the ops module (no BulkIngest) every bulk replay stays refused.
     */
    @Bean
    public PermissionPort sangamSecurityPermissionPort(SangamSecurity security, TxSupport tx,
            ObjectProvider<org.sangam.ops.bulk.BulkIngest> bulkIngest) {
        return new SecurityPermissionBridge(security, tx, new GroupPermissionAdapter(),
                bulkJobPermissions(bulkIngest::getIfAvailable));
    }

    /**
     * Owner decision 273: a stored bulk job's sink permission, {@code BulkIngest.job(tenant, job)}'s object type then
     * that type's sink. BulkIngest is looked up per call (no start-up cycle with the permission port). Empty, never
     * thrown, for an absent BulkIngest, a blank tenant or job, an unknown job or one of another tenant (BulkIngest is
     * tenant-scoped and answers NOT_FOUND), a type without a sink, or any other failure, which refuses the replay.
     */
    static BulkJobPermissions bulkJobPermissions(Supplier<org.sangam.ops.bulk.BulkIngest> bulkIngest) {
        return (tenantId, jobId) -> {
            if (tenantId == null || tenantId.isBlank() || jobId == null || jobId.isBlank()) {
                return Optional.empty();
            }
            try {
                org.sangam.ops.bulk.BulkIngest ingest = bulkIngest.get();
                if (ingest == null) {
                    return Optional.empty();
                }
                String objectType = ingest.job(tenantId, jobId).objectType();
                return ingest.sink(objectType).map(org.sangam.ops.bulk.BulkIngest.Sink::permission)
                        .filter(p -> !p.isBlank());
            } catch (RuntimeException e) {
                LOG.debug("bulk job {} sink permission not resolved: {}", jobId, e.toString());
                return Optional.empty();
            }
        };
    }

    @Bean
    public AdminPort sangamSecurityAdminPort(SangamSecurity security, TxSupport tx, AuditPort audit,
                                             RestProperties props, org.sangam.rest.support.DocumentStore store,
                                             org.springframework.beans.factory.ObjectProvider<org.sangam.core.time.BusinessClock> businessClock) {
        // Parts the security bridge does not serve (person delegations) persist in REST_DOCUMENT.
        return new SecurityAdminBridge(security, tx, new JdbcAdminAdapter(store, tx, audit, props,
                new org.sangam.rest.support.BusinessDates(businessClock::getIfAvailable)), props);
    }

    @Bean
    public IdentityAdminPort sangamSecurityIdentityAdminPort(SangamSecurity security, TxSupport tx, AdminPort admin,
            TaskPort tasks, PermissionPort permissions, AuditPort audit, RestProperties props, Environment env,
            org.sangam.rest.support.DocumentStore store) {
        IdentityAdminPort fallback = new JdbcIdentityAdminAdapter(store, admin, tasks, permissions, audit, props,
                env.getProperty("sangam.outbound.enabled", Boolean.class, false));
        return new SecurityIdentityAdminBridge(security, tx, fallback, props, audit);
    }

    /** For sangam-flowable-ports' {@code IdmFacade} (FLOWABLE-INTO-OPERATON FC-42). */
    @Bean
    public IdentityExtrasPort sangamIdentityExtrasPort(SangamSecurity security) {
        return new SecurityIdentityExtras(security.privileges(), security.tokens());
    }

    /** Actor id of the start-time platform role rows (decision 370), recorded as their {@code updated_by}. */
    static final String PLATFORM_ROLE_ROWS_ACTOR = "platform-role-rows";

    /**
     * Decision 370 (1): at start, every existing tenant gets the platform role rows it lacks (the same code-derived
     * list as a new tenant, {@link #platformRoles()}): idempotent, never overwriting a role an administrator defined,
     * audited per tenant. A failure stops the start (fail closed): a tenant without its rows would refuse its service
     * principals' scopes.
     */
    @Bean
    public org.springframework.boot.ApplicationRunner sangamPlatformRoleRows(SangamSecurity security,
                                                                             Transactions sangamProductTransactions) {
        return args -> fillPlatformRoleRows(security, sangamProductTransactions);
    }

    static Map<String, List<String>> fillPlatformRoleRows(SangamSecurity security, Transactions tx) {
        Map<String, List<String>> created = security.roles().ensurePlatformRolesOfEveryTenant(tx, PLATFORM_ROLE_ROWS_ACTOR);
        if (!created.isEmpty()) {
            LOG.info("platform role rows created at start (decision 370): {}", created);
        }
        return created;
    }

    /** Actor id of the start-time policy split (decision 392), recorded on its audit events and superseded changes. */
    static final String POLICY_SPLIT_ACTOR = SecurityBootstrap.POLICY_SPLIT_ACTOR;

    /**
     * Decision 392 data migration (decision 409): at start, every tenant's policies that name several principals are
     * split into one policy id per principal, audited per split, and pending four-eyes changes they invalidate are
     * marked SUPERSEDED. Cluster-safe (tenant row lock) and idempotent; a failure stops the start (fail closed) with
     * the failing tenant's transaction rolled back.
     */
    @Bean
    public org.springframework.boot.ApplicationRunner sangamPolicySplit(SangamSecurity security,
                                                                        Transactions sangamProductTransactions) {
        return args -> splitPolicies(security, sangamProductTransactions);
    }

    static Map<String, org.sangam.security.admin.PolicyAdminService.SplitResult> splitPolicies(SangamSecurity security,
                                                                                              Transactions tx) {
        var split = security.policies().splitMultiPrincipalOfEveryTenant(tx, POLICY_SPLIT_ACTOR);
        if (!split.isEmpty()) {
            LOG.info("multi-principal policies split at start (decision 392): {}", split);
        }
        return split;
    }

    /** Dev only: tenant + administrator grants on an empty database (see {@link SecurityBootstrap}). */
    @Bean
    @ConditionalOnProperty(name = "sangam.security.bootstrap.enabled", havingValue = "true")
    public InitializingBean sangamSecurityBootstrap(SangamSecurity security, Transactions sangamProductTransactions,
                                                    SecurityProperties props, Environment env,
                                                    org.springframework.beans.factory.BeanFactory beans) {
        if (!ProductionEnvironment.isProduction(env) && !ProductionEnvironment.developmentDefaultsAllowed(env)) {
            // decisions 143 and 152: the bootstrap grants every resource type to the admin groups, a development
            // default; staging is refused here, production by SecurityBootstrap.run itself (SL-109 message)
            throw new IllegalStateException("sangam.security.bootstrap.enabled=true is allowed only in a development"
                    + " environment (sangam.security.production=false with a dev, h2, test or local profile;"
                    + " decisions 143 and 152)");
        }
        return () -> {
            for (String name : List.of("sangamLiquibase", "sangamLiquibaseTask", "sangamDevSchema")) {
                if (beans.containsBean(name)) {
                    beans.getBean(name); // the product schema must exist first (SchemaBootstrap)
                }
            }
            List<String> tenants = List.of(env.getProperty("sangam.security.bootstrap.tenants", String[].class,
                    new String[]{env.getProperty("sangam.security.dev-token.tenant", "dev")}));
            List<String> groups = List.of(env.getProperty("sangam.security.bootstrap.admin-groups", String[].class,
                    // SL-70: sangam-admin in a tenant token arrives as sangam-tenant-admin
                    new String[]{"sangam-admin", "sangam-tenant-admin"}));
            SecurityBootstrap.run(security, sangamProductTransactions, tenants, groups,
                    ProductionEnvironment.isProduction(env)); // SL-109
        };
    }
}
