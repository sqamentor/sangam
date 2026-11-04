// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.admin;

import org.sangam.core.layer.Jdbc;
import org.sangam.core.security.SangamSecurityException;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.security.audit.SecurityAuditService;
import org.sangam.security.audit.SecurityEventType;
import org.sangam.security.dialect.SecurityDialect;
import org.sangam.security.policy.AuthorizationService;
import org.sangam.security.policy.MembershipIndex;
import org.sangam.security.policy.Permission;
import org.sangam.security.policy.ResourceRegistry;
import org.sangam.security.support.Sql;

import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Sangam groups and memberships (IDM part owned by WS-SECURITY: users, groups, privileges, tokens; FC-42, A-17, R67).
 * Memberships are mirrored into {@code AUTH_MEMBERSHIP} (source {@code grp:<group>:<member>}) and every change bumps
 * {@code policy_version} (VI.4 rule 5). Sources: {@code LOCAL} (admin), {@code LDAP} (directory sync), {@code IDP}
 * (token claims are handled by {@link MembershipIndex#syncIdp}).
 */
public final class GroupAdminService {

    public record Group(String groupId, String name, String externalRef, String source) {
    }

    private final SecurityDialect dialect;
    private final AuthorizationService authz;
    private final MembershipIndex memberships;
    private final SecurityAuditService audit;

    public GroupAdminService(SecurityDialect dialect, AuthorizationService authz, MembershipIndex memberships,
                             SecurityAuditService audit) {
        this.dialect = dialect;
        this.authz = authz;
        this.memberships = memberships;
        this.audit = audit;
    }

    public void createGroup(Connection c, SecurityIdentity actor, String groupId, String name, String externalRef, String source) {
        authz.require(c, actor, ResourceRegistry.GROUP, Permission.ADMIN, ResourceRegistry.ANY_ID);
        if (!Sql.insertIfAbsent(c, dialect, "INSERT INTO SEC_GROUP(tenant_id, group_id, name, external_ref, source, created_at)"
                        + " VALUES (?,?,?,?,?,?)", actor.tenantId(), groupId, name, externalRef, source == null ? "LOCAL" : source,
                Sql.dbNow(c, dialect))) {
            throw new SangamSecurityException.AdminConflict("GROUP_EXISTS", "group " + groupId + " exists");
        }
        audit.security(c, actor, SecurityEventType.MEMBERSHIP_CHANGED, Map.of("group_id", groupId, "created", true));
    }

    public void deleteGroup(Connection c, SecurityIdentity actor, String groupId) {
        authz.require(c, actor, ResourceRegistry.GROUP, Permission.ADMIN, groupId);
        String t = actor.tenantId();
        for (String m : members(c, t, groupId)) {
            memberships.removeSource(c, t, source(groupId, m));
        }
        Jdbc.update(c, "DELETE FROM SEC_GROUP_MEMBER WHERE tenant_id=? AND group_id=?", t, groupId);
        Jdbc.update(c, "DELETE FROM SEC_GROUP WHERE tenant_id=? AND group_id=?", t, groupId);
        audit.security(c, actor, SecurityEventType.MEMBERSHIP_CHANGED, Map.of("group_id", groupId, "deleted", true));
    }

    /** Adds a person to a group (member id is the person id, stored as {@code user:<id>} in the index). */
    public void addMember(Connection c, SecurityIdentity actor, String groupId, String personId, String source) {
        authz.require(c, actor, ResourceRegistry.GROUP, Permission.ADMIN, groupId);
        String t = actor.tenantId();
        if (Sql.insertIfAbsent(c, dialect, "INSERT INTO SEC_GROUP_MEMBER(tenant_id, group_id, person_id, source, added_at)"
                + " VALUES (?,?,?,?,?)", t, groupId, personId, source == null ? "LOCAL" : source, Sql.dbNow(c, dialect))) {
            long v = memberships.put(c, t, SecurityIdentity.USER + personId, SecurityIdentity.GROUP + groupId,
                    source(groupId, personId), Instant.EPOCH, null);
            audit.security(c, actor, SecurityEventType.MEMBERSHIP_CHANGED, Map.of("group_id", groupId, "person_id", personId,
                    "added", true, "new_policy_version", v));
        }
    }

    public void removeMember(Connection c, SecurityIdentity actor, String groupId, String personId) {
        authz.require(c, actor, ResourceRegistry.GROUP, Permission.ADMIN, groupId);
        String t = actor.tenantId();
        if (Jdbc.update(c, "DELETE FROM SEC_GROUP_MEMBER WHERE tenant_id=? AND group_id=? AND person_id=?", t, groupId, personId) > 0) {
            long v = memberships.removeSource(c, t, source(groupId, personId));
            audit.security(c, actor, SecurityEventType.MEMBERSHIP_CHANGED, Map.of("group_id", groupId, "person_id", personId,
                    "removed", true, "new_policy_version", v));
        }
    }

    public List<String> members(Connection c, String tenant, String groupId) {
        return Jdbc.query(c, "SELECT person_id FROM SEC_GROUP_MEMBER WHERE tenant_id=? AND group_id=? ORDER BY person_id",
                rs -> rs.getString(1), tenant, groupId);
    }

    public List<Group> groups(Connection c, SecurityIdentity actor) {
        authz.require(c, actor, ResourceRegistry.GROUP, Permission.READ, ResourceRegistry.ANY_ID);
        return Jdbc.query(c, "SELECT group_id, name, external_ref, source FROM SEC_GROUP WHERE tenant_id=? ORDER BY group_id",
                rs -> new Group(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)), actor.tenantId());
    }

    public List<String> groupsOf(Connection c, String tenant, String personId) {
        return Jdbc.query(c, "SELECT group_id FROM SEC_GROUP_MEMBER WHERE tenant_id=? AND person_id=? ORDER BY group_id",
                rs -> rs.getString(1), tenant, personId);
    }

    static String source(String groupId, String personId) {
        return "grp:" + groupId + ":" + personId;
    }
}
