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
import org.sangam.security.policy.Permission;
import org.sangam.security.policy.ResourceRegistry;

/**
 * Decision 258 (FC-18): {@code history.migrate} is admin-only and tenant-scoped. Among the tenant personas only the tenant
 * administrator holds it; the sangam-security path is the tenant administration gate, never a grant on historic
 * instances. Untenanted history stays platform-only in the engine (HistoricInstanceMigration, not tested here). The
 * replay of {@code history.process-instance.migrate} re-checks {@code history.migrate}. Written for the test phase (not
 * run here).
 */
class HistoryMigratePermissionTest {

    private final GroupPermissionAdapter groups = new GroupPermissionAdapter();

    /** The bridge without sangam-security: only the persona groups decide (the grant path fails closed here). */
    private final SecurityPermissionBridge bridge = new SecurityPermissionBridge(null, null, groups);

    private static RequestContext ctx(String... g) {
        return new RequestContext("t1", "p1", Set.of(g), null, null, null, null, null, "OIDC");
    }

    @Test
    void theNamesAreTheContractNames() {
        assertEquals("history.migrate", Permissions.HISTORY_MIGRATE);
        assertEquals("history.process-instance.migrate", SecurityPermissionBridge.HISTORY_MIGRATE_COMMAND);
    }

    @Test
    void onlyTheTenantAdministratorPersonaHoldsIt() {
        assertTrue(groups.has(ctx(Permissions.GROUP_TENANT_ADMIN), Permissions.HISTORY_MIGRATE));
        assertTrue(bridge.has(ctx(Permissions.GROUP_TENANT_ADMIN), Permissions.HISTORY_MIGRATE));
        Permissions.DEFAULT_GROUP_PERMISSIONS.forEach((group, perms) -> {
            if (!Permissions.GROUP_TENANT_ADMIN.equals(group)) {
                assertFalse(perms.contains(Permissions.HISTORY_MIGRATE), group);
            }
        });
        for (String g : new String[] {Permissions.GROUP_USER, Permissions.GROUP_WORKER, Permissions.GROUP_OPERATOR,
                Permissions.GROUP_AUDITOR, Permissions.GROUP_INTEGRATION, Permissions.GROUP_SYSTEM,
                Permissions.GROUP_MODEL_DEPLOYER, Permissions.GROUP_ENGINE_ADMIN}) {
            assertFalse(bridge.has(ctx(g), Permissions.HISTORY_MIGRATE), g);
        }
        // reading history is not migrating it
        assertFalse(bridge.has(ctx(Permissions.GROUP_OPERATOR, Permissions.GROUP_AUDITOR), Permissions.HISTORY_MIGRATE));
    }

    @Test
    void itIsTheTenantAdministrationGateNotAHistoricInstanceGrantNorAPlatformPermission() {
        SecurityPermissionBridge.Mapping m = SecurityPermissionBridge.MAP.get(Permissions.HISTORY_MIGRATE);
        assertEquals(ResourceRegistry.TENANT, m.resourceType());
        assertEquals(Permission.ADMIN, m.permission());
        assertEquals(SecurityPermissionBridge.MAP.get(Permissions.TENANT_ADMIN), m);
        assertFalse(SecurityPermissionBridge.PLATFORM_PERMISSIONS.containsKey(Permissions.HISTORY_MIGRATE));
    }

    @Test
    void aMigrationIsReplayedOnlyWhileHistoryMigrateIsHeld() {
        String type = SecurityPermissionBridge.HISTORY_MIGRATE_COMMAND;
        assertTrue(bridge.mayReplay(ctx(Permissions.GROUP_TENANT_ADMIN), type, "pi-1"));
        // the role was taken away: process.read and history.read remain, which the default rule would have accepted
        assertFalse(bridge.mayReplay(ctx(Permissions.GROUP_USER), type, "pi-1"));
        assertFalse(bridge.mayReplay(ctx(Permissions.GROUP_OPERATOR), type, "pi-1"));
        assertFalse(bridge.mayReplay(ctx(Permissions.GROUP_AUDITOR), type, "pi-1"));
        assertFalse(bridge.mayReplay(ctx(), type, "pi-1"));
    }
}
