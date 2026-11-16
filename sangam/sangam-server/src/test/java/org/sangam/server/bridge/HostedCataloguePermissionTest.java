// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.sangam.rest.adapter.memory.GroupPermissionAdapter;
import org.sangam.rest.modules.modeller.CatalogueHostController;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.Permissions;

/**
 * Owner decision 380 (the recommended default groups): the hosted catalogue's permissions are platform-only. A platform
 * deployer submits but never reviews; the platform administrator submits and reviews; no tenant identity, persona group,
 * wildcard or grant confers either, and a replay of each command goes only to a current holder.
 */
class HostedCataloguePermissionTest {

    static final String PUBLISH = CatalogueHostController.PUBLISH;
    static final String REVIEW = CatalogueHostController.REVIEW;

    private final SecurityPermissionBridge bridge = new SecurityPermissionBridge(null, null, new GroupPermissionAdapter());

    private static RequestContext ctx(String... groups) {
        return new RequestContext("t1", "p1", Set.of(groups), null, null, null, null, null, "OIDC");
    }

    static final RequestContext DEPLOYER = ctx(Permissions.GROUP_PLATFORM, SecurityPermissionBridge.GROUP_PLATFORM_DEPLOYER);
    static final RequestContext ADMIN = ctx(Permissions.GROUP_PLATFORM, Permissions.GROUP_ADMIN);

    @Test
    void aPlatformDeployerPublishesButDoesNotReview() {
        assertTrue(bridge.has(DEPLOYER, PUBLISH));
        assertFalse(bridge.has(DEPLOYER, REVIEW));
        assertTrue(bridge.permissions(DEPLOYER).contains(PUBLISH));
        assertFalse(bridge.permissions(DEPLOYER).contains(REVIEW));
    }

    @Test
    void thePlatformAdministratorReviews() {
        assertTrue(bridge.has(ADMIN, REVIEW));
        assertTrue(bridge.has(ADMIN, PUBLISH));
        assertTrue(bridge.permissions(ADMIN).containsAll(Set.of(PUBLISH, REVIEW)));
    }

    @Test
    void noTenantIdentityOrWildcardHoldsEither() {
        for (RequestContext refused : new RequestContext[] {
                ctx(Permissions.GROUP_ADMIN),
                ctx(Permissions.GROUP_TENANT_ADMIN),
                ctx(SecurityPermissionBridge.GROUP_PLATFORM_DEPLOYER),
                ctx(Permissions.GROUP_MODEL_DEPLOYER, Permissions.GROUP_MODEL_APPROVER),
                ctx(Permissions.GROUP_PLATFORM, Permissions.GROUP_OPERATOR),
                ctx(Permissions.GROUP_PLATFORM, Permissions.GROUP_SYSTEM, Permissions.GROUP_ADMIN)}) {
            for (String p : new String[] {PUBLISH, REVIEW}) {
                assertFalse(bridge.has(refused, p), refused.groups() + " " + p);
                assertFalse(bridge.permissions(refused).contains(p), refused.groups() + " " + p);
            }
        }
        for (String p : new String[] {PUBLISH, REVIEW}) {
            assertFalse(SecurityPermissionBridge.MAP.containsKey(p), "never a tenant-scoped grant: " + p);
            Permissions.DEFAULT_GROUP_PERMISSIONS.forEach((group, perms) -> assertFalse(perms.contains(p), group + " " + p));
        }
    }

    @Test
    void aReplayGoesOnlyToACurrentHolder() {
        assertTrue(bridge.mayReplay(DEPLOYER, "modeller.catalogue.submit", "x@1"));
        assertFalse(bridge.mayReplay(DEPLOYER, "modeller.catalogue.approve", "x@1"));
        assertFalse(bridge.mayReplay(DEPLOYER, "modeller.catalogue.reject", "x@1"));
        assertTrue(bridge.mayReplay(ADMIN, "modeller.catalogue.approve", "x@1"));
        assertTrue(bridge.mayReplay(ADMIN, "modeller.catalogue.reject", "x@1"));
        assertFalse(bridge.mayReplay(ctx(Permissions.GROUP_TENANT_ADMIN), "modeller.catalogue.submit", "x@1"));
    }
}
