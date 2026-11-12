// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.oidc;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.sangam.core.layer.Transactions;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.security.admin.ApiTokenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

/**
 * Non-JWT bearer authentication: Sangam API tokens ({@code sgm_...}, FLOWABLE-INTO-OPERATON FC-42, R26 worker principals) and, in the dev
 * profile only, static development tokens (ARCHITECTURE.md §4 "dev profile uses a static token").
 *
 * <p>Both store the authentication for the whole request, as Spring's bearer-token filter stores a JWT's. They do not
 * run again on the container's later dispatches of the same request (the error page, async); without the stored
 * context such a dispatch was anonymous, so a real 404/409/422 reached an API-token caller as 401 UNAUTHENTICATED.
 */
public final class TokenAuthenticationFilters {

    /** The stateless chain's context store (request attribute), shared by every authentication of the request. */
    private static final SecurityContextRepository CONTEXTS = new RequestAttributeSecurityContextRepository();

    private TokenAuthenticationFilters() {
    }

    private static void authenticate(Authentication authentication, HttpServletRequest req, HttpServletResponse res) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        CONTEXTS.saveContext(context, req, res);
    }

    /** {@code Authorization: Bearer sgm_<id>.<secret>}. */
    public static final class ApiTokenFilter extends OncePerRequestFilter {
        private final Transactions tx;
        private final ApiTokenService tokens;

        public ApiTokenFilter(Transactions tx, ApiTokenService tokens) {
            this.tx = tx;
            this.tokens = tokens;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                throws ServletException, IOException {
            String t = SecurityWebSupport.bearer(req);
            if (t == null || !t.startsWith(ApiTokenService.PREFIX)) {
                chain.doFilter(req, res);
                return;
            }
            String ip = SecurityWebSupport.clientIp(req);
            Optional<SecurityIdentity> id = tx.inTransaction(c -> tokens.authenticate(c, t, ip));
            if (id.isEmpty()) {
                SecurityWebSupport.problem(res, 401, "UNAUTHENTICATED", "invalid, expired or revoked API token");
                return;
            }
            authenticate(new SangamAuthenticationToken(id.get(), null, false), req, res);
            chain.doFilter(req, res);
        }
    }

    /** Dev profile: a configured static token maps to a fixed identity. Refuses to run on a production profile. */
    public static final class DevStaticTokenFilter extends OncePerRequestFilter {
        private static final Logger LOG = LoggerFactory.getLogger(DevStaticTokenFilter.class);
        private final SecurityProperties props;

        public DevStaticTokenFilter(SecurityProperties props) {
            if (props.isProduction()) {
                throw new IllegalStateException("static development tokens cannot be enabled on a production profile");
            }
            this.props = props;
            LOG.warn("DEV MODE: static bearer tokens are accepted ({} configured). Never use this profile in production.",
                    props.getDevTokens().size());
        }

        @Override
        protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                throws ServletException, IOException {
            String t = SecurityWebSupport.bearer(req);
            if (t != null && props.devTokensActive() && props.getDevTokens().containsKey(t)) {
                SecurityProperties.DevToken d = SecurityProperties.DevToken.parse(props.getDevTokens().get(t));
                SecurityIdentity id = new SecurityIdentity(d.tenant(), d.person(), d.person(), d.groups(), d.roles(),
                        java.util.Set.of(), java.util.Set.of(), "DEV_STATIC", SecurityWebSupport.clientIp(req), null, false,
                        Map.of());
                authenticate(new SangamAuthenticationToken(id, null, false), req, res);
            }
            chain.doFilter(req, res);
        }
    }
}
