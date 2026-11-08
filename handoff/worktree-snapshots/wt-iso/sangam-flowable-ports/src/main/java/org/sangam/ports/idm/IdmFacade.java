// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.idm;

import org.sangam.core.layer.Transactions;
import org.sangam.core.ports.IdentityExtrasPort;
import org.sangam.core.security.SecurityIdentity;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Flowable IDM-style API for privileges, privilege mappings and tokens (FLOWABLE-INTO-OPERATON FC-42; Flowable {@code IdmIdentityService}
 * {@code createPrivilege / addUserPrivilegeMapping / addGroupPrivilegeMapping / getPrivileges / createToken}).
 * <b>No state here</b>: every call delegates to WS-SECURITY through {@link IdentityExtrasPort} (tables
 * {@code SEC_PRIVILEGE}, {@code SEC_PRIVILEGE_MAPPING}, {@code SEC_API_TOKEN}; deny-wins, audit, scopes, expiry), so
 * there is one privilege and token store. This facade only maps Flowable's user/group vocabulary onto principal ids
 * and runs each call in a transaction.
 */
public class IdmFacade {

    private final IdentityExtrasPort security;
    private final Transactions tx;

    public IdmFacade(IdentityExtrasPort security, Transactions tx) {
        this.security = security;
        this.tx = tx;
    }

    public void createPrivilege(SecurityIdentity actor, String privilegeId, String name) {
        tx.inTransaction(c -> {
            security.createPrivilege(c, actor, privilegeId, name);
            return null;
        });
    }

    public void addUserPrivilegeMapping(SecurityIdentity actor, String privilegeId, String userId) {
        map(actor, privilegeId, SecurityIdentity.USER + userId, false);
    }

    public void addGroupPrivilegeMapping(SecurityIdentity actor, String privilegeId, String groupId) {
        map(actor, privilegeId, SecurityIdentity.GROUP + groupId, false);
    }

    /** Explicit deny mapping (Sangam addition: deny wins over any grant, VI.4). */
    public void denyPrivilege(SecurityIdentity actor, String privilegeId, String principalId) {
        map(actor, privilegeId, principalId, true);
    }

    public void deleteUserPrivilegeMapping(SecurityIdentity actor, String privilegeId, String userId) {
        tx.inTransaction(c -> {
            security.unmapPrivilege(c, actor, privilegeId, SecurityIdentity.USER + userId);
            return null;
        });
    }

    public void deleteGroupPrivilegeMapping(SecurityIdentity actor, String privilegeId, String groupId) {
        tx.inTransaction(c -> {
            security.unmapPrivilege(c, actor, privilegeId, SecurityIdentity.GROUP + groupId);
            return null;
        });
    }

    public boolean hasPrivilege(SecurityIdentity who, String privilegeId) {
        return tx.inTransaction(c -> security.hasPrivilege(c, who, privilegeId));
    }

    public List<String> privilegesOf(SecurityIdentity who) {
        return tx.inTransaction(c -> security.privilegesOf(c, who));
    }

    public IdentityExtrasPort.IssuedToken createToken(SecurityIdentity actor, String ownerPersonId, String name, Set<String> scopes, Duration ttl) {
        Instant expires = org.sangam.core.engine.SangamClockHolder.now().plus(ttl);
        return tx.inTransaction(c -> security.issueToken(c, actor, ownerPersonId, name, scopes, expires));
    }

    public Optional<SecurityIdentity> authenticateToken(String presented, String clientIp) {
        return tx.inTransaction(c -> security.authenticateToken(c, presented, clientIp));
    }

    public void deleteToken(SecurityIdentity actor, String tokenId) {
        tx.inTransaction(c -> {
            security.revokeToken(c, actor, tokenId);
            return null;
        });
    }

    public int deleteAllTokensOf(SecurityIdentity actor, String personId) {
        return tx.inTransaction(c -> security.revokeAllTokensOf(c, actor, personId));
    }

    public List<IdentityExtrasPort.TokenInfo> tokensOf(SecurityIdentity actor, String ownerPersonId) {
        return tx.inTransaction(c -> security.tokensOf(c, actor, ownerPersonId));
    }

    private void map(SecurityIdentity actor, String privilegeId, String principal, boolean deny) {
        tx.inTransaction(c -> {
            security.mapPrivilege(c, actor, privilegeId, principal, deny);
            return null;
        });
    }
}
