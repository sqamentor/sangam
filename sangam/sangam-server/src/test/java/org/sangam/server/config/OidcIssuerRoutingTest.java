// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import org.junit.jupiter.api.Test;
import org.sangam.server.security.PlatformIssuers;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * SL-107 (conformance audit): direct unit tests of {@link OidcConfig.IssuerRouting} and the SL-70 start-up checks of
 * {@link OidcConfig#sangamJwtDecoder}. A token whose {@code iss} is a platform issuer is decoded only by that issuer's
 * decoder; every other token by the tenant decoder; a platform issuer without an OIDC decoder (the dev token's) and a
 * malformed token are refused.
 */
class OidcIssuerRoutingTest {

    static final String TENANT = "https://idp.example/realms/tenants";
    static final String PLATFORM = "https://idp.example/realms/platform";

    /** A recording decoder: remembers the tokens it was given and answers a Jwt naming itself. */
    static final class Recording implements JwtDecoder {
        final String name;
        final List<String> seen = new ArrayList<>();

        Recording(String name) {
            this.name = name;
        }

        @Override
        public Jwt decode(String token) {
            seen.add(token);
            return Jwt.withTokenValue(token).header("alg", "none").claim("decodedBy", name)
                    .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        }
    }

    static String token(String issuer) {
        JWTClaimsSet.Builder b = new JWTClaimsSet.Builder().subject("alice");
        if (issuer != null) {
            b.issuer(issuer);
        }
        return new PlainJWT(b.build()).serialize();
    }

    @Test
    void eachTokenGoesToTheDecoderOfItsIssuer() {
        Recording tenant = new Recording("tenant");
        Recording platform = new Recording("platform");
        OidcConfig.IssuerRouting r = new OidcConfig.IssuerRouting(tenant,
                new PlatformIssuers(List.of(PLATFORM, PlatformIssuers.DEV_TOKEN_ISSUER)), Map.of(PLATFORM, platform));
        assertEquals("platform", r.decode(token(PLATFORM)).getClaimAsString("decodedBy"));
        assertEquals("tenant", r.decode(token(TENANT)).getClaimAsString("decodedBy"));
        assertEquals("tenant", r.decode(token(null)).getClaimAsString("decodedBy"), "no iss: the tenant decoder decides");
        assertEquals("tenant", r.decode(token(PLATFORM + "/")).getClaimAsString("decodedBy"), "issuers compare exactly");
        assertEquals(1, platform.seen.size());
        assertEquals(3, tenant.seen.size());
    }

    @Test
    void aPlatformIssuerWithoutAnOidcDecoderAndAMalformedTokenAreRefused() {
        Recording tenant = new Recording("tenant");
        OidcConfig.IssuerRouting r = new OidcConfig.IssuerRouting(tenant,
                new PlatformIssuers(List.of(PlatformIssuers.DEV_TOKEN_ISSUER)), Map.of());
        assertThrows(BadJwtException.class, () -> r.decode(token(PlatformIssuers.DEV_TOKEN_ISSUER)),
                "a JWT claiming the dev token's issuer");
        assertThrows(BadJwtException.class, () -> r.decode("not-a-jwt"));
        assertTrue(tenant.seen.isEmpty(), "neither reached the tenant decoder");
    }

    private static SangamSecurityProperties props(String... platformIssuers) {
        SangamSecurityProperties p = new SangamSecurityProperties();
        p.setPlatformIssuer(new ArrayList<>(List.of(platformIssuers)));
        return p;
    }

    @Test
    void withoutAPlatformIssuerTheTenantDecoderIsUsedAsBefore() {
        JwtDecoder d = new OidcConfig().sangamJwtDecoder(new MockEnvironment().withProperty(OidcConfig.ISSUER, TENANT),
                props());
        assertTrue(!(d instanceof OidcConfig.IssuerRouting), d.getClass().getName());
    }

    @Test
    void withAPlatformIssuerTheDecoderRoutes() {
        JwtDecoder d = new OidcConfig().sangamJwtDecoder(new MockEnvironment().withProperty(OidcConfig.ISSUER, TENANT),
                props(PLATFORM));
        assertInstanceOf(OidcConfig.IssuerRouting.class, d);
        assertNotSame(null, d);
    }

    @Test
    void aPlatformIssuerEqualToTheTenantIssuerIsRefusedAtStartup() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> new OidcConfig().sangamJwtDecoder(
                new MockEnvironment().withProperty(OidcConfig.ISSUER, TENANT), props(" " + TENANT + " ")));
        assertTrue(e.getMessage().contains("separate issuer"), e.getMessage());
    }

    @Test
    void aPlatformJwksNeedsExactlyOnePlatformIssuer() {
        SangamSecurityProperties p = props(PLATFORM, PLATFORM + "-2");
        p.setPlatformJwkSetUri("https://idp.example/realms/platform/protocol/openid-connect/certs");
        assertThrows(IllegalStateException.class, () -> new OidcConfig().sangamJwtDecoder(
                new MockEnvironment().withProperty(OidcConfig.ISSUER, TENANT), p));
        SangamSecurityProperties one = props(PLATFORM);
        one.setPlatformJwkSetUri("https://idp.example/realms/platform/protocol/openid-connect/certs");
        assertSame(OidcConfig.IssuerRouting.class, new OidcConfig().sangamJwtDecoder(
                new MockEnvironment().withProperty(OidcConfig.ISSUER, TENANT), one).getClass());
    }
}
