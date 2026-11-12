// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.saml;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The optional SAML 2.0 service-provider adapter (M-076, owner decision 52). <b>Off by default.</b> The documented
 * default for a SAML identity provider is Keycloak brokering ({@code deploy/compose/keycloak/README.md}): Keycloak
 * accepts the SAML assertion and issues the OIDC token that Sangam already verifies, so nothing here runs. A
 * deployment that cannot run Keycloak turns this on for one asserting party per tenant registration
 * ({@code sangam.security.saml.*}).
 *
 * <p>The adapter runs as its own filter chain on {@link #getLoginPath()} and {@link #getAcsPath()} only. It never
 * touches the bearer-token chain or the per-token identity cache: a SAML sign-in ends in a server session for the web
 * UI, the API keeps bearer tokens.
 *
 * <p>Plain settings, no host framework (owner decision 73): the filter chain and the check that its SAML library is on
 * the class path belong to the host ({@code sangam-server} {@code SamlWiring}). {@link #validate()} refuses an enabled
 * but incomplete registration; it never starts half-configured.
 */
public class SamlProperties {

    /** Standard attribute names (eduPerson/inetOrgPerson OIDs, X.500) to claim names the claims profile reads. */
    public static final Map<String, String> STANDARD_ATTRIBUTES = Map.of(
            "urn:oid:0.9.2342.19200300.100.1.3", "email",          // mail
            "urn:oid:2.16.840.1.113730.3.1.241", "name",           // displayName
            "urn:oid:2.5.4.42", "given_name",                      // givenName
            "urn:oid:2.5.4.4", "family_name",                      // sn
            "urn:oid:0.9.2342.19200300.100.1.1", "preferred_username", // uid
            "urn:oid:1.3.6.1.4.1.5923.1.1.1.6", "eppn");          // eduPersonPrincipalName

    private boolean enabled;
    private String registrationId = "idp";
    private String tenantId;
    private String entityId;
    private String assertingPartyMetadataLocation;
    private String loginPath = "/saml2/authenticate/{registrationId}";
    private String acsPath = "/login/saml2/sso/{registrationId}";
    /** Assertions are signed by the asserting party (always required) and, when true, encrypted to this SP. */
    private boolean requireEncryptedAssertions = true;
    /** SAML attribute name to claim name; added to and overriding {@link #STANDARD_ATTRIBUTES}. */
    private Map<String, String> attributeNames = new LinkedHashMap<>();
    /** The claims profile ({@code ClaimsProfile.of}); empty = default. */
    private Map<String, Object> claimsProfile = new LinkedHashMap<>();
    /** AuthnContextClassRef values that show multi-factor (added to the OIDC acr values). */
    private List<String> mfaAuthnContexts = List.of(
            "urn:oasis:names:tc:SAML:2.0:ac:classes:MobileTwoFactorContract",
            "urn:oasis:names:tc:SAML:2.0:ac:classes:MobileTwoFactorUnregistered",
            "urn:oasis:names:tc:SAML:2.0:ac:classes:TimeSyncToken",
            "urn:oasis:names:tc:SAML:2.0:ac:classes:Smartcard",
            "urn:oasis:names:tc:SAML:2.0:ac:classes:SmartcardPKI");

    /** Refuses an enabled but incomplete adapter; a disabled adapter is always valid. */
    public void validate() {
        if (!enabled) {
            return;
        }
        require(tenantId, "tenant-id");
        require(entityId, "entity-id");
        require(assertingPartyMetadataLocation, "asserting-party-metadata-location");
        require(registrationId, "registration-id");
        if (!registrationId.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalStateException("sangam.security.saml.registration-id must be 1..64 of [A-Za-z0-9_-]");
        }
    }

    /** The effective attribute-name map: the standard names plus the configured ones. */
    public Map<String, String> effectiveAttributeNames() {
        Map<String, String> out = new LinkedHashMap<>(STANDARD_ATTRIBUTES);
        out.putAll(attributeNames);
        return out;
    }

    private static void require(String v, String key) {
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("sangam.security.saml." + key + " is required when the SAML adapter is enabled");
        }
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getRegistrationId() { return registrationId; }
    public void setRegistrationId(String registrationId) { this.registrationId = registrationId; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getEntityId() { return entityId; }
    public void setEntityId(String entityId) { this.entityId = entityId; }
    public String getAssertingPartyMetadataLocation() { return assertingPartyMetadataLocation; }
    public void setAssertingPartyMetadataLocation(String v) { this.assertingPartyMetadataLocation = v; }
    public String getLoginPath() { return loginPath; }
    public void setLoginPath(String loginPath) { this.loginPath = loginPath; }
    public String getAcsPath() { return acsPath; }
    public void setAcsPath(String acsPath) { this.acsPath = acsPath; }
    public boolean isRequireEncryptedAssertions() { return requireEncryptedAssertions; }
    public void setRequireEncryptedAssertions(boolean v) { this.requireEncryptedAssertions = v; }
    public Map<String, String> getAttributeNames() { return attributeNames; }
    public void setAttributeNames(Map<String, String> v) { this.attributeNames = v == null ? new LinkedHashMap<>() : v; }
    public Map<String, Object> getClaimsProfile() { return claimsProfile; }
    public void setClaimsProfile(Map<String, Object> v) { this.claimsProfile = v == null ? new LinkedHashMap<>() : v; }
    public List<String> getMfaAuthnContexts() { return mfaAuthnContexts; }
    public void setMfaAuthnContexts(List<String> v) { this.mfaAuthnContexts = v == null ? List.of() : List.copyOf(v); }
}
