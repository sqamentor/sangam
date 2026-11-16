// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.sangam.core.security.SecurityIdentity;
import org.sangam.rest.adapter.memory.GroupPermissionAdapter;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.Permissions;
import org.sangam.rest.support.TxSupport;
import org.sangam.security.SangamSecurity;
import org.sangam.security.policy.ModuleResourceTypes;
import org.sangam.security.policy.Permission;
import org.sangam.security.policy.ResourceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * sangam-rest {@link PermissionPort} on sangam-security (VI.4).
 *
 * <p><b>Endpoint gate.</b> An endpoint permission is granted when the caller's groups grant it (the coarse persona map
 * of {@link Permissions}, the same as the REST default) <em>or</em> sangam-security grants the mapped permission on
 * every resource of the mapped type ({@code resourceId = '*'}). The gate decides only who may call an endpoint; the
 * object checks (deny wins, A-13; re-checked in the command transaction, VI.4 rule 5) are made by the adapters and by
 * sangam-security's listeners on the objects themselves.
 *
 * <p><b>Replay re-check (R41 step 4).</b> A stored response is returned only if the caller still satisfies the
 * command's replay rule ({@link #replayRule}: the permission its route declares in the contract, or an explicit
 * decision; a command type without a rule is refused, never sent to a default permission), and, when the command's resource type is known and the caller is not a tenant or platform
 * administrator, sangam-security still allows {@code READ} on the stored resource, evaluated without cache. That object
 * check is final: deny wins, and holding a persona group that grants the command's endpoint permission does not
 * override a refused object check (RM-11, owner decision 124 (C-09): the persona-group override was not intended and
 * is removed).
 */
public final class SecurityPermissionBridge implements PermissionPort {

    private static final Logger LOG = LoggerFactory.getLogger(SecurityPermissionBridge.class);

    record Mapping(String resourceType, Permission permission) {
    }

    /** Endpoint permission of {@code /scim/v2} (contract routes/scim.py; not a sangam-rest {@code Permissions} constant). */
    static final String SCIM_PROVISION = "scim.provision";

    /** Decision 141 endpoint permissions (sangam-rest UnverifiedPaymentController.ACCEPT and RESOLVE). */
    static final String BILLING_ACCEPT_UNVERIFIED = "billing.accept-unverified";
    static final String BILLING_RESOLVE_UNVERIFIED = "billing.resolve-unverified";

    /** Decision 149 endpoint permission (contract routes/continuation.py). */
    static final String PROCESS_CONTINUE = "process.continue";

    /**
     * Decision 197 (system ownership proof): read the shared-deployment registry ({@code GET
     * /admin/shared-deployments}). Platform-only, see {@link #PLATFORM_PERMISSIONS}.
     */
    public static final String SHARED_DEPLOYMENT_READ = "platform.shared-deployment.read";
    /**
     * Decision 197: register or revoke a shared deployment ({@code POST /admin/shared-deployments}, {@code DELETE
     * /admin/shared-deployments/{deploymentId}}); only an authorised platform deployer. Platform-only.
     */
    public static final String SHARED_DEPLOYMENT_MANAGE = "platform.shared-deployment.manage";
    /**
     * Decision 197: the platform deployer group. It counts only on a platform-issuer identity (marker
     * {@link Permissions#GROUP_PLATFORM}); {@code PlatformIssuers} removes it from every tenant token.
     */
    public static final String GROUP_PLATFORM_DEPLOYER = "sangam-platform-deployer";
    /**
     * Decision 144: read audit recorded under the system audit scope ({@code SystemAuditScope}); only the platform
     * administrator and the platform auditor on a platform-issuer identity. Tenant {@code audit.read} never reaches
     * system-scope rows: no caller's tenant can be a system scope (SecurityIdentityFilter refuses it).
     */
    public static final String SYSTEM_AUDIT_READ = "platform.audit.system.read";

