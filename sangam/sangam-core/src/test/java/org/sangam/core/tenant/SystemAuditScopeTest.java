// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.sangam.core.layer.TenantKey;

/**
 * Decision 144: the configured system audit scope must be a reserved system scope, never a customer tenant, and no
 * identity may use a reserved id as its tenant. Written for the test phase (not run here).
 */
class SystemAuditScopeTest {

    /** The tenant-id grammar of TenantAdminService and OnboardingService. */
    private static final String TENANT_GRAMMAR = "[A-Za-z0-9][A-Za-z0-9_.-]{0,59}";

    @Test
    void aValidScopeIsReturnedUnchanged() {
        assertEquals("system:audit", SystemAuditScope.validate("system:audit", "sangam.engine.system-audit-scope"));
        assertEquals("system:engine-eu.1", SystemAuditScope.validate("system:engine-eu.1", null));
    }

    @Test
    void invalidDefaultConfigurationIsRefused() {
        for (String bad : new String[] {null, "", "   ", "acme", "ACME", TenantKey.SHARED, "system:", "system:Audit",
                "system: audit", "System:audit", "system:-x", "system:a/b", " system:audit", "systemaudit",
                "system:" + "a".repeat(49)}) {
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> SystemAuditScope.validate(bad, "sangam.engine.system-audit-scope"), String.valueOf(bad));
            assertTrue(e.getMessage().contains("sangam.engine.system-audit-scope") && e.getMessage().contains("decision 144"),
                    e.getMessage());
        }
    }

    @Test
    void noTenantIdOfThePlatformGrammarIsASystemScope() {
        for (String tenant : new String[] {"acme", "tenant-1", "T_2", "a.b", "system", "system-audit", "system.audit",
                "SYSTEM", "x"}) {
            assertTrue(tenant.matches(TENANT_GRAMMAR), tenant);
            assertFalse(SystemAuditScope.isSystemScope(tenant), tenant);
            assertFalse(SystemAuditScope.isReserved(tenant), tenant);
            assertEquals(tenant, SystemAuditScope.requireTenantId(tenant));
        }
        // a system scope is never accepted by the tenant grammar (':' is not part of it)
        assertFalse("system:audit".matches(TENANT_GRAMMAR));
        assertFalse(TenantKey.SHARED.matches(TENANT_GRAMMAR));
    }

    @Test
    void reservedIdsAreRefusedAsTenantIds() {
        String first = null;
        for (String reserved : new String[] {"system:audit", "system:", "system:Bad Name", TenantKey.SHARED}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> SystemAuditScope.requireTenantId(reserved), reserved);
            // one fixed message for every value: it names no value
            first = first == null ? e.getMessage() : first;
            assertEquals(first, e.getMessage());
            assertFalse(e.getMessage().contains("Bad Name"));
        }
        assertEquals(null, SystemAuditScope.requireTenantId(null), "absence is the caller's own refusal");
    }
}
