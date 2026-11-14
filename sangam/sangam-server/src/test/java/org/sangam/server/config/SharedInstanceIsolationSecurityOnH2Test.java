// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import org.sangam.rest.config.SpringTxSupport;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.security.SangamSecurityException;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.persistence.SangamSchema;
import org.sangam.rest.port.AdminPort;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.RestProperties;
import org.sangam.rest.support.TxSupport;
import org.sangam.security.SangamSecurity;
import org.sangam.security.dialect.SecurityDialect;
import org.sangam.server.bridge.DedicatedTenantFilter;
import org.sangam.server.bridge.SecurityAdminBridge;
import org.sangam.server.bridge.SharedInstanceIsolation;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Decision 97 root cause (integrator, W3-H): with security on, {@link SharedInstanceIsolationWiring} reads the tenant
 * directory as the system principal {@value SharedInstanceIsolationWiring#PRINCIPAL}, and the admin bridge answered
 * 404 to anyone but the tenant itself or a platform admin, so the recorded isolation was never seen and a DATABASE
 * tenant was served on a shared instance. On the real schema with the real {@link SecurityAdminBridge}: the system
 * principal now gets the read-only system view (id, status, isolation; no admin data), a DATABASE or SCHEMA tenant is
 * refused 421 {@value DedicatedTenantFilter#CODE}, a SHARED tenant and an unknown tenant are served, and a user of
 * another tenant still gets 404. Written for the test phase (not run here).
 */
class SharedInstanceIsolationSecurityOnH2Test {

    private final RequestContext platform = new RequestContext("platform", "root", Set.of("platform-admin"), null, null,
            null, null, null, "OIDC");

    private SangamSecurity security;
    private SecurityAdminBridge admin;
    private DedicatedTenantFilter filter;
    private JdbcDataSource ds;

    @BeforeEach
    void schema() throws Exception {
        ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:sii" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        SangamSchema.apply(ds);
        try (Connection c = ds.getConnection()) {
            security = new SangamSecurity(SecurityDialect.detect(c), null);
        }
        admin = new SecurityAdminBridge(security,
                new SpringTxSupport(ds, new DataSourceTransactionManager(ds), TxSupport.detectDialect(ds)), null, new RestProperties());
        admin.createTenant(platform, tenant("d1", "DATABASE"));
        admin.createTenant(platform, tenant("s1", "SCHEMA"));
        admin.createTenant(platform, tenant("h1", "SHARED"));
        filter = new DedicatedTenantFilter(new SharedInstanceIsolation(
                SharedInstanceIsolationWiring.isolationLookup(admin, new MockEnvironment())));
    }

    static AdminPort.Tenant tenant(String id, String isolation) {
        return new AdminPort.Tenant(id, "Tenant " + id, null, "STANDARD", isolation, null, List.of(), null, null, 0);
    }

    private record Outcome(MockHttpServletResponse response, boolean passed) {
    }

    private Outcome request(String tenant) throws Exception {
        MockHttpServletRequest r = new MockHttpServletRequest("GET", "/api/v1/tasks");
        r.setRequestURI("/api/v1/tasks");
        r.addHeader("X-Sangam-Tenant", tenant);
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(r, res, chain);
        return new Outcome(res, chain.getRequest() != null);
    }

    @Test
    void aDatabaseOrSchemaTenantIsRefusedWith421OnASharedInstance() throws Exception {
        for (String t : List.of("d1", "s1")) {
            Outcome o = request(t);
            assertFalse(o.passed(), t + " must not reach the chain");
            assertEquals(421, o.response().getStatus(), t);
            assertTrue(o.response().getContentAsString().contains(DedicatedTenantFilter.CODE), t);
        }
    }

    @Test
    void aSharedTenantAndAnUnknownTenantAreServed() throws Exception {
        assertTrue(request("h1").passed());
        assertTrue(request("nobody").passed(), "no record: the later layers answer as before");
    }

    @Test
    void theSystemPrincipalReadsTheSystemViewOnlyAndAUserOfAnotherTenantStillGets404() {
        RequestContext sys = ScheduleTenants.systemContext(new MockEnvironment(), SharedInstanceIsolationWiring.PRINCIPAL);
        AdminPort.Tenant view = admin.tenant(sys, "d1");
        assertEquals("d1", view.id());
        assertEquals("DATABASE", view.isolation());
        assertNull(view.name(), "the system view carries no admin data");
        assertEquals(404, assertThrows(ApiProblem.class, () -> admin.tenant(sys, "nobody")).status());

        RequestContext other = new RequestContext("h1", "u1", Set.of("tenant-admin"), null, null, null, null, null, "OIDC");
        assertEquals(404, assertThrows(ApiProblem.class, () -> admin.tenant(other, "d1")).status(),
                "a user's read of another tenant is unchanged");
        assertEquals("SHARED", admin.tenant(other, "h1").isolation());
    }

    @Test
    void theSystemReadIsForTheSystemPrincipalOnly() throws Exception {
        try (Connection c = ds.getConnection()) {
            assertEquals("SCHEMA", security.tenants().findForSystem(c, SecurityIdentity.system("default", "SYSTEM"), "s1")
                    .orElseThrow().isolation());
            assertTrue(security.tenants().findForSystem(c, SecurityIdentity.system("default", "SYSTEM"), "nobody").isEmpty());
            SecurityIdentity platformAdmin = new SecurityIdentity("platform", "root", "root", Set.of(),
                    Set.of("platform-admin"), Set.of(), Set.of(), "OIDC", null, null, false, Map.of());
            assertThrows(SangamSecurityException.AccessDenied.class,
                    () -> security.tenants().findForSystem(c, platformAdmin, "s1"));
            assertThrows(SangamSecurityException.AccessDenied.class, () -> security.tenants().findForSystem(c, null, "s1"));
        }
    }
}
