// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.sangam.rest.adapter.memory.GroupPermissionAdapter;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.Permissions;
import org.sangam.server.security.PlatformIssuers;

/**
 * Decision 197: the shared-deployment registry permissions are platform-only. Manage (register, revoke) only for a
 * platform-issuer identity in {@code sangam-platform-deployer}; read also for the platform administrator and operator.
 * No tenant role, no wildcard and no sangam-security grant confers them; the platform-only decision never reaches
 * sangam-security, so the bridge is built without it here. Written for the test phase (not run here).
 */
class SharedDeploymentPermissionTest {

    private static final String T = "t1";

    private final SecurityPermissionBridge bridge = new SecurityPermissionBridge(null, null, new GroupPermissionAdapter());

    private static RequestContext ctx(String... groups) {
        return new RequestContext(T, "p1", Set.of(groups), null, null, null, null, null, "OIDC");
    }

    @Test
    void thePermissionNamesAreStableAndReadIsSeparateFromManage() {
        assertEquals("platform.shared-deployment.read", SecurityPermissionBridge.SHARED_DEPLOYMENT_READ);
        assertEquals("platform.shared-deployment.manage", SecurityPermissionBridge.SHARED_DEPLOYMENT_MANAGE);
        assertEquals("sangam-platform-deployer", SecurityPermissionBridge.GROUP_PLATFORM_DEPLOYER);
        assertFalse(SecurityPermissionBridge.MAP.containsKey(SecurityPermissionBridge.SHARED_DEPLOYMENT_MANAGE),
                "never mapped to a tenant-scoped sangam-security grant");
        assertFalse(SecurityPermissionBridge.MAP.containsKey(SecurityPermissionBridge.SHARED_DEPLOYMENT_READ));
    }

    @Test
    void onlyThePlatformDeployerManages() {
        RequestContext deployer = ctx(Permissions.GROUP_PLATFORM, SecurityPermissionBridge.GROUP_PLATFORM_DEPLOYER);
        assertTrue(bridge.has(deployer, SecurityPermissionBridge.SHARED_DEPLOYMENT_MANAGE));
        assertTrue(bridge.has(deployer, SecurityPermissionBridge.SHARED_DEPLOYMENT_READ));
        assertTrue(bridge.permissions(deployer).containsAll(Set.of(SecurityPermissionBridge.SHARED_DEPLOYMENT_MANAGE,
                SecurityPermissionBridge.SHARED_DEPLOYMENT_READ)));
    }

    @Test
    void thePlatformAdministratorAndOperatorReadButNeverManage() {
        // the platform administrator holds the '*' wildcard of the group map: it never confers a platform permission
        RequestContext admin = ctx(Permissions.GROUP_PLATFORM, Permissions.GROUP_ADMIN);
        assertTrue(bridge.has(admin, SecurityPermissionBridge.SHARED_DEPLOYMENT_READ));
        assertFalse(bridge.has(admin, SecurityPermissionBridge.SHARED_DEPLOYMENT_MANAGE));
        assertFalse(bridge.permissions(admin).contains(SecurityPermissionBridge.SHARED_DEPLOYMENT_MANAGE));
        RequestContext operator = ctx(Permissions.GROUP_PLATFORM, Permissions.GROUP_OPERATOR);
        assertTrue(bridge.has(operator, SecurityPermissionBridge.SHARED_DEPLOYMENT_READ));
        assertFalse(bridge.has(operator, SecurityPermissionBridge.SHARED_DEPLOYMENT_MANAGE));
    }

