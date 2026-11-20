// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.sangam.core.layer.Transactions;
import org.sangam.core.security.SangamSecurityException;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.rest.port.SangamPrincipal;
import org.sangam.rest.port.SeatHoldings;
import org.sangam.security.SangamSecurity;
import org.sangam.security.audit.LoginEventBatcher;
import org.sangam.security.spring.SangamAuthenticationToken;
import org.sangam.security.oidc.SecurityProperties;
import org.sangam.security.tenant.TenantContext;
import org.sangam.server.security.PlatformIssuers;
import org.sangam.server.security.ProblemResponses;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Resolves the caller against sangam-security's organisation model for every authenticated request (the server-side
 * counterpart of sangam-security's {@code IdentityResolutionFilter}, adapted to the server's single filter chain):
 * <ul>
 *   <li>an API token authenticated by sangam-security's {@code ApiTokenFilter} becomes the {@link SangamPrincipal}
 *       request attribute the REST layer reads (R26, K14);</li>
 *   <li>a JWT or dev-token principal is resolved through {@code PrincipalResolver} (person, seats held on the date,
 *       acting-for seats, roles, memberships; JIT provisioning for the tenants in
 *       {@code sangam.security.jit-provisioning-tenants}); MFA is required where configured;</li>
 *   <li>the resolved identity is bound with {@link TenantContext} for the request and also put on the request as
 *       {@link SecurityIdentities#ATTRIBUTE}; first sight of a session writes a batched {@code LOGIN} event;</li>
 *   <li>the seats of the resolved identity are put on the request as {@link SeatHoldings#ATTRIBUTE}: the only source
 *       of the seat context sangam-rest accepts ({@code X-Sangam-Seat}, {@code X-Sangam-Acting-For-Seat}; a seat the
 *       person does not hold now is 403 {@code SEAT_NOT_HELD}; IX.5 rules 1 and 4, K7). An API token holds no
 *       seat.</li>
 * </ul>
 * A {@link SangamSecurityException} (unknown person, MFA missing, tenant suspended) is answered with problem+json.
 * SL-70: an API token is always a tenant identity, and a tenant identity keeps no platform-only group or role, also
 * none from the organisation model ({@link PlatformIssuers#tenant(SecurityIdentity)}). Runs after the server's
 * {@code PrincipalAttributeFilter}. Never registered as a servlet filter bean: it is added
 * to the security chain only.
 */
public final class SecurityIdentityFilter extends OncePerRequestFilter {

    private static final int SEEN_SESSIONS = 10_000;

    private final Transactions tx;
    private final SangamSecurity security;
    private final SecurityProperties props;
    private final LoginEventBatcher logins;
    private final org.sangam.security.identity.ResolvedIdentityCache identities;
    private final Set<String> seen = Collections.newSetFromMap(new LinkedHashMap<>(1024, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > SEEN_SESSIONS;
        }
    });

    /** Decision 152: whether development identities (static development tokens) are accepted at all. */
    private final boolean developmentIdentitiesAllowed;

    /**
     * @param developmentIdentitiesAllowed true only in a validated development environment (decision 143,
     *                                     {@code ProductionEnvironment.developmentDefaultsAllowed}); otherwise every
     *                                     development identity, on any authentication path, is refused with 401
     */
    public SecurityIdentityFilter(Transactions tx, SangamSecurity security, SecurityProperties props,
                                  LoginEventBatcher logins, boolean developmentIdentitiesAllowed) {
        this.developmentIdentitiesAllowed = developmentIdentitiesAllowed;
        this.tx = tx;
        this.security = security;
        this.props = props;
        this.logins = logins;
        // SG-15: exact per-token cache, validated against the database on every request (see the class)
        this.identities = new org.sangam.security.identity.ResolvedIdentityCache(security.dialect(), security.principals());
    }

    /** Problem code of a refused development identity (decision 152). */
    public static final String DEVELOPMENT_IDENTITY_REFUSED = "DEVELOPMENT_IDENTITY_REFUSED";

    /** Problem code of an identity whose tenant is a reserved id (decision 144). */
    public static final String RESERVED_TENANT_REFUSED = "RESERVED_TENANT_REFUSED";

    /**
     * Decision 144: an authenticated identity whose tenant is the shared key or a system audit scope
     * ({@link org.sangam.core.tenant.SystemAuditScope#isReserved}). The system identity is internal and never arrives
     * here from a token.
     */
    public static boolean reservedTenant(SecurityIdentity raw) {
        return raw != null && org.sangam.core.tenant.SystemAuditScope.isReserved(raw.tenantId());
    }

    /** A static development token's identity, or any token naming the development token issuer. */
    public static boolean isDevelopmentIssued(SecurityIdentity raw) {
        return org.sangam.security.identity.PrincipalResolver.isDevelopmentIdentity(raw)
                || (raw.attributes() != null && PlatformIssuers.DEV_TOKEN_ISSUER.equals(raw.attributes().get("iss")));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        SecurityIdentity raw;
        boolean resolvedAlready = false;
        boolean platform = false;
        if (auth instanceof SangamAuthenticationToken token) {
            // API token (sangam-security): its identity is already the owner's, tenant-bound and scoped; SL-70: an API
            // token is issued for one tenant, so it is never a platform identity.
            raw = PlatformIssuers.tenant(token.identity());
            resolvedAlready = token.resolved();
            if (!(req.getAttribute(SangamPrincipal.ATTRIBUTE) instanceof SangamPrincipal)) {
                req.setAttribute(SangamPrincipal.ATTRIBUTE, SecurityIdentities.toPrincipal(raw));
            }
        } else if (req.getAttribute(SangamPrincipal.ATTRIBUTE) instanceof SangamPrincipal p) {
            raw = SecurityIdentities.fromPrincipal(p, req.getRemoteAddr());
            // the JWT / dev-token groups were confined by issuer when the principal was built (JwtPrincipalConverter)
            platform = PlatformIssuers.isPlatformIdentity(p.groups());
        } else {
            chain.doFilter(req, res);
            return;
        }
        if (!developmentIdentitiesAllowed && isDevelopmentIssued(raw)) {
            // decision 152: production (and staging) reject development-issued identities on any path
            if (logins != null) {
                logins.failed(raw.tenantId(), raw.subject(), raw.authMethod(), req.getRemoteAddr(),
                        DEVELOPMENT_IDENTITY_REFUSED);
            }
            ProblemResponses.write(req, res, 401, DEVELOPMENT_IDENTITY_REFUSED, "Authentication refused",
                    "development identities are accepted only in a development environment");
            return;
        }
        if (reservedTenant(raw)) {
            // decision 144: the shared key and every system audit scope are never a caller's tenant, so no request
            // runs as (or reads audit of) the system scope through a tenant path. No login event: it would be written
            // into the reserved scope itself; the refusal is logged without the tenant value.
            org.slf4j.LoggerFactory.getLogger(SecurityIdentityFilter.class).warn(
                    "{}: {} identity with a reserved tenant id refused", RESERVED_TENANT_REFUSED, raw.authMethod());
            ProblemResponses.write(req, res, 401, RESERVED_TENANT_REFUSED, "Authentication refused",
                    "the identity's tenant is a reserved id");
            return;
        }
        SecurityIdentity resolved;
        try {
            if (resolvedAlready || "API_KEY".equals(raw.authMethod())) {
                resolved = raw;
            } else {
                if ("OIDC".equals(raw.authMethod())) {
                    new org.sangam.security.oidc.KeycloakClaimsMapper(props).requireMfaIfConfigured(raw);
                }
                boolean jit = props.getJitProvisioningTenants().contains(raw.tenantId());
                String name = raw.attributes().getOrDefault("name", raw.attributes().get("preferred_username"));
                String email = raw.attributes().get("email");
                resolved = identities.resolve(tx, raw, jit, name, email);
            }
            if (!platform) {
                // SL-70: memberships and role bindings of the organisation model cannot make a tenant identity platform
                resolved = PlatformIssuers.tenant(resolved);
            }
        } catch (SangamSecurityException e) {
            if (logins != null) {
                logins.failed(raw.tenantId(), raw.subject(), raw.authMethod(), req.getRemoteAddr(), e.code());
            }
            ProblemResponses.write(req, res, e.httpStatus(), e.code(), "Authentication refused", e.getMessage());
            return;
        }
        if (logins != null) {
            String session = resolved.tenantId() + "|" + resolved.subject() + "|"
                    + resolved.attributes().getOrDefault("sid", resolved.attributes().getOrDefault("jti", ""));
            boolean first;
            synchronized (seen) {
                first = seen.add(session);
            }
            if (first) {
                logins.loggedIn(resolved);
            }
        }
        req.setAttribute(SecurityIdentities.ATTRIBUTE, resolved);
        // IX.5 rules 1 and 4, K7: the seats this person can act in now, from the postings, additional charge and leave
        // in force at the database time (PostingService.seatsOf, re-read whenever one of them changed or reached an
        // effective date). The request context accepts X-Sangam-Seat / X-Sangam-Acting-For-Seat only from here.
        req.setAttribute(SeatHoldings.ATTRIBUTE, new SeatHoldings(resolved.seats(), resolved.actingForSeats()));
        TenantContext.set(resolved);
        try {
            chain.doFilter(req, res);
        } finally {
            TenantContext.clear();
        }
    }
}
