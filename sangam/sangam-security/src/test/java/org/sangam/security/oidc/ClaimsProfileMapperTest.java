// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.oidc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.sangam.core.security.SangamSecurityException;
import org.sangam.core.security.SecurityIdentity;

/**
 * M-077 (R50, AJ-05 step 3): a claims profile decides the person link, whether IdP roles and groups count, and
 * multi-factor. The fixture claim names are those the W3-B identity-provider simulator issues (sub, email,
 * email_verified, name, acr, amr, tenant). Written for the test phase (not run here).
 */
class ClaimsProfileMapperTest {

    /** A government single sign-on as a deployment configures it: the neutral preset plus its e-mail domain. */
    static final ClaimsProfile GOV_SSO = ClaimsProfile.of(Map.of("preset", "verified-email", "name", "gov-sso",
            "emailDomains", List.of("gov.in")));

    private static Map<String, Object> claims() {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("sub", "f3a9-idp-subject");
        c.put("tenant", "t1");
        c.put("email", "Officer.One@Dept.GOV.IN");
        c.put("email_verified", true);
        c.put("name", "Officer One");
        c.put("acr", "aal2");
        c.put("amr", List.of("pwd", "otp"));
        c.put("groups", List.of("/finance/section-a"));
        c.put("realm_access", Map.of("roles", List.of("tenant-admin")));
        return c;
    }

    private static ClaimsProfileMapper mapper(ClaimsProfile profile) {
        return new ClaimsProfileMapper(new SecurityProperties(), profile);
    }

    private static String code(Runnable r) {
        return assertThrows(SangamSecurityException.Unauthenticated.class, r::run).code();
    }

    @Test
    void theDefaultProfileKeepsTheEarlierBehaviour() {
        SecurityIdentity id = mapper(ClaimsProfile.DEFAULT).map(claims(), "10.0.0.1");
        assertEquals("f3a9-idp-subject", id.subject());
        assertEquals("f3a9-idp-subject", id.personId());
        assertEquals(Set.of("tenant-admin"), id.roles());
        assertEquals(Set.of("finance/section-a"), id.groups());
        assertEquals(Set.of(), id.seats(), "seats never come from the token");
        assertEquals("OIDC", id.authMethod());
        assertEquals("default", id.attributes().get(ClaimsProfileMapper.ATTR_PROFILE));
        assertEquals(ClaimsProfile.DEFAULT, ClaimsProfile.of(null));
        assertEquals(ClaimsProfile.DEFAULT, ClaimsProfile.of(Map.of()));
    }

    @Test
    void theGovernmentPresetLinksByTheVerifiedEmailAndTakesNoRolesOrGroups() {
        SecurityIdentity id = mapper(GOV_SSO).map(claims(), null);
        assertEquals("officer.one@dept.gov.in", id.subject(), "the link key is the lower-cased e-mail");
        assertEquals("officer.one@dept.gov.in", id.personId());
        assertEquals("f3a9-idp-subject", id.attributes().get(ClaimsProfileMapper.ATTR_IDP_SUBJECT));
        assertEquals("gov-sso", id.attributes().get(ClaimsProfileMapper.ATTR_PROFILE));
        assertEquals(Set.of(), id.roles(), "authority comes from postings, not the IdP (AJ-05 step 3)");
        assertEquals(Set.of(), id.groups());
        assertEquals("t1", id.tenantId());
    }

    @Test
    void anUnverifiedOrMissingEmailIsRefusedWithoutFallback() {
        Map<String, Object> unverified = claims();
        unverified.put("email_verified", "false");
        assertEquals("EMAIL_NOT_VERIFIED", code(() -> mapper(GOV_SSO).map(unverified, null)));
        Map<String, Object> absent = claims();
        absent.remove("email_verified");
        assertEquals("EMAIL_NOT_VERIFIED", code(() -> mapper(GOV_SSO).map(absent, null)));
        Map<String, Object> noEmail = claims();
        noEmail.remove("email");
        assertEquals("EMAIL_CLAIM_MISSING", code(() -> mapper(GOV_SSO).map(noEmail, null)));
        Map<String, Object> two = claims();
        two.put("email", List.of("a@x.gov.in", "b@x.gov.in"));
        assertEquals("EMAIL_CLAIM_MISSING", code(() -> mapper(GOV_SSO).map(two, null)),
                "two e-mails are ambiguous");
        Map<String, Object> textTrue = claims();
        textTrue.put("email_verified", "true");
        assertEquals("officer.one@dept.gov.in", mapper(GOV_SSO).map(textTrue, null).subject());
    }