    /**
     * Platform-only endpoint permissions → the groups that hold them, in addition to the platform marker. They are
     * decided here and nowhere else: no tenant role, no sangam-security grant (grants are per tenant) and no wildcard
     * (the {@code sangam-admin} {@code *} of the group map) confers them.
     */
    static final Map<String, Set<String>> PLATFORM_PERMISSIONS = Map.of(
            SHARED_DEPLOYMENT_MANAGE, Set.of(GROUP_PLATFORM_DEPLOYER),
            SHARED_DEPLOYMENT_READ, Set.of(GROUP_PLATFORM_DEPLOYER, Permissions.GROUP_ADMIN, Permissions.GROUP_OPERATOR),
            SYSTEM_AUDIT_READ, Set.of(Permissions.GROUP_ADMIN, Permissions.GROUP_AUDITOR),
            // decision 146: partition resize request, get and list act on the engine of every tenant
            Permissions.ADMIN_EXECUTOR, Set.of(Permissions.GROUP_ADMIN, Permissions.GROUP_OPERATOR),
            // decision 381(3): the tenant migration inventory and repair span tenants; platform administrator only
            Permissions.TENANT_MIGRATION_ADMIN, Set.of(Permissions.GROUP_ADMIN));

    /** Command type prefix of the decision 146 partition resize. */
    private static final String EXECUTOR_COMMANDS = "executor.partitions.";

    /** Command type prefix of the decision 227 DSC certificate enrolment commands. */
    static final String DSC_CERTIFICATE_COMMANDS = "dsc.certificate.";

    /** Decision 227: the permission a replay of each enrolment command re-checks; any other type is refused. */
    static final Map<String, String> DSC_CERTIFICATE_REPLAY = Map.of(
            "dsc.certificate.approve", Permissions.DSC_CERTIFICATE_APPROVE,
            "dsc.certificate.reject", Permissions.DSC_CERTIFICATE_APPROVE,
            "dsc.certificate.revoke", Permissions.DSC_CERTIFICATE_APPROVE,
            "dsc.certificate.enrol", Permissions.TASK_WRITE,
            "dsc.certificate.revoke-own", Permissions.TASK_WRITE);

    /**
     * Decision 258 (FLOWABLE-INTO-OPERATON FC-18): the idempotent command of {@code POST /admin/history/process-instances/{id}/migrate}. Its
     * replay re-checks {@link Permissions#HISTORY_MIGRATE}; the default rule would only ask for {@code process.read}.
     */
    static final String HISTORY_MIGRATE_COMMAND = "history.process-instance.migrate";

    /** Command type prefixes of the shared-deployment registry (any naming the contract picks). */
    private static final java.util.List<String> SHARED_DEPLOYMENT_COMMANDS = java.util.List.of(
            "platform.shared-deployment.", "admin.shared-deployment.", "shared-deployment.");

