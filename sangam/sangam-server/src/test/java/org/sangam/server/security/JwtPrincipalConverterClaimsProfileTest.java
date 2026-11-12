// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.sangam.rest.port.SangamPrincipal;
import org.sangam.security.oidc.ClaimsProfile;
import org.sangam.security.oidc.ClaimsProfileMapper;
import org.sangam.server.config.SangamSecurityProperties;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * M-077 server token path: the claims profile ({@code sangam.security.claims-profile.*}) decides the principal, keeps
 * or drops the token's roles and groups, and requires multi-factor; without a profile the converter is unchanged.
 * Profile settings come from configuration only (SL-104: the domain below is a test value). Written for the test
 * phase (not run here).
 */
class JwtPrincipalConverterClaimsProfileTest {

    static final List<String> ACR = List.of("mfa", "aal2");

    static Jwt jwt(Map<String, Object> extra) {
        Jwt.Builder b = Jwt.withTokenValue("t").header("alg", "RS256").issuer("https://idp.test/realms/t")
                .subject("idp-sub-1").claim("tenant", "t1").claim("preferred_username", "alice")
                .claim("roles", List.of("sangam-user")).claim("groups", List.of("/clerks"))
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        extra.forEach(b::claim);
        return b.build();
    }

    static SangamPrincipal principal(JwtPrincipalConverter c, Jwt jwt) {
        return ((SangamAuthenticationToken) c.convert(jwt)).sangamPrincipal();
    }

    static JwtPrincipalConverter converter(Map<String, String> profile) {
        SangamSecurityProperties props = new SangamSecurityProperties();
        props.setClaimsProfile(new LinkedHashMap<>(profile));
        return new JwtPrincipalConverter(props.getClaims(), PlatformIssuers.none(), props.claimsProfileOrNull(), ACR);
    }

    static Map<String, String> verifiedEmail() {
        return Map.of("preset", "verified-email", "name", "gov-sso", "email-domains", "dept.test");
    }

    @Test
    void withoutAProfileTheConverterIsUnchanged() {
        SangamSecurityProperties props = new SangamSecurityProperties();
        assertNull(props.claimsProfileOrNull());
        SangamPrincipal p = principal(new JwtPrincipalConverter(props.getClaims(), PlatformIssuers.none(),
                props.claimsProfileOrNull(), props.getMfaAcrValues()), jwt(Map.of()));
        SangamPrincipal before = principal(new JwtPrincipalConverter(props.getClaims(), PlatformIssuers.none()), jwt(Map.of()));
        assertEquals("alice", p.principalId());
        assertEquals(before.principalId(), p.principalId());
        assertEquals(before.groups(), p.groups());
        assertTrue(!p.claims().containsKey(ClaimsProfileMapper.ATTR_PROFILE));
    }

    @Test
    void kebabCaseConfigurationKeysAreRead() {
        ClaimsProfile p = ClaimsProfile.of(verifiedEmail());
        assertEquals(List.of("dept.test"), p.emailDomains());
        assertEquals(ClaimsProfile.of(Map.of("preset", "verified-email", "name", "gov-sso", "emailDomains", "dept.test")), p);
    }

    @Test
    void theVerifiedEmailInTheDomainIsThePrincipalAndTokenRolesAreDropped() {
        SangamPrincipal p = principal(converter(verifiedEmail()),
                jwt(Map.of("email", "Asha@Sub.Dept.Test", "email_verified", true, "amr", List.of("pwd", "otp"))));
        assertEquals("asha@sub.dept.test", p.principalId());
        assertEquals("idp-sub-1", p.claims().get(ClaimsProfileMapper.ATTR_IDP_SUBJECT));
        assertEquals("gov-sso", p.claims().get(ClaimsProfileMapper.ATTR_PROFILE));
        assertEquals(Set.of(), p.groups(), "verified-email is postings-only: no roles or groups from the token");
        // the preset requires multi-factor
        assertCode("MFA_REQUIRED", converter(verifiedEmail()),
                jwt(Map.of("email", "asha@dept.test", "email_verified", true, "amr", List.of("pwd"))));
    }

    @Test
    void refusedLinksAreUnauthenticatedWithTheProfileCode() {
        JwtPrincipalConverter c = converter(verifiedEmail());
        Map<String, Object> mfa = Map.of("acr", "mfa");
        assertCode("EMAIL_CLAIM_MISSING", c, jwt(mfa));
        assertCode("EMAIL_NOT_VERIFIED", c, jwt(Map.of("acr", "mfa", "email", "a@dept.test", "email_verified", false)));
        assertCode("EMAIL_DOMAIN_REFUSED", c, jwt(Map.of("acr", "mfa", "email", "a@other.test", "email_verified", true)));
        assertCode("EMAIL_DOMAIN_REFUSED", c, jwt(Map.of("acr", "mfa", "email", "a@notdept.test", "email_verified", true)));
        JwtPrincipalConverter byClaim = converter(Map.of("link-by", "claim", "link-claim", "person_id"));
        assertCode("LINK_CLAIM_MISSING", byClaim, jwt(Map.of()));
        assertEquals("P-42", principal(byClaim, jwt(Map.of("person_id", "P-42"))).principalId());
    }

    @Test
    void multiFactorIsRequiredWhenTheProfileSaysSo() {
        JwtPrincipalConverter c = converter(Map.of("link-by", "subject", "require-mfa", "true"));
        assertCode("MFA_REQUIRED", c, jwt(Map.of()));
        assertCode("MFA_REQUIRED", c, jwt(Map.of("acr", "1", "amr", List.of("pwd"))));
        assertEquals("idp-sub-1", principal(c, jwt(Map.of("acr", "aal2"))).principalId());
        assertEquals("idp-sub-1", principal(c, jwt(Map.of("amr", List.of("pwd", "hwk")))).principalId());
    }

    @Test
    void aSubjectProfileKeepsTheTokenRolesUnlessTurnedOff() {
        assertEquals(Set.of("sangam-user"), principal(converter(Map.of("link-by", "subject")), jwt(Map.of())).groups());
        assertEquals(Set.of("clerks"), principal(converter(Map.of("link-by", "subject", "idp-roles", "false")),
                jwt(Map.of())).groups());
        assertEquals(Set.of(), principal(converter(Map.of("link-by", "subject", "idp-roles", "false",
                "idp-groups", "false")), jwt(Map.of())).groups());
    }

    static void assertCode(String code, JwtPrincipalConverter c, Jwt jwt) {
        OAuth2AuthenticationException e = assertThrows(OAuth2AuthenticationException.class, () -> c.convert(jwt));
        assertEquals("invalid_token", e.getError().getErrorCode());
        assertTrue(e.getError().getDescription().startsWith(code + ":"), e.getError().getDescription());
    }
}
