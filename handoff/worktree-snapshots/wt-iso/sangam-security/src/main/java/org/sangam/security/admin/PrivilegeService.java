// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.admin;

import org.sangam.core.layer.Jdbc;
import org.sangam.core.security.SangamSecurityException;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.security.audit.SecurityAuditService;
import org.sangam.security.audit.SecurityEventType;
import org.sangam.security.dialect.SecurityDialect;
import org.sangam.security.policy.AuthEffectiveMaintainer;
import org.sangam.security.policy.AuthorizationService;
import org.sangam.security.policy.Permission;
import org.sangam.security.policy.ResourceRegistry;
import org.sangam.security.support.Sql;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Privileges and privilege mappings (FLOWABLE-INTO-OPERATON FC-42, Flowable IDM {@code Privilege}/{@code PrivilegeMapping}): named
 * application capabilities ({@code access-admin}, {@code access-modeler}, {@code access-console}, {@code access-rest-api}
 * ...) mapped to users, groups, roles or seats. A privilege check is an object check on the virtual resource
 * {@code PRIVILEGE/<id>} with {@code READ}, so it goes through the same policy engine and an explicit deny mapping wins.
 */
public final class PrivilegeService {

    public static final List<String> BUILT_IN = List.of("access-admin", "access-modeler", "access-console", "access-tasklist",
            "access-rest-api", "access-audit");

    private final SecurityDialect dialect;
    private final AuthorizationService authz;
    private final AuthEffectiveMaintainer maintainer;
    private final SecurityAuditService audit;

    public PrivilegeService(SecurityDialect dialect, AuthorizationService authz, AuthEffectiveMaintainer maintainer,
                            SecurityAuditService audit) {
        this.dialect = dialect;
        this.authz = authz;
        this.maintainer = maintainer;
        this.audit = audit;
    }

    public void createPrivilege(Connection c, SecurityIdentity actor, String privilegeId, String name) {
        authz.require(c, actor, ResourceRegistry.PRIVILEGE, Permission.CHANGE_GRANT, privilegeId);
        if (!Sql.insertIfAbsent(c, dialect, "INSERT INTO SEC_PRIVILEGE(tenant_id, privilege_id, name) VALUES (?,?,?)",
                actor.tenantId(), privilegeId, name)) {
            throw new SangamSecurityException.AdminConflict("PRIVILEGE_EXISTS", "privilege " + privilegeId + " exists");
        }
        audit.security(c, actor, SecurityEventType.PRIVILEGE_CHANGED, Map.of("privilege_id", privilegeId, "created", true));
    }

    /** Maps a privilege to a principal ({@code user:}, {@code group:}, {@code role:}, {@code seat:}); {@code deny} revokes. */
    public void map(Connection c, SecurityIdentity actor, String privilegeId, String principalId, boolean deny) {
        authz.require(c, actor, ResourceRegistry.PRIVILEGE, Permission.CHANGE_GRANT, privilegeId);
        String t = actor.tenantId();
        Jdbc.update(c, "DELETE FROM SEC_PRIVILEGE_MAPPING WHERE tenant_id=? AND privilege_id=? AND principal_id=?", t, privilegeId, principalId);
        Jdbc.update(c, "INSERT INTO SEC_PRIVILEGE_MAPPING(tenant_id, privilege_id, principal_id, deny_flag) VALUES (?,?,?,?)",
                t, privilegeId, principalId, deny ? 1 : 0);
        long v = rematerialise(c, t, privilegeId);
        audit.security(c, actor, SecurityEventType.PRIVILEGE_CHANGED, Map.of("privilege_id", privilegeId, "principal_id", principalId,
                "deny", deny, "new_policy_version", v));
    }

    public void unmap(Connection c, SecurityIdentity actor, String privilegeId, String principalId) {
        authz.require(c, actor, ResourceRegistry.PRIVILEGE, Permission.CHANGE_GRANT, privilegeId);
        String t = actor.tenantId();
        Jdbc.update(c, "DELETE FROM SEC_PRIVILEGE_MAPPING WHERE tenant_id=? AND privilege_id=? AND principal_id=?", t, privilegeId, principalId);
        long v = rematerialise(c, t, privilegeId);
        audit.security(c, actor, SecurityEventType.PRIVILEGE_CHANGED, Map.of("privilege_id", privilegeId, "principal_id", principalId,
                "removed", true, "new_policy_version", v));
    }

    public boolean hasPrivilege(Connection c, SecurityIdentity who, String privilegeId) {
        return authz.allow(c, who, ResourceRegistry.PRIVILEGE, Permission.READ, privilegeId, Map.of());
    }

    public void requirePrivilege(Connection c, SecurityIdentity who, String privilegeId) {
        authz.require(c, who, ResourceRegistry.PRIVILEGE, Permission.READ, privilegeId);
    }

    public List<String> privilegesOf(Connection c, SecurityIdentity who) {
        List<String> out = new ArrayList<>();
        for (String p : Jdbc.query(c, "SELECT privilege_id FROM SEC_PRIVILEGE WHERE tenant_id=? ORDER BY privilege_id",
                rs -> rs.getString(1), who.tenantId())) {
            if (hasPrivilege(c, who, p)) {
                out.add(p);
            }
        }
        return out;
    }

    private long rematerialise(Connection c, String tenant, String privilegeId) {
        List<AuthEffectiveMaintainer.GrantRow> rows = new ArrayList<>();
        Jdbc.query(c, "SELECT principal_id, deny_flag FROM SEC_PRIVILEGE_MAPPING WHERE tenant_id=? AND privilege_id=?", rs -> {
            rows.add(new AuthEffectiveMaintainer.GrantRow(rs.getString(1), ResourceRegistry.PRIVILEGE, privilegeId,
                    rs.getInt(2) == 1, Permission.READ.bit(), null));
            return null;
        }, tenant, privilegeId);
        return maintainer.replaceSource(c, tenant, "privilege:" + privilegeId, rows);
    }
}