    @Test
    void anEmailOutsideTheAllowedDomainsIsRefused() {
        for (String email : List.of("someone@example.com", "someone@notgov.in", "someone@gov.in.example.com")) {
            Map<String, Object> c = claims();
            c.put("email", email);
            assertEquals("EMAIL_DOMAIN_REFUSED", code(() -> mapper(GOV_SSO).map(c, null)), email);
        }
        Map<String, Object> exact = claims();
        exact.put("email", "head@gov.in");
        assertEquals("head@gov.in", mapper(GOV_SSO).map(exact, null).subject());
    }

    @Test
    void theGovernmentPresetRequiresMultiFactorForEveryTenant() {
        Map<String, Object> c = claims();
        c.put("acr", "aal1");
        c.put("amr", List.of("pwd"));
        assertEquals("MFA_REQUIRED", code(() -> mapper(GOV_SSO).map(c, null)));
        assertEquals("aal1", mapper(ClaimsProfile.DEFAULT).map(c, null).attributes().get("acr"),
                "the default profile requires MFA only for the configured tenants");
        Map<String, Object> amrOnly = claims();
        amrOnly.put("acr", "aal1");
        amrOnly.put("amr", List.of("pwd", "hwk"));
        assertEquals("officer.one@dept.gov.in", mapper(GOV_SSO).map(amrOnly, null).subject());
    }

    @Test
    void aNamedPersonIdClaimCanBeTheLink() {
        ClaimsProfile p = ClaimsProfile.of(Map.of("preset", "verified-email", "emailDomains", "gov.in", "linkBy", "claim", "linkClaim", "person_id"));
        assertEquals(ClaimsProfile.LinkBy.CLAIM, p.linkBy());
        assertTrue(p.requireMfa(), "the preset's other settings stay");
        Map<String, Object> c = claims();
        c.put("person_id", "GOV-000123");
        assertEquals("GOV-000123", mapper(p).map(c, null).subject());
        assertEquals("LINK_CLAIM_MISSING", code(() -> mapper(p).map(claims(), null)));
    }

    @Test
    void configurationOverridesThePreset() {
        ClaimsProfile p = ClaimsProfile.of(Map.of("preset", "verified-email", "name", "state-sso", "emailDomains", "state.example, .gov.in",
                "requireMfa", "false", "idpGroups", true));
        assertEquals("state-sso", p.name());
        assertEquals(List.of("state.example", "gov.in"), p.emailDomains());
        Map<String, Object> c = claims();
        c.put("email", "a@unit.state.example");
        c.put("acr", "aal1");
        c.put("amr", List.of("pwd"));
        SecurityIdentity id = mapper(p).map(c, null);
        assertEquals("a@unit.state.example", id.subject());
        assertEquals(Set.of("finance/section-a"), id.groups());
        assertEquals(Set.of(), id.roles());
        assertThrows(IllegalArgumentException.class, () -> ClaimsProfile.of(Map.of("preset", "unknown")));
        assertThrows(IllegalArgumentException.class, () -> ClaimsProfile.of(Map.of("linkBy", "claim")),
                "linkBy CLAIM needs a claim name");
        assertThrows(IllegalArgumentException.class, () -> ClaimsProfile.of(Map.of("requireMfa", "yes")));
    }

    @Test
    void theTenantRuleIsUnchanged() {
        Map<String, Object> c = claims();
        c.remove("tenant");
        assertEquals("TENANT_CLAIM_MISSING", code(() -> mapper(GOV_SSO).map(c, null)));
    }
}
