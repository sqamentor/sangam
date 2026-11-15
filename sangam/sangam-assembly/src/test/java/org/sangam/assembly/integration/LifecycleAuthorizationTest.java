// SPDX-License-Identifier: Apache-2.0
package org.sangam.assembly.integration;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.sangam.integration.lifecycle.LifecyclePorts;

/**
 * Extraction phase 3, blocker 4 (owner decision 374): the lifecycle services get the bound security module's authorizer
 * or nothing is assembled; a host without one is refused by name and never gets an allow-everything authorizer.
 */
class LifecycleAuthorizationTest {

    @Test
    void theBoundSecurityModulesAuthorizerIsUsedAsItIs() {
        LifecyclePorts.Authorizer denyAll = (c, tenant, principal, permission, instanceId) -> {
            throw new SecurityException("denied " + permission);
        };
        LifecyclePorts.Authorizer used = LifecycleAuthorization.require("test", Optional.of(denyAll));
        assertSame(denyAll, used, "no wrapper that could weaken it");
        assertThrows(SecurityException.class, () -> used.require(null, "T1", "p", "CANCEL_INSTANCE", "i1"));
    }

    @Test
    void withoutABoundSecurityModuleTheStartIsRefusedByName() {
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> LifecycleAuthorization.require("quarkus", Optional.empty()));
        assertTrue(refused.getMessage().startsWith(LifecycleAuthorization.CODE + ": the quarkus host"),
                refused.getMessage());
        assertTrue(refused.getMessage().contains("decision 374"), refused.getMessage());
    }
}
