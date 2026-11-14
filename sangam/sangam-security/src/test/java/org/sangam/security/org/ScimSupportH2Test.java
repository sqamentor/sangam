// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.org;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
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
import org.sangam.core.security.SangamSecurityException;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.security.admin.GroupAdminService;
import org.sangam.security.audit.SecurityAuditService;
import org.sangam.security.dialect.SecurityDialect;
import org.sangam.security.policy.AuthEffectiveMaintainer;
import org.sangam.security.policy.AuthorizationService;
import org.sangam.security.policy.MembershipIndex;
import org.sangam.security.policy.Permission;
import org.sangam.security.policy.PolicyCompiler;
import org.sangam.security.policy.ResourceRegistry;

/**
 * Decision 154 support (W4-I REQUEST 19:27:53Z): the security methods SCIM calls. {@link PersonService#enableUser}
 * re-enables only a disabled person and audits {@code USER_UPDATED how=ENABLE}; {@link PersonService#createUser} with a
 * source writes it ({@code SCIM}), the 6-argument form keeps {@code LOCAL}; {@link GroupAdminService#updateGroup}
 * renames a group and sets its external reference, {@code GROUP_NOT_FOUND} otherwise. Every method requires USER or
 * GROUP ADMIN. Written for the test phase, not run.
 */
class ScimSupportH2Test {

    static final String T = "t";

    private Connection c;
    private PersonService persons;
    private GroupAdminService groups;
    private final SecurityIdentity admin = new SecurityIdentity(T, "adm", "adm", Set.of(), Set.of("user-admin"), Set.of(),
            Set.of(), "OIDC", null, null, false, Map.of());
    private final SecurityIdentity clerk = new SecurityIdentity(T, "clk", "clk", Set.of(), Set.of(), Set.of(), Set.of(),
            "OIDC", null, null, false, Map.of());

