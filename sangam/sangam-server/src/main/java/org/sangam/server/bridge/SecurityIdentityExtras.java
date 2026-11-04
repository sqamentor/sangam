// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.sangam.core.ports.IdentityExtrasPort;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.security.admin.ApiTokenService;
import org.sangam.security.admin.PrivilegeService;

/**
 * {@link IdentityExtrasPort} (sangam-core, consumed by sangam-flowable-ports' {@code IdmFacade}, FC-42) as a thin
 * adapter over sangam-security's {@link PrivilegeService} and {@link ApiTokenService}. Same transaction semantics as
 * the services: every call runs on the caller's connection.
 */
public final class SecurityIdentityExtras implements IdentityExtrasPort {

    private final PrivilegeService privileges;
    private final ApiTokenService tokens;

    public SecurityIdentityExtras(PrivilegeService privileges, ApiTokenService tokens) {
        this.privileges = privileges;
        this.tokens = tokens;
    }

    @Override
    public void createPrivilege(Connection c, SecurityIdentity actor, String privilegeId, String name) {
        privileges.createPrivilege(c, actor, privilegeId, name);
    }

    @Override
    public void mapPrivilege(Connection c, SecurityIdentity actor, String privilegeId, String principalId, boolean deny) {
        privileges.map(c, actor, privilegeId, principalId, deny);
    }

    @Override
    public void unmapPrivilege(Connection c, SecurityIdentity actor, String privilegeId, String principalId) {
        privileges.unmap(c, actor, privilegeId, principalId);
    }

    @Override
    public boolean hasPrivilege(Connection c, SecurityIdentity who, String privilegeId) {
        return privileges.hasPrivilege(c, who, privilegeId);
    }

    @Override
    public List<String> privilegesOf(Connection c, SecurityIdentity who) {
        return privileges.privilegesOf(c, who);
    }

    @Override
    public IssuedToken issueToken(Connection c, SecurityIdentity actor, String ownerPersonId, String name,
                                  Set<String> scopes, Instant expiresAt) {
        ApiTokenService.IssuedToken t = tokens.issue(c, actor, ownerPersonId, name, scopes, expiresAt);
        return new IssuedToken(t.tokenId(), t.plaintext(), t.expiresAt());
    }

    @Override
    public Optional<SecurityIdentity> authenticateToken(Connection c, String presented, String clientIp) {
        return tokens.authenticate(c, presented, clientIp);
    }

    @Override
    public void revokeToken(Connection c, SecurityIdentity actor, String tokenId) {
        tokens.revoke(c, actor, tokenId);
    }

    @Override
    public int revokeAllTokensOf(Connection c, SecurityIdentity actor, String personId) {
        return tokens.revokeAllOf(c, actor, personId);
    }

    @Override
    public List<TokenInfo> tokensOf(Connection c, SecurityIdentity actor, String ownerPersonId) {
        return tokens.list(c, actor, ownerPersonId).stream()
                .map(t -> new TokenInfo(t.tokenId(), t.ownerPersonId(), t.name(), t.scopes(), t.expiresAt(), t.revoked()))
                .toList();
    }
}