    /** REST endpoint permission → (security resource type, permission). */
    static final Map<String, Mapping> MAP = Map.ofEntries(
            Map.entry(Permissions.WORK_EXECUTE, new Mapping(ResourceRegistry.WORKER_TOPIC, Permission.WORK)),
            Map.entry(Permissions.TASK_READ, new Mapping(ResourceRegistry.TASK, Permission.READ_TASK)),
            Map.entry(Permissions.TASK_WRITE, new Mapping(ResourceRegistry.TASK, Permission.COMPLETE)),
            Map.entry(Permissions.PROCESS_READ, new Mapping(ResourceRegistry.PROCESS_INSTANCE, Permission.READ)),
            Map.entry(Permissions.PROCESS_START, new Mapping(ResourceRegistry.PROCESS_DEFINITION, Permission.START)),
            Map.entry(Permissions.PROCESS_WRITE, new Mapping(ResourceRegistry.PROCESS_INSTANCE, Permission.MODIFY)),
            Map.entry(Permissions.PROCESS_CANCEL, new Mapping(ResourceRegistry.PROCESS_INSTANCE, Permission.CANCEL)),
            Map.entry(Permissions.PROCESS_DELETE, new Mapping(ResourceRegistry.PROCESS_INSTANCE, Permission.DELETE_INSTANCE)),
            Map.entry(Permissions.PROCESS_MODIFY, new Mapping(ResourceRegistry.PROCESS_INSTANCE, Permission.MODIFY)),
            Map.entry(Permissions.CASE_READ, new Mapping(ResourceRegistry.CASE_INSTANCE, Permission.READ)),
            Map.entry(Permissions.CASE_WRITE, new Mapping(ResourceRegistry.CASE_INSTANCE, Permission.COMPLETE)),
            Map.entry(Permissions.DECISION_EVALUATE, new Mapping(ResourceRegistry.DECISION_DEFINITION, Permission.READ)),
            Map.entry(Permissions.MESSAGE_SEND, new Mapping(ResourceRegistry.PROCESS_INSTANCE, Permission.MODIFY)),
            Map.entry(Permissions.DEPLOYMENT_READ, new Mapping(ResourceRegistry.DEPLOYMENT, Permission.READ)),
            Map.entry(Permissions.DEPLOYMENT_WRITE, new Mapping(ResourceRegistry.DEPLOYMENT, Permission.DEPLOY)),
            Map.entry(Permissions.PACKAGE_READ, new Mapping(ResourceRegistry.DEPLOYMENT, Permission.READ)),
            Map.entry(Permissions.PACKAGE_REVIEW, new Mapping(ResourceRegistry.DEPLOYMENT, Permission.REVIEW_PACKAGE)),
            Map.entry(Permissions.PACKAGE_APPROVE, new Mapping(ResourceRegistry.DEPLOYMENT, Permission.REVIEW_PACKAGE)),
            Map.entry(Permissions.PACKAGE_DEPLOY, new Mapping(ResourceRegistry.DEPLOYMENT, Permission.DEPLOY)),
            Map.entry(Permissions.HISTORY_READ, new Mapping(ResourceRegistry.HISTORIC_PROCESS_INSTANCE, Permission.READ_HISTORY)),
            Map.entry(Permissions.AUDIT_READ, new Mapping(ResourceRegistry.AUDIT, Permission.READ_AUDIT)),
            Map.entry(Permissions.AUDIT_EXPORT, new Mapping(ResourceRegistry.AUDIT, Permission.EXPORT)),
            Map.entry(Permissions.ADMIN_READ, new Mapping(ResourceRegistry.ORG_UNIT, Permission.READ)),
            Map.entry(Permissions.ADMIN_WRITE, new Mapping(ResourceRegistry.ORG_UNIT, Permission.ADMIN)),
            Map.entry(Permissions.POLICY_ADMIN, new Mapping(ResourceRegistry.POLICY, Permission.CHANGE_GRANT)),
            Map.entry(Permissions.TENANT_ADMIN, new Mapping(ResourceRegistry.TENANT, Permission.ADMIN)),
            Map.entry(Permissions.OPS_READ, new Mapping(ResourceRegistry.OPERATIONS, Permission.READ)),
            Map.entry(Permissions.OPS_WRITE, new Mapping(ResourceRegistry.OPERATIONS, Permission.ADMIN)),
            Map.entry(Permissions.RECON_RESOLVE, new Mapping(ResourceRegistry.RECONCILIATION_ITEM, Permission.MODIFY)),
            Map.entry(Permissions.BATCH_ADMIN, new Mapping(ResourceRegistry.BATCH, Permission.BULK)),
            Map.entry(Permissions.EVENT_ADMIN, new Mapping(ResourceRegistry.OPERATIONS, Permission.ADMIN)),
            Map.entry(Permissions.EVENT_PUBLISH, new Mapping(ResourceRegistry.OPERATIONS, Permission.WORK)),
            Map.entry(Permissions.DOCUMENT_READ, new Mapping(ResourceRegistry.PROCESS_INSTANCE, Permission.READ)),
            Map.entry(Permissions.DOCUMENT_WRITE, new Mapping(ResourceRegistry.PROCESS_INSTANCE, Permission.MODIFY)),
            Map.entry(Permissions.DOCUMENT_LEGAL_HOLD, new Mapping(ResourceRegistry.AUDIT, Permission.ERASE_DATA)),
            Map.entry(Permissions.ANALYTICS_READ, new Mapping(ResourceRegistry.HISTORIC_PROCESS_INSTANCE, Permission.READ_HISTORY)),
            Map.entry(Permissions.POWER_TABLE_APPROVE, new Mapping(ResourceRegistry.POWER_TABLE, Permission.APPROVE)),
            // decision 154 (M-099): the IdP provisioning client passes the same USER ADMIN gate as the admin screens; the
            // SCIM service re-checks USER ADMIN and GROUP ADMIN on the objects
            Map.entry(SCIM_PROVISION, new Mapping(ResourceRegistry.USER, Permission.ADMIN)),
            // decision 141: explicit-only, so the gate needs one explicit channel grant (see gate); the authority
            // (UnverifiedPaymentAuthorityBridge) decides on the exact channel, office and tenant
            Map.entry(BILLING_ACCEPT_UNVERIFIED, new Mapping(ModuleResourceTypes.BILLING, Permission.ACCEPT_UNVERIFIED)),
            Map.entry(BILLING_RESOLVE_UNVERIFIED, new Mapping(ModuleResourceTypes.BILLING, Permission.RESOLVE_UNVERIFIED)),
            // decision 149 (W4-E contract v1): continue-as-new ends the instance and starts its successor
            Map.entry(PROCESS_CONTINUE, new Mapping(ResourceRegistry.PROCESS_INSTANCE, Permission.MODIFY)),
            // decision 227: binding a person's signing certificate is user administration (tenant grant); the
            // controller refuses self-approval as a second check
            Map.entry(Permissions.DSC_CERTIFICATE_APPROVE, new Mapping(ResourceRegistry.USER, Permission.ADMIN)),
            // decision 258 (FLOWABLE-INTO-OPERATON FC-18): admin-only, so the tenant administration gate (as tenant.admin), never a grant on
            // historic instances; untenanted history needs a platform identity (HistoricInstanceMigration)
            Map.entry(Permissions.HISTORY_MIGRATE, new Mapping(ResourceRegistry.TENANT, Permission.ADMIN)),
            // decision 305: the tenant's DLT registration is tenant administration, read and write alike
            Map.entry(Permissions.DLT_REGISTRATION_READ, new Mapping(ResourceRegistry.TENANT, Permission.ADMIN)),
            Map.entry(Permissions.DLT_REGISTRATION_WRITE, new Mapping(ResourceRegistry.TENANT, Permission.ADMIN)));

