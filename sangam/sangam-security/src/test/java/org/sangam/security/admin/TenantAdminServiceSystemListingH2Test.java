// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.security.SangamSecurityException;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.security.dialect.SecurityDialect;

/**
 * SL-117 Q1: background schedules read the tenant directory as the system principal. {@link TenantAdminService#list}
 * is platform-admin only and refused it (403), so every schedule's directory read failed. The dedicated read-only
 * {@link TenantAdminService#listForSystem} admits only the system principal and returns ids and statuses only; the
 * platform-admin listing and its guard are unchanged.
 */
class TenantAdminServiceSystemListingH2Test {

    private final TenantAdminService tenants = new TenantAdminService(SecurityDialect.H2, null, null);
    private Connection c;

    @BeforeEach
    void db() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=TRUE");
        c = ds.getConnection();
        Jdbc.update(c, "CREATE TABLE SEC_TENANT (tenant_id VARCHAR(64) PRIMARY KEY, name_en VARCHAR(255), name_hi VARCHAR(255),"
                + " name_local VARCHAR(255), hosting_profile VARCHAR(64), status VARCHAR(32), config TEXT, quotas TEXT,"
                + " four_eyes INT, isolation VARCHAR(16) DEFAULT 'SHARED' NOT NULL)");
        Jdbc.update(c, "INSERT INTO SEC_TENANT(tenant_id, name_en, name_hi, name_local, hosting_profile, status, config, quotas,"
                + " four_eyes) VALUES ('RJ','Rajasthan',NULL,NULL,'GOVERNMENT','ACTIVE','{}','{}',1),"
                + " ('MP','Madhya Pradesh',NULL,NULL,'GOVERNMENT','SUSPENDED','{}','{}',1),"
                + " ('GJ','Gujarat',NULL,NULL,'STANDARD','CREATED','{\"secret\":\"admin-only\"}','{}',0)");
    }

    @AfterEach
    void close() throws Exception {
        c.close();
    }

    @Test
    void theSystemPrincipalMayListIdsAndStatusesOnly() {
        List<TenantAdminService.SystemTenant> out = tenants.listForSystem(c, SecurityIdentity.system("default", "SYSTEM"));
        assertEquals(List.of(new TenantAdminService.SystemTenant("GJ", "CREATED"),
                new TenantAdminService.SystemTenant("MP", "SUSPENDED"),
                new TenantAdminService.SystemTenant("RJ", "ACTIVE")), out);
        assertEquals(List.of("tenantId", "status", "isolation"),
                java.util.Arrays.stream(TenantAdminService.SystemTenant.class.getRecordComponents())
                        .map(java.lang.reflect.RecordComponent::getName).toList(),
                "no admin data (names, config, quotas) in the system view: id, status and isolation only");
    }

    @Test
    void theSystemViewCarriesTheRecordedIsolation() {
        Jdbc.update(c, "UPDATE SEC_TENANT SET isolation = 'DATABASE' WHERE tenant_id = 'MP'");
        SecurityIdentity sys = SecurityIdentity.system("default", "SYSTEM");
        assertEquals("DATABASE", tenants.listForSystem(c, sys).get(1).isolation());
        assertEquals(java.util.Optional.of(new TenantAdminService.SystemTenant("MP", "SUSPENDED", "DATABASE")),
                tenants.findForSystem(c, sys, "MP"));
        assertEquals(java.util.Optional.of(new TenantAdminService.SystemTenant("RJ", "ACTIVE", "SHARED")),
                tenants.findForSystem(c, sys, "RJ"));
        assertEquals(java.util.Optional.empty(), tenants.findForSystem(c, sys, "XX"));
        assertThrows(SangamSecurityException.AccessDenied.class,
                () -> tenants.findForSystem(c, user("RJ", TenantAdminService.PLATFORM_ADMIN_ROLE), "MP"),
                "the one-tenant system read is for the system principal only");
    }

    @Test
    void aNonSystemIdentityIsRefusedEvenWithAdminRoles() {
        assertThrows(SangamSecurityException.AccessDenied.class, () -> tenants.listForSystem(c, user("RJ", "tenant-admin")));
        assertThrows(SangamSecurityException.AccessDenied.class,
                () -> tenants.listForSystem(c, user("RJ", TenantAdminService.PLATFORM_ADMIN_ROLE)),
                "the system listing is for the system principal only");
        assertThrows(SangamSecurityException.AccessDenied.class, () -> tenants.listForSystem(c, null));
    }

    @Test
    void thePlatformAdminListingIsUnchanged() {
        List<TenantAdminService.Tenant> out = tenants.list(c, user("RJ", TenantAdminService.PLATFORM_ADMIN_ROLE));
        assertEquals(List.of("GJ", "MP", "RJ"), out.stream().map(TenantAdminService.Tenant::tenantId).toList());
        assertEquals("Gujarat", out.get(0).nameEn());
        assertEquals(Map.of("secret", "admin-only"), out.get(0).config());

        assertThrows(SangamSecurityException.AccessDenied.class, () -> tenants.list(c, user("RJ", "tenant-admin")));
        assertThrows(SangamSecurityException.AccessDenied.class,
                () -> tenants.list(c, SecurityIdentity.system("default", "SYSTEM")),
                "list() still requires the platform-admin role; the system principal uses listForSystem");
    }

    private static SecurityIdentity user(String tenant, String role) {
        return new SecurityIdentity(tenant, "u1", "u1", Set.of(), Set.of(role), Set.of(), Set.of(), "OIDC", null, null,
                false, Map.of());
    }
}
