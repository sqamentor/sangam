// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.sangam.rest.port.AdminPort;
import org.sangam.rest.port.ApiProblem;
import org.sangam.server.bridge.DedicatedTenant;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;

/**
 * Owner decision 97 wiring: exactly one of the dedicated and the shared wiring applies; the shared filter reads the
 * recorded isolation as the system principal (no record = served, any other failure propagates) and runs at the
 * dedicated filter's order; without a tenant directory it is registered disabled.
 */
class SharedInstanceIsolationWiringTest {

    private static AdminPort admin(Map<String, String> isolation, RuntimeException failure) {
        return (AdminPort) Proxy.newProxyInstance(AdminPort.class.getClassLoader(), new Class<?>[]{AdminPort.class},
                (proxy, m, args) -> switch (m.getName()) {
                    case "tenant" -> {
                        if (failure != null) {
                            throw failure;
                        }
                        String id = (String) args[1];
                        if (!isolation.containsKey(id)) {
                            throw ApiProblem.notFound("tenant", id);
                        }
                        yield new AdminPort.Tenant(id, id, "ACTIVE", "STANDARD", isolation.get(id), "en", List.of("en"),
                                Map.of(), Instant.parse("2026-01-01T00:00:00Z"), 1);
                    }
                    case "toString" -> "test AdminPort";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(m.getName());
                });
    }

    @Test
    void exactlyOneOfTheTwoWiringsApplies() {
        assertFalse(DedicatedTenantWiring.dedicated(new MockEnvironment()), "absent: shared");
        assertTrue(DedicatedTenantWiring.dedicated(new MockEnvironment().withProperty(DedicatedTenant.PROPERTY, "acme")));
        assertTrue(DedicatedTenantWiring.dedicated(new MockEnvironment().withProperty(DedicatedTenant.PROPERTY, "")),
                "an empty value counts as set, so the dedicated wiring refuses it instead of running shared");
    }

    @Test
    void theLookupReadsTheRecordedIsolation() {
        var lookup = SharedInstanceIsolationWiring.isolationLookup(
                admin(Map.of("t-db", "DATABASE", "t-shared", "SHARED"), null), new MockEnvironment());
        assertEquals("DATABASE", lookup.apply("t-db"));
        assertEquals("SHARED", lookup.apply("t-shared"));
        assertNull(lookup.apply("missing"), "no record");
    }

    @Test
    void aFailingDirectoryIsNotTreatedAsShared() {
        ApiProblem down = new ApiProblem(503, "UNAVAILABLE", "directory down");
        var lookup = SharedInstanceIsolationWiring.isolationLookup(admin(Map.of(), down), new MockEnvironment());
        assertSame(down, assertThrows(ApiProblem.class, () -> lookup.apply("t1")));
        IllegalStateException other = new IllegalStateException("db");
        var lookup2 = SharedInstanceIsolationWiring.isolationLookup(admin(Map.of(), other), new MockEnvironment());
        assertSame(other, assertThrows(IllegalStateException.class, () -> lookup2.apply("t1")));
    }

    @Test
    void theFilterRunsAtTheDedicatedFiltersOrder() {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("admin", admin(Map.of(), null));
        var reg = new SharedInstanceIsolationWiring().sangamSharedIsolationFilter(new MockEnvironment(),
                beans.getBeanProvider(AdminPort.class));
        assertEquals(DedicatedTenantWiring.FILTER_ORDER, reg.getOrder());
        assertTrue(reg.getUrlPatterns().containsAll(List.of("/api/*", "/engine-rest/*")));
        assertTrue(reg.isEnabled());

        var none = new SharedInstanceIsolationWiring().sangamSharedIsolationFilter(new MockEnvironment(),
                new StaticListableBeanFactory().getBeanProvider(AdminPort.class));
        assertFalse(none.isEnabled(), "no tenant directory, no recorded isolation to honour");
    }
}
