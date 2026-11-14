// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.persistence.SangamSchema;
import org.sangam.security.SangamSecurity;
import org.sangam.security.dialect.SecurityDialect;
import org.sangam.security.policy.MembershipIndex;

/**
 * SL-175 (regression): sangam-security's resolver mirrors a login's IdP groups and roles into {@code AUTH_MEMBERSHIP}
 * (source {@code idp}), but never an API key's scopes. An API-key identity of person {@code p1} writes no IdP
 * membership and leaves p1's real IdP memberships (from an earlier OIDC login) in place. Written for the test phase.
 */
class PrincipalResolverApiKeyMembershipH2Test {

    private static final String T = "T-SL175";

    private SangamSecurity security;
    private Transactions tx;

    @BeforeEach
    void schema() throws Exception {
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:sl175" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        SangamSchema.apply(h2);
        try (Connection c = h2.getConnection()) {
            security = new SangamSecurity(SecurityDialect.detect(c), null);
        }
        tx = Transactions.of(h2);
        tx.inTransaction(c -> {
            Instant now = Instant.now();
            Jdbc.update(c, "INSERT INTO SEC_TENANT(tenant_id, name_en, status, config, quotas, four_eyes, created_at)"
                    + " VALUES (?,?,?,?,?,?,?)", T, "t", "ACTIVE", "{}", "{}", 0, now);
            Jdbc.update(c, "INSERT INTO SEC_PERSON(tenant_id, person_id, display_name, external_subject, status, created_at)"
                    + " VALUES (?,?,?,?,?,?)", T, "p1", "P One", "sub-1", "ACTIVE", now);
            return null;
        });
    }

    private List<String> idpMemberships() {
        return memberships(MembershipIndex.SOURCE_IDP);
    }

    private List<String> memberships(String source) {
        return tx.inTransaction(c -> Jdbc.query(c, "SELECT principal_id FROM AUTH_MEMBERSHIP WHERE tenant_id=? AND"
                + " member_id=? AND source_id=? ORDER BY principal_id", rs -> rs.getString(1), T, "user:p1", source));
    }

    @Test
    void aDevelopmentIdentityWritesSyntheticMembershipsOnly() {
        // decision 152: DEV_TOKEN / DEV_STATIC groups are synthetic provenance, never presented as IdP claims
        resolve("OIDC", "sub-1", Set.of("clerks"), Set.of("approver"));
        List<String> verified = idpMemberships();
        resolve("DEV_TOKEN", "sub-1", Set.of("sangam-user"), Set.of());
        assertEquals(verified, idpMemberships(), "the verified IdP memberships are neither replaced nor extended");
        assertEquals(List.of("group:sangam-user"), memberships(MembershipIndex.SOURCE_DEV_STATIC));
        resolve("DEV_STATIC", "sub-1", Set.of(), Set.of("tester"));
        assertEquals(List.of("role:tester"), memberships(MembershipIndex.SOURCE_DEV_STATIC),
                "the synthetic set follows the development configuration exactly");
        assertEquals(verified, idpMemberships());
    }

    private void resolve(String authMethod, String personOrSubject, Set<String> groups, Set<String> roles) {
        SecurityIdentity raw = new SecurityIdentity(T, personOrSubject, personOrSubject, groups, roles, Set.of(),
                Set.of(), authMethod, "10.0.0.1", null, false, Map.of());
        tx.inTransaction(c -> security.principals().resolve(c, raw, false, null, null));
    }

    @Test
    void anApiKeyNeverCreatesAnIdpMembership() {
        resolve("API_KEY", "p1", Set.of("payments-scope"), Set.of("scope-role"));
        assertEquals(List.of(), idpMemberships(), "an API key's scopes are not IdP claims");
    }

    @Test
    void anApiKeyLeavesTheLoginsIdpMembershipsInPlace() {
        resolve("OIDC", "sub-1", Set.of("clerks"), Set.of("approver"));
        List<String> afterLogin = idpMemberships();
        assertEquals(List.of("group:clerks", "role:approver"), afterLogin, "a login mirrors its IdP claims");

        resolve("API_KEY", "p1", Set.of("payments-scope"), Set.of());
        assertEquals(afterLogin, idpMemberships(), "the API key neither adds nor removes IdP memberships");
        assertTrue(idpMemberships().stream().noneMatch(p -> p.contains("payments-scope")));
    }
}
