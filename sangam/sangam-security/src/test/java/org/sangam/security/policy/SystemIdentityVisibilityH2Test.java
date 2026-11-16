// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
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
import org.sangam.core.layer.SqlPredicate;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.security.audit.SecurityAuditService;
import org.sangam.security.dialect.SecurityDialect;

/**
 * The list predicate of the system identity decides as its object check does (H2, PostgreSQL mode). The system
 * principal holds no grant rows; {@link AuthorizationService#allow} allows it every non-explicit-only action in its own
 * tenant, but {@link AuthorizationService#visible} compiled the grant predicate for it and so showed it nothing. A
 * connector running as system (the registry payee directory, {@code system:payments}) then could not read back the
 * party it had just created: IfmsPacksIT on PostgreSQL, {@code PAYEE_DIRECTORY_ERROR party not found}.
 */
class SystemIdentityVisibilityH2Test {

    static final String T = "t";
    static final String U = "u";
    static final String PARTY = ModuleResourceTypes.REGISTRY_PARTY;

    private Connection c;
    private AuthorizationService authz;

    @BeforeEach
    void db() throws Exception {
        c = DriverManager.getConnection("jdbc:h2:mem:sysvis" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=TRUE", "sa", "");
        Schema.create(c, Dialect.POSTGRESQL);   // AUTH_EFFECTIVE, AUTH_POLICY_VERSION, AUDIT_EVENT, AUDIT_PAYLOAD, ROOT_SEQ
        Jdbc.update(c, "CREATE TABLE AUTH_CONDITION (tenant_id VARCHAR(64), condition_id VARCHAR(64),"
                + " resource_type VARCHAR(64), expression TEXT)");
        // the columns the registry-party descriptor maps (ModuleResourceTypes.registry)
        Jdbc.update(c, "CREATE TABLE REG_PARTY (tenant_id VARCHAR(64), id VARCHAR(64), party_kind VARCHAR(32),"
                + " status VARCHAR(32), source VARCHAR(32), created_by VARCHAR(64))");
        for (String tenant : List.of(T, U)) {
            Jdbc.update(c, "INSERT INTO AUTH_POLICY_VERSION(tenant_id, policy_version) VALUES (?, 1)", tenant);
        }
        party(T, "p1");
        party(T, "p2");
        party(U, "q1");
        authz = new AuthorizationService(new PolicyCompiler(SecurityDialect.H2, new ResourceRegistry(), Duration.ZERO),
                new SecurityAuditService(SecurityDialect.H2));
    }

    @AfterEach
    void close() throws Exception {
        c.close();
    }

    private void party(String tenant, String id) {
        Jdbc.update(c, "INSERT INTO REG_PARTY(tenant_id, id, party_kind, status, source, created_by)"
                + " VALUES (?, ?, 'PERSON', 'ACTIVE', 'payments', 'system:payments')", tenant, id);
    }

    private List<String> listed(SecurityIdentity who, Permission p) {
        SqlPredicate vis = authz.visible(c, who, PARTY, p.name(), "p");
        List<Object> params = new ArrayList<>(vis.params());
        return Jdbc.query(c, "SELECT p.id FROM REG_PARTY p WHERE " + vis.sql() + " ORDER BY p.id", rs -> rs.getString(1),
                params.toArray());
    }

    @Test
    void theSystemIdentitySeesTheRowsOfItsOwnTenant() {
        SecurityIdentity system = SecurityIdentity.system(T, "system:payments");
        assertEquals(List.of("p1", "p2"), listed(system, Permission.READ), "its own tenant's parties");
        assertEquals(List.of("q1"), listed(SecurityIdentity.system(U, "system:payments"), Permission.READ));
    }

    @Test
    void theListPathDecidesAsTheObjectCheck() {
        for (String tenant : List.of(T, U)) {
            SecurityIdentity system = SecurityIdentity.system(tenant, "job");
            for (Permission p : List.of(Permission.READ, Permission.MODIFY, Permission.ACCEPT_UNVERIFIED)) {
                List<String> rows = listed(system, p);
                for (String id : List.of("p1", "p2", "q1")) {
                    assertEquals(authz.allow(c, system, PARTY, p, id, Map.of()) && exists(tenant, id), rows.contains(id),
                            tenant + " " + p + " " + id);
                }
            }
        }
    }

    @Test
    void anExplicitOnlyPermissionListsNothingForTheSystemIdentity() {
        assertEquals(List.of(), listed(SecurityIdentity.system(T, "job"), Permission.ACCEPT_UNVERIFIED), "decision 141");
    }

    @Test
    void aPersonWithoutGrantsStillSeesNothing() {
        SecurityIdentity person = new SecurityIdentity(T, "al", "al", Set.of(), Set.of(), Set.of(), Set.of(), "OIDC", null,
                null, false, Map.of());
        assertEquals(List.of(), listed(person, Permission.READ), "the grant predicate is unchanged for everyone else");
        Jdbc.update(c, "INSERT INTO AUTH_EFFECTIVE(tenant_id, principal_id, resource_type, resource_id, deny_flag, perms,"
                + " condition_id, cond_key, policy_version) VALUES (?, 'user:al', ?, 'p1', 0, ?, NULL, '-', 0)",
                T, PARTY, Permission.READ.bit());
        assertEquals(List.of("p1"), listed(person, Permission.READ));
    }

    @Test
    void theTenantScopeIsTheSystemIdentitysOnly() {
        SecurityIdentity person = new SecurityIdentity(T, "al", "al", Set.of(), Set.of(), Set.of(), Set.of(), "OIDC", null,
                null, false, Map.of());
        assertThrows(IllegalArgumentException.class,
                () -> authz.compiler().tenantScope(person, PARTY, Permission.READ, "p"));
        assertThrows(IllegalArgumentException.class, () -> authz.compiler().tenantScope(SecurityIdentity.system(T, "job"),
                ModuleResourceTypes.BILLING, Permission.READ, "p"), "a virtual type has no list path");
        assertTrue(authz.compiler().tenantScope(SecurityIdentity.system(T, "job"), PARTY, Permission.READ, "p").sql()
                .startsWith("p.tenant_id = ?"));
    }

    private boolean exists(String tenant, String id) {
        return Jdbc.one(c, "SELECT COUNT(*) FROM REG_PARTY WHERE tenant_id=? AND id=?", rs -> rs.getLong(1), tenant, id)
                .orElse(0L) > 0;
    }
}
