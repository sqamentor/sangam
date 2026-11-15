// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.persistence.SangamSchema;
import org.sangam.security.SangamSecurity;
import org.sangam.security.admin.RoleAdminService;
import org.sangam.security.dialect.SecurityDialect;
import org.sangam.security.policy.Permission;
import org.sangam.security.policy.ResourceRegistry;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;

/**
 * Decision 370 (1): tenants that existed before the platform role rows (decision 368) get them at server start. The
 * step fills in what is missing in every tenant, from the same code-derived list as a new tenant, never overwrites a
 * role an administrator defined, is audited per tenant, is idempotent, and refuses when no list is bound.
 */
class PlatformRoleRowsAtStartH2Test {

    private SangamSecurity security;
    private Transactions tx;

    @BeforeEach
    void schema() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:d370" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        SangamSchema.apply(ds);
        try (Connection c = ds.getConnection()) {
            security = new SangamSecurity(SecurityDialect.detect(c), null);
        }
        tx = Transactions.of(ds);
        // two tenants created before decision 368: no platform role rows; t1 has its own sangam-worker role
        tx.inTransaction(c -> {
            for (String t : List.of("t1", "t2")) {
                Jdbc.update(c, "INSERT INTO SEC_TENANT(tenant_id, name_en, status, config, quotas, four_eyes, created_at)"
                        + " VALUES (?,?,?,?,?,?,?)", t, t, "ACTIVE", "{}", "{}", 0, Instant.now());
            }
            security.roles().defineRole(c, SecurityIdentity.system("t1", "d370-admin"), "sangam-worker",
                    "Workers of the department", List.of(new RoleAdminService.RoleRule(ResourceRegistry.ANY_TYPE,
                            ResourceRegistry.ANY_ID, EnumSet.of(Permission.READ), false)));
            return null;
        });
    }

    private List<String> rows(String tenant) {
        return tx.inTransaction(c -> Jdbc.query(c, "SELECT role_id FROM SEC_ROLE WHERE tenant_id=? ORDER BY role_id",
                rs -> rs.getString(1), tenant));
    }

    private long audits(String tenant) {
        return tx.inTransaction(c -> Jdbc.one(c, "SELECT COUNT(*) FROM AUDIT_EVENT e JOIN AUDIT_PAYLOAD p"
                + " ON p.event_id=e.event_id WHERE e.tenant_id=? AND e.event_type='GRANT_CHANGED'"
                + " AND p.payload LIKE '%platform_roles_created%'", rs -> rs.getLong(1), tenant).orElse(0L));
    }

    @Test
    void everyExistingTenantGetsTheMissingPlatformRoleRowsOnceAndADefinedRoleStays() {
        security.roles().usePlatformRoles(SecurityWiring.platformRoles());
        Map<String, List<String>> created = SecurityWiring.fillPlatformRoleRows(security, tx);

        List<String> all = new ArrayList<>(SecurityWiring.platformRoles());
        all.sort(null);
        List<String> withoutWorker = new ArrayList<>(all);
        withoutWorker.remove("sangam-worker");
        assertEquals(Map.of("t1", withoutWorker, "t2", all), created, "what each tenant lacked, nothing else");
        assertTrue(rows("t1").containsAll(all) && rows("t2").containsAll(all));
        assertEquals("Workers of the department", tx.inTransaction(c -> Jdbc.one(c,
                "SELECT name FROM SEC_ROLE WHERE tenant_id='t1' AND role_id='sangam-worker'", rs -> rs.getString(1))
                .orElseThrow()), "a role an administrator defined is never overwritten");
        assertEquals(1, audits("t1"));
        assertEquals(1, audits("t2"));

        // the next start finds nothing to do and audits nothing
        assertEquals(Map.of(), SecurityWiring.fillPlatformRoleRows(security, tx));
        assertEquals(1, audits("t1"));
        assertEquals(1, audits("t2"));
    }

    @Test
    void withoutABoundListTheStepRefusesAndWritesNothing() {
        assertThrows(IllegalStateException.class, () -> SecurityWiring.fillPlatformRoleRows(security, tx));
        assertEquals(List.of(), rows("t2"));
        assertEquals(0, audits("t2"));
    }

    @Test
    void withoutABoundListNoTenantIsCreated() {
        SecurityIdentity platform = SecurityIdentity.system("t3", "d370-platform").withGroupsAndRoles(java.util.Set.of(),
                java.util.Set.of("system", org.sangam.security.admin.TenantAdminService.PLATFORM_ADMIN_ROLE));
        assertThrows(IllegalStateException.class, () -> tx.inTransaction(c -> security.tenants().createTenant(c,
                platform, "t3", "T3", null, null, null, Map.of())));
        assertTrue(tx.inTransaction(c -> security.tenants().find(c, "t3")).isEmpty(), "the creation rolled back");
    }

    @Test
    void theServerRunsTheStepAtStart() throws Exception {
        Method m = SecurityWiring.class.getMethod("sangamPlatformRoleRows", SangamSecurity.class, Transactions.class);
        assertTrue(m.isAnnotationPresent(Bean.class), "an unconditional bean of the security wiring");
        assertEquals(ApplicationRunner.class, m.getReturnType());
        security.roles().usePlatformRoles(SecurityWiring.platformRoles());
        ApplicationRunner runner = (ApplicationRunner) m.invoke(new SecurityWiring(), security, tx);
        runner.run(null);
        assertTrue(rows("t2").containsAll(SecurityWiring.platformRoles()));
    }
}