    @Test
    void noTenantIdentityHoldsEither() {
        for (RequestContext tenant : new RequestContext[] {
                ctx(SecurityPermissionBridge.GROUP_PLATFORM_DEPLOYER),            // the group without the marker
                ctx(Permissions.GROUP_ADMIN),                                     // wildcard without the marker
                ctx(Permissions.GROUP_TENANT_ADMIN),
                ctx(Permissions.GROUP_MODEL_DEPLOYER),
                ctx(Permissions.GROUP_OPERATOR, Permissions.GROUP_AUDITOR),
                ctx(Permissions.GROUP_PLATFORM),                                  // the marker alone
                ctx(Permissions.GROUP_PLATFORM, Permissions.GROUP_SYSTEM, SecurityPermissionBridge.GROUP_PLATFORM_DEPLOYER),
                ctx()}) {
            assertFalse(bridge.has(tenant, SecurityPermissionBridge.SHARED_DEPLOYMENT_MANAGE), tenant.groups().toString());
            assertFalse(bridge.has(tenant, SecurityPermissionBridge.SHARED_DEPLOYMENT_READ), tenant.groups().toString());
            assertFalse(bridge.permissions(tenant).contains(SecurityPermissionBridge.SHARED_DEPLOYMENT_MANAGE));
            assertFalse(bridge.permissions(tenant).contains(SecurityPermissionBridge.SHARED_DEPLOYMENT_READ));
        }
        assertFalse(bridge.has(null, SecurityPermissionBridge.SHARED_DEPLOYMENT_READ));
    }

    @Test
    void aTenantTokenNeverCarriesTheDeployerGroup() {
        assertTrue(PlatformIssuers.PLATFORM_ONLY.contains(SecurityPermissionBridge.GROUP_PLATFORM_DEPLOYER));
        assertFalse(PlatformIssuers.tenant(Set.of(SecurityPermissionBridge.GROUP_PLATFORM_DEPLOYER, "sangam-user"))
                .contains(SecurityPermissionBridge.GROUP_PLATFORM_DEPLOYER));
    }

    /** Decision 144: system-scope audit only for the platform administrator and auditor (cross-tenant audit access). */
    @Test
    void systemScopeAuditIsReadOnlyByPlatformAuditRoles() {
        String p = SecurityPermissionBridge.SYSTEM_AUDIT_READ;
        assertEquals("platform.audit.system.read", p);
        assertFalse(SecurityPermissionBridge.MAP.containsKey(p));
        assertTrue(bridge.has(ctx(Permissions.GROUP_PLATFORM, Permissions.GROUP_ADMIN), p));
        assertTrue(bridge.has(ctx(Permissions.GROUP_PLATFORM, Permissions.GROUP_AUDITOR), p));
        for (RequestContext refused : new RequestContext[] {
                ctx(Permissions.GROUP_AUDITOR),                                   // a tenant auditor
                ctx(Permissions.GROUP_TENANT_ADMIN),
                ctx(Permissions.GROUP_ADMIN),                                     // wildcard without the marker
                ctx(Permissions.GROUP_PLATFORM, Permissions.GROUP_OPERATOR),
                ctx(Permissions.GROUP_PLATFORM, SecurityPermissionBridge.GROUP_PLATFORM_DEPLOYER),
                ctx(Permissions.GROUP_PLATFORM, Permissions.GROUP_SYSTEM, Permissions.GROUP_ADMIN)}) {
            assertFalse(bridge.has(refused, p), refused.groups().toString());
            assertFalse(bridge.permissions(refused).contains(p), refused.groups().toString());
        }
    }

    @Test
    void aStoredRegistryResponseIsReplayedOnlyToACurrentDeployer() {
        RequestContext deployer = ctx(Permissions.GROUP_PLATFORM, SecurityPermissionBridge.GROUP_PLATFORM_DEPLOYER);
        RequestContext tenantAdmin = ctx(Permissions.GROUP_TENANT_ADMIN);
        for (String type : new String[] {"platform.shared-deployment.register", "admin.shared-deployment.revoke",
                "shared-deployment.register"}) {
            assertTrue(bridge.mayReplay(deployer, type, "dep-1"), type);
            assertFalse(bridge.mayReplay(tenantAdmin, type, "dep-1"), type);
            assertFalse(bridge.mayReplay(ctx(Permissions.GROUP_PLATFORM, Permissions.GROUP_ADMIN), type, "dep-1"), type);
        }
    }
}
