// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.oidc;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.sangam.core.security.SangamSecurityException;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.security.support.Sql;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Small web helpers shared by the security filters. */
public final class SecurityWebSupport {

    private SecurityWebSupport() {
    }

    /** Bearer token from the {@code Authorization} header, or null. */
    public static String bearer(HttpServletRequest req) {
        String h = req.getHeader("Authorization");
        if (h == null || !h.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String t = h.substring(7).trim();
        return t.isEmpty() ? null : t;
    }

    /** Client address; {@code X-Forwarded-For} is honoured only when the server enables forwarded headers upstream. */
    public static String clientIp(HttpServletRequest req) {
        return req.getRemoteAddr();
    }

    /** RFC 9457 problem document; no SQL or stack trace (R28). */
    public static void problem(HttpServletResponse res, int status, String code, String detail) throws IOException {
        if (res.isCommitted()) {
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "https://sangam.dev/problems/" + code.toLowerCase().replace('_', '-'));
        body.put("title", code);
        body.put("status", status);
        body.put("detail", detail);
        body.put("code", code);
        res.setStatus(status);
        res.setContentType("application/problem+json");
        res.setCharacterEncoding(StandardCharsets.UTF_8.name());
        res.getWriter().write(Sql.toJson(body));
    }

    public static void problem(HttpServletResponse res, SangamSecurityException e) throws IOException {
        problem(res, e.httpStatus(), e.code(), e.getMessage());
    }

    public static SecurityIdentity withClientIp(SecurityIdentity id, String ip) {
        return new SecurityIdentity(id.tenantId(), id.personId(), id.subject(), id.groups(), id.roles(), id.seats(),
                id.actingForSeats(), id.authMethod(), ip, id.onBehalfOf(), id.system(), id.attributes());
    }

    /** Resolves only JWTs: Sangam API tokens and dev static tokens are left to their own filters. */
    public static BearerTokenResolver jwtOnlyResolver(SecurityProperties props) {
        DefaultBearerTokenResolver delegate = new DefaultBearerTokenResolver();
        return req -> {
            String t = delegate.resolve(req);
            if (t == null || t.startsWith("sgm_") || (props.devTokensActive() && props.getDevTokens().containsKey(t))) {
                return null;
            }
            return t;
        };
    }

    /** JWT → token identity (unresolved). A mapping failure becomes an authentication failure. */
    public static Converter<Jwt, AbstractAuthenticationToken> jwtConverter(KeycloakClaimsMapper mapper) {
        return jwt -> {
            try {
                return new SangamAuthenticationToken(mapper.map(jwt.getClaims(), null), jwt, false);
            } catch (SangamSecurityException e) {
                throw new org.springframework.security.oauth2.server.resource.InvalidBearerTokenException(e.code() + ": " + e.getMessage());
            }
        };
    }
}
