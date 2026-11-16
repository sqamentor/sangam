// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.sangam.rest.adapter.memory.GroupPermissionAdapter;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.Permissions;

/**
 * Owner decision 381(3): the tenant migration inventory and repair are platform-only (platform administrator on a
 * platform-issuer identity); no tenant role, persona group or grant confers them, and a repair replay re-checks it.
 */
class TenantMigrationPermissionTest {

    private final SecurityPermissionBridge bridge = new SecurityPermissionBridge(null, null, new GroupPermissionAdapter());

    private static RequestContext ctx(String... groups) {
        return new RequestContext("t1", "p1", Set.of(groups), null, null, null, null, null, "OIDC");
    }

    @Test
    void onlyThePlatformAdministratorHoldsIt() {
        String p = Permissions.TENANT_MIGRATION_ADMIN;
        assertTrue(bridge.has(ctx(Permissions.GROUP_PLATFORM, Permissions.GROUP_ADMIN), p));
        assertTrue(bridge.permissions(ctx(Permissions.GROUP_PLATFORM, Permissions.GROUP_ADMIN)).contains(p));
        for (RequestContext refused : new RequestContext[] {
                ctx(Permissions.GROUP_ADMIN),
                ctx(Permissions.GROUP_TENANT_ADMIN),
                ctx(Permissions.GROUP_PLATFORM, Permissions.GROUP_OPERATOR),
                ctx(Permissions.GROUP_PLATFORM, Permissions.GROUP_AUDITOR),
                ctx(Permissions.GROUP_PLATFORM, Permissions.GROUP_SYSTEM, Permissions.GROUP_ADMIN)}) {
            assertFalse(bridge.has(refused, p), refused.groups().toString());
            assertFalse(bridge.permissions(refused).contains(p), refused.groups().toString());
        }
        assertFalse(SecurityPermissionBridge.MAP.containsKey(p), "never a tenant-scoped grant");
        Permissions.DEFAULT_GROUP_PERMISSIONS.forEach((group, perms) -> assertFalse(perms.contains(p), group));
    }

    @Test
    void aRepairReplayGoesOnlyToACurrentHolder() {
        assertTrue(bridge.mayReplay(ctx(Permissions.GROUP_PLATFORM, Permissions.GROUP_ADMIN), "tenant.migration.repair",
                "p-1"));
        assertFalse(bridge.mayReplay(ctx(Permissions.GROUP_TENANT_ADMIN), "tenant.migration.repair", "p-1"));
    }
}
