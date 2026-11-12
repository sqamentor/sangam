// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.sangam.rest.port.AdminPort;
import org.sangam.rest.port.Page;
import org.sangam.server.bridge.DedicatedTenant;
import org.springframework.mock.env.MockEnvironment;

/**
 * M-016: the start-up checks of a dedicated instance (attribution tenant, directory with suspended tenants, recorded
 * isolation) and the filter order. The default (property absent) has no bean at all: {@code @ConditionalOnProperty}.
 */
class DedicatedTenantWiringTest {

    private static AdminPort.Tenant tenant(String id, String status, String isolation) {
        return new AdminPort.Tenant(id, id, status, "STANDARD", isolation, "en", List.of("en"), Map.of(),
                Instant.parse("2026-01-01T00:00:00Z"), 1);
    }

    /** An AdminPort that answers tenants() and tenant() only (one page). */
    private static AdminPort admin(List<AdminPort.Tenant> all) {
        return (AdminPort) Proxy.newProxyInstance(AdminPort.class.getClassLoader(), new Class<?>[]{AdminPort.class},
                (proxy, m, args) -> switch (m.getName()) {
                    case "tenants" -> Page.of(all, all.size(), false);
                    case "tenant" -> all.stream().filter(t -> t.id().equals(args[1])).findFirst().orElseThrow();
                    case "toString" -> "test AdminPort";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(m.getName());
                });
    }

    private static MockEnvironment env() {
        return new MockEnvironment().withProperty(DedicatedTenant.PROPERTY, "acme")
                .withProperty(DedicatedTenant.CELL_PROPERTY, "in-acme")
                .withProperty(AttributionTenant.PROPERTY, "acme");
    }

    @Test
    void theBindingNeedsACell() {
        MockEnvironment noCell = new MockEnvironment().withProperty(DedicatedTenant.PROPERTY, "acme")
                .withProperty(AttributionTenant.PROPERTY, "acme");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new DedicatedTenantWiring().sangamDedicatedTenant(noCell));
        assertTrue(e.getMessage().contains(DedicatedTenant.CELL_PROPERTY), e.getMessage());
    }

    @Test
    void theBindingNeedsTheAttributionTenant() {
        DedicatedTenantWiring w = new DedicatedTenantWiring();
        assertEquals("acme", w.sangamDedicatedTenant(env()).tenant());
        MockEnvironment noDefault = new MockEnvironment().withProperty(DedicatedTenant.PROPERTY, "acme")
                .withProperty(DedicatedTenant.CELL_PROPERTY, "in-acme");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> w.sangamDedicatedTenant(noDefault));
        assertTrue(e.getMessage().contains("'" + AttributionTenant.FALLBACK + "'"), e.getMessage());
    }

    @Test
    void anEmptyDirectoryOrTheBoundTenantAloneStarts() {
        DedicatedTenant d = DedicatedTenant.of("acme");
        assertDoesNotThrow(() -> DedicatedTenantWiring.checkDirectory(d, env(), admin(List.of())));
        assertDoesNotThrow(() -> DedicatedTenantWiring.checkDirectory(d, env(),
                admin(List.of(tenant("acme", "ACTIVE", "DATABASE")))));
    }

    @Test
    void aSuspendedOtherTenantStillBlocksTheStart() {
        DedicatedTenant d = DedicatedTenant.of("acme");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> DedicatedTenantWiring.checkDirectory(
                d, env(), admin(List.of(tenant("acme", "ACTIVE", "DATABASE"), tenant("old", "SUSPENDED", "SHARED")))));
        assertTrue(e.getMessage().contains("[old]"), e.getMessage());
    }

    @Test
    void theBoundTenantMustBeRecordedWithDatabaseIsolation() {
        DedicatedTenant d = DedicatedTenant.of("acme");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> DedicatedTenantWiring.checkDirectory(
                d, env(), admin(List.of(tenant("acme", "ACTIVE", "SHARED")))));
        assertTrue(e.getMessage().contains("'SHARED'"), e.getMessage());
    }

    @Test
    void withoutTheDirectoryTheStartIsRefusedNotSkipped() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> DedicatedTenantWiring.checkDirectory(DedicatedTenant.of("acme"), env(), null));
        assertTrue(e.getMessage().contains("refusing to start"), e.getMessage());
    }

    @Test
    void theFilterRunsJustBeforeTheMoveStateFilter() {
        assertEquals(CellWiring.STATE_FILTER_ORDER - 1, DedicatedTenantWiring.FILTER_ORDER);
        var reg = new DedicatedTenantWiring().sangamDedicatedTenantFilter(DedicatedTenant.of("acme"));
        assertEquals(DedicatedTenantWiring.FILTER_ORDER, reg.getOrder());
        assertTrue(reg.getUrlPatterns().containsAll(List.of("/api/*", "/engine-rest/*")));
    }
}
