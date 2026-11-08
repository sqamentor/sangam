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
import org.sangam.security.SangamSecurity;
import org.sangam.security.audit.LoginEventBatcher;
import org.sangam.security.oidc.SangamAuthenticationToken;
import org.sangam.security.oidc.SecurityProperties;
import org.sangam.security.tenant.TenantContext;
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
 *       {@link SecurityIdentities#ATTRIBUTE}; first sight of a session writes a batched {@code LOGIN} event.</li>
 * </ul>
 * A {@link SangamSecurityException} (unknown person, MFA missing, tenant suspended) is answered with problem+json.
 * Runs after the server's {@code PrincipalAttributeFilter}. Never registered as a servlet filter bean: it is added
 * to the security chain only.
 */
public final class SecurityIdentityFilter extends OncePerRequestFilter {

    private static final int SEEN_SESSIONS = 10_000;

    private final Transactions tx;
    private final SangamSecurity security;
    private final SecurityProperties props;
    private final LoginEventBatcher logins;
    private final Set<String> seen = Collections.newSetFromMap(new LinkedHashMap<>(1024, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > SEEN_SESSIONS;
        }
    });

    public SecurityIdentityFilter(Transactions tx, SangamSecurity security, SecurityProperties props,
                                  LoginEventBatcher logins) {
        this.tx = tx;
        this.security = security;
        this.props = props;
        this.logins = logins;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        SecurityIdentity raw;
        boolean resolvedAlready = false;
        if (auth instanceof SangamAuthenticationToken token) {
            // API token (sangam-security): its identity is already the owner's, tenant-bound and scoped.
            raw = token.identity();
            resolvedAlready = token.resolved();
            if (!(req.getAttribute(SangamPrincipal.ATTRIBUTE) instanceof SangamPrincipal)) {
                req.setAttribute(SangamPrincipal.ATTRIBUTE, SecurityIdentities.toPrincipal(raw));
            }
        } else if (req.getAttribute(SangamPrincipal.ATTRIBUTE) instanceof SangamPrincipal p) {
            raw = SecurityIdentities.fromPrincipal(p, req.getRemoteAddr());
        } else {
            chain.doFilter(req, res);
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
                SecurityIdentity input = raw;
                resolved = tx.inTransaction(c -> security.principals().resolve(c, input, jit, name, email));
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
        TenantContext.set(resolved);
        try {
            chain.doFilter(req, res);
        } finally {
            TenantContext.clear();
        }
    }
}
