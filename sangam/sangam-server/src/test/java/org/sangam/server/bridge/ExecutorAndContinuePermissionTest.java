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
 * Decision 146: {@code executor.admin} is platform-only (platform-issuer identity in sangam-admin or sangam-operator),
 * for partition resize request, get and list, and its replay re-checks it. Decision 149: {@code process.continue}
 * (registered earlier) is tenant-scoped (sangam-security PROCESS_INSTANCE MODIFY). Written for the test phase (not run).
 */
class ExecutorAndContinuePermissionTest {

    private final SecurityPermissionBridge bridge = new SecurityPermissionBridge(null, null, new GroupPermissionAdapter());

    private static RequestContext ctx(String... groups) {
        return new RequestContext("t1", "p1", Set.of(groups), null, null, null, null, null, "OIDC");
    }

    @Test
    void theNamesAreTheContractNames() {
        assertEquals("executor.admin", Permissions.ADMIN_EXECUTOR);
        assertEquals("process.continue", SecurityPermissionBridge.PROCESS_CONTINUE);
    }

    @Test
    void executorAdminIsPlatformOnly() {
        String p = Permissions.ADMIN_EXECUTOR;
        assertTrue(bridge.has(ctx(Permissions.GROUP_PLATFORM, Permissions.GROUP_ADMIN), p));
        assertTrue(bridge.has(ctx(Permissions.GROUP_PLATFORM, Permissions.GROUP_OPERATOR), p));
        assertTrue(bridge.permissions(ctx(Permissions.GROUP_PLATFORM, Permissions.GROUP_OPERATOR)).contains(p));
        for (RequestContext refused : new RequestContext[] {
                ctx(Permissions.GROUP_ADMIN),                      // the wildcard without the platform marker
                ctx(Permissions.GROUP_OPERATOR),                   // a tenant operator
                ctx(Permissions.GROUP_TENANT_ADMIN),
                ctx(Permissions.GROUP_PLATFORM),
                ctx(Permissions.GROUP_PLATFORM, Permissions.GROUP_AUDITOR),
                ctx(Permissions.GROUP_PLATFORM, Permissions.GROUP_SYSTEM, Permissions.GROUP_OPERATOR)}) {
            assertFalse(bridge.has(refused, p), refused.groups().toString());
            assertFalse(bridge.permissions(refused).contains(p), refused.groups().toString());
        }
        assertFalse(SecurityPermissionBridge.MAP.containsKey(p), "never a tenant-scoped grant");
        Permissions.DEFAULT_GROUP_PERMISSIONS.forEach((group, perms) ->
                assertFalse(perms.contains(p), group + " must not hold the platform-only executor.admin"));
    }

    @Test
    void aResizeReplayGoesOnlyToACurrentHolder() {
        assertTrue(bridge.mayReplay(ctx(Permissions.GROUP_PLATFORM, Permissions.GROUP_OPERATOR),
                "executor.partitions.resize", "resize-1"));
        assertFalse(bridge.mayReplay(ctx(Permissions.GROUP_OPERATOR), "executor.partitions.resize", "resize-1"));
        assertFalse(bridge.mayReplay(ctx(Permissions.GROUP_TENANT_ADMIN), "executor.partitions.resize", "resize-1"));
    }

    /** Decision 149: registered earlier (SecurityPermissionBridge.PROCESS_CONTINUE); confirmed tenant-scoped. */
    @Test
    void processContinueIsTenantScoped() {
        String p = SecurityPermissionBridge.PROCESS_CONTINUE;
        SecurityPermissionBridge.Mapping m = SecurityPermissionBridge.MAP.get(p);
        assertEquals(ResourceRegistry.PROCESS_INSTANCE, m.resourceType());
        assertEquals(Permission.MODIFY, m.permission());
        assertFalse(m.permission().explicitOnly());
        assertFalse(SecurityPermissionBridge.PLATFORM_PERMISSIONS.containsKey(p));
    }
}
