// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.oidc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.sangam.core.security.SangamSecurityException;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.integration.sim.parichay.ParichayIdpSimulator;

/**
 * M-077 T87/T89: the government single sign-on adapter against its simulator (W3-B {@code ParichayIdpSimulator}). The
 * simulator's RS256 ID token verifies with its published key, and its claims map through the claims profile a
 * deployment configures for that IdP: linked by the verified e-mail, no IdP roles or groups, multi-factor required.
 * Written for the test phase (not run here).
 */
class GovSsoSimulatorConformanceTest {

    /** The deployment profile for the simulated IdP (its fixture e-mail domain is sim.gov.invalid). */
    static final ClaimsProfile PROFILE = ClaimsProfile.of(Map.of("preset", "verified-email", "name", "gov-sso",
            "emailDomains", "sim.gov.invalid"));

    static Map<String, Object> verifiedClaims(ParichayIdpSimulator sim, String token) throws Exception {
        SignedJWT jwt = SignedJWT.parse(token);
        assertEquals(JWSAlgorithm.RS256, jwt.getHeader().getAlgorithm());
        assertEquals(ParichayIdpSimulator.KEY_ID, jwt.getHeader().getKeyID());
        assertTrue(jwt.verify(new RSASSAVerifier(sim.publicKey())), "the ID token verifies with the published key");
        assertEquals(sim.issuer(), jwt.getJWTClaimsSet().getIssuer());
        assertEquals(List.of(ParichayIdpSimulator.CLIENT_ID), jwt.getJWTClaimsSet().getAudience());
        return new LinkedHashMap<>(jwt.getJWTClaimsSet().getClaims());
    }

    @Test
    void theFixtureLoginMapsToTheLinkedPerson() throws Exception {
        try (ParichayIdpSimulator sim = new ParichayIdpSimulator().start()) {
            Map<String, Object> claims = verifiedClaims(sim, sim.idToken(null, "n-1"));
            SecurityIdentity id = new ClaimsProfileMapper(new SecurityProperties(), PROFILE).map(claims, null);
            assertEquals(String.valueOf(ParichayIdpSimulator.FIXTURE_CLAIMS.get("email")).toLowerCase(), id.subject());
            assertEquals(String.valueOf(ParichayIdpSimulator.FIXTURE_CLAIMS.get("tenant")), id.tenantId());
            assertEquals(String.valueOf(ParichayIdpSimulator.FIXTURE_CLAIMS.get("sub")),
                    id.attributes().get(ClaimsProfileMapper.ATTR_IDP_SUBJECT));
            assertEquals(Set.of(), id.roles());
            assertEquals(Set.of(), id.groups());
        }
    }

    @Test
    void anUnverifiedEmailOrASingleFactorLoginIsRefused() throws Exception {
        try (ParichayIdpSimulator sim = new ParichayIdpSimulator().start()) {
            ClaimsProfileMapper mapper = new ClaimsProfileMapper(new SecurityProperties(), PROFILE);
            Map<String, Object> unverified = new LinkedHashMap<>(ParichayIdpSimulator.FIXTURE_CLAIMS);
            unverified.put("email_verified", false);
            Map<String, Object> c1 = verifiedClaims(sim, sim.idToken(unverified, null));
            assertEquals("EMAIL_NOT_VERIFIED", assertThrows(SangamSecurityException.Unauthenticated.class,
                    () -> mapper.map(c1, null)).code());

            Map<String, Object> singleFactor = new LinkedHashMap<>(ParichayIdpSimulator.FIXTURE_CLAIMS);
            singleFactor.put("acr", "urn:sangam:acr:password");
            singleFactor.put("amr", List.of("pwd"));
            Map<String, Object> c2 = verifiedClaims(sim, sim.idToken(singleFactor, null));
            assertEquals("MFA_REQUIRED", assertThrows(SangamSecurityException.Unauthenticated.class,
                    () -> mapper.map(c2, null)).code());

            Map<String, Object> otherDomain = new LinkedHashMap<>(ParichayIdpSimulator.FIXTURE_CLAIMS);
            otherDomain.put("email", "someone@example.com");
            Map<String, Object> c3 = verifiedClaims(sim, sim.idToken(otherDomain, null));
            assertEquals("EMAIL_DOMAIN_REFUSED", assertThrows(SangamSecurityException.Unauthenticated.class,
                    () -> mapper.map(c3, null)).code());
        }
    }
}
