// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;
import org.sangam.persistence.SangamSchema;
import org.sangam.rest.config.SpringTxSupport;
import org.sangam.rest.port.AdminPort;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.RestProperties;
import org.sangam.rest.support.TxSupport;
import org.sangam.security.SangamSecurity;
import org.sangam.security.dialect.SecurityDialect;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * EV-M-0923: a tenant's org level types could be defined only by the platform onboarding flow, so a tenant created by
 * FirstAdminBootstrap (GOI.FIN on demo-021498ea60d1) could create no org unit (409 UNKNOWN_LEVEL), hence no seat and no
 * posting. {@code PUT /api/v1/admin/org-levels} lets the tenant's administrator define them: tenant-scoped, audited
 * (TENANT_CONFIGURED once), idempotent, fixed once a unit exists, never for another tenant, refused without the
 * org-unit admin permission.
 */
class OrgLevelsAdminH2Test {

    static final String T = "GOI.FIN";
    static final String OTHER = "GOI.EDU";

    Transactions tx;
    SecurityAdminBridge bridge;
    RequestContext admin;

    @BeforeEach
    void setUp() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:orglevels" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        SangamSchema.apply(ds);
        SangamSecurity security;
        try (Connection c = ds.getConnection()) {
            security = new SangamSecurity(SecurityDialect.detect(c), null);
        }
        tx = Transactions.of(ds);
        Instant past = Instant.parse("2020-01-01T00:00:00Z");
        tx.inTransaction(c -> {
            for (String t : List.of(T, OTHER)) {
                Jdbc.update(c, "INSERT INTO SEC_TENANT(tenant_id, name_en, status, config, quotas, four_eyes, created_at)"
                        + " VALUES (?,?,?,?,?,?,?)", t, "Finance", "CREATED", "{}", "{}", 0, past);
            }
            return null;
        });
        bridge = new SecurityAdminBridge(security,
                new SpringTxSupport(ds, new DataSourceTransactionManager(ds), TxSupport.detectDialect(ds)), null, new RestProperties());
        admin = new RequestContext(T, "rajendra.sharma", Set.of("sangam-system"), null, null, null, null, null, "SYSTEM");
    }

    @Test
    void theTenantAdministratorDefinesItsLevelsOnceAndAgainChangesNothing() {
        assertEquals(List.of(), bridge.orgLevels(admin).levels());
        AdminPort.OrgLevels defined = bridge.defineOrgLevels(admin, new AdminPort.OrgLevels(null, List.of("DEPARTMENT", "OFFICE")));
        assertEquals(new AdminPort.OrgLevels(T, List.of("DEPARTMENT", "OFFICE")), defined);
        assertEquals(1, events("TENANT_CONFIGURED"));

        // an org unit at a defined level now exists
        bridge.createOrgUnit(admin, new AdminPort.OrgUnit("PAO-OFFICE", null, null, "PAO-OFFICE", "Pay and Accounts Office Jaipur",
                null, "OFFICE", null, null, 0));

        // the same list again (a rerun of the seed): no change, no second event, even though units exist
        assertEquals(defined, bridge.defineOrgLevels(admin, new AdminPort.OrgLevels(T, List.of("DEPARTMENT", "OFFICE"))));
        assertEquals(1, events("TENANT_CONFIGURED"));

        // a different list once units exist: the levels are fixed
        ApiProblem p = assertThrows(ApiProblem.class,
                () -> bridge.defineOrgLevels(admin, new AdminPort.OrgLevels(null, List.of("OFFICE"))));
        assertEquals("LEVELS_IN_USE", p.code());
        assertEquals(List.of("DEPARTMENT", "OFFICE"), bridge.orgLevels(admin).levels());
    }

    @Test
    void anotherTenantsLevelsAreNeverWritten() {
        ApiProblem p = assertThrows(ApiProblem.class,
                () -> bridge.defineOrgLevels(admin, new AdminPort.OrgLevels(OTHER, List.of("OFFICE"))));
        assertEquals(403, p.status());
        assertEquals("TENANT_MISMATCH", p.code());
        assertEquals(List.of(), levelsOf(OTHER));
        assertEquals(List.of(), levelsOf(T));
    }

    @Test
    void invalidListsAreRefused() {
        for (List<String> bad : List.of(List.<String>of(), List.of("OFFICE", "OFFICE"), List.of("Office unit"))) {
            ApiProblem p = assertThrows(ApiProblem.class, () -> bridge.defineOrgLevels(admin, new AdminPort.OrgLevels(null, bad)));
            assertEquals("VALIDATION_FAILED", p.code(), bad.toString());
        }
        assertEquals(List.of(), levelsOf(T));
    }

    @Test
    void aCallerWithoutTheOrgUnitAdminPermissionIsRefused() {
        RequestContext clerk = new RequestContext(T, "kavita.sharma", Set.of("sangam-user"), null, null, null, null, null, "OIDC");
        ApiProblem p = assertThrows(ApiProblem.class,
                () -> bridge.defineOrgLevels(clerk, new AdminPort.OrgLevels(null, List.of("OFFICE"))));
        assertEquals(403, p.status(), p.code());
        assertEquals(List.of(), levelsOf(T));
    }

    private List<String> levelsOf(String tenant) {
        return tx.inTransaction(c -> Jdbc.query(c, "SELECT level_type FROM SEC_ORG_LEVEL WHERE tenant_id=? ORDER BY level_no",
                rs -> rs.getString(1), tenant));
    }

    private long events(String type) {
        return tx.inTransaction(c -> Jdbc.one(c, "SELECT COUNT(*) FROM AUDIT_EVENT WHERE tenant_id=? AND event_type=?",
                rs -> rs.getLong(1), T, type).orElse(0L));
    }
}