    /** R41 command type prefix → resource type of {@code resource_ref}. */
    private static final Map<String, String> COMMAND_RESOURCE = Map.of(
            "task.", ResourceRegistry.TASK,
            "process.", ResourceRegistry.PROCESS_INSTANCE,
            "message.", ResourceRegistry.PROCESS_INSTANCE,
            "case.", ResourceRegistry.CASE_INSTANCE,
            "deployment.", ResourceRegistry.DEPLOYMENT,
            "ops.reconciliation.", ResourceRegistry.RECONCILIATION_ITEM,
            "ops.batch.", ResourceRegistry.BATCH);

    private final SangamSecurity security;
    private final TxSupport tx;
    private final GroupPermissionAdapter groups;
    private final ReplayRules replayRules;
    private final BulkJobPermissions bulkJobs;

    /**
     * The replay rules are those of the contract on the class path. No bulk-job resolver: a replay of a bulk command is
     * refused (owner decision 273).
     */
    public SecurityPermissionBridge(SangamSecurity security, TxSupport tx, GroupPermissionAdapter groups) {
        this(security, tx, groups, (BulkJobPermissions) null);
    }

    /** Owner decision 273: {@code bulkJobs} resolves a stored bulk job to its sink permission (null: none). */
    public SecurityPermissionBridge(SangamSecurity security, TxSupport tx, GroupPermissionAdapter groups,
                                    BulkJobPermissions bulkJobs) {
        this(security, tx, groups, ReplayRules.fromClassPath(), bulkJobs);
    }

