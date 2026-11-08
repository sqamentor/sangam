// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.admin;

import org.sangam.core.layer.Hashes;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.security.SangamSecurityException;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.security.audit.SecurityAuditService;
import org.sangam.security.audit.SecurityEventType;
import org.sangam.security.dialect.SecurityDialect;
import org.sangam.security.policy.AuthorizationService;
import org.sangam.security.policy.Permission;
import org.sangam.security.policy.ResourceRegistry;
import org.sangam.security.support.Sql;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Personal and service API tokens (FLOWABLE-INTO-OPERATON FC-42 {@code Token}; worker/service principals of R26 and IJ-01). A token is
 * {@code sgm_<tokenId>.<secret>}; only {@code SHA-256(secret)} is stored, the plaintext is returned once at issue.
 * Tokens carry an owner, a tenant, scopes (role ids the token may use, a subset of the owner's), an expiry and a
 * revocation flag. Authentication compares hashes in constant time and records {@code last_used_at}.
 */
public final class ApiTokenService {

    public static final String PREFIX = "sgm_";
    private static final SecureRandom RANDOM = new SecureRandom();

    public record IssuedToken(String tokenId, String plaintext, Instant expiresAt) {
    }

    public record TokenInfo(String tokenId, String ownerPersonId, String name, List<String> scopes, Instant expiresAt,
                            Instant lastUsedAt, boolean revoked) {
    }

    private final SecurityDialect dialect;
    private final AuthorizationService authz;
    private final SecurityAuditService audit;

    public ApiTokenService(SecurityDialect dialect, AuthorizationService authz, SecurityAuditService audit) {
        this.dialect = dialect;
        this.authz = authz;
        this.audit = audit;
    }

    /** Issues a token for {@code ownerPersonId} (self-service for one's own tokens, else ADMIN on API_TOKEN). */
    public IssuedToken issue(Connection c, SecurityIdentity actor, String ownerPersonId, String name, Set<String> scopes,
                             Instant expiresAt) {
        if (!actor.personId().equals(ownerPersonId)) {
            authz.require(c, actor, ResourceRegistry.API_TOKEN, Permission.ADMIN, ResourceRegistry.ANY_ID);
        }
        Instant now = Sql.dbNow(c, dialect);
        if (expiresAt == null || !expiresAt.isAfter(now) || expiresAt.isAfter(now.plusSeconds(366L * 24 * 3600))) {
            throw new SangamSecurityException.AdminConflict("INVALID_EXPIRY", "a token needs an expiry within one year");
        }
        byte[] secret = new byte[32];
        RANDOM.nextBytes(secret);
        String secretText = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        String id = Sql.newId().replace("-", "");
        Jdbc.update(c, "INSERT INTO SEC_API_TOKEN(token_id, tenant_id, owner_person_id, name, secret_hash, scopes, created_at,"
                        + " expires_at, last_used_at, revoked_at, created_by) VALUES (?,?,?,?,?,?,?,?,NULL,NULL,?)",
                id, actor.tenantId(), ownerPersonId, name, hash(secretText), Sql.toJson(scopes == null ? List.of() : List.copyOf(scopes)),
                now, expiresAt, actor.personId());
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("token_id", id);
        p.put("owner_person_id", ownerPersonId);
        p.put("name", name);
        p.put("expires_at", expiresAt);
        audit.security(c, actor, SecurityEventType.API_TOKEN_ISSUED, p);
        return new IssuedToken(id, PREFIX + id + "." + secretText, expiresAt);
    }

    /**
     * Authenticates a presented token. Returns the bound identity (tenant, owner, scoped roles, {@code API_KEY}); the
     * caller enriches it with groups and seats. Empty for an unknown, expired, revoked or malformed token, or a
     * disabled owner.
     */
    public Optional<SecurityIdentity> authenticate(Connection c, String presented, String clientIp) {
        if (presented == null || !presented.startsWith(PREFIX) || presented.indexOf('.') < 0) {
            return Optional.empty();
        }
        String body = presented.substring(PREFIX.length());
        String id = body.substring(0, body.indexOf('.'));
        String secret = body.substring(body.indexOf('.') + 1);
        Optional<String[]> row = Jdbc.one(c, "SELECT t.tenant_id, t.owner_person_id, t.secret_hash, t.scopes FROM SEC_API_TOKEN t"
                        + " JOIN SEC_PERSON p ON p.tenant_id=t.tenant_id AND p.person_id=t.owner_person_id"
                        + " WHERE t.token_id=? AND t.revoked_at IS NULL AND t.expires_at > " + dialect.now() + " AND p.status='ACTIVE'",
                rs -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)}, id);
        if (row.isEmpty() || !MessageDigest.isEqual(row.get()[2].getBytes(StandardCharsets.US_ASCII),
                hash(secret).getBytes(StandardCharsets.US_ASCII))) {
            return Optional.empty();
        }
        Jdbc.update(c, "UPDATE SEC_API_TOKEN SET last_used_at=" + dialect.now() + " WHERE token_id=?", id);
        Set<String> roles = new LinkedHashSet<>(Sql.jsonList(row.get()[3]));
        return Optional.of(new SecurityIdentity(row.get()[0], row.get()[1], "token:" + id, Set.of(), roles, Set.of(), Set.of(),
                "API_KEY", clientIp, null, false, Map.of("token_id", id)));
    }

    public void revoke(Connection c, SecurityIdentity actor, String tokenId) {
        String owner = Jdbc.one(c, "SELECT owner_person_id FROM SEC_API_TOKEN WHERE token_id=? AND tenant_id=?",
                        rs -> rs.getString(1), tokenId, actor.tenantId())
                .orElseThrow(() -> new SangamSecurityException.AdminConflict("UNKNOWN_TOKEN", "token not found"));
        if (!actor.personId().equals(owner)) {
            authz.require(c, actor, ResourceRegistry.API_TOKEN, Permission.ADMIN, tokenId);
        }
        Jdbc.update(c, "UPDATE SEC_API_TOKEN SET revoked_at=" + dialect.now() + " WHERE token_id=? AND revoked_at IS NULL", tokenId);
        audit.security(c, actor, SecurityEventType.API_TOKEN_REVOKED, Map.of("token_id", tokenId, "owner_person_id", owner));
    }

    /** Revokes every token of a person (user disabled, transfer out, incident). */
    public int revokeAllOf(Connection c, SecurityIdentity actor, String personId) {
        authz.require(c, actor, ResourceRegistry.API_TOKEN, Permission.ADMIN, ResourceRegistry.ANY_ID);
        int n = Jdbc.update(c, "UPDATE SEC_API_TOKEN SET revoked_at=" + dialect.now()
                + " WHERE tenant_id=? AND owner_person_id=? AND revoked_at IS NULL", actor.tenantId(), personId);
        audit.security(c, actor, SecurityEventType.API_TOKEN_REVOKED, Map.of("owner_person_id", personId, "count", n));
        return n;
    }

    public List<TokenInfo> list(Connection c, SecurityIdentity actor, String ownerPersonId) {
        if (!actor.personId().equals(ownerPersonId)) {
            authz.require(c, actor, ResourceRegistry.API_TOKEN, Permission.READ, ResourceRegistry.ANY_ID);
        }
        return Jdbc.query(c, "SELECT token_id, owner_person_id, name, scopes, expires_at, last_used_at, revoked_at FROM SEC_API_TOKEN"
                        + " WHERE tenant_id=? AND owner_person_id=? ORDER BY created_at",
                rs -> new TokenInfo(rs.getString(1), rs.getString(2), rs.getString(3), Sql.jsonList(rs.getString(4)),
                        Jdbc.instant(rs, "expires_at"), Jdbc.instant(rs, "last_used_at"), rs.getTimestamp(7) != null),
                actor.tenantId(), ownerPersonId);
    }

    /**
     * Tenant-wide token list for the admin screens (service principals, R26): newest first, paged, with a capped
     * total (A-16). Requires {@code READ} on {@code API_TOKEN}; the tenant comes from the actor (VI.4 rule 6). Secrets
     * are never returned (only their hashes are stored).
     */
    public org.sangam.security.policy.AuthorizedQueryBuilder.Page<TokenInfo> listTenant(Connection c,
            SecurityIdentity actor, int offset, int limit, int cap) {
        authz.require(c, actor, ResourceRegistry.API_TOKEN, Permission.READ, ResourceRegistry.ANY_ID);
        if (offset < 0 || limit < 1 || limit > org.sangam.security.policy.AuthorizedQueryBuilder.MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("offset >= 0 and 1 <= limit <= "
                    + org.sangam.security.policy.AuthorizedQueryBuilder.MAX_PAGE_SIZE);
        }
        List<TokenInfo> items = new java.util.ArrayList<>();
        String select = "SELECT token_id, owner_person_id, name, scopes, expires_at, last_used_at, revoked_at"
                + " FROM SEC_API_TOKEN WHERE tenant_id=? ORDER BY created_at DESC, token_id";
        try (java.sql.PreparedStatement ps = c.prepareStatement(select)) {
            ps.setMaxRows(offset + limit);
            ps.setString(1, actor.tenantId());
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                int i = 0;
                while (rs.next()) {
                    if (i++ >= offset) {
                        items.add(new TokenInfo(rs.getString(1), rs.getString(2), rs.getString(3),
                                Sql.jsonList(rs.getString(4)), Jdbc.instant(rs, "expires_at"),
                                Jdbc.instant(rs, "last_used_at"), rs.getTimestamp(7) != null));
                    }
                }
            }
            long n;
            try (java.sql.PreparedStatement cnt = c.prepareStatement("SELECT token_id FROM SEC_API_TOKEN WHERE tenant_id=?")) {
                cnt.setMaxRows(cap + 1);
                cnt.setString(1, actor.tenantId());
                n = 0;
                try (java.sql.ResultSet rs = cnt.executeQuery()) {
                    while (rs.next()) {
                        n++;
                    }
                }
            }
            return new org.sangam.security.policy.AuthorizedQueryBuilder.Page<>(items, offset, limit,
                    new org.sangam.security.policy.AuthorizedQueryBuilder.CappedCount(Math.min(n, cap), n > cap));
        } catch (java.sql.SQLException e) {
            throw new Jdbc.LayerSqlException("SEC_API_TOKEN list", e);
        }
    }

    /** One token of the actor's tenant ({@code READ} on {@code API_TOKEN}). */
    public Optional<TokenInfo> find(Connection c, SecurityIdentity actor, String tokenId) {
        authz.require(c, actor, ResourceRegistry.API_TOKEN, Permission.READ, ResourceRegistry.ANY_ID);
        return Jdbc.one(c, "SELECT token_id, owner_person_id, name, scopes, expires_at, last_used_at, revoked_at"
                        + " FROM SEC_API_TOKEN WHERE tenant_id=? AND token_id=?",
                rs -> new TokenInfo(rs.getString(1), rs.getString(2), rs.getString(3), Sql.jsonList(rs.getString(4)),
                        Jdbc.instant(rs, "expires_at"), Jdbc.instant(rs, "last_used_at"), rs.getTimestamp(7) != null),
                actor.tenantId(), tokenId);
    }

    private static String hash(String secret) {
        return Hashes.hex(Hashes.sha256(secret));
    }
}
