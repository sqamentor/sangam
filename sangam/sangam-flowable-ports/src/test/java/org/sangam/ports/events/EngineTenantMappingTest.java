// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.events;

import org.junit.jupiter.api.Test;
import org.sangam.core.layer.TenantKey;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * SL-86 / FW-29: the registry stores "no tenant" as the reserved shared key; the engine's tenant for it is null, so an
 * event of the shared scope matches untenanted subscriptions. Legacy '' rows (and Oracle's NULL) map the same way.
 */
class EngineTenantMappingTest {

    @Test
    void sharedAndLegacyKeysAreTheEngineNullTenant() {
        assertNull(EventDispatcher.engineTenant(TenantKey.SHARED));
        assertNull(EventDispatcher.engineTenant(""));
        assertNull(EventDispatcher.engineTenant(null));
        assertEquals("RJ", EventDispatcher.engineTenant("RJ"));
    }
}