    SecurityPermissionBridge(SangamSecurity security, TxSupport tx, GroupPermissionAdapter groups,
                             ReplayRules replayRules, BulkJobPermissions bulkJobs) {
        this.security = security;
        this.tx = tx;
        this.groups = groups;
        this.replayRules = java.util.Objects.requireNonNull(replayRules, "replayRules");
        this.bulkJobs = bulkJobs;
    }

    /** Decision 197: a platform-only permission, decided by the platform marker and the named groups alone. */
    static boolean platformHolds(RequestContext ctx, String permission) {
        Set<String> holders = PLATFORM_PERMISSIONS.get(permission);
        if (ctx == null || holders == null || !ctx.inGroup(Permissions.GROUP_PLATFORM)
                || ctx.inGroup(Permissions.GROUP_SYSTEM)) {
            return false;
        }
        return holders.stream().anyMatch(ctx::inGroup);
    }

    @Override
    public boolean has(RequestContext ctx, String permission) {
        if (PLATFORM_PERMISSIONS.containsKey(permission)) {
            return platformHolds(ctx, permission);
        }
        if (groups.has(ctx, permission)) {
            return true;
        }
        Mapping m = MAP.get(permission);
        return m != null && gate(SecurityIdentities.of(ctx), m);
    }

    /**
     * The endpoint gate of a mapping: the permission on every resource of the type, except for an explicit-only one
     * (decision 141), which no wildcard confers: there the caller must hold it explicitly on at least one resource, and
     * the object check on the exact resource decides. The system identity never passes an explicit-only gate.
     */
    private boolean gate(SecurityIdentity id, Mapping m) {
        if (!m.permission().explicitOnly()) {
            return allow(id, m.resourceType(), m.permission(), ResourceRegistry.ANY_ID);
        }
        if (id.system()) {
            return false;
        }
        try {
            return tx.withConnection(c -> security.compiler().holdsExplicitGrant(c, id, m.resourceType(), m.permission()));
        } catch (RuntimeException e) {
            LOG.debug("explicit grant check {} on {} failed: {}", m.permission(), m.resourceType(), e.toString());
            return false;
        }
    }

    @Override
    public Set<String> permissions(RequestContext ctx) {
        Set<String> out = new LinkedHashSet<>(groups.permissions(ctx));
        SecurityIdentity id = SecurityIdentities.of(ctx);
        MAP.forEach((name, m) -> {
            if (!out.contains(name) && gate(id, m)) {
                out.add(name);
            }
        });
        // decision 197: platform-only permissions are listed exactly when held, never because of a group map entry
        PLATFORM_PERMISSIONS.keySet().forEach(name -> {
            if (platformHolds(ctx, name)) {
                out.add(name);
            } else {
                out.remove(name);
            }
        });
        return out;
    }

    /**
     * The requirement a replay of {@code commandType} re-checks, or {@code null} when the type has none, which refuses
     * the replay (fail closed). The explicit decisions come first; every other type takes the permission its route
     * declares in the contract ({@link ReplayRules}).
     */
    ReplayRules.Rule replayRule(String commandType) {
        if (commandType == null) {
            return null;
        }
        if (SHARED_DEPLOYMENT_COMMANDS.stream().anyMatch(commandType::startsWith)) {
            // decision 197: a stored registry response goes only to a caller who may still manage the registry
            return ReplayRules.Holds.of(SHARED_DEPLOYMENT_MANAGE);
        }
        if (commandType.startsWith(EXECUTOR_COMMANDS)) {
            // decision 146: a stored resize response goes only to a caller who still holds executor.admin
            return ReplayRules.Holds.of(Permissions.ADMIN_EXECUTOR);
        }
        if (commandType.startsWith(DSC_CERTIFICATE_COMMANDS)) {
            String p = DSC_CERTIFICATE_REPLAY.get(commandType);
            // decision 227: re-check the command's own permission; an unknown dsc.certificate.* type is refused. The
            // own-principal rule of enrol and revoke-own holds already: the idempotency store refuses another
            // principal (422 IDEMPOTENCY_KEY_PRINCIPAL_MISMATCH) before a replay is offered.
            return p == null ? null : ReplayRules.Holds.of(p);
        }
        if (HISTORY_MIGRATE_COMMAND.equals(commandType)) {
            // decision 258: a stored history migration goes only to a caller who still holds history.migrate
            return ReplayRules.Holds.of(Permissions.HISTORY_MIGRATE);
        }
        return replayRules.rule(commandType).orElse(null);
    }

