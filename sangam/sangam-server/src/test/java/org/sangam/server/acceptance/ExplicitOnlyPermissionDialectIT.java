// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.persistence.SangamSchema;
import org.sangam.security.SangamSecurity;
import org.sangam.security.billing.UnverifiedPaymentPermissions;
import org.sangam.security.dialect.SecurityDialect;
import org.sangam.security.policy.ModuleResourceTypes;
import org.sangam.security.policy.Permission;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Decision 141 on the lane's real database and driver (PostgreSQL, Oracle, SQL Server, MySQL through
 * {@code -Dsangam.db}): the explicit-only rule of ACCEPT_UNVERIFIED and RESOLVE_UNVERIFIED in the object check and in its
 * set form, with the bit at ordinal 24 and 25 in {@code AUTH_EFFECTIVE.perms} (a {@code {long}} column: NUMBER(19) on
 * Oracle, BIGINT on SQL Server), the {@code resource_type}/{@code resource_id} columns read back for the wildcard test,
 * tenant isolation and the AUTH_DENIED audit of a refusal. Product schema applied by its changelog (idempotent); rows of
 * two random tenants, removed at the end. Plain JDBC on {@link AcceptanceDatabase}; no server is started.
 *
 * <p>Status: written, NOT_RUN on every lane (Oracle and SQL Server included).
 */
class ExplicitOnlyPermissionDialectIT {

    @Test
    void explicitOnlyPermissionsOnTheLaneDatabase() throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource(AcceptanceDatabase.jdbcUrl(), AcceptanceDatabase.username(),
                AcceptanceDatabase.password());
        SangamSchema.apply(ds);
        String t = "d141-" + UUID.randomUUID().toString().substring(0, 8);
        String u = "d141-" + UUID.randomUUID().toString().substring(0, 8);
        String upi1 = UnverifiedPaymentPermissions.resourceId("upi", "off-1");
        String upi2 = UnverifiedPaymentPermissions.resourceId("upi", "off-2");
        String billing = ModuleResourceTypes.BILLING;
        long accept = Permission.ACCEPT_UNVERIFIED.bit();
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(true);
            SangamSecurity security = new SangamSecurity(SecurityDialect.detect(c), null);
            try {
                grant(c, t, "user:al", billing, upi1, accept, 0);
                grant(c, t, "user:bo", billing, "*", accept | Permission.READ.bit(), 0);
                grant(c, t, "user:cy", "*", "*", accept, 0);
                grant(c, t, "user:dd", billing, upi1, accept, 0);
                grant(c, t, "user:dd", billing, "*", accept, 1);
                grant(c, u, "user:al", billing, upi2, accept, 0);

                var compiler = security.compiler();
                assertTrue(compiler.allow(c, person(t, "al"), billing, Permission.ACCEPT_UNVERIFIED, upi1));
                assertFalse(compiler.allow(c, person(t, "al"), billing, Permission.ACCEPT_UNVERIFIED, upi2), "office");
                assertFalse(compiler.allow(c, person(t, "al"), billing, Permission.RESOLVE_UNVERIFIED, upi1), "action");
                assertFalse(compiler.allow(c, person(t, "bo"), billing, Permission.ACCEPT_UNVERIFIED, upi1), "'*' id");
                assertTrue(compiler.allow(c, person(t, "bo"), billing, Permission.READ, upi1), "ordinary bit on '*'");
                assertFalse(compiler.allow(c, person(t, "cy"), billing, Permission.ACCEPT_UNVERIFIED, upi1), "'*' type");
                assertFalse(compiler.allow(c, person(t, "dd"), billing, Permission.ACCEPT_UNVERIFIED, upi1), "deny wins");
                assertFalse(compiler.allow(c, person(t, "al"), billing, Permission.ACCEPT_UNVERIFIED, upi2), "tenant");
                assertTrue(compiler.allow(c, person(u, "al"), billing, Permission.ACCEPT_UNVERIFIED, upi2));
                for (String p : List.of("al", "bo", "cy", "dd")) {
                    Set<String> ids = compiler.allowedIds(c, person(t, p), billing, Permission.ACCEPT_UNVERIFIED,
                            List.of(upi1, upi2));
                    assertEquals(compiler.allow(c, person(t, p), billing, Permission.ACCEPT_UNVERIFIED, upi1),
                            ids.contains(upi1), p);
                }

                UnverifiedPaymentPermissions payments = new UnverifiedPaymentPermissions(security.authorization());
                assertTrue(payments.permits(c, person(t, "al"), Permission.ACCEPT_UNVERIFIED, t, "off-1", "upi", "c-7"));
                assertFalse(payments.permits(c, SecurityIdentity.system(t, "job"), Permission.ACCEPT_UNVERIFIED, t,
                        "off-1", "upi", null));
                assertFalse(payments.permits(c, person(t, "bo"), Permission.ACCEPT_UNVERIFIED, t, "off-1", "upi", null));
                long denials = Jdbc.one(c, "SELECT COUNT(*) FROM AUDIT_EVENT WHERE tenant_id=? AND event_type='AUTH_DENIED'",
                        rs -> rs.getLong(1), t).orElse(0L);
                assertTrue(denials > 0, "the refusal is audited on this database");
            } finally {
                for (String tenant : List.of(t, u)) {
                    Jdbc.update(c, "DELETE FROM AUTH_EFFECTIVE WHERE tenant_id=?", tenant);
                }
            }
        }
    }

    private static void grant(Connection c, String tenant, String principal, String type, String id, long perms, int deny) {
        Jdbc.update(c, "INSERT INTO AUTH_EFFECTIVE(tenant_id, principal_id, resource_type, resource_id, deny_flag, perms,"
                + " condition_id, cond_key, policy_version) VALUES (?, ?, ?, ?, ?, ?, NULL, '-', 0)",
                tenant, principal, type, id, deny, perms);
    }

    private static SecurityIdentity person(String tenant, String person) {
        return new SecurityIdentity(tenant, person, person, Set.of(), Set.of(), Set.of(), Set.of(), "OIDC", null, null,
                false, Map.of());
    }
}
