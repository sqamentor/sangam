// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.IdentityService;
import org.operaton.bpm.engine.impl.identity.Authentication;
import org.sangam.rest.port.SangamPrincipal;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * SL-45: {@code /engine-rest} runs with the caller's engine authentication limited to the token's tenant, so Operaton's
 * tenant check applies; without this filter Operaton saw no authentication and every tenant was visible (the
 * PostgreSQL proof is {@code CrossTenantIsolationIT#engineRestIsScopedToTheCallersTenant}).
 */
class EngineRestTenantFilterTest {

    /** An IdentityService that only keeps the current authentication. */
    private static IdentityService identity(AtomicReference<Authentication> current) {
        return (IdentityService) Proxy.newProxyInstance(IdentityService.class.getClassLoader(),
                new Class<?>[]{IdentityService.class}, (p, m, args) -> switch (m.getName()) {
                    case "getCurrentAuthentication" -> current.get();
                    case "setAuthentication" -> {
                        if (args.length == 1) {
                            current.set((Authentication) args[0]);
                        } else {
                            @SuppressWarnings("unchecked") List<String> g = (List<String>) args[1];
                            @SuppressWarnings("unchecked") List<String> t = args.length > 2 ? (List<String>) args[2] : null;
                            current.set(new Authentication((String) args[0], g, t));
                        }
                        yield null;
                    }
                    case "clearAuthentication" -> {
                        current.set(null);
                        yield null;
                    }
                    default -> throw new UnsupportedOperationException(m.getName());
                });
    }

    private static MockHttpServletRequest request(String uri, SangamPrincipal p) {
        MockHttpServletRequest r = new MockHttpServletRequest("GET", uri);
        if (p != null) {
            r.setAttribute(SangamPrincipal.ATTRIBUTE, p);
        }
        return r;
    }

    @Test
    void engineRestRunsWithTheTokensTenantOnlyAndWithoutOperatonAdminGroups() throws Exception {
        AtomicReference<Authentication> current = new AtomicReference<>();
        AtomicReference<Authentication> seen = new AtomicReference<>();
        EngineRestTenantFilter f = new EngineRestTenantFilter(() -> identity(current), () -> List.of("operaton-admin"));
        SangamPrincipal p = new SangamPrincipal("tenant-a", "alice", Set.of("sangam-engine-admin", "operaton-admin"),
                "OIDC", Map.of());
        f.doFilter(request("/engine-rest/task", p), new MockHttpServletResponse(), (rq, rs) -> seen.set(current.get()));
        assertEquals("alice", seen.get().getUserId());
        assertEquals(List.of("tenant-a"), seen.get().getTenantIds());
        assertEquals(List.of("sangam-engine-admin"), seen.get().getGroupIds(), "an Operaton admin group never passes");
        assertNull(current.get(), "the engine authentication is cleared after the request");
    }

    @Test
    void engineRestWithoutATenantIsRefused() throws Exception {
        AtomicReference<Authentication> current = new AtomicReference<>();
        EngineRestTenantFilter f = new EngineRestTenantFilter(() -> identity(current), List::of);
        MockHttpServletResponse res = new MockHttpServletResponse();
        boolean[] passed = {false};
        f.doFilter(request("/engine-rest/task", null), res, (rq, rs) -> passed[0] = true);
        assertEquals(403, res.getStatus());
        assertTrue(!passed[0], "no engine call without a tenant");
    }

    @Test
    void otherPathsAreUntouched() throws Exception {
        AtomicReference<Authentication> current = new AtomicReference<>();
        EngineRestTenantFilter f = new EngineRestTenantFilter(() -> identity(current), List::of);
        boolean[] passed = {false};
        f.doFilter(request("/api/v1/tasks", null), new MockHttpServletResponse(), (rq, rs) -> {
            passed[0] = true;
            assertNull(current.get());
        });
        assertTrue(passed[0]);
    }

    @Test
    void sl73TenantIdentityIsRefusedOnResourcesWithoutATenant() throws Exception {
        AtomicReference<Authentication> current = new AtomicReference<>();
        EngineRestTenantFilter f = new EngineRestTenantFilter(() -> identity(current), List::of);
        SangamPrincipal tenant = new SangamPrincipal("tenant-a", "alice", Set.of("sangam-engine-admin"), "OIDC", Map.of());
        for (String uri : List.of("/engine-rest/authorization", "/engine-rest/filter", "/engine-rest/user",
                "/engine-rest/group", "/engine-rest/tenant", "/engine-rest/identity/groups", "/engine-rest/metrics",
                "/engine-rest/history/cleanup", "/engine-rest/engine/default/authorization", "/engine-rest/au%74horization")) {
            MockHttpServletResponse res = new MockHttpServletResponse();
            boolean[] passed = {false};
            f.doFilter(request(uri, tenant), res, (rq, rs) -> passed[0] = true);
            assertEquals(403, res.getStatus(), uri);
            assertTrue(res.getContentAsString().contains("ENGINE_REST_RESOURCE_NOT_TENANT_SCOPED"), uri);
            assertTrue(!passed[0], uri + ": no engine call");
            assertNull(current.get(), uri);
        }
        boolean[] passed = {false};
        f.doFilter(request("/engine-rest/task", tenant), new MockHttpServletResponse(), (rq, rs) -> passed[0] = true);
        assertTrue(passed[0], "a tenant-scoped resource still passes");
    }

    @Test
    void sl73PlatformIdentityIsNotLimitedByTheAllowlist() throws Exception {
        AtomicReference<Authentication> current = new AtomicReference<>();
        AtomicReference<Authentication> seen = new AtomicReference<>();
        EngineRestTenantFilter f = new EngineRestTenantFilter(() -> identity(current), List::of);
        SangamPrincipal platform = new SangamPrincipal("tenant-a", "root", Set.of("sangam-admin", "sangam-platform"),
                "OIDC", Map.of());
        f.doFilter(request("/engine-rest/authorization", platform), new MockHttpServletResponse(),
                (rq, rs) -> seen.set(current.get()));
        assertEquals(List.of("tenant-a"), seen.get().getTenantIds(), "unchanged: the SL-45 tenant authentication");
    }
}
