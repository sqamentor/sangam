// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import org.sangam.core.security.SecurityIdentity;
import org.sangam.persistence.SangamSchema;
import org.sangam.rest.config.SpringTxSupport;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.IdentityAdminPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.RestProperties;
import org.sangam.rest.support.TxSupport;
import org.sangam.security.SangamSecurity;
import org.sangam.security.dialect.SecurityDialect;
import org.sangam.server.config.SecurityWiring;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * Round 6 item 4 on a real H2 security schema: an administrator creates, updates and disables a LOCAL user (the disable
 * revokes the user's API tokens in the same transaction), and creates and renames a group; a rename keeps the group's
 * external reference and memberships; the core's refusals keep their names.
 */
class SecurityIdentityAdminUsersGroupsH2Test {

    static final String T = "t-r6";

    private SangamSecurity security;
    private Transactions tx;
    private SecurityIdentityAdminBridge bridge;
    private final RequestContext admin = new RequestContext(T, "r6-admin", Set.of("sangam-admin"), null, null, null,
            null, null, "DEV_TOKEN");

    @BeforeEach
    void schema() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:r6id" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        SangamSchema.apply(ds);
        try (Connection c = ds.getConnection()) {
            security = new SangamSecurity(SecurityDialect.detect(c), null);
        }
        security.roles().usePlatformRoles(SecurityWiring.platformRoles());
        tx = Transactions.of(ds);
        SecurityBootstrap.run(security, tx, List.of(T), List.of("sangam-admin"), false);
        bridge = new SecurityIdentityAdminBridge(security,
                new SpringTxSupport(ds, new DataSourceTransactionManager(ds), TxSupport.detectDialect(ds)), null,
                new RestProperties());
    }

    private static IdentityAdminPort.UserRequest user(String id, String name, String email) {
        return new IdentityAdminPort.UserRequest(null, id, name, email, true, "LOCAL");
    }

    @Test
    void aUserIsCreatedUpdatedAndDisabledWithItsTokens() {
        IdentityAdminPort.User asha = bridge.createUser(admin, user("asha", "Asha", "asha@example.test"));
        assertEquals("asha", asha.id());
        assertEquals("Asha", asha.displayName());
        assertTrue(asha.active());

        IdentityAdminPort.User renamed = bridge.updateUser(admin, "asha",
                new IdentityAdminPort.UserRequest(null, null, "Asha Rao", "rao@example.test", null, null));
        assertEquals("Asha Rao", renamed.displayName());
        assertEquals("rao@example.test", renamed.email());

        SecurityIdentity system = SecurityIdentity.system(T, "r6-setup");
        tx.inTransaction(c -> security.tokens().issue(c, system, "asha", "asha-cli", Set.of(),
                Instant.now().plusSeconds(30L * 24 * 3600)));
        IdentityAdminPort.User off = bridge.disableUser(admin, "asha", "Left the department");
        assertFalse(off.active());
        long live = tx.inTransaction(c -> Jdbc.one(c, "SELECT COUNT(*) FROM SEC_API_TOKEN WHERE tenant_id=? AND"
                + " owner_person_id=? AND revoked_at IS NULL", rs -> rs.getLong(1), T, "asha").orElse(0L));
        assertEquals(0L, live, "the disable revokes every token of the user");

        ApiProblem twice = assertThrows(ApiProblem.class, () -> bridge.createUser(admin, user("asha", "Again", null)));
        assertEquals(409, twice.status());
        assertEquals("USER_EXISTS", twice.code());
        assertEquals(404, assertThrows(ApiProblem.class, () -> bridge.disableUser(admin, "nobody", "r")).status());
        bridge.createUser(admin, user("r6-admin", "The administrator", null));
        ApiProblem self = assertThrows(ApiProblem.class, () -> bridge.disableUser(admin, "r6-admin", "r"));
        assertEquals("SELF_DISABLE", self.code());
    }

    @Test
    void aGroupIsCreatedAndRenamedKeepingItsReferenceAndMembers() {
        IdentityAdminPort.Group g = bridge.createGroup(admin, new IdentityAdminPort.Group("clerks", "Clerks", null, null,
                null));
        assertEquals("clerks", g.id());
        assertEquals("LOCAL", g.source());
        bridge.createUser(admin, user("ravi", "Ravi", null));
        bridge.setMembership(admin, "clerks", "ravi", true);
        tx.inTransaction(c -> Jdbc.update(c, "UPDATE SEC_GROUP SET external_ref='idp:clerks' WHERE tenant_id=? AND"
                + " group_id='clerks'", T));

        IdentityAdminPort.Group renamed = bridge.updateGroup(admin, "clerks",
                new IdentityAdminPort.Group(null, "Front-desk clerks", null, null, null));
        assertEquals("Front-desk clerks", renamed.name());
        assertEquals(1, renamed.members());
        assertEquals("idp:clerks", tx.inTransaction(c -> Jdbc.one(c, "SELECT external_ref FROM SEC_GROUP WHERE"
                + " tenant_id=? AND group_id='clerks'", rs -> rs.getString(1), T).orElseThrow()));

        assertEquals("GROUP_EXISTS", assertThrows(ApiProblem.class, () -> bridge.createGroup(admin,
                new IdentityAdminPort.Group("clerks", "Again", null, null, null))).code());
        assertEquals(404, assertThrows(ApiProblem.class, () -> bridge.updateGroup(admin, "none",
                new IdentityAdminPort.Group(null, "X", null, null, null))).status());
    }
}
