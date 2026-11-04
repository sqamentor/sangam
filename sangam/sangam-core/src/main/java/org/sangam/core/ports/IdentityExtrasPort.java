// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.ports;

import org.sangam.core.security.SecurityIdentity;

import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Flowable IDM privileges, privilege mappings and tokens (FC-42), owned by WS-SECURITY ({@code PrivilegeService},
 * {@code ApiTokenService} on {@code SEC_PRIVILEGE}, {@code SEC_PRIVILEGE_MAPPING}, {@code SEC_API_TOKEN}). Added by
 * WS-PORTS so the ports' Flowable-style facade can delegate without depending on sangam-security (which carries
 * Spring Security). The integrator binds it with a thin adapter over the two security services; every call is
 * authorised there (deny-wins), in the caller's transaction.
 */
public interface IdentityExtrasPort {

    record IssuedToken(String tokenId, String plaintext, Instant expiresAt) {
    }

    record TokenInfo(String tokenId, String ownerPersonId, String name, List<String> scopes, Instant expiresAt, boolean revoked) {
    }

    void createPrivilege(Connection c, SecurityIdentity actor, String privilegeId, String name);

    /** Maps a privilege to a principal ({@code user:..}, {@code group:..}); {@code deny=true} wins over grants. */
    void mapPrivilege(Connection c, SecurityIdentity actor, String privilegeId, String principalId, boolean deny);

    void unmapPrivilege(Connection c, SecurityIdentity actor, String privilegeId, String principalId);

    boolean hasPrivilege(Connection c, SecurityIdentity who, String privilegeId);

    List<String> privilegesOf(Connection c, SecurityIdentity who);

    IssuedToken issueToken(Connection c, SecurityIdentity actor, String ownerPersonId, String name, Set<String> scopes, Instant expiresAt);

    Optional<SecurityIdentity> authenticateToken(Connection c, String presented, String clientIp);

    void revokeToken(Connection c, SecurityIdentity actor, String tokenId);

    int revokeAllTokensOf(Connection c, SecurityIdentity actor, String personId);

    List<TokenInfo> tokensOf(Connection c, SecurityIdentity actor, String ownerPersonId);
}
