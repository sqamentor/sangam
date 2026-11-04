// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.policy;

import org.sangam.core.layer.SqlPredicate;
import org.sangam.core.security.SangamSecurityException;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.core.security.SecurityPorts;
import org.sangam.security.audit.SecurityAuditService;
import org.sangam.security.audit.SecurityEventType;

import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The {@link SecurityPorts.Authorization} implementation (R19 / VI.4, K2, K3). The system principal (jobs, VI.4 rule 7)
 * is allowed for its own tenant only; its actions carry {@code onBehalfOf} in audit.
 */
public final class AuthorizationService implements SecurityPorts.Authorization {

    private final PolicyCompiler compiler;
    private final SecurityAuditService audit;

    /** {@code AUTH_DENIED} is written through {@link SecurityAuditService#denial}, so it survives the rollback (CX-1). */
    public AuthorizationService(PolicyCompiler compiler, SecurityAuditService audit) {
        this.compiler = compiler;
        this.audit = audit;
    }

    public PolicyCompiler compiler() {
        return compiler;
    }

    @Override
    public SqlPredicate visible(Connection c, SecurityIdentity identity, String resourceType, String permission, String alias) {
        return compiler.visible(c, identity, resourceType, Permission.parse(permission), alias);
    }

    @Override
    public boolean allow(Connection c, SecurityIdentity identity, String resourceType, String permission, String resourceId) {
        return allow(c, identity, resourceType, Permission.parse(permission), resourceId, Map.of());
    }

    public boolean allow(Connection c, SecurityIdentity identity, String resourceType, Permission permission,
                         String resourceId, Map<String, ?> attributes) {
        if (identity.system()) {
            return true;    // tenant isolation still applies: the system identity is bound to one tenant
        }
        return compiler.allow(c, identity, resourceType, permission, resourceId, attributes);
    }

    @Override
    public void require(Connection c, SecurityIdentity identity, String resourceType, String permission, String resourceId) {
        require(c, identity, resourceType, Permission.parse(permission), resourceId, Map.of());
    }

    public void require(Connection c, SecurityIdentity identity, String resourceType, Permission permission, String resourceId) {
        require(c, identity, resourceType, permission, resourceId, Map.of());
    }

    /** Object check inside the command transaction (no cache for sensitive actions, VI.4 rule 5); audits a denial. */
    public void require(Connection c, SecurityIdentity identity, String resourceType, Permission permission,
                        String resourceId, Map<String, ?> attributes) {
        if (allow(c, identity, resourceType, permission, resourceId, attributes)) {
            return;
        }
        long version = policyVersion(c, identity.tenantId());
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("action", permission.name());
        p.put("resource_type", resourceType);
        p.put("resource_id", resourceId);
        p.put("outcome", "DENIED");
        p.put("reason", "ACTION_NOT_PERMITTED");
        audit.denial(c, identity, SecurityEventType.AUTH_DENIED, null, p);
        throw new SangamSecurityException.AccessDenied(permission + " on " + resourceType + " not permitted", version);
    }

    @Override
    public long policyVersion(Connection c, String tenantId) {
        return AuthEffectiveMaintainer.policyVersion(c, tenantId);
    }
}
