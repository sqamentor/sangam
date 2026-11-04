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
 * <p><b>Replay re-check (R41 step 4).</b> A stored response is returned only if the caller still passes the endpoint
 * gate for the command, and, when the command's resource type is known and the caller is not a tenant or platform
 * administrator, sangam-security still allows {@code READ} on the stored resource, evaluated without cache.
 */
public final class SecurityPermissionBridge implements PermissionPort {

    private static final Logger LOG = LoggerFactory.getLogger(SecurityPermissionBridge.class);

    record Mapping(String resourceType, Permission permission) {
    }

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
            Map.entry(Permissions.POWER_TABLE_APPROVE, new Mapping(ResourceRegistry.POWER_TABLE, Permission.APPROVE)));

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

    public SecurityPermissionBridge(SangamSecurity security, TxSupport tx, GroupPermissionAdapter groups) {
        this.security = security;
        this.tx = tx;
        this.groups = groups;
    }

    @Override
    public boolean has(RequestContext ctx, String permission) {
        if (groups.has(ctx, permission)) {
            return true;
        }
        Mapping m = MAP.get(permission);
        return m != null && allow(SecurityIdentities.of(ctx), m.resourceType(), m.permission(), ResourceRegistry.ANY_ID);
    }

    @Override
    public Set<String> permissions(RequestContext ctx) {
        Set<String> out = new LinkedHashSet<>(groups.permissions(ctx));
        SecurityIdentity id = SecurityIdentities.of(ctx);
        MAP.forEach((name, m) -> {
            if (!out.contains(name) && allow(id, m.resourceType(), m.permission(), ResourceRegistry.ANY_ID)) {
                out.add(name);
            }
        });
        return out;
    }

    @Override
    public boolean mayReplay(RequestContext ctx, String commandType, String resourceRef) {
        if (!groups.mayReplay(ctx, commandType, resourceRef) && !has(ctx, commandPermission(commandType))) {
            return false;
        }
        String type = resourceType(commandType);
        if (type == null || resourceRef == null || resourceRef.isBlank() || resourceRef.contains(",")
                || ctx.inGroup(Permissions.GROUP_ADMIN) || ctx.inGroup(Permissions.GROUP_TENANT_ADMIN)) {
            return true;
        }
        return allow(SecurityIdentities.of(ctx), type, Permission.READ, resourceRef)
                || groups.has(ctx, commandPermission(commandType));
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

    private static String commandPermission(String commandType) {
        if (commandType == null) {
            return Permissions.PROCESS_READ;
        }
        if (commandType.startsWith("task.")) {
            return Permissions.TASK_WRITE;
        }
        if (commandType.startsWith("process.") || commandType.startsWith("message.")) {
            return Permissions.PROCESS_WRITE;
        }
        if (commandType.startsWith("case.")) {
            return Permissions.CASE_WRITE;
        }
        if (commandType.startsWith("deployment.") || commandType.startsWith("package.")) {
            return Permissions.DEPLOYMENT_WRITE;
        }
        if (commandType.startsWith("admin.")) {
            return Permissions.ADMIN_WRITE;
        }
        if (commandType.startsWith("ops.")) {
            return Permissions.OPS_WRITE;
        }
        return Permissions.PROCESS_READ;
    }
}
