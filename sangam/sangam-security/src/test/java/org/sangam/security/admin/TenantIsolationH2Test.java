// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Schema;
import org.sangam.core.security.SangamSecurityException;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.security.audit.SecurityAuditService;
import org.sangam.security.dialect.SecurityDialect;
import org.sangam.security.policy.AuthorizationService;
import org.sangam.security.policy.PolicyCompiler;
import org.sangam.security.policy.ResourceRegistry;

/**
 * Decision 97 root defect (W3-H): {@code SEC_TENANT.isolation} (changeset 0043-11) was dropped by the tenant service,
 * so every tenant read back as SHARED. The isolation is stored on create, read by {@code find} and {@code list}, never
 * downgraded, and a dedicated instance ({@code sangam.tenancy.dedicated-tenant}) creates only its own tenant, as
 * DATABASE. Uses the module's reference DDL for SEC_TENANT (with the column, its default and check). Written for the
 * test phase (not run here).
 */
class TenantIsolationH2Test {

    private Connection c;
    private TenantAdminService tenants;
    private final SecurityIdentity platform = new SecurityIdentity("platform", "root", "root", Set.of(),
            Set.of(TenantAdminService.PLATFORM_ADMIN_ROLE), Set.of(), Set.of(), "OIDC", null, null, false, Map.of());

    @BeforeEach
    void db() throws Exception {
        c = DriverManager.getConnection("jdbc:h2:mem:iso" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=TRUE", "sa", "");
        Schema.create(c, Dialect.POSTGRESQL);   // AUDIT_EVENT, AUDIT_PAYLOAD, ROOT_SEQ
        Jdbc.update(c, "CREATE TABLE SEC_TENANT (tenant_id VARCHAR(64) NOT NULL PRIMARY KEY, name_en VARCHAR(255) NOT NULL,"
                + " name_hi VARCHAR(255), name_local VARCHAR(255), hosting_profile VARCHAR(64), status VARCHAR(16) NOT NULL,"
                + " config TEXT NOT NULL, quotas TEXT NOT NULL, four_eyes SMALLINT NOT NULL,"
                + " created_at TIMESTAMP WITH TIME ZONE NOT NULL, activated_at TIMESTAMP WITH TIME ZONE,"
                + " isolation VARCHAR(16) DEFAULT 'SHARED' NOT NULL CHECK (isolation IN ('SHARED', 'SCHEMA', 'DATABASE')))");
        SecurityAuditService audit = new SecurityAuditService(SecurityDialect.H2);
        AuthorizationService authz = new AuthorizationService(
                new PolicyCompiler(SecurityDialect.H2, new ResourceRegistry(), Duration.ZERO), audit);
        tenants = new TenantAdminService(SecurityDialect.H2, authz, audit);
    }

    @AfterEach
    void close() throws Exception {
        c.close();
    }

    @Test
    void databaseAndSchemaRoundTripThroughCreateFindAndList() {
        assertEquals("DATABASE", tenants.createTenant(c, platform, "d1", "D1", null, null, null, Map.of(), "DATABASE")
                .isolation());
        assertEquals("SCHEMA", tenants.createTenant(c, platform, "s1", "S1", null, null, null, Map.of(), "schema")
                .isolation());
        assertEquals("SHARED", tenants.createTenant(c, platform, "h1", "H1", null, null, null, Map.of()).isolation(),
                "the old signature is SHARED on a shared instance");
        assertEquals("DATABASE", tenants.find(c, "d1").orElseThrow().isolation());
        assertEquals("SCHEMA", tenants.find(c, "s1").orElseThrow().isolation());
        assertEquals(List.of("DATABASE", "SHARED", "SCHEMA"),
                tenants.list(c, platform).stream().map(TenantAdminService.Tenant::isolation).toList());
        assertEquals("DATABASE", Jdbc.one(c, "SELECT isolation FROM SEC_TENANT WHERE tenant_id='d1'",
                rs -> rs.getString(1)).orElseThrow(), "stored, not only echoed");
    }

    @Test
    void laterAdministrationNeverDowngradesTheIsolation() {
        tenants.createTenant(c, platform, "d1", "D1", null, null, null, Map.of(), "DATABASE");
        tenants.createTenant(c, platform, "s1", "S1", null, null, null, Map.of(), "SCHEMA");
        tenants.suspend(c, platform, "d1", "test");
        tenants.resume(c, platform, "d1");
        tenants.suspend(c, platform, "s1", "test");
        assertEquals("DATABASE", tenants.find(c, "d1").orElseThrow().isolation());
        assertEquals("SCHEMA", tenants.find(c, "s1").orElseThrow().isolation());
        // a second create of the same id is refused and leaves the stored isolation as it was
        assertThrows(SangamSecurityException.AdminConflict.class,
                () -> tenants.createTenant(c, platform, "d1", "D1", null, null, null, Map.of(), "SHARED"));
        assertEquals("DATABASE", tenants.find(c, "d1").orElseThrow().isolation());
    }

    @Test
    void anUnknownIsolationIsRefused() {
        SangamSecurityException.AdminConflict e = assertThrows(SangamSecurityException.AdminConflict.class,
                () -> tenants.createTenant(c, platform, "x1", "X1", null, null, null, Map.of(), "POOLED"));
        assertEquals("INVALID_ISOLATION", e.code());
        assertEquals(0L, Jdbc.one(c, "SELECT COUNT(*) FROM SEC_TENANT", rs -> rs.getLong(1)).orElseThrow());
    }

    @Test
    void aDedicatedInstanceCreatesOnlyItsOwnTenantAsDatabase() {
        tenants.useDedicatedTenant("d1");
        SangamSecurityException.AdminConflict other = assertThrows(SangamSecurityException.AdminConflict.class,
                () -> tenants.createTenant(c, platform, "x1", "X1", null, null, null, Map.of(), "SHARED"));
        assertEquals("DEDICATED_INSTANCE", other.code());
        assertThrows(SangamSecurityException.AdminConflict.class,
                () -> tenants.createTenant(c, platform, "x2", "X2", null, null, null, Map.of()));
        SangamSecurityException.AdminConflict shared = assertThrows(SangamSecurityException.AdminConflict.class,
                () -> tenants.createTenant(c, platform, "d1", "D1", null, null, null, Map.of(), "SHARED"));
        assertEquals("INVALID_ISOLATION", shared.code());
        assertEquals("DATABASE", tenants.createTenant(c, platform, "d1", "D1", null, null, null, Map.of()).isolation(),
                "the instance's own tenant (old signature, as the bootstraps call it) is DATABASE");
        assertEquals(List.of("d1"), Jdbc.query(c, "SELECT tenant_id FROM SEC_TENANT", rs -> rs.getString(1)));
    }
}
