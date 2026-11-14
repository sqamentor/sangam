// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import org.sangam.rest.config.SpringTxSupport;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.persistence.SangamSchema;
import org.sangam.rest.port.AdminPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.RestProperties;
import org.sangam.rest.support.TxSupport;
import org.sangam.security.SangamSecurity;
import org.sangam.security.dialect.SecurityDialect;
import org.sangam.server.bridge.DedicatedTenant;
import org.sangam.server.bridge.DedicatedTenantFilter;
import org.sangam.server.bridge.SecurityAdminBridge;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * M-016 / owner decision 97 with sangam-security on, through the wiring's own bean methods (not a hand-built filter):
 * the real {@link SecurityAdminBridge} on the real schema is the {@link AdminPort} both wirings read as the system
 * principal (W3-D 14:34:41Z: the system view of one tenant carries its recorded isolation).
 * <ul>
 *   <li>shared instance: the registered {@code sangamSharedIsolationFilter} is enabled and refuses DATABASE and SCHEMA
 *       tenants 421 {@value DedicatedTenantFilter#CODE}; SHARED and unknown tenants pass;</li>
 *   <li>dedicated instance: the start-up directory check passes when the database holds only the bound tenant recorded
 *       as DATABASE (before this fix it could never pass with security on), and still refuses a bound tenant recorded
 *       as SHARED or a database that holds another tenant.</li>
 * </ul>
 * Written for the test phase (not run here).
 */
class IsolationWiringSecurityOnH2Test {

    private final RequestContext platform = new RequestContext("platform", "root", Set.of("platform-admin"), null, null,
            null, null, null, "OIDC");

    private SangamSecurity security;
    private SecurityAdminBridge admin;

    @BeforeEach
    void schema() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:iws" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        SangamSchema.apply(ds);
        try (Connection c = ds.getConnection()) {
            security = new SangamSecurity(SecurityDialect.detect(c), null);
        }
        admin = new SecurityAdminBridge(security,
                new SpringTxSupport(ds, new DataSourceTransactionManager(ds), TxSupport.detectDialect(ds)), null, new RestProperties());
    }

    private static AdminPort.Tenant tenant(String id, String isolation) {
        return new AdminPort.Tenant(id, "Tenant " + id, null, "STANDARD", isolation, null, List.of(), null, null, 0);
    }

    private static boolean passes(DedicatedTenantFilter filter, String tenant, int[] status) throws Exception {
        MockHttpServletRequest r = new MockHttpServletRequest("GET", "/api/v1/tasks");
        r.setRequestURI("/api/v1/tasks");
        r.addHeader("X-Sangam-Tenant", tenant);
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(r, res, chain);
        status[0] = res.getStatus();
        if (chain.getRequest() == null) {
            assertTrue(res.getContentAsString().contains(DedicatedTenantFilter.CODE), tenant);
        }
        return chain.getRequest() != null;
    }

    private static MockEnvironment dedicatedTo(String tenant) {
        return new MockEnvironment().withProperty(DedicatedTenant.PROPERTY, tenant)
                .withProperty(DedicatedTenant.CELL_PROPERTY, "in-" + tenant)
                .withProperty(AttributionTenant.PROPERTY, tenant);
    }

    @Test
    void theRegisteredSharedFilterRefusesDatabaseAndSchemaTenantsWithSecurityOn() throws Exception {
        admin.createTenant(platform, tenant("d1", "DATABASE"));
        admin.createTenant(platform, tenant("s1", "SCHEMA"));
        admin.createTenant(platform, tenant("h1", "SHARED"));
        MockEnvironment env = new MockEnvironment();
        assertFalse(DedicatedTenantWiring.dedicated(env), "no dedicated-tenant property: the shared wiring applies");

        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("sangamAdminPort", admin);
        FilterRegistrationBean<DedicatedTenantFilter> reg = new SharedInstanceIsolationWiring()
                .sangamSharedIsolationFilter(env, beans.getBeanProvider(AdminPort.class));
        assertTrue(reg.isEnabled(), "the security module's directory is present");
        DedicatedTenantFilter filter = reg.getFilter();

        int[] status = new int[1];
        for (String t : List.of("d1", "s1")) {
            assertFalse(passes(filter, t, status), t + " is recorded for a database or schema of its own");
            assertEquals(421, status[0], t);
        }
        assertTrue(passes(filter, "h1", status), "SHARED is served");
        assertTrue(passes(filter, "nobody", status), "no record: the later layers answer as before");
    }

    @Test
    void aDedicatedInstanceStartsWithSecurityOnWhenItsTenantIsRecordedAsDatabase() {
        security.tenants().useDedicatedTenant("acme");
        assertEquals("DATABASE", admin.createTenant(platform, tenant("acme", null)).isolation(),
                "a dedicated instance records its own tenant as DATABASE");
        MockEnvironment env = dedicatedTo("acme");
        DedicatedTenant binding = new DedicatedTenantWiring().sangamDedicatedTenant(env);
        assertDoesNotThrow(() -> DedicatedTenantWiring.checkDirectory(binding, env, admin),
                "the system principal reads the directory and the recorded isolation");
    }

    @Test
    void aDedicatedInstanceStillRefusesASharedRecordOrAnotherTenantWithSecurityOn() {
        admin.createTenant(platform, tenant("acme", "SHARED"));
        MockEnvironment env = dedicatedTo("acme");
        DedicatedTenant binding = new DedicatedTenantWiring().sangamDedicatedTenant(env);
        IllegalStateException shared = assertThrows(IllegalStateException.class,
                () -> DedicatedTenantWiring.checkDirectory(binding, env, admin));
        assertTrue(shared.getMessage().contains("'SHARED'"), shared.getMessage());

        admin.createTenant(platform, tenant("other", "SHARED"));
        IllegalStateException mixed = assertThrows(IllegalStateException.class,
                () -> DedicatedTenantWiring.checkDirectory(binding, env, admin));
        assertTrue(mixed.getMessage().contains("other"), mixed.getMessage());
    }
}
