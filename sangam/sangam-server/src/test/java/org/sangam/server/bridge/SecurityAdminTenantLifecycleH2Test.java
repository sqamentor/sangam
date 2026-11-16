// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.Transactions;
import org.sangam.persistence.SangamSchema;
import org.sangam.rest.config.SpringTxSupport;
import org.sangam.rest.port.AdminPort;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.RestProperties;
import org.sangam.rest.support.TxSupport;
import org.sangam.security.SangamSecurity;
import org.sangam.security.admin.TenantAdminService;
import org.sangam.security.dialect.SecurityDialect;
import org.sangam.server.config.SecurityWiring;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * Round 6 item 4 on a real H2 security schema: validate is the activation dry run; a tenant's own administrator
 * activates it once the go-live checklist has evidence; a platform administrator suspends with a reason and resumes;
 * a platform administrator naming another tenant that is not suspended is refused by name and that tenant keeps its
 * status (before this round updateTenant's ACTIVE branch activated the caller's own tenant instead of the named one).
 */
class SecurityAdminTenantLifecycleH2Test {

    static final String OWN = "t-own";
    static final String OTHER = "t-other";

    private SangamSecurity security;
    private Transactions tx;
    private SecurityAdminBridge bridge;
    private final RequestContext platform = new RequestContext("platform", "root",
            Set.of(TenantAdminService.PLATFORM_ADMIN_ROLE), null, null, null, null, null, "OIDC");
    private final RequestContext ownAdmin = new RequestContext(OWN, "own-admin", Set.of("sangam-admin"), null, null,
            null, null, null, "DEV_TOKEN");

    @BeforeEach
    void schema() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:r6tl" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        SangamSchema.apply(ds);
        try (Connection c = ds.getConnection()) {
            security = new SangamSecurity(SecurityDialect.detect(c), null);
        }
        security.roles().usePlatformRoles(SecurityWiring.platformRoles());
        tx = Transactions.of(ds);
        SecurityBootstrap.run(security, tx, List.of(OWN), List.of("sangam-admin"), false);
        bridge = new SecurityAdminBridge(security,
                new SpringTxSupport(ds, new DataSourceTransactionManager(ds), TxSupport.detectDialect(ds)), null,
                new RestProperties());
        bridge.createTenant(platform, new AdminPort.Tenant(OTHER, "Other", null, "STANDARD", null, null, List.of(), null,
                null, 0));
    }

    private void recordEvidence() {
        tx.inTransaction(c -> {
            for (String item : TenantAdminService.CORE_CHECKLIST) {
                security.tenants().recordChecklistItem(c, SecurityIdentities.of(ownAdmin), item, "evidence:" + item);
            }
            return null;
        });
    }

    @Test
    void validateIsTheActivationDryRunAndTheOwnAdministratorActivates() {
        AdminPort.TenantReadiness before = bridge.validateTenant(ownAdmin, OWN);
        assertFalse(before.ready());
        assertEquals(TenantAdminService.CORE_CHECKLIST, before.missing());
        assertTrue(before.required().containsAll(TenantAdminService.CORE_CHECKLIST));
        assertNotEquals("ACTIVE", bridge.tenant(ownAdmin, OWN).status(), "validate changes nothing");

        ApiProblem blocked = assertThrows(ApiProblem.class, () -> bridge.activateTenant(ownAdmin, OWN, null));
        assertEquals(409, blocked.status());
        assertEquals("ACTIVATION_BLOCKED", blocked.code());

        recordEvidence();
        AdminPort.TenantReadiness ready = bridge.validateTenant(ownAdmin, OWN);
        assertTrue(ready.ready());
        assertEquals(List.of(), ready.missing());
        assertEquals("ACTIVE", bridge.activateTenant(ownAdmin, OWN, "go-live approved").status());
    }

    @Test
    void aPlatformAdministratorSuspendsAndResumes() {
        recordEvidence();
        bridge.activateTenant(ownAdmin, OWN, null);
        assertEquals("SUSPENDED", bridge.suspendTenant(platform, OWN, "unpaid invoice").status());
        assertEquals("ACTIVE", bridge.activateTenant(platform, OWN, "invoice paid").status());

        ApiProblem notPlatform = assertThrows(ApiProblem.class, () -> bridge.suspendTenant(ownAdmin, OWN, "self"));
        assertEquals(403, notPlatform.status());
        assertEquals("ACTIVE", bridge.tenant(ownAdmin, OWN).status());
    }

    @Test
    void anotherTenantIsNeverActivatedInTheCallersPlace() {
        String status = bridge.tenant(platform, OTHER).status();
        ApiProblem byRoute = assertThrows(ApiProblem.class, () -> bridge.activateTenant(platform, OTHER, null));
        assertEquals(409, byRoute.status());
        assertEquals("TENANT_ACTIVATION_BY_OWN_ADMIN", byRoute.code());
        // the same refusal through updateTenant's status change (the root defect: it activated the caller's tenant)
        ApiProblem byUpdate = assertThrows(ApiProblem.class, () -> bridge.updateTenant(platform, OTHER,
                new AdminPort.Tenant(OTHER, "Other", "ACTIVE", "STANDARD", null, null, List.of(), null, null, 0)));
        assertEquals("TENANT_ACTIVATION_BY_OWN_ADMIN", byUpdate.code());
        assertEquals(status, bridge.tenant(platform, OTHER).status());
    }

    @Test
    void aTenantAdministratorSeesOnlyItsOwnTenant() {
        assertEquals(404, assertThrows(ApiProblem.class, () -> bridge.validateTenant(ownAdmin, OTHER)).status());
        assertEquals(404, assertThrows(ApiProblem.class, () -> bridge.activateTenant(ownAdmin, OTHER, null)).status());
        assertEquals(404, assertThrows(ApiProblem.class, () -> bridge.suspendTenant(ownAdmin, OTHER, "r")).status());
    }
}
