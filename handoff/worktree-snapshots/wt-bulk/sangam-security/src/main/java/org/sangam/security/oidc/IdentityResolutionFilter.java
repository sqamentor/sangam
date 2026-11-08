// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.oidc;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.sangam.core.layer.Transactions;
import org.sangam.core.security.SangamSecurityException;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.security.audit.LoginEventBatcher;
import org.sangam.security.identity.PrincipalResolver;
import org.sangam.security.tenant.TenantContext;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Runs after authentication (JWT, API token or dev token): resolves the token identity to the full Sangam identity
 * ({@link PrincipalResolver}: tenant active, person linked or JIT-created, groups, roles, seats at the database time),
 * enforces MFA pass-through, binds it to {@link TenantContext} for the request and clears it afterwards. A login
 * event is batched once per token session ({@code sid}/{@code jti}) (X.2.3.3 rule 2).
 */
public final class IdentityResolutionFilter extends OncePerRequestFilter {

    private static final int SEEN_SESSIONS = 10_000;

    private final Transactions tx;
    private final PrincipalResolver resolver;
    private final KeycloakClaimsMapper mapper;
    private final SecurityProperties props;
    private final LoginEventBatcher logins;
    private final Set<String> seen = Collections.newSetFromMap(new LinkedHashMap<String, Boolean>(1024, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > SEEN_SESSIONS;
        }
    });

    public IdentityResolutionFilter(Transactions tx, PrincipalResolver resolver, KeycloakClaimsMapper mapper,
                                    SecurityProperties props, LoginEventBatcher logins) {
        this.tx = tx;
        this.resolver = resolver;
        this.mapper = mapper;
        this.props = props;
        this.logins = logins;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof SangamAuthenticationToken token)) {
            chain.doFilter(req, res);
            return;
        }
        SecurityIdentity resolved;
        try {
            SecurityIdentity raw = SecurityWebSupport.withClientIp(token.identity(), SecurityWebSupport.clientIp(req));
            if ("OIDC".equals(raw.authMethod())) {
                mapper.requireMfaIfConfigured(raw);
            }
            boolean jit = props.getJitProvisioningTenants().contains(raw.tenantId());
            String name = raw.attributes().getOrDefault("name", raw.attributes().get("preferred_username"));
            String email = raw.attributes().get("email");
            resolved = token.resolved() ? raw : tx.inTransaction(c -> resolver.resolve(c, raw, jit, name, email));
        } catch (SangamSecurityException e) {
            if (logins != null) {
                logins.failed(token.identity().tenantId(), token.identity().subject(), token.identity().authMethod(),
                        SecurityWebSupport.clientIp(req), e.code());
            }
            SecurityContextHolder.clearContext();
            SecurityWebSupport.problem(res, e);
            return;
        }
        SecurityContextHolder.getContext().setAuthentication(new SangamAuthenticationToken(resolved, token.getCredentials(), true));
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
        TenantContext.set(resolved);
        try {
            chain.doFilter(req, res);
        } finally {
            TenantContext.clear();
        }
    }
}
