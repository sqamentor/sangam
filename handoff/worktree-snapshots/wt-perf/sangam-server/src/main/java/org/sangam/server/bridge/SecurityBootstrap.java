// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.sangam.core.layer.Transactions;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.security.SangamSecurity;
import org.sangam.security.admin.PolicyAdminService;
import org.sangam.security.admin.TenantAdminService;
import org.sangam.security.policy.Permission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Development bootstrap ({@code sangam.security.bootstrap.enabled=true}, h2 profile only): on an empty database there
 * are no tenants and no grants, so every VI.4 object check would deny. For each configured tenant this creates the
 * tenant and one ALLOW policy {@code bootstrap-admin} for the configured admin groups on every resource type, then
 * approves it with a second system identity when the tenant requires four-eyes. Idempotent; never use in production
 * ({@code sangam.security.production=true} refuses it).
 */
public final class SecurityBootstrap {

    private static final Logger LOG = LoggerFactory.getLogger(SecurityBootstrap.class);
    static final String POLICY_ID = "bootstrap-admin";

    private SecurityBootstrap() {
    }

    public static void run(SangamSecurity security, Transactions tx, List<String> tenants, List<String> adminGroups,
                           boolean production) {
        if (production) {
            throw new IllegalStateException("sangam.security.bootstrap.enabled=true is not allowed with "
                    + "sangam.security.production=true");
        }
        for (String tenant : tenants) {
            try {
                tx.inTransaction(c -> {
                    SecurityIdentity platform = new SecurityIdentity(tenant, "system-bootstrap", "system-bootstrap",
                            Set.of(), Set.of(TenantAdminService.PLATFORM_ADMIN_ROLE, "system"), Set.of(), Set.of(),
                            "SYSTEM", null, "bootstrap", true, Map.of());
                    if (security.tenants().find(c, tenant).isEmpty()) {
                        security.tenants().createTenant(c, platform, tenant, tenant, null, null, "dev", Map.of());
                        LOG.warn("DEV bootstrap: created tenant '{}'", tenant);
                    }
                    if (security.policies().find(c, tenant, POLICY_ID).isEmpty()) {
                        List<String> subjects = adminGroups.stream().map(g -> SecurityIdentity.GROUP + g).toList();
                        PolicyAdminService.PolicyDefinition def = new PolicyAdminService.PolicyDefinition(POLICY_ID,
                                "Development bootstrap: administrators", PolicyAdminService.Effect.ALLOW, subjects, "*",
                                "*", EnumSet.allOf(Permission.class), null);
                        PolicyAdminService.PolicyChange change = security.policies().create(c, platform, def,
                                "development bootstrap");
                        if (change != null && "PENDING".equalsIgnoreCase(change.status())) {
                            SecurityIdentity approver = new SecurityIdentity(tenant, "system-bootstrap-approver",
                                    "system-bootstrap-approver", Set.of(), Set.of("system"), Set.of(), Set.of(), "SYSTEM",
                                    null, "bootstrap", true, Map.of());
                            security.policies().approve(c, approver, change.changeId());
                        }
                        LOG.warn("DEV bootstrap: granted every permission on every resource to groups {} in tenant '{}'",
                                adminGroups, tenant);
                    }
                    return null;
                });
            } catch (RuntimeException e) {
                LOG.warn("DEV bootstrap for tenant '{}' failed: {}", tenant, e.toString());
            }
        }
    }
}
