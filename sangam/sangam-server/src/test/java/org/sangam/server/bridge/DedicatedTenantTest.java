// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/** M-016 (decision 51): the binding of an instance to one tenant's database and its fail-closed start-up rules. */
class DedicatedTenantTest {

    @Test
    void theBindingNamesExactlyOneTenant() {
        assertEquals("acme", DedicatedTenant.of(" acme ").tenant());
        for (String bad : Arrays.asList(null, "", "   ", "*")) {
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> DedicatedTenant.of(bad));
            assertTrue(e.getMessage().contains(DedicatedTenant.PROPERTY), e.getMessage());
        }
    }

    @Test
    void onlyTheBoundTenantIsAdmitted() {
        DedicatedTenant d = DedicatedTenant.of("acme");
        assertTrue(d.admits("acme"));
        assertTrue(d.admits(" acme "));
        assertTrue(d.admits(null), "a request without a tenant is not tenant data");
        assertTrue(d.admits(""));
        assertFalse(d.admits("other"));
        assertFalse(d.admits("ACME"), "tenant ids are compared exactly");
        assertFalse(d.admits("*"), "the wildcard is never this tenant");
    }

    @Test
    void theAttributionTenantMustBeTheBoundTenant() {
        DedicatedTenant d = DedicatedTenant.of("acme");
        assertDoesNotThrow(() -> d.requireAttribution("acme"));
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> d.requireAttribution("default"));
        assertTrue(e.getMessage().contains("sangam.tenant.default=acme"), e.getMessage());
        assertTrue(e.getMessage().contains("'default'"), e.getMessage());
    }

    @Test
    void aDedicatedInstanceIsACell() {
        DedicatedTenant d = DedicatedTenant.of("acme");
        assertDoesNotThrow(() -> d.requireCell("in-acme"));
        for (String none : Arrays.asList(null, "", "  ")) {
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> d.requireCell(none));
            assertTrue(e.getMessage().contains(DedicatedTenant.CELL_PROPERTY), e.getMessage());
        }
    }

    @Test
    void theDatabaseHoldsNoOtherTenant() {
        DedicatedTenant d = DedicatedTenant.of("acme");
        assertDoesNotThrow(() -> d.requireDirectory(List.of()), "before onboarding the directory is empty");
        assertDoesNotThrow(() -> d.requireDirectory(List.of("acme")));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> d.requireDirectory(List.of("zeta", "acme", "beta", "zeta")));
        assertTrue(e.getMessage().contains("[beta, zeta]"), "other tenants listed once, sorted: " + e.getMessage());
    }

    @Test
    void theBoundTenantIsRecordedWithDatabaseIsolation() {
        DedicatedTenant d = DedicatedTenant.of("acme");
        assertDoesNotThrow(() -> d.requireIsolation(DedicatedTenant.DATABASE));
        for (String other : Arrays.asList("SHARED", "SCHEMA", null, "database")) {
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> d.requireIsolation(other));
            assertTrue(e.getMessage().contains("DATABASE"), e.getMessage());
        }
    }
}