    private boolean satisfies(RequestContext ctx, ReplayRules.Rule rule, String resourceRef) {
        return switch (rule) {
            // platform-only permissions go through has() as well, which decides them by platformHolds alone
            case ReplayRules.Holds h -> h.permissions().stream().allMatch(p -> has(ctx, p));
            case ReplayRules.InGroup g -> g.groups().stream().anyMatch(ctx::inGroup);
            case ReplayRules.Authenticated a -> ctx.tenantId() != null && ctx.principalId() != null
                    && !ctx.principalId().isBlank();
            case ReplayRules.BulkJobSink b -> bulkJobSinkHeld(ctx, resourceRef);
        };
    }

    /** Owner decision 273: the caller still holds the stored bulk job's sink permission; anything unresolved refuses. */
    private boolean bulkJobSinkHeld(RequestContext ctx, String resourceRef) {
        if (bulkJobs == null || ctx.tenantId() == null || resourceRef == null
                || !resourceRef.startsWith(ReplayRules.BULK_JOB_REF)) {
            return false;
        }
        String jobId = resourceRef.substring(ReplayRules.BULK_JOB_REF.length());
        if (jobId.isBlank() || jobId.contains(",")) {
            return false;
        }
        java.util.Optional<String> permission;
        try {
            permission = bulkJobs.sinkPermission(ctx.tenantId(), jobId);
        } catch (RuntimeException e) {
            LOG.debug("bulk job {} sink permission lookup failed: {}", jobId, e.toString());
            return false;
        }
        return permission != null && permission.filter(p -> !p.isBlank()).map(p -> has(ctx, p)).orElse(false);
    }

    @Override
    public boolean mayReplay(RequestContext ctx, String commandType, String resourceRef) {
        ReplayRules.Rule rule = replayRule(commandType);
        // fail closed: a type without a rule is refused, never sent to a default permission
        if (ctx == null || rule == null || !satisfies(ctx, rule, resourceRef)) {
            return false;
        }
        String type = resourceType(commandType);
        if (type == null || resourceRef == null || resourceRef.isBlank() || resourceRef.contains(",")
                || ctx.inGroup(Permissions.GROUP_ADMIN) || ctx.inGroup(Permissions.GROUP_TENANT_ADMIN)) {
            return true;
        }
        // RM-11, owner decision 124 (C-09): deny wins. A persona group never overrides the object check on replay.
        return allow(SecurityIdentities.of(ctx), type, Permission.READ, resourceRef);
    }

    private boolean allow(SecurityIdentity id, String resourceType, Permission permission, String resourceId) {
        try {
            return tx.withConnection(c -> security.authorization().allow(c, id, resourceType, permission, resourceId,
                    Map.of()));
        } catch (RuntimeException e) {
            LOG.debug("authorization check {} {} on {} failed: {}", permission, resourceType, resourceId, e.toString());
            return false;
        }
    }

    private static String resourceType(String commandType) {
        if (commandType == null) {
            return null;
        }
        return COMMAND_RESOURCE.entrySet().stream().filter(e -> commandType.startsWith(e.getKey()))
                .map(Map.Entry::getValue).findFirst().orElse(null);
    }
}
