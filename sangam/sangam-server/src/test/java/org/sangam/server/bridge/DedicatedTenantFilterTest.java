// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.sangam.rest.port.SangamPrincipal;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * M-016: on an instance dedicated to {@code acme}, a request naming another tenant (principal, header, parameter or
 * cell tenant-admin path) is refused with 421 TENANT_NOT_ON_INSTANCE before the chain; everything else passes.
 */
class DedicatedTenantFilterTest {

    private final DedicatedTenantFilter filter = new DedicatedTenantFilter(DedicatedTenant.of("acme"));

    private static MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest r = new MockHttpServletRequest(method, uri);
        r.setRequestURI(uri);
        return r;
    }

    private static void principal(MockHttpServletRequest r, String tenant) {
        r.setAttribute(SangamPrincipal.ATTRIBUTE, new SangamPrincipal(tenant, "u1", Set.of(), "jwt", Map.of()));
    }

    /** Runs the filter; returns the response, and whether the chain was reached. */
    private record Outcome(MockHttpServletResponse response, boolean passed) {
    }

    private Outcome run(MockHttpServletRequest r) throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(r, res, chain);
        return new Outcome(res, chain.getRequest() != null);
    }

    private static void assertRefused(Outcome o, String tenant) throws Exception {
        assertFalse(o.passed(), "the chain must not run");
        assertEquals(421, o.response().getStatus());
        String body = o.response().getContentAsString();
        assertTrue(body.contains(DedicatedTenantFilter.CODE), body);
        assertTrue(body.contains(tenant), body);
        assertFalse(body.contains("acme"), "the answer never names the bound tenant: " + body);
    }

    @Test
    void theBoundTenantPasses() throws Exception {
        MockHttpServletRequest r = request("POST", "/api/v1/process-instances");
        principal(r, "acme");
        assertTrue(run(r).passed());
    }

    @Test
    void anotherTenantsPrincipalIsRefused() throws Exception {
        MockHttpServletRequest r = request("GET", "/api/v1/tasks");
        principal(r, "other");
        assertRefused(run(r), "other");
    }

    @Test
    void anotherTenantInTheHeaderOrParameterIsRefused() throws Exception {
        MockHttpServletRequest h = request("GET", "/api/v1/public/forms/f1");
        h.addHeader("X-Sangam-Tenant", "other");
        assertRefused(run(h), "other");

        MockHttpServletRequest p = request("GET", "/engine-rest/process-definition");
        p.setParameter("tenant", "other");
        assertRefused(run(p), "other");
    }

    @Test
    void aCellAdminCallForAnotherTenantIsRefused() throws Exception {
        MockHttpServletRequest r = request("POST", "/api/v1/admin/cell/tenants/other/import/prepare");
        principal(r, "*");
        assertRefused(run(r), "other");

        MockHttpServletRequest own = request("POST", "/api/v1/admin/cell/tenants/acme/import/prepare");
        principal(own, "*");
        assertTrue(run(own).passed(), "the bound tenant may be moved in or out");
    }

    @Test
    void requestsWithoutATenantPass() throws Exception {
        MockHttpServletRequest platform = request("GET", "/api/v1/admin/cell/exports/x1");
        principal(platform, "*");
        assertTrue(run(platform).passed());
        assertTrue(run(request("GET", "/api/v1/health")).passed());
    }

    @Test
    void pathsOutsideTheApiAreNotFiltered() throws Exception {
        MockHttpServletRequest r = request("GET", "/actuator/health");
        r.addHeader("X-Sangam-Tenant", "other");
        assertTrue(run(r).passed());
    }

    @Test
    void aSharedInstanceRefusesDatabaseAndSchemaTenants() throws Exception {
        Map<String, String> directory = Map.of("t-shared", "SHARED", "t-db", "DATABASE", "t-schema", "SCHEMA");
        DedicatedTenantFilter shared = new DedicatedTenantFilter(new SharedInstanceIsolation(directory::get));
        for (String refused : java.util.List.of("t-db", "t-schema")) {
            MockHttpServletRequest r = request("GET", "/api/v1/tasks");
            principal(r, refused);
            MockHttpServletResponse res = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();
            shared.doFilter(r, res, chain);
            assertNull(chain.getRequest(), refused + " must not reach the chain");
            assertEquals(421, res.getStatus());
            assertTrue(res.getContentAsString().contains(DedicatedTenantFilter.CODE), res.getContentAsString());
        }
        for (String served : java.util.List.of("t-shared", "unknown")) {
            MockHttpServletRequest r = request("GET", "/api/v1/tasks");
            principal(r, served);
            MockFilterChain chain = new MockFilterChain();
            shared.doFilter(r, new MockHttpServletResponse(), chain);
            assertTrue(chain.getRequest() != null, served + " is served");
        }
    }

    @Test
    void theAdminPathTenantIsParsed() {
        assertEquals("t1", DedicatedTenantFilter.adminPathTenant("/api/v1/admin/cell/tenants/t1/freeze"));
        assertEquals("t1", DedicatedTenantFilter.adminPathTenant("/api/v1/admin/cell/tenants/t1"));
        assertNull(DedicatedTenantFilter.adminPathTenant("/api/v1/admin/cell/tenants/"));
        assertNull(DedicatedTenantFilter.adminPathTenant("/api/v1/admin/cell/exports/e1"));
        assertNull(DedicatedTenantFilter.adminPathTenant("/api/v1/tasks"));
    }
}
