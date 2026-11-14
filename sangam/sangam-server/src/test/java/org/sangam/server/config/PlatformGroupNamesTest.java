// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.sangam.engine.tenant.SharedDeployments;
import org.sangam.rest.support.Permissions;
import org.sangam.server.bridge.SecurityPermissionBridge;
import org.sangam.server.security.PlatformIssuers;

/**
 * Decisions 144 and 197 (W4-E REQUEST 03:29:48Z item 4): the engine's shared-deployment registry checks the actor's
 * groups itself (defence in depth), so its two group names must be the server's: the platform marker that
 * PlatformIssuers keeps out of every tenant token, and the deployer group SecurityPermissionBridge grants the
 * shared-deployment permissions to. A rename on one side alone would lock every platform deployer out.
 */
class PlatformGroupNamesTest {

    @Test
    void theEnginePlatformMarkerIsTheServers() {
        assertEquals(Permissions.GROUP_PLATFORM, SharedDeployments.PLATFORM_MARKER);
        assertTrue(PlatformIssuers.PLATFORM_ONLY.contains(SharedDeployments.PLATFORM_MARKER),
                "a tenant token never carries the marker the engine trusts");
        assertTrue(PlatformIssuers.isPlatformIdentity(Set.of(SharedDeployments.PLATFORM_MARKER)));
    }

    @Test
    void theEngineDeployerGroupIsTheServers() {
        assertEquals(SecurityPermissionBridge.GROUP_PLATFORM_DEPLOYER, SharedDeployments.DEPLOYER_GROUP);
        assertFalse(PlatformIssuers.isPlatformIdentity(Set.of(SharedDeployments.DEPLOYER_GROUP)),
                "the deployer group alone is not a platform identity; the engine needs both groups");
    }

    /** Ruling 5: the audit routes ask for the permission the bridge maps platform-only. */
    @Test
    void theSystemAuditReadPermissionIsTheBridges() {
        assertEquals(SecurityPermissionBridge.SYSTEM_AUDIT_READ, Permissions.AUDIT_SYSTEM_READ);
    }
}
