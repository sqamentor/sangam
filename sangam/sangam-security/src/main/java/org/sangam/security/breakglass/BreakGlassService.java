// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.breakglass;

import org.sangam.core.layer.Jdbc;
import org.sangam.core.security.SangamSecurityException;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.security.admin.ApiTokenService;
import org.sangam.security.admin.TenantAdminService;
import org.sangam.security.audit.SecurityAuditService;
import org.sangam.security.audit.SecurityEventType;
import org.sangam.security.dialect.SecurityDialect;
import org.sangam.security.policy.AuthorizationService;
import org.sangam.security.policy.Permission;
import org.sangam.security.policy.ResourceRegistry;
import org.sangam.security.support.Sql;

import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Break-glass access (M-084, AJ-05 step 7, SANGAM.md S01/A05: "break-glass only for PER-5 with reason"; the README
 * design: a time-limited API token plus an alert on use). Off unless the tenant configures a break-glass token class.
 *
 * <p><b>Token classes come from tenant config</b> (owner decision 49): key {@value #CONFIG_TOKEN_CLASSES} of
 * {@code SEC_TENANT.config}, a map of class name to
 * <pre>
 * {"breakGlass": true, "roles": ["tenant-admin"], "validMinutes": 60, "approvals": 2}
 * </pre>
 * {@code roles} (required, not empty) are the roles the token carries; {@code validMinutes} (1..{@value #MAX_VALID_MINUTES},
 * required) is the time box from issue; {@code approvals} (default and minimum {@value #MIN_APPROVALS}) is the number of
 * distinct approvers. Only classes with {@code breakGlass: true} are break-glass classes.
 *
 * <p>Lifecycle of a grant ({@code SEC_BREAK_GLASS}):
 * <ol>
 *   <li>{@link #request}: an administrator ({@code ADMIN} on {@code API_TOKEN}) asks for a class for a holder (an
 *       active person of the tenant) with a reason: {@code REQUESTED}, {@code BREAK_GLASS_REQUESTED};</li>
 *   <li>{@link #approve}: distinct administrators approve; neither the requester nor the holder may approve (dual
 *       control). The last required approval issues the token ({@link ApiTokenService#issue}, scopes = the class roles,
 *       expiry = now + {@code validMinutes}) and returns its plaintext once: {@code ISSUED},
 *       {@code BREAK_GLASS_APPROVED} then {@code BREAK_GLASS_ISSUED}. {@link #reject} ends a request;</li>
 *   <li>every authentication with the token ({@link #used}, called by {@link ApiTokenService#authenticate}) writes
 *       {@code BREAK_GLASS_USED} with {@code alarm=true} and raises the {@link BreakGlassAlarm}; the first use opens the
 *       review task ({@code review_status = PENDING});</li>
 *   <li>{@link #review}: an administrator other than the holder closes the review ({@code ACCEPTED} or
 *       {@code ESCALATED}, with notes): {@code BREAK_GLASS_REVIEWED}. The pending reviews are the review task list
 *       ({@link #list} with {@code reviewStatus=PENDING}).</li>
 * </ol>
 * The token is an ordinary API token afterwards: revocation ({@link ApiTokenService#revoke}) and expiry apply, and its
 * identity carries {@code token_class} so every downstream audit event shows the break-glass origin. All checks run in
 * the caller's transaction at the database time.
 */
public final class BreakGlassService {

    public static final String CONFIG_TOKEN_CLASSES = "tokenClasses";
    public static final int MIN_APPROVALS = 2;
    public static final int MAX_VALID_MINUTES = 24 * 60;
    /** Name prefix of the API tokens this service issues (a cheap filter in authentication; the grant row decides). */
    public static final String TOKEN_NAME_PREFIX = ApiTokenService.BREAK_GLASS_NAME_PREFIX;
    public static final String ATTR_TOKEN_CLASS = "token_class";
    public static final String ATTR_GRANT = "break_glass_grant";

    /** A break-glass token class from tenant config. */
    public record TokenClass(String name, Set<String> roles, int validMinutes, int approvals) {
    }

    public record Grant(String grantId, String tokenClass, String holderPersonId, String requestedBy, String reason,
                        String status, int requiredApprovals, List<String> approvals, int validMinutes, String tokenId,
                        Instant requestedAt, Instant issuedAt, Instant expiresAt, Instant firstUsedAt, Instant lastUsedAt,
                        long useCount, String reviewStatus, String reviewedBy, String reviewOutcome, String reviewNotes,
                        Instant reviewedAt) {
    }

    /** The result of an approval: the grant, and the token plaintext (once) when this approval issued it. */
    public record Approval(Grant grant, Optional<ApiTokenService.IssuedToken> token) {
    }

    private static final String COLUMNS = "grant_id, token_class, holder_person_id, requested_by, reason, status,"
            + " required_approvals, approvals, valid_minutes, token_id, requested_at, issued_at, expires_at, first_used_at,"
            + " last_used_at, use_count, review_status, reviewed_by, review_outcome, review_notes, reviewed_at";

    private final SecurityDialect dialect;
    private final AuthorizationService authz;
    private final SecurityAuditService audit;
    private final TenantAdminService tenants;
    private final ApiTokenService.BreakGlassIssuer issuer;
    private volatile BreakGlassAlarm alarm = BreakGlassAlarm.LOG;

    public BreakGlassService(SecurityDialect dialect, AuthorizationService authz, SecurityAuditService audit,
                             TenantAdminService tenants, ApiTokenService tokens) {
        this.dialect = dialect;
        this.authz = authz;
        this.audit = audit;
        this.tenants = tenants;
        // decision 358: this service holds the token service's one break-glass issuer
        this.issuer = tokens.breakGlassIssuer();
    }

    /** Binds the deployment's alarm (default {@link BreakGlassAlarm#LOG}). */
    public void useAlarm(BreakGlassAlarm alarm) {
        this.alarm = Objects.requireNonNull(alarm, "alarm");
    }

    // ------------------------------------------------------------------ token classes (tenant config)

    /** The break-glass token classes of the tenant config; empty when break-glass is not configured (off). */
    public Map<String, TokenClass> tokenClasses(Connection c, String tenantId) {
        Map<String, Object> config = tenants.find(c, tenantId).map(TenantAdminService.Tenant::config).orElse(Map.of());
        Map<String, TokenClass> out = new LinkedHashMap<>();
        if (!(config.get(CONFIG_TOKEN_CLASSES) instanceof Map<?, ?> classes)) {
            return out;
        }
        for (Map.Entry<?, ?> e : classes.entrySet()) {
            if (e.getValue() instanceof Map<?, ?> def && Boolean.TRUE.equals(def.get("breakGlass"))) {
                out.put(String.valueOf(e.getKey()), parse(String.valueOf(e.getKey()), def));
            }
        }
        return out;
    }

    static TokenClass parse(String name, Map<?, ?> def) {
        Set<String> roles = new LinkedHashSet<>();
        if (def.get("roles") instanceof Collection<?> rs) {
            rs.stream().filter(Objects::nonNull).map(String::valueOf).filter(s -> !s.isBlank()).forEach(roles::add);
        }
        int minutes = integer(def.get("validMinutes"), -1);
        int approvals = integer(def.get("approvals"), MIN_APPROVALS);
        if (roles.isEmpty() || minutes < 1 || minutes > MAX_VALID_MINUTES || approvals < MIN_APPROVALS) {
            throw new SangamSecurityException.AdminConflict("BREAK_GLASS_CLASS_INVALID", "token class " + name
                    + " needs roles, validMinutes 1.." + MAX_VALID_MINUTES + " and approvals >= " + MIN_APPROVALS);
        }
        return new TokenClass(name, Set.copyOf(roles), minutes, approvals);
    }

    private static int integer(Object v, int dflt) {
        if (v == null) {
            return dflt;
        }
        if (v instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue())) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(v.toString().trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Every operation is off (404) for a tenant whose config has no break-glass token class. */
    private Map<String, TokenClass> requireConfigured(Connection c, String tenantId) {
        Map<String, TokenClass> classes = tokenClasses(c, tenantId);
        if (classes.isEmpty()) {
            throw new SangamSecurityException("BREAK_GLASS_NOT_CONFIGURED", 404,
                    "break-glass access is not configured for this tenant");
        }
        return classes;
    }

    private TokenClass requireClass(Connection c, String tenantId, String name) {
        TokenClass k = requireConfigured(c, tenantId).get(name);
        if (k == null) {
            throw new SangamSecurityException.AdminConflict("BREAK_GLASS_CLASS_UNKNOWN", "no break-glass token class " + name);
        }
        return k;
    }

    // ------------------------------------------------------------------ lifecycle

    public Grant request(Connection c, SecurityIdentity actor, String tokenClass, String holderPersonId, String reason) {
        authz.require(c, actor, ResourceRegistry.API_TOKEN, Permission.ADMIN, ResourceRegistry.ANY_ID);
        TokenClass k = requireClass(c, actor.tenantId(), tokenClass);
        if (reason == null || reason.isBlank() || reason.length() > 1000) {
            throw new SangamSecurityException.AdminConflict("REASON_REQUIRED", "a reason of 1..1000 characters is required");
        }
        boolean active = Jdbc.one(c, "SELECT status FROM SEC_PERSON WHERE tenant_id=? AND person_id=?", rs -> rs.getString(1),
                actor.tenantId(), holderPersonId).map("ACTIVE"::equals).orElse(false);
        if (!active) {
            throw new SangamSecurityException.AdminConflict("UNKNOWN_PERSON", "the holder must be an active person of the tenant");
        }
        String id = Sql.newId();
        Instant now = Sql.dbNow(c, dialect);
        Jdbc.update(c, "INSERT INTO SEC_BREAK_GLASS(tenant_id, grant_id, token_class, holder_person_id, requested_by, reason, status,"
                        + " required_approvals, approvals, valid_minutes, token_id, requested_at, issued_at, expires_at,"
                        + " first_used_at, last_used_at, use_count, review_status, reviewed_by, review_outcome, review_notes,"
                        + " reviewed_at) VALUES (?,?,?,?,?,?,'REQUESTED',?,'[]',?,NULL,?,NULL,NULL,NULL,NULL,0,'NONE',NULL,NULL,NULL,NULL)",
                actor.tenantId(), id, k.name(), holderPersonId, actor.personId(), reason, k.approvals(), k.validMinutes(), now);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("grant_id", id);
        p.put("token_class", k.name());
        p.put("holder_person_id", holderPersonId);
        p.put("reason", reason);
        p.put("roles", List.copyOf(k.roles()));
        p.put("valid_minutes", k.validMinutes());
        audit.security(c, actor, SecurityEventType.BREAK_GLASS_REQUESTED, p);
        return find(c, actor.tenantId(), id).orElseThrow();
    }

    public Approval approve(Connection c, SecurityIdentity approver, String grantId) {
        authz.require(c, approver, ResourceRegistry.API_TOKEN, Permission.ADMIN, ResourceRegistry.ANY_ID);
        requireConfigured(c, approver.tenantId());
        Grant g = lock(c, approver.tenantId(), grantId);
        if (!"REQUESTED".equals(g.status())) {
            throw new SangamSecurityException.AdminConflict("BREAK_GLASS_NOT_PENDING", "the grant is " + g.status());
        }
        String me = approver.personId();
        if (me.equals(g.requestedBy()) || me.equals(g.holderPersonId())) {
            throw new SangamSecurityException.SodViolation("the requester and the holder cannot approve break-glass access");
        }
        if (g.approvals().contains(me)) {
            throw new SangamSecurityException.AdminConflict("ALREADY_APPROVED", "this approver has already approved");
        }
        // the class may have been changed or removed since the request: issue only what the config allows now
        TokenClass k = requireClass(c, approver.tenantId(), g.tokenClass());
        List<String> approvals = new ArrayList<>(g.approvals());
        approvals.add(me);
        Jdbc.update(c, "UPDATE SEC_BREAK_GLASS SET approvals=? WHERE tenant_id=? AND grant_id=?",
                Sql.toJson(approvals), approver.tenantId(), grantId);
        audit.security(c, approver, SecurityEventType.BREAK_GLASS_APPROVED, Map.of("grant_id", grantId,
                "approval", approvals.size(), "required", g.requiredApprovals()));
        if (approvals.size() < Math.max(g.requiredApprovals(), k.approvals())) {
            return new Approval(find(c, approver.tenantId(), grantId).orElseThrow(), Optional.empty());
        }
        Instant now = Sql.dbNow(c, dialect);
        int minutes = Math.min(g.validMinutes(), k.validMinutes());
        Instant expires = now.plusSeconds(60L * minutes);
        // decision 358: the roles come from the token class after dual approval, through the one break-glass issuer
        ApiTokenService.IssuedToken token = issuer.issue(c, approver, g.holderPersonId(), TOKEN_NAME_PREFIX + k.name(),
                k.roles(), expires);
        Jdbc.update(c, "UPDATE SEC_BREAK_GLASS SET status='ISSUED', token_id=?, issued_at=?, expires_at=?, valid_minutes=?"
                + " WHERE tenant_id=? AND grant_id=?", token.tokenId(), now, expires, minutes, approver.tenantId(), grantId);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("grant_id", grantId);
        p.put("token_id", token.tokenId());
        p.put("token_class", k.name());
        p.put("holder_person_id", g.holderPersonId());
        p.put("approvers", approvals);
        p.put("expires_at", expires);
        audit.security(c, approver, SecurityEventType.BREAK_GLASS_ISSUED, p);
        return new Approval(find(c, approver.tenantId(), grantId).orElseThrow(), Optional.of(token));
    }

    public Grant reject(Connection c, SecurityIdentity approver, String grantId, String reason) {
        authz.require(c, approver, ResourceRegistry.API_TOKEN, Permission.ADMIN, ResourceRegistry.ANY_ID);
        requireConfigured(c, approver.tenantId());
        Grant g = lock(c, approver.tenantId(), grantId);
        if (!"REQUESTED".equals(g.status())) {
            throw new SangamSecurityException.AdminConflict("BREAK_GLASS_NOT_PENDING", "the grant is " + g.status());
        }
        Jdbc.update(c, "UPDATE SEC_BREAK_GLASS SET status='REJECTED' WHERE tenant_id=? AND grant_id=?", approver.tenantId(), grantId);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("grant_id", grantId);
        p.put("reason", reason);
        audit.security(c, approver, SecurityEventType.BREAK_GLASS_REJECTED, p);
        return find(c, approver.tenantId(), grantId).orElseThrow();
    }

    /** Closes the review task of a used grant: {@code outcome} ACCEPTED or ESCALATED; the reviewer is not the holder. */
    public Grant review(Connection c, SecurityIdentity reviewer, String grantId, String outcome, String notes) {
        authz.require(c, reviewer, ResourceRegistry.API_TOKEN, Permission.ADMIN, ResourceRegistry.ANY_ID);
        if (!"ACCEPTED".equals(outcome) && !"ESCALATED".equals(outcome)) {
            throw new SangamSecurityException.AdminConflict("INVALID_OUTCOME", "outcome must be ACCEPTED or ESCALATED");
        }
        if (notes == null || notes.isBlank()) {
            throw new SangamSecurityException.AdminConflict("NOTES_REQUIRED", "review notes are required");
        }
        requireConfigured(c, reviewer.tenantId());
        Grant g = lock(c, reviewer.tenantId(), grantId);
        if (!"PENDING".equals(g.reviewStatus())) {
            throw new SangamSecurityException.AdminConflict("BREAK_GLASS_NO_REVIEW", "the grant has no pending review");
        }
        if (reviewer.personId().equals(g.holderPersonId())) {
            throw new SangamSecurityException.SodViolation("the holder cannot review their own break-glass use");
        }
        Instant now = Sql.dbNow(c, dialect);
        Jdbc.update(c, "UPDATE SEC_BREAK_GLASS SET review_status='DONE', reviewed_by=?, review_outcome=?, review_notes=?, reviewed_at=?"
                + " WHERE tenant_id=? AND grant_id=?", reviewer.personId(), outcome, notes, now, reviewer.tenantId(), grantId);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("grant_id", grantId);
        p.put("outcome", outcome);
        p.put("use_count", g.useCount());
        audit.security(c, reviewer, SecurityEventType.BREAK_GLASS_REVIEWED, p);
        return find(c, reviewer.tenantId(), grantId).orElseThrow();
    }

    /**
     * Records one use of a break-glass token (called by {@link ApiTokenService#authenticate} in its transaction for a
     * token whose name carries {@link #TOKEN_NAME_PREFIX}). Returns the grant id and class when the token belongs to a
     * grant, so the identity can carry them; empty for any other token.
     */
    public Optional<Map<String, String>> used(Connection c, String tenantId, String tokenId, SecurityIdentity holder) {
        Optional<String[]> row = Jdbc.one(c, "SELECT grant_id, token_class, use_count FROM SEC_BREAK_GLASS WHERE tenant_id=?"
                + " AND token_id=? AND status='ISSUED'", rs -> new String[]{rs.getString(1), rs.getString(2),
                Long.toString(rs.getLong(3))}, tenantId, tokenId);
        if (row.isEmpty()) {
            return Optional.empty();
        }
        String grantId = row.get()[0];
        Instant now = Sql.dbNow(c, dialect);
        Jdbc.update(c, "UPDATE SEC_BREAK_GLASS SET use_count=use_count+1, last_used_at=?,"
                + " first_used_at=COALESCE(first_used_at, ?),"
                + " review_status=CASE WHEN review_status='NONE' THEN 'PENDING' ELSE review_status END"
                + " WHERE tenant_id=? AND grant_id=?", now, now, tenantId, grantId);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("grant_id", grantId);
        p.put("token_id", tokenId);
        p.put("token_class", row.get()[1]);
        p.put("holder_person_id", holder.personId());
        if (holder.clientIp() != null) {
            p.put("client_ip", holder.clientIp());
        }
        p.put("use_count", Long.parseLong(row.get()[2]) + 1);
        p.put("alarm", true);
        audit.security(c, holder, SecurityEventType.BREAK_GLASS_USED, p);
        try {
            alarm.raised(tenantId, p);
        } catch (RuntimeException e) {
            // the audit event above is the durable record; a failing alarm sink never blocks the emergency access
            org.slf4j.LoggerFactory.getLogger(BreakGlassAlarm.LOGGER).error("break-glass alarm sink failed: {}", e.toString());
        }
        return Optional.of(Map.of(ATTR_GRANT, grantId, ATTR_TOKEN_CLASS, row.get()[1]));
    }

    // ------------------------------------------------------------------ reads

    public Optional<Grant> find(Connection c, String tenantId, String grantId) {
        return Jdbc.one(c, "SELECT " + COLUMNS + " FROM SEC_BREAK_GLASS WHERE tenant_id=? AND grant_id=?", BreakGlassService::map,
                tenantId, grantId);
    }

    /** Grants of the actor's tenant, newest first; {@code status}/{@code reviewStatus} null = any. {@code READ} on API_TOKEN. */
    public List<Grant> list(Connection c, SecurityIdentity actor, String status, String reviewStatus, int offset, int limit) {
        List<Object> params = new ArrayList<>();
        String where = listWhere(c, actor, status, reviewStatus, params);
        int size = Math.min(Math.max(limit, 1), org.sangam.security.policy.AuthorizedQueryBuilder.MAX_PAGE_SIZE);
        return Jdbc.query(c, dialect.page("SELECT " + COLUMNS + " FROM SEC_BREAK_GLASS" + where
                        + " ORDER BY requested_at DESC, grant_id", Math.max(offset, 0), size),
                BreakGlassService::map, params.toArray());
    }

    /** The number of grants {@link #list} pages over (same filter and checks). */
    public long count(Connection c, SecurityIdentity actor, String status, String reviewStatus) {
        List<Object> params = new ArrayList<>();
        String where = listWhere(c, actor, status, reviewStatus, params);
        return Jdbc.one(c, "SELECT COUNT(*) FROM SEC_BREAK_GLASS" + where, rs -> rs.getLong(1), params.toArray()).orElse(0L);
    }

    private String listWhere(Connection c, SecurityIdentity actor, String status, String reviewStatus, List<Object> params) {
        authz.require(c, actor, ResourceRegistry.API_TOKEN, Permission.READ, ResourceRegistry.ANY_ID);
        requireConfigured(c, actor.tenantId());
        StringBuilder where = new StringBuilder(" WHERE tenant_id=?");
        params.add(actor.tenantId());
        if (status != null) {
            where.append(" AND status=?");
            params.add(status);
        }
        if (reviewStatus != null) {
            where.append(" AND review_status=?");
            params.add(reviewStatus);
        }
        return where.toString();
    }

    private Grant lock(Connection c, String tenantId, String grantId) {
        Jdbc.one(c, dialect.lockingSelect("grant_id", "SEC_BREAK_GLASS", "tenant_id=? AND grant_id=?"), rs -> rs.getString(1),
                tenantId, grantId).orElseThrow(() -> new SangamSecurityException.AdminConflict("UNKNOWN_GRANT", "grant not found"));
        return find(c, tenantId, grantId).orElseThrow();
    }

    private static Grant map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Grant(rs.getString("grant_id"), rs.getString("token_class"), rs.getString("holder_person_id"),
                rs.getString("requested_by"), rs.getString("reason"), rs.getString("status"), rs.getInt("required_approvals"),
                Sql.jsonList(rs.getString("approvals")), rs.getInt("valid_minutes"), rs.getString("token_id"),
                Jdbc.instant(rs, "requested_at"), Jdbc.instant(rs, "issued_at"), Jdbc.instant(rs, "expires_at"),
                Jdbc.instant(rs, "first_used_at"), Jdbc.instant(rs, "last_used_at"), rs.getLong("use_count"),
                rs.getString("review_status"), rs.getString("reviewed_by"), rs.getString("review_outcome"),
                rs.getString("review_notes"), Jdbc.instant(rs, "reviewed_at"));
    }
}
