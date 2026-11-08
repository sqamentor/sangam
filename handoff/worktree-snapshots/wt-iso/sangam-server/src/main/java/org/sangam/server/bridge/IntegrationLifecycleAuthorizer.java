// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.sql.Connection;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.sangam.core.security.SangamSecurityException;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.integration.lifecycle.LifecyclePorts;
import org.sangam.security.SangamSecurity;
import org.sangam.security.policy.Permission;
import org.sangam.security.policy.ResourceRegistry;
import org.sangam.security.tenant.TenantContext;

/**
 * sangam-integration's {@link LifecyclePorts.Authorizer} on sangam-security (VI.5, A-4): {@code CANCEL_INSTANCE},
 * {@code DELETE_INSTANCE} and {@code ERASE_DATA} are object checks on the process instance, inside the command
 * transaction, without cache (VI.4 rule 5). The identity is the request's resolved identity when it is the same
 * principal, else a token identity of that principal; the system principal is allowed (tenant-bound).
 */
public final class IntegrationLifecycleAuthorizer implements LifecyclePorts.Authorizer {

    private static final Map<String, Permission> ALIASES = Map.of(
            "CANCEL_INSTANCE", Permission.CANCEL,
            "DELETE_INSTANCE", Permission.DELETE_INSTANCE,
            "ERASE_DATA", Permission.ERASE_DATA);

    private final SangamSecurity security;

    public IntegrationLifecycleAuthorizer(SangamSecurity security) {
        this.security = security;
    }

    @Override
    public void require(Connection c, String tenantId, String principalId, String permission, String instanceId) {
        SecurityIdentity id = TenantContext.current()
                .filter(i -> i.tenantId().equals(tenantId) && (i.system() || i.personId().equals(principalId)))
                .orElseGet(() -> principalId == null || principalId.startsWith("system")
                        ? SecurityIdentity.system(tenantId, principalId)
                        : new SecurityIdentity(tenantId, principalId, principalId, Set.of(), Set.of(), Set.of(),
                        Set.of(), "INTERNAL", null, null, false, Map.of()));
        Permission p = ALIASES.get(permission.toUpperCase(Locale.ROOT));
        if (p == null) {
            p = Permission.parse(permission);
        }
        try {
            security.authorization().require(c, id, ResourceRegistry.PROCESS_INSTANCE, p, instanceId);
        } catch (SangamSecurityException e) {
            throw e;
        }
    }
}
