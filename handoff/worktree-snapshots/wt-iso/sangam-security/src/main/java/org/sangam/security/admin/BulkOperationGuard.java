// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.admin;

import org.sangam.core.layer.Jdbc;
import org.sangam.core.security.SangamSecurityException;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.security.audit.SecurityAuditService;
import org.sangam.security.audit.SecurityEventType;
import org.sangam.security.dialect.SecurityDialect;
import org.sangam.security.policy.AuthorizationService;
import org.sangam.security.policy.AuthorizedQueryBuilder;
import org.sangam.security.policy.Permission;
import org.sangam.security.policy.ResourceRegistry;
import org.sangam.security.support.Sql;

import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Guard for bulk / batch and operator actions (R64, R26, R63, EC-20).
 * <ul>
 *   <li>the caller needs {@code BULK} on {@code OPERATIONS} (admin permission, R26);</li>
 *   <li>the pre-execution count uses the same R19 predicate as the list (R64), capped (A-16);</li>
 *   <li>above a configurable item count, a second person must approve (four-eyes, R64);</li>
 *   <li>every operator action needs a reason and is audited ({@code OPERATOR_ACTION}, R63).</li>
 * </ul>
 * The batch executor (WS-REST / WS-ENGINE) asks {@link #requireExecutable} before it starts.
 */
public final class BulkOperationGuard {

    public record BulkPlan(String planId, String operation, String resourceType, long count, boolean countCapped,
                           boolean needsSecondApprover, String status) {
    }

    private final SecurityDialect dialect;
    private final AuthorizationService authz;
    private final SecurityAuditService audit;
    private final long fourEyesThreshold;

    public BulkOperationGuard(SecurityDialect dialect, AuthorizationService authz, SecurityAuditService audit,
                              long fourEyesThreshold) {
        this.dialect = dialect;
        this.authz = authz;
        this.audit = audit;
        this.fourEyesThreshold = fourEyesThreshold;
    }

    /**
     * Plans a bulk operation over the rows of {@code query} the caller may act on with {@code itemPermission}.
     * The query must have been built with that permission, so the count equals the set the batch will touch.
     */
    public BulkPlan plan(Connection c, SecurityIdentity actor, String operation, String resourceType,
                         AuthorizedQueryBuilder.Query query, String reason, int countCap) {
        authz.require(c, actor, ResourceRegistry.OPERATIONS, Permission.BULK, operation);
        requireReason(reason);
        AuthorizedQueryBuilder.CappedCount n = query.count(c, countCap);
        boolean fourEyes = n.capped() || n.value() > fourEyesThreshold;
        String id = Sql.newId();
        String status = fourEyes ? "AWAITING_APPROVAL" : "APPROVED";
        Jdbc.update(c, "INSERT INTO SEC_BULK_PLAN(tenant_id, plan_id, operation, resource_type, item_count, count_capped,"
                        + " requested_by, reason, status, approved_by, created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                actor.tenantId(), id, operation, resourceType, n.value(), n.capped() ? 1 : 0, actor.personId(), reason, status,
                fourEyes ? null : actor.personId(), Sql.dbNow(c, dialect));
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("plan_id", id);
        p.put("operation", operation);
        p.put("resource_type", resourceType);
        p.put("count", n.value());
        p.put("count_capped", n.capped());
        p.put("reason", reason);
        p.put("status", status);
        audit.security(c, actor, SecurityEventType.OPERATOR_ACTION, p);
        return new BulkPlan(id, operation, resourceType, n.value(), n.capped(), fourEyes, status);
    }

    public void approve(Connection c, SecurityIdentity approver, String planId) {
        authz.require(c, approver, ResourceRegistry.OPERATIONS, Permission.BULK, ResourceRegistry.ANY_ID);
        String requester = Jdbc.one(c, "SELECT requested_by FROM SEC_BULK_PLAN WHERE tenant_id=? AND plan_id=? AND status='AWAITING_APPROVAL'",
                        rs -> rs.getString(1), approver.tenantId(), planId)
                .orElseThrow(() -> new SangamSecurityException.AdminConflict("PLAN_NOT_PENDING", "no pending plan " + planId));
        if (requester.equals(approver.personId())) {
            throw new SangamSecurityException.SodViolation("the requester cannot approve their own bulk operation");
        }
        Jdbc.update(c, "UPDATE SEC_BULK_PLAN SET status='APPROVED', approved_by=? WHERE tenant_id=? AND plan_id=?",
                approver.personId(), approver.tenantId(), planId);
        audit.security(c, approver, SecurityEventType.BULK_OPERATION_APPROVED, Map.of("plan_id", planId));
    }

    /** Called by the batch executor; marks the plan as started exactly once. */
    public void requireExecutable(Connection c, String tenant, String planId) {
        int n = Jdbc.update(c, "UPDATE SEC_BULK_PLAN SET status='STARTED' WHERE tenant_id=? AND plan_id=? AND status='APPROVED'",
                tenant, planId);
        if (n != 1) {
            throw new SangamSecurityException.AdminConflict("PLAN_NOT_EXECUTABLE", "plan " + planId + " is not approved or already started");
        }
    }

    /** A single guarded operator action (retry, cancel, modify ...): permission re-check + reason + audit (R63, OJ-04). */
    public void operatorAction(Connection c, SecurityIdentity actor, String resourceType, Permission permission, String resourceId,
                               String action, String reason, String rootId) {
        authz.require(c, actor, resourceType, permission, resourceId);
        requireReason(reason);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("action", action);
        p.put("resource_type", resourceType);
        p.put("resource_id", resourceId);
        p.put("reason", reason);
        p.put("outcome", "SUCCESS");
        audit.record(c, actor, SecurityEventType.OPERATOR_ACTION, rootId, p);
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new SangamSecurityException.AdminConflict("REASON_REQUIRED", "a reason is required");
        }
    }
}
