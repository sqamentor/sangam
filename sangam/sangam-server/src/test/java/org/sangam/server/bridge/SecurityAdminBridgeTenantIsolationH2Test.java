// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Connection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.persistence.SangamSchema;
import org.sangam.rest.port.AdminPort;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.RestProperties;
import org.sangam.rest.support.TxSupport;
import org.sangam.security.SangamSecurity;
import org.sangam.security.dialect.SecurityDialect;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * Decision 97 root defect (W3-H): the admin bridge dropped the tenant's isolation on create and answered SHARED on
 * every read. On the real schema (changeset 0043-11, {@code SEC_TENANT.isolation}) DATABASE and SCHEMA now round-trip
 * through create, read and update, an update never changes (and so never downgrades) the isolation, and a dedicated
 * instance creates no other tenant. Written for the test phase (not run here).
 */
class SecurityAdminBridgeTenantIsolationH2Test {

    private SangamSecurity security;
    private SecurityAdminBridge bridge;
    private final RequestContext platform = new RequestContext("platform", "root", Set.of("platform-admin"), null, null,
            null, null, null, "OIDC");

    @BeforeEach
    void schema() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:iso" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        SangamSchema.apply(ds);
        try (Connection c = ds.getConnection()) {
            security = new SangamSecurity(SecurityDialect.detect(c), null);
        }
        bridge = new SecurityAdminBridge(security,
                new TxSupport(ds, new DataSourceTransactionManager(ds), TxSupport.detectDialect(ds)), null, new RestProperties());
    }

    static AdminPort.Tenant request(String id, String isolation) {
        return new AdminPort.Tenant(id, "Tenant " + id, null, "STANDARD", isolation, null, List.of(), null, null, 0);
    }

    @Test
    void databaseAndSchemaRoundTripThroughCreateAndRead() {
        assertEquals("DATABASE", bridge.createTenant(platform, request("d1", "DATABASE")).isolation());
        assertEquals("SCHEMA", bridge.createTenant(platform, request("s1", "SCHEMA")).isolation());
        assertEquals("SHARED", bridge.createTenant(platform, request("h1", null)).isolation());
        assertEquals("DATABASE", bridge.tenant(platform, "d1").isolation());
        assertEquals("SCHEMA", bridge.tenant(platform, "s1").isolation());
        assertEquals("SHARED", bridge.tenant(platform, "h1").isolation());
    }

    @Test
    void anUpdateNeverDowngradesTheIsolation() {
        bridge.createTenant(platform, request("d1", "DATABASE"));
        bridge.createTenant(platform, request("s1", "SCHEMA"));
        for (String target : List.of("SHARED", "SCHEMA")) {
            ApiProblem e = assertThrows(ApiProblem.class,
                    () -> bridge.updateTenant(platform, "d1", request("d1", target)));
            assertEquals(409, e.status());
            assertEquals("ISOLATION_IMMUTABLE", e.code());
        }
        ApiProblem e = assertThrows(ApiProblem.class, () -> bridge.updateTenant(platform, "s1", request("s1", "SHARED")));
        assertEquals("ISOLATION_IMMUTABLE", e.code());
        // an update without an isolation, or with the same one, keeps it
        assertEquals("DATABASE", bridge.updateTenant(platform, "d1", request("d1", null)).isolation());
        assertEquals("DATABASE", bridge.updateTenant(platform, "d1", request("d1", "database")).isolation());
        assertEquals("DATABASE", bridge.tenant(platform, "d1").isolation());
        assertEquals("SCHEMA", bridge.tenant(platform, "s1").isolation());
    }

    @Test
    void aDedicatedInstanceRefusesOtherTenants() {
        security.tenants().useDedicatedTenant("d1");
        ApiProblem e = assertThrows(ApiProblem.class, () -> bridge.createTenant(platform, request("x1", "SHARED")));
        assertEquals(409, e.status());
        assertEquals("DATABASE", bridge.createTenant(platform, request("d1", null)).isolation());
    }
}
