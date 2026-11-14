// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Set;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.sangam.rest.port.SangamPrincipal;
import org.sangam.server.config.SangamSecurityProperties;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Development-only static bearer token (ARCHITECTURE section 4: "dev profile uses a static token"). The token is compared
 * in constant time. The resulting principal is tenant-bound like any OIDC principal ({@code authMethod=DEV_TOKEN}).
 *
 * <p>The authentication is stored for the whole request, as Spring's bearer-token filter stores a JWT's: this filter
 * does not run again on the container's later dispatches of the same request (the error page, async), and without the
 * stored context such a dispatch was anonymous, so a real 404/409/422 reached the caller as 401 UNAUTHENTICATED
 * (approve-401).
 */
public final class DevTokenFilter extends OncePerRequestFilter {

    /** The stateless chain's context store (request attribute), shared by every authentication of the request. */
    private static final SecurityContextRepository CONTEXTS = new RequestAttributeSecurityContextRepository();

    private final byte[] token;
    private final SangamPrincipal principal;

    public DevTokenFilter(SangamSecurityProperties.DevToken cfg) {
        this.token = cfg.getToken().getBytes(StandardCharsets.UTF_8);
        this.principal = new SangamPrincipal(cfg.getTenant(), cfg.getPrincipal(), Set.copyOf(cfg.getGroups()),
                "DEV_TOKEN", Map.of("tenant", cfg.getTenant(), "sub", cfg.getPrincipal()));
    }

    /** True when the header carries exactly the dev token (used to keep it away from the JWT decoder). */
    public boolean matches(String bearer) {
        return bearer != null && MessageDigest.isEqual(token, bearer.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7) && matches(header.substring(7).trim())) {
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new SangamAuthenticationToken(principal, null));
            SecurityContextHolder.setContext(context);
            CONTEXTS.saveContext(context, request, response);
            request.setAttribute(SangamPrincipal.ATTRIBUTE, principal);
        }
        chain.doFilter(request, response);
    }
}
