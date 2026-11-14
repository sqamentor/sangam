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
 * Decision 305 (W4-M REQUEST 07:50:45Z): the DLT registration admin routes use {@code dlt.registration.read} and
 * {@code dlt.registration.write}, tenant-scoped, held by the tenant administrator persona and no other persona group;
 * the sangam-security path is the tenant administration gate. Written for the test phase (not run here).
 */
class DltRegistrationPermissionTest {

    private static final Set<String> BOTH = Set.of(Permissions.DLT_REGISTRATION_READ, Permissions.DLT_REGISTRATION_WRITE);

    private final GroupPermissionAdapter groups = new GroupPermissionAdapter();
    /** The bridge without sangam-security: only the persona groups decide (the grant path fails closed here). */
    private final SecurityPermissionBridge bridge = new SecurityPermissionBridge(null, null, groups);

    private static RequestContext ctx(String... g) {
        return new RequestContext("t1", "p1", Set.of(g), null, null, null, null, null, "OIDC");
    }

    @Test
    void theNamesAreTheContractNames() {
        assertEquals("dlt.registration.read", Permissions.DLT_REGISTRATION_READ);
        assertEquals("dlt.registration.write", Permissions.DLT_REGISTRATION_WRITE);
    }

    @Test
    void onlyTheTenantAdministratorPersonaHoldsThem() {
        for (String p : BOTH) {
            assertTrue(bridge.has(ctx(Permissions.GROUP_TENANT_ADMIN), p), p);
            Permissions.DEFAULT_GROUP_PERMISSIONS.forEach((group, perms) -> {
                if (!Permissions.GROUP_TENANT_ADMIN.equals(group)) {
                    assertFalse(perms.contains(p), group + " must not hold " + p);
                }
            });
            for (String g : new String[] {Permissions.GROUP_USER, Permissions.GROUP_WORKER, Permissions.GROUP_OPERATOR,
                    Permissions.GROUP_AUDITOR, Permissions.GROUP_INTEGRATION, Permissions.GROUP_SYSTEM}) {
                assertFalse(bridge.has(ctx(g), p), g + " " + p);
            }
        }
    }

    @Test
    void theyAreTenantAdministrationNotPlatformPermissions() {
        for (String p : BOTH) {
            SecurityPermissionBridge.Mapping m = SecurityPermissionBridge.MAP.get(p);
            assertEquals(ResourceRegistry.TENANT, m.resourceType(), p);
            assertEquals(Permission.ADMIN, m.permission(), p);
            assertFalse(SecurityPermissionBridge.PLATFORM_PERMISSIONS.containsKey(p), p);
        }
    }
}