    @BeforeEach
    void db() throws Exception {
        c = DriverManager.getConnection("jdbc:h2:mem:scimsup" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=TRUE", "sa", "");
        Schema.create(c, Dialect.POSTGRESQL);
        Jdbc.update(c, "CREATE TABLE AUTH_CONDITION (tenant_id VARCHAR(64), condition_id VARCHAR(64),"
                + " resource_type VARCHAR(64), expression TEXT)");
        Jdbc.update(c, "CREATE TABLE AUTH_MEMBERSHIP (tenant_id VARCHAR(64) NOT NULL, member_id VARCHAR(255) NOT NULL,"
                + " principal_id VARCHAR(255) NOT NULL, source_id VARCHAR(255) NOT NULL, valid_from TIMESTAMP WITH TIME ZONE NOT NULL,"
                + " valid_to TIMESTAMP WITH TIME ZONE, PRIMARY KEY (tenant_id, member_id, principal_id, source_id))");
        Jdbc.update(c, "CREATE TABLE SEC_PERSON (tenant_id VARCHAR(64) NOT NULL, person_id VARCHAR(64) NOT NULL,"
                + " display_name VARCHAR(255), external_subject VARCHAR(255), email VARCHAR(255), status VARCHAR(16) NOT NULL,"
                + " source VARCHAR(16) DEFAULT 'LOCAL' NOT NULL, created_at TIMESTAMP WITH TIME ZONE,"
                + " PRIMARY KEY (tenant_id, person_id))");
        Jdbc.update(c, "CREATE UNIQUE INDEX SEC_PERSON_SUBJECT ON SEC_PERSON (tenant_id, external_subject)");
        Jdbc.update(c, "CREATE TABLE SEC_GROUP (tenant_id VARCHAR(64) NOT NULL, group_id VARCHAR(255) NOT NULL, name VARCHAR(255),"
                + " external_ref VARCHAR(255), source VARCHAR(16) NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL,"
                + " PRIMARY KEY (tenant_id, group_id))");
        Jdbc.update(c, "CREATE TABLE SEC_GROUP_MEMBER (tenant_id VARCHAR(64) NOT NULL, group_id VARCHAR(255) NOT NULL,"
                + " person_id VARCHAR(64) NOT NULL, source VARCHAR(16) NOT NULL, added_at TIMESTAMP WITH TIME ZONE NOT NULL,"
                + " PRIMARY KEY (tenant_id, group_id, person_id))");
        Jdbc.update(c, "INSERT INTO AUTH_POLICY_VERSION(tenant_id, policy_version) VALUES (?, 1)", T);
        for (String type : List.of(ResourceRegistry.USER, ResourceRegistry.GROUP)) {
            Jdbc.update(c, "INSERT INTO AUTH_EFFECTIVE(tenant_id, principal_id, resource_type, resource_id, deny_flag, perms,"
                    + " condition_id, cond_key, policy_version) VALUES (?, 'role:user-admin', ?, '*', 0, ?, NULL, '-', 0)",
                    T, type, Permission.ADMIN.bit() | Permission.READ.bit());
        }
        SecurityAuditService audit = new SecurityAuditService(SecurityDialect.H2);
        AuthorizationService authz = new AuthorizationService(
                new PolicyCompiler(SecurityDialect.H2, new ResourceRegistry(), Duration.ZERO), audit);
        persons = new PersonService(SecurityDialect.H2, authz, audit);
        groups = new GroupAdminService(SecurityDialect.H2, authz,
                new MembershipIndex(SecurityDialect.H2, new AuthEffectiveMaintainer(SecurityDialect.H2)), audit);
    }

    @AfterEach
    void close() throws Exception {
        c.close();
    }

    private String column(String table, String column, String key, String id) {
        return Jdbc.one(c, "SELECT " + column + " FROM " + table + " WHERE tenant_id=? AND " + key + "=?",
                rs -> rs.getString(1), T, id).orElse(null);
    }

    private long audits(String type) {
        return Jdbc.one(c, "SELECT COUNT(*) FROM AUDIT_EVENT WHERE event_type=?", rs -> rs.getLong(1), type).orElse(0L);
    }

    @Test
    void createUserRecordsItsSource() {
        persons.createUser(c, admin, "p-scim", "P Scim", "ext-1", null, "SCIM");
        assertEquals("SCIM", column("SEC_PERSON", "source", "person_id", "p-scim"));
        persons.createUser(c, admin, "p-local", "P Local", "ext-2", null);
        assertEquals("LOCAL", column("SEC_PERSON", "source", "person_id", "p-local"), "the 6-argument form is unchanged");
        assertThrows(SangamSecurityException.AdminConflict.class,
                () -> persons.createUser(c, admin, "p-scim", "again", "ext-3", null, "SCIM"), "USER_EXISTS as before");
    }

    @Test
    void enableUserReEnablesOnlyADisabledPerson() {
        persons.createUser(c, admin, "p1", "P One", "ext-1", null, "SCIM");
        assertFalse(persons.enableUser(c, admin, "p1"), "an active person is unchanged");
        persons.disableUser(c, admin, "p1", "left");
        assertEquals("DISABLED", column("SEC_PERSON", "status", "person_id", "p1"));
        long updates = audits("USER_UPDATED");
        assertTrue(persons.enableUser(c, admin, "p1"));
        assertEquals("ACTIVE", column("SEC_PERSON", "status", "person_id", "p1"));
        assertEquals(updates + 1, audits("USER_UPDATED"), "USER_UPDATED how=ENABLE");
        assertFalse(persons.enableUser(c, admin, "nobody"), "an unknown person changes nothing");
    }

    @Test
    void enableUserRequiresUserAdmin() {
        persons.createUser(c, admin, "p1", "P One", "ext-1", null);
        persons.disableUser(c, admin, "p1", "left");
        assertThrows(SangamSecurityException.AccessDenied.class, () -> persons.enableUser(c, clerk, "p1"));
        assertEquals("DISABLED", column("SEC_PERSON", "status", "person_id", "p1"));
    }

    @Test
    void updateGroupRenamesAndSetsTheExternalReference() {
        groups.createGroup(c, admin, "g1", "Clerks", null, "SCIM");
        groups.updateGroup(c, admin, "g1", "Senior clerks", "ext-g1");
        assertEquals("Senior clerks", column("SEC_GROUP", "name", "group_id", "g1"));
        assertEquals("ext-g1", column("SEC_GROUP", "external_ref", "group_id", "g1"));
        assertEquals("SCIM", column("SEC_GROUP", "source", "group_id", "g1"), "the source is kept");
        SangamSecurityException.AdminConflict e = assertThrows(SangamSecurityException.AdminConflict.class,
                () -> groups.updateGroup(c, admin, "g-missing", "x", null));
        assertEquals("GROUP_NOT_FOUND", e.code());
        assertThrows(SangamSecurityException.AccessDenied.class, () -> groups.updateGroup(c, clerk, "g1", "x", null));
    }
}
