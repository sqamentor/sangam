// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.saml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.sangam.core.security.SangamSecurityException;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.security.oidc.SecurityProperties;

/**
 * M-076: the optional SAML SP adapter is off by default and refuses to start incomplete; a validated assertion maps
 * through the same claims profile as OIDC; the tenant comes from the registration, never from the assertion. Written
 * for the test phase (not run here).
 */
class SamlAttributeMapperTest {

    static final String MFA = "urn:oasis:names:tc:SAML:2.0:ac:classes:MobileTwoFactorContract";

    private static SamlProperties registration() {
        SamlProperties p = new SamlProperties();
        p.setEnabled(true);
        p.setTenantId("t1");
        p.setEntityId("https://sangam.example/saml2/sp");
        p.setAssertingPartyMetadataLocation("file:/etc/sangam/idp-metadata.xml");
        p.setClaimsProfile(Map.of("preset", "verified-email", "emailDomains", "gov.in"));
        return p;
    }

    private static Map<String, List<Object>> attributes() {
        Map<String, List<Object>> a = new LinkedHashMap<>();
        a.put("urn:oid:0.9.2342.19200300.100.1.3", List.of("Officer.One@dept.gov.in"));
        a.put("urn:oid:2.16.840.1.113730.3.1.241", List.of("Officer One"));
        a.put("email_verified", List.of("true"));
        a.put("tenant", List.of("other-tenant"));
        a.put("roles", List.of("platform-admin"));
        return a;
    }

    @Test
    void theAdapterIsOffByDefaultAndADisabledAdapterIsAlwaysValid() {
        SamlProperties p = new SamlProperties();
        assertFalse(p.isEnabled());
        p.validate();
    }

    @Test
    void anEnabledAdapterNeedsItsSettings() {
        SamlProperties p = registration();
        p.setTenantId(null);
        assertThrows(IllegalStateException.class, p::validate);
        SamlProperties q = registration();
        q.setAssertingPartyMetadataLocation(" ");
        assertThrows(IllegalStateException.class, q::validate);
        SamlProperties r = registration();
        r.setRegistrationId("bad id/..");
        assertThrows(IllegalStateException.class, r::validate);
    }

    @Test
    void aCompleteRegistrationIsValid() {
        registration().validate();
        assertTrue(registration().effectiveAttributeNames().containsKey("urn:oid:0.9.2342.19200300.100.1.3"));
    }

    @Test
    void anAssertionMapsThroughTheClaimsProfile() {
        SecurityIdentity id = new SamlAttributeMapper(registration(), new SecurityProperties())
                .map("AAdzZWNyZXQx-nameid", attributes(), MFA, "192.0.2.9");
        assertEquals("t1", id.tenantId(), "the registration's tenant, not the assertion's");
        assertEquals("officer.one@dept.gov.in", id.subject());
        assertEquals("SAML", id.authMethod());
        assertEquals(Set.of(), id.roles(), "the preset takes no roles from the IdP");
        assertEquals("Officer One", id.attributes().get("name"));
        assertEquals("AAdzZWNyZXQx-nameid", id.attributes().get("idp_sub"));
        assertEquals(MFA, id.attributes().get("acr"));
        assertEquals("192.0.2.9", id.clientIp());
    }

    @Test
    void aSingleFactorAssertionIsRefusedByAnMfaProfile() {
        SamlAttributeMapper m = new SamlAttributeMapper(registration(), new SecurityProperties());
        assertEquals("MFA_REQUIRED", assertThrows(SangamSecurityException.Unauthenticated.class,
                () -> m.map("n1", attributes(), "urn:oasis:names:tc:SAML:2.0:ac:classes:PasswordProtectedTransport", null)).code());
        Map<String, List<Object>> spoof = attributes();
        spoof.put("amr", List.of("mfa"));
        spoof.put("acr", List.of(MFA));
        assertEquals("MFA_REQUIRED", assertThrows(SangamSecurityException.Unauthenticated.class,
                () -> m.map("n1", spoof, null, null)).code(), "an attribute cannot claim multi-factor");
    }

    @Test
    void theDefaultProfileKeepsAttributeRolesAndConfiguredNamesApply() {
        SamlProperties p = registration();
        p.setClaimsProfile(Map.of());
        p.setAttributeNames(Map.of("urn:example:role", "roles"));
        Map<String, List<Object>> a = attributes();
        a.remove("roles");
        a.put("urn:example:role", List.of("reader", "writer"));
        SecurityIdentity id = new SamlAttributeMapper(p, new SecurityProperties()).map("n-42", a, null, null);
        assertEquals("n-42", id.subject());
        assertEquals(Set.of("reader", "writer"), id.roles());
    }

    @Test
    void anAssertionWithoutSubjectIsRefused() {
        SamlAttributeMapper m = new SamlAttributeMapper(registration(), new SecurityProperties());
        assertThrows(SangamSecurityException.Unauthenticated.class, () -> m.map(" ", attributes(), MFA, null));
    }
}
