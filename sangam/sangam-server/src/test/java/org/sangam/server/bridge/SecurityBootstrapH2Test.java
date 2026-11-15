// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.Transactions;
import org.sangam.persistence.SangamSchema;
import org.sangam.security.SangamSecurity;
import org.sangam.security.dialect.SecurityDialect;
import org.sangam.security.policy.Permission;

/**
 * The development bootstrap on a real H2 security schema. Regression: the bootstrap asked for every permission on
 * {@code *}, which includes the explicit-only permissions of decision 141; the policy was refused, the transaction
 * rolled back with the tenant in it, and every signed-in call of the development server answered 401
 * {@code TENANT_UNAVAILABLE}.
 */
class SecurityBootstrapH2Test {

    private SangamSecurity security;
    private Transactions tx;

    @BeforeEach
    void schema() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:sb" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        SangamSchema.apply(ds);
        try (Connection c = ds.getConnection()) {
            security = new SangamSecurity(SecurityDialect.detect(c), null);
        }
        tx = Transactions.of(ds);
    }

    @Test
    void theBootstrapCreatesTheTenantAndItsAdministratorPolicyAndIsIdempotent() {
        SecurityBootstrap.run(security, tx, List.of("dev", "second"), List.of("sangam-admin"), false);
        for (String tenant : List.of("dev", "second")) {
            assertTrue(tenantExists(tenant), "tenant " + tenant);
            assertTrue(policyExists(tenant), "administrator policy of " + tenant);
        }
        SecurityBootstrap.run(security, tx, List.of("dev"), List.of("sangam-admin"), false);
        assertTrue(policyExists("dev"));
    }

    private boolean tenantExists(String tenant) {
        Boolean found = tx.inTransaction(c -> security.tenants().find(c, tenant).isPresent());
        return found;
    }

    private boolean policyExists(String tenant) {
        Boolean found = tx.inTransaction(c -> security.policies().find(c, tenant, SecurityBootstrap.POLICY_ID).isPresent());
        return found;
    }

    /** Decision 141: the explicit-only permissions are never part of a wildcard grant; everything else is. */
    @Test
    void theWildcardGrantLeavesOutExactlyTheExplicitOnlyPermissions() {
        EnumSet<Permission> missing = EnumSet.allOf(Permission.class);
        missing.removeAll(SecurityBootstrap.wildcardPermissions());
        assertFalse(missing.isEmpty());
        assertTrue(missing.stream().allMatch(Permission::explicitOnly), missing.toString());
        assertTrue(SecurityBootstrap.wildcardPermissions().stream().noneMatch(Permission::explicitOnly));
    }
}
