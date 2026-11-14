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
 * Decision 227: {@code dsc.certificate.approve} is tenant-scoped and held by the tenant administrator persona (or a
 * sangam-security USER ADMIN grant), never by the signer personas; self-approval is refused by the controller as a
 * second check (not tested here). Written for the test phase (not run here).
 */
class DscCertificateApprovePermissionTest {

    private final GroupPermissionAdapter groups = new GroupPermissionAdapter();

    private static RequestContext ctx(String... g) {
        return new RequestContext("t1", "p1", Set.of(g), null, null, null, null, null, "OIDC");
    }

    @Test
    void theNameIsTheContractName() {
        assertEquals("dsc.certificate.approve", Permissions.DSC_CERTIFICATE_APPROVE);
    }

    @Test
    void onlyTheTenantAdministratorPersonaHoldsIt() {
        assertTrue(groups.has(ctx(Permissions.GROUP_TENANT_ADMIN), Permissions.DSC_CERTIFICATE_APPROVE));
        Permissions.DEFAULT_GROUP_PERMISSIONS.forEach((group, perms) -> {
            if (!Permissions.GROUP_TENANT_ADMIN.equals(group)) {
                assertFalse(perms.contains(Permissions.DSC_CERTIFICATE_APPROVE), group);
            }
        });
        for (String signer : new String[] {Permissions.GROUP_USER, Permissions.GROUP_WORKER, Permissions.GROUP_OPERATOR,
                Permissions.GROUP_AUDITOR, Permissions.GROUP_INTEGRATION}) {
            assertFalse(groups.has(ctx(signer), Permissions.DSC_CERTIFICATE_APPROVE), signer);
        }
    }

    /** The bridge without sangam-security: only the persona groups decide (the grant path fails closed here). */
    private final SecurityPermissionBridge bridge = new SecurityPermissionBridge(null, null, groups);

    @Test
    void anAdminDecisionIsReplayedOnlyWhileTheApprovePermissionIsHeld() {
        RequestContext admin = ctx(Permissions.GROUP_TENANT_ADMIN);
        RequestContext formerAdmin = ctx(Permissions.GROUP_USER);   // the role was taken away: user permissions remain
        for (String type : new String[] {"dsc.certificate.approve", "dsc.certificate.reject", "dsc.certificate.revoke"}) {
            assertTrue(bridge.mayReplay(admin, type, "enr-1"), type);
            assertFalse(bridge.mayReplay(formerAdmin, type, "enr-1"), type);
            assertFalse(bridge.mayReplay(ctx(Permissions.GROUP_WORKER), type, "enr-1"), type);
        }
    }

    @Test
    void enrolAndRevokeOwnAreReplayedOnlyWhileTaskWriteIsHeld() {
        for (String type : new String[] {"dsc.certificate.enrol", "dsc.certificate.revoke-own"}) {
            assertTrue(bridge.mayReplay(ctx(Permissions.GROUP_USER), type, "enr-1"), type);
            // still some tenant permission, but no task.write any more
            assertFalse(bridge.mayReplay(ctx(Permissions.GROUP_AUDITOR), type, "enr-1"), type);
            assertFalse(bridge.mayReplay(ctx(Permissions.GROUP_CITIZEN), type, "enr-1"), type);
        }
    }

    @Test
    void anyOtherDscCertificateCommandIsRefused() {
        RequestContext admin = ctx(Permissions.GROUP_TENANT_ADMIN, Permissions.GROUP_USER);
        for (String type : new String[] {"dsc.certificate.delete", "dsc.certificate.", "dsc.certificate.approve.x",
                "dsc.certificate.APPROVE"}) {
            assertFalse(bridge.mayReplay(admin, type, "enr-1"), type);
        }
        assertEquals(5, SecurityPermissionBridge.DSC_CERTIFICATE_REPLAY.size());
    }

    @Test
    void itIsATenantGrantOfUserAdministrationNotAPlatformPermission() {
        SecurityPermissionBridge.Mapping m = SecurityPermissionBridge.MAP.get(Permissions.DSC_CERTIFICATE_APPROVE);
        assertEquals(ResourceRegistry.USER, m.resourceType());
        assertEquals(Permission.ADMIN, m.permission());
        assertFalse(SecurityPermissionBridge.PLATFORM_PERMISSIONS.containsKey(Permissions.DSC_CERTIFICATE_APPROVE));
    }
}
