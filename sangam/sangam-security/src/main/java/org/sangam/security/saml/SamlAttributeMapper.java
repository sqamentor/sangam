// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.saml;

import org.sangam.core.security.SangamSecurityException;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.security.oidc.ClaimsProfile;
import org.sangam.security.oidc.ClaimsProfileMapper;
import org.sangam.security.oidc.SecurityProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps a validated SAML assertion (M-076) to a token identity through the same {@link ClaimsProfile} as OIDC (M-077),
 * so a SAML sign-in and a brokered OIDC sign-in of the same person give the same identity.
 * <ul>
 *   <li>the NameID becomes {@code sub}; attribute names are translated by {@link SamlProperties#effectiveAttributeNames()}
 *       (standard OIDs to {@code email}, {@code name}, ...), others keep their name; one value is unwrapped, several
 *       stay a list;</li>
 *   <li>the tenant is the registration's tenant, never an assertion attribute (an asserting party cannot choose the
 *       tenant; VI.4 rule 6);</li>
 *   <li>the AuthnContextClassRef becomes {@code acr}; one of {@link SamlProperties#getMfaAuthnContexts()} adds
 *       {@code amr=mfa};</li>
 *   <li>{@code authMethod} is {@code SAML}.</li>
 * </ul>
 * Signature, audience, recipient, time window and replay checks belong to the SAML library and happen before this.
 */
public final class SamlAttributeMapper {

    private final SamlProperties saml;
    private final SecurityProperties security;
    private final ClaimsProfileMapper profile;

    public SamlAttributeMapper(SamlProperties saml, SecurityProperties security) {
        this.saml = saml;
        this.security = security;
        this.profile = new ClaimsProfileMapper(security, ClaimsProfile.of(saml.getClaimsProfile()));
    }

    public SecurityIdentity map(String nameId, Map<String, List<Object>> attributes, String authnContextClassRef,
                                String clientIp) {
        if (nameId == null || nameId.isBlank()) {
            throw new SangamSecurityException.Unauthenticated("UNAUTHENTICATED", "the assertion has no subject");
        }
        if (saml.getTenantId() == null || saml.getTenantId().isBlank()) {
            throw new IllegalStateException("the SAML registration has no tenant");
        }
        Map<String, String> names = saml.effectiveAttributeNames();
        Map<String, Object> claims = new LinkedHashMap<>();
        if (attributes != null) {
            for (Map.Entry<String, List<Object>> e : attributes.entrySet()) {
                List<Object> values = new ArrayList<>();
                for (Object v : e.getValue() == null ? List.of() : e.getValue()) {
                    if (v != null) {
                        values.add(v.toString());
                    }
                }
                if (!values.isEmpty()) {
                    claims.put(names.getOrDefault(e.getKey(), e.getKey()), values.size() == 1 ? values.get(0) : values);
                }
            }
        }
        claims.put("sub", nameId);
        claims.put(security.getTenantClaim(), saml.getTenantId());
        claims.remove("acr");
        claims.remove("amr");
        if (authnContextClassRef != null && !authnContextClassRef.isBlank()) {
            claims.put("acr", authnContextClassRef);
            if (saml.getMfaAuthnContexts().contains(authnContextClassRef)) {
                claims.put("amr", List.of("mfa"));
            }
        }
        SecurityIdentity id = profile.map(claims, clientIp);
        return new SecurityIdentity(id.tenantId(), id.personId(), id.subject(), id.groups(), id.roles(), id.seats(),
                id.actingForSeats(), "SAML", id.clientIp(), id.onBehalfOf(), false, id.attributes());
    }
}
