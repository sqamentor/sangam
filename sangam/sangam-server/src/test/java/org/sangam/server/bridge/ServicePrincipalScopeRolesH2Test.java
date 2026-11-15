// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;
import org.sangam.core.security.SangamSecurityException;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.persistence.SangamSchema;
import org.sangam.rest.config.SpringTxSupport;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.IdentityAdminPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.Permissions;
import org.sangam.rest.support.RestProperties;
import org.sangam.rest.support.TxSupport;
import org.sangam.security.SangamSecurity;
import org.sangam.security.admin.ApiTokenService;
import org.sangam.security.admin.RoleAdminService;
import org.sangam.security.dialect.SecurityDialect;
import org.sangam.security.policy.Permission;
import org.sangam.security.policy.ResourceRegistry;
import org.sangam.server.config.SecurityWiring;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * Decisions 358 and 368 on a real H2 security schema: the platform groups used as token scopes are role rows of each
 * tenant, created when the tenant is set up (the list is the server's group permission map); a token's scopes must be
 * roles its owner holds ({@code 409 TOKEN_SCOPE_NOT_HELD}, by name); a service principal gets each requested scope
 * through {@code RoleAdminService#bind} first, which refuses an undefined role ({@code 409 UNKNOWN_ROLE}) and a role the
 * caller may not grant ({@code 403}), both naming the scope; and the role-definition path defines the role-less scope
 * of the e2e set-up.
 */
class ServicePrincipalScopeRolesH2Test {

    static final String T = "t-d368";
    static final Instant IN_A_MONTH = Instant.now().plusSeconds(30L * 24 * 3600);

    private SangamSecurity security;
    private Transactions tx;
    private SecurityIdentityAdminBridge bridge;
    /** The development administrator of the bootstrap policy (every wildcard permission). */
    private final RequestContext admin = new RequestContext(T, "d368-admin", Set.of("sangam-admin"), null, null, null,
            null, null, "DEV_TOKEN");
    /** May do all the administrator does except grant a role (deny CHANGE_GRANT on ROLE). */
    private final RequestContext maker = new RequestContext(T, "d368-maker", Set.of("sp-maker"), null, null, null,
            null, null, "DEV_TOKEN");

    @BeforeEach
    void schema() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:d368" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        SangamSchema.apply(ds);
        try (Connection c = ds.getConnection()) {
            security = new SangamSecurity(SecurityDialect.detect(c), null);
        }
        // as SecurityWiring.sangamSecurity does
        security.roles().usePlatformRoles(SecurityWiring.platformRoles());
        tx = Transactions.of(ds);
        SecurityBootstrap.run(security, tx, List.of(T), List.of("sangam-admin"), false);
        SecurityIdentity system = SecurityIdentity.system(T, "d368-setup");
        tx.inTransaction(c -> {
            security.roles().defineRole(c, system, "sp-maker", "Service principal maker without grant right", List.of(
                    new RoleAdminService.RoleRule(ResourceRegistry.ANY_TYPE, ResourceRegistry.ANY_ID,
                            SecurityBootstrap.wildcardPermissions(), false),
                    new RoleAdminService.RoleRule(ResourceRegistry.ROLE, ResourceRegistry.ANY_ID,
                            EnumSet.of(Permission.CHANGE_GRANT), true)));
            return null;
        });
        bridge = new SecurityIdentityAdminBridge(security,
                new SpringTxSupport(ds, new DataSourceTransactionManager(ds), TxSupport.detectDialect(ds)), null,
                new RestProperties());
    }

    private List<String> roleRows() {
        return tx.inTransaction(c -> Jdbc.query(c, "SELECT role_id FROM SEC_ROLE WHERE tenant_id=? ORDER BY role_id",
                rs -> rs.getString(1), T));
    }

    private long count(String sql, Object... args) {
        return tx.inTransaction(c -> Jdbc.one(c, sql, rs -> rs.getLong(1), args).orElse(0L));
    }

    private IdentityAdminPort.ServicePrincipal principal(String name, String... scopes) {
        return new IdentityAdminPort.ServicePrincipal(null, name, List.of(scopes), List.of("d368-topic"), IN_A_MONTH,
                null, null);
    }

    @Test
    void aNewTenantHasARoleRowForEveryPlatformGroupOfTheCode() {
        List<String> rows = roleRows();
        assertTrue(rows.containsAll(Permissions.DEFAULT_GROUP_PERMISSIONS.keySet()),
                "every group the server grants permissions to is a role of the tenant: " + rows);
        assertTrue(rows.containsAll(List.of("sangam-worker", "sangam-citizen", "sangam-dpo")), rows.toString());
        assertFalse(rows.contains(Permissions.GROUP_ADMIN), "sangam-admin (every permission) is never a token scope");
        assertEquals(0, count("SELECT COUNT(*) FROM AUTH_EFFECTIVE WHERE tenant_id=? AND principal_id='role:sangam-worker'", T),
                "a platform role row grants nothing itself; what the group may do stays in the code");
    }

    @Test
    void thePlatformRolesNeverOverwriteARoleAnAdministratorDefined() {
        SecurityIdentity system = SecurityIdentity.system(T, "d368-setup");
        tx.inTransaction(c -> {
            security.roles().defineRole(c, system, "sangam-worker", "Workers of the department", List.of(
                    new RoleAdminService.RoleRule(ResourceRegistry.ANY_TYPE, ResourceRegistry.ANY_ID,
                            EnumSet.of(Permission.READ), false)));
            return null;
        });
        List<String> created = tx.inTransaction(c -> security.roles().ensurePlatformRoles(c, system, T));
        assertEquals(List.of(), created, "idempotent: nothing is created twice");
        assertEquals("Workers of the department", tx.inTransaction(c -> Jdbc.one(c,
                "SELECT name FROM SEC_ROLE WHERE tenant_id=? AND role_id='sangam-worker'", rs -> rs.getString(1), T)
                .orElseThrow()));
        // the development bootstrap of an existing tenant ensures the rows again, without overwriting
        tx.inTransaction(c -> Jdbc.update(c, "DELETE FROM SEC_ROLE WHERE tenant_id=? AND role_id='sangam-dpo'", T));
        SecurityBootstrap.run(security, tx, List.of(T), List.of("sangam-admin"), false);
        assertTrue(roleRows().contains("sangam-dpo"), "a missing platform role row is created for an existing tenant");
    }

    @Test
    void aServicePrincipalGetsItsScopesAsBoundRolesAndItsTokenCarriesThem() {
        IdentityAdminPort.ServicePrincipalSecret s = bridge.createServicePrincipal(admin,
                principal("d368-worker", "sangam-worker", "sangam-citizen"));
        String owner = "svc-d368-worker";
        Set<String> held = tx.inTransaction(c -> security.principals().heldRoles(c, T, owner));
        assertTrue(held.containsAll(Set.of("sangam-worker", "sangam-citizen")), "bound through RoleAdminService.bind: " + held);
        assertEquals(2, count("SELECT COUNT(*) FROM SEC_ROLE_BINDING WHERE tenant_id=? AND member_id=?", T, "user:" + owner));
        SecurityIdentity who = tx.inTransaction(c -> security.tokens().authenticate(c, s.secret(), null)).orElseThrow();
        assertEquals(Set.of("sangam-worker", "sangam-citizen"), who.roles());

        // rotation issues the same scopes again: the owner still holds them
        IdentityAdminPort.ServicePrincipalSecret next = bridge.rotateServicePrincipal(admin, s.principal().id());
        assertTrue(tx.inTransaction(c -> security.tokens().authenticate(c, next.secret(), null)).isPresent());
        // a second principal of the same name binds nothing twice
        bridge.createServicePrincipal(admin, principal("d368-worker", "sangam-worker"));
        assertEquals(2, count("SELECT COUNT(*) FROM SEC_ROLE_BINDING WHERE tenant_id=? AND member_id=?", T, "user:" + owner));
    }

    @Test
    void aScopeThatIsNoDefinedRoleIsRefusedByNameAndNothingIsCreated() {
        ApiProblem e = assertThrows(ApiProblem.class,
                () -> bridge.createServicePrincipal(admin, principal("d368-ghost", "sangam-worker", "no-such-role")));
        assertEquals(409, e.status());
        assertEquals("UNKNOWN_ROLE", e.code());
        assertTrue(e.getMessage().contains("no-such-role"), e.getMessage());
        assertEquals(0, count("SELECT COUNT(*) FROM SEC_API_TOKEN WHERE tenant_id=?", T), "no token");
        assertEquals(0, count("SELECT COUNT(*) FROM SEC_ROLE_BINDING WHERE tenant_id=? AND member_id=?", T,
                "user:svc-d368-ghost"), "the creation rolled back, the binding of sangam-worker with it");
        assertEquals(0, count("SELECT COUNT(*) FROM SEC_PERSON WHERE tenant_id=? AND person_id=?", T, "svc-d368-ghost"));
    }

    @Test
    void aScopeTheCallerMayNotGrantIsRefusedByName() {
        ApiProblem e = assertThrows(ApiProblem.class,
                () -> bridge.createServicePrincipal(maker, principal("d368-dpo", "sangam-dpo")));
        assertEquals(403, e.status());
        assertEquals("ACTION_NOT_PERMITTED", e.code());
        assertTrue(e.getMessage().contains("sangam-dpo"), e.getMessage());
        assertEquals(0, count("SELECT COUNT(*) FROM SEC_API_TOKEN WHERE tenant_id=?", T));
    }

    @Test
    void aTokenScopeMustBeARoleTheOwnerHolds() {
        SecurityIdentity actor = SecurityIdentities.of(admin);
        tx.inTransaction(c -> {
            security.persons().createUser(c, actor, "d368-p1", "P One", null, null);
            return null;
        });
        SangamSecurityException.AdminConflict e = assertThrows(SangamSecurityException.AdminConflict.class,
                () -> tx.inTransaction(c -> security.tokens().issue(c, actor, "d368-p1", "personal",
                        Set.of("sangam-dpo", "authenticated"), IN_A_MONTH)));
        assertEquals(ApiTokenService.SCOPE_NOT_HELD, e.code());
        assertTrue(e.getMessage().contains("sangam-dpo") && !e.getMessage().contains("authenticated"), e.getMessage());
        assertEquals(0, count("SELECT COUNT(*) FROM SEC_API_TOKEN WHERE tenant_id=?", T));

        tx.inTransaction(c -> security.roles().bind(c, actor, "sangam-dpo", "user:d368-p1", null, null));
        assertTrue(tx.inTransaction(c -> security.tokens().issue(c, actor, "d368-p1", "personal", Set.of("sangam-dpo"),
                IN_A_MONTH)).plaintext().startsWith(ApiTokenService.PREFIX), "a bound role is a valid scope");
        // one's own token may also use a role of one's own session
        SecurityIdentity self = new SecurityIdentity(T, "d368-p1", "d368-p1", Set.of(), Set.of("session-role"), Set.of(),
                Set.of(), "OIDC", null, null, false, java.util.Map.of());
        assertTrue(tx.inTransaction(c -> security.tokens().issue(c, self, "d368-p1", "mine", Set.of("session-role"),
                IN_A_MONTH)).tokenId() != null);
    }

    @Test
    void theRoleDefinitionPathDefinesTheRolelessScopeOfTheE2eSetUp() {
        IdentityAdminPort.RoleDefinition r = bridge.defineRole(admin, "e2e-no-group",
                new IdentityAdminPort.RoleDefinition(null, "e2e-no-group", List.of()));
        assertEquals("e2e-no-group", r.id());
        IdentityAdminPort.ServicePrincipalSecret s = bridge.createServicePrincipal(admin,
                principal("d368-roleless", "e2e-no-group"));
        SecurityIdentity who = tx.inTransaction(c -> security.tokens().authenticate(c, s.secret(), null)).orElseThrow();
        assertEquals(Set.of("e2e-no-group"), who.roles());

        assertEquals(400, assertThrows(ApiProblem.class, () -> bridge.defineRole(admin, "bad id",
                new IdentityAdminPort.RoleDefinition(null, "x", List.of()))).status());
        assertEquals(400, assertThrows(ApiProblem.class, () -> bridge.defineRole(admin, "r1",
                new IdentityAdminPort.RoleDefinition(null, "x", List.of(new IdentityAdminPort.RoleRule("*", null,
                        List.of("NOT_A_PERMISSION"), null))))).status());
        assertEquals(403, assertThrows(ApiProblem.class, () -> bridge.defineRole(maker, "r2",
                new IdentityAdminPort.RoleDefinition(null, "x", List.of()))).status(),
                "defining a role needs CHANGE_GRANT on ROLE");
    }
}
