// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.sangam.core.security.SangamSecurityException;
import org.sangam.rest.port.SangamPrincipal;
import org.sangam.security.oidc.ClaimsProfile;
import org.sangam.security.oidc.ClaimsProfileMapper;
import org.sangam.security.oidc.KeycloakClaimsMapper;
import org.sangam.server.config.SangamSecurityProperties;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Maps an OIDC JWT to a {@link SangamPrincipal}. The tenant comes only from the token claim (VI.4 rule 6, K1); a token
 * without it is rejected with 401 and there is <b>no</b> default-tenant fallback. Roles/groups: the configured roles
 * claim (default {@code roles}), else {@code groups}, else Keycloak {@code realm_access.roles}. The seats the token
 * names (claim default {@code seats}) are put in {@code claims["seats"]} as a list of strings; effective dating and
 * additional charge are resolved server-side (sangam-security), the token list only limits {@code X-Sangam-Seat}.
 *
 * <p>SL-70: the groups are confined by the verified issuer ({@link PlatformIssuers}): only a platform issuer's token is
 * a platform identity; in every other token {@code sangam-admin} is the tenant administrator of its tenant and the
 * platform-only groups are removed.
 */
public final class JwtPrincipalConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    public static final String SEATS = "seats";

    private final SangamSecurityProperties.Claims claims;
    private final PlatformIssuers platform;
    /** M-077: the configured claims profile, or null (none: principal claim and roles as configured). */
    private final ClaimsProfile profile;
    private final Collection<String> mfaAcrValues;

    /** Without a platform issuer policy: every token is a tenant identity (fail closed). */
    public JwtPrincipalConverter(SangamSecurityProperties.Claims claims) {
        this(claims, PlatformIssuers.none());
    }

    public JwtPrincipalConverter(SangamSecurityProperties.Claims claims, PlatformIssuers platform) {
        this(claims, platform, null, List.of());
    }

    /**
     * M-077: with a claims profile ({@code sangam.security.claims-profile.*}) the profile decides the principal (the
     * link key: {@code sub}, a named claim, or the verified e-mail in an allowed domain), whether the token's roles and
     * groups are kept, and whether multi-factor is required (its {@code acr} in {@code mfaAcrValues} or a second factor
     * in its {@code amr}). A refused link or a missing second factor is {@code 401}. The IdP's own {@code sub} and the
     * profile name are kept in the claims {@value ClaimsProfileMapper#ATTR_IDP_SUBJECT} and
     * {@value ClaimsProfileMapper#ATTR_PROFILE}. {@code profile == null} is the behaviour without a profile.
     */
    public JwtPrincipalConverter(SangamSecurityProperties.Claims claims, PlatformIssuers platform, ClaimsProfile profile,
                                 Collection<String> mfaAcrValues) {
        this.claims = claims;
        this.platform = platform == null ? PlatformIssuers.none() : platform;
        this.profile = profile;
        this.mfaAcrValues = mfaAcrValues == null ? List.of() : List.copyOf(mfaAcrValues);
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        String tenant = jwt.getClaimAsString(claims.getTenant());
        if (tenant == null || tenant.isBlank()) {
            throw new OAuth2AuthenticationException(new OAuth2Error("invalid_token",
                    "The token has no '" + claims.getTenant() + "' claim; Sangam requires a tenant-bound identity", null));
        }
        String principal;
        if (profile == null) {
            principal = jwt.getClaimAsString(claims.getPrincipal());
            if (principal == null || principal.isBlank()) {
                principal = jwt.getSubject();
            }
        } else {
            principal = profiled(jwt);
        }
        Map<String, Object> all = new LinkedHashMap<>();
        jwt.getClaims().forEach((k, v) -> {
            if (k != null && v != null) {
                all.put(k, v);
            }
        });
        if (profile != null) {
            if (jwt.getSubject() != null) {
                all.put(ClaimsProfileMapper.ATTR_IDP_SUBJECT, jwt.getSubject());
            }
            all.put(ClaimsProfileMapper.ATTR_PROFILE, profile.name());
        }
        Object seatClaim = jwt.getClaims().get(claims.getSeats());
        if (seatClaim != null) {
            all.put(SEATS, strings(seatClaim));
        } else {
            all.remove(SEATS);
        }
        SangamPrincipal p = new SangamPrincipal(tenant, principal,
                platform.groups(jwt.getClaimAsString("iss"), roles(jwt)), "OIDC", all);
        return new SangamAuthenticationToken(p, jwt);
    }

    /** The profile's link key, after its multi-factor check; a refusal is a 401 with the profile's code. */
    private String profiled(Jwt jwt) {
        try {
            String link = profile.linkKey(jwt.getClaims(), jwt.getSubject());
            if (link == null || link.isBlank()) {
                throw new SangamSecurityException.Unauthenticated("LINK_CLAIM_MISSING", "the sign-in carries no subject");
            }
            if (profile.requireMfa() && !KeycloakClaimsMapper.mfaShown(jwt.getClaims(), mfaAcrValues)) {
                throw new SangamSecurityException.Unauthenticated("MFA_REQUIRED",
                        "this sign-in requires multi-factor authentication");
            }
            return link;
        } catch (SangamSecurityException e) {
            throw new OAuth2AuthenticationException(new OAuth2Error("invalid_token", e.code() + ": " + e.getMessage(), null));
        }
    }

    /** Roles, else groups, else Keycloak realm roles; a claims profile can drop the roles, the groups or both. */
    private Set<String> roles(Jwt jwt) {
        boolean keepRoles = profile == null || profile.idpRoles();
        boolean keepGroups = profile == null || profile.idpGroups();
        Set<String> out = new LinkedHashSet<>();
        if (keepRoles) {
            addAll(out, jwt.getClaims().get(claims.getRoles()));
        }
        if (out.isEmpty() && keepGroups) {
            addAll(out, jwt.getClaims().get("groups"));
        }
        if (out.isEmpty() && keepRoles && jwt.getClaims().get("realm_access") instanceof Map<?, ?> realm) {
            addAll(out, realm.get("roles"));
        }
        return out;
    }

    private static List<String> strings(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof Collection<?> c) {
            c.stream().filter(Objects::nonNull).map(String::valueOf).forEach(out::add);
        } else if (value instanceof String s && !s.isBlank()) {
            for (String x : s.split("[,\\s]+")) {
                out.add(x);
            }
        }
        return List.copyOf(out);
    }

    private static void addAll(Set<String> out, Object value) {
        for (String g : strings(value)) {
            out.add(g.startsWith("/") ? g.substring(1) : g); // Keycloak group paths "/name"
        }
    }
}
