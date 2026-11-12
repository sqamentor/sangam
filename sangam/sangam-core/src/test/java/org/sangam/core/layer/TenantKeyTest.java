// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.layer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SL-86: the shared tenant key is one fixed, non-empty, reserved value; it round-trips to the engine's null tenant. */
class TenantKeyTest {

    @Test
    void noTenantIsTheReservedNonEmptySharedKey() {
        assertEquals("-", TenantKey.SHARED);
        assertEquals(AbsentKey.NONE, TenantKey.SHARED, "the same platform 'none' key value as SL-84");
        assertEquals(TenantKey.SHARED, TenantKey.of(null));
        assertEquals(TenantKey.SHARED, TenantKey.of(""));
        assertEquals(TenantKey.SHARED, TenantKey.of(" "));
        assertEquals("RJ", TenantKey.of("RJ"));
        assertEquals(TenantKey.SHARED, TenantKey.of(TenantKey.SHARED), "a key read back keeps its meaning");
    }

    @Test
    void sharedReadsAsTheEngineNullTenantIncludingLegacyRows() {
        assertNull(TenantKey.toEngine(TenantKey.SHARED));
        assertNull(TenantKey.toEngine(""), "a row written before the migration");
        assertNull(TenantKey.toEngine(null), "Oracle returned '' as NULL");
        assertEquals("RJ", TenantKey.toEngine("RJ"));
        assertTrue(TenantKey.isShared(TenantKey.SHARED));
        assertFalse(TenantKey.isShared("default"), "the attribution tenant is a tenant, not the shared key");
    }

    @Test
    void theSharedKeyCannotBeARealTenant() {
        assertThrows(IllegalArgumentException.class, () -> TenantKey.requireNotShared(TenantKey.SHARED));
        assertEquals("RJ", TenantKey.requireNotShared("RJ"));
        assertEquals("default", TenantKey.requireNotShared("default"));
    }
}
