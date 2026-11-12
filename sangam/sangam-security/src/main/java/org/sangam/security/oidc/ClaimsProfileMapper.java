// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.oidc;

import org.sangam.core.security.SangamSecurityException;
import org.sangam.core.security.SecurityIdentity;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Applies a {@link ClaimsProfile} to verified claims (M-077). The tenant, the raw roles and groups, and the copied
 * attributes come from {@link KeycloakClaimsMapper} (unchanged rules: tenant claim required, seats never from the
 * token); the profile then decides:
 * <ol>
 *   <li>the link key, which becomes the identity's subject and the person id that {@code PrincipalResolver} maps to a
 *       Sangam person ({@code SEC_PERSON.external_subject}): the {@code sub}, the lower-cased verified e-mail, or the
 *       named person-id claim. A missing link claim, an unverified e-mail, or an e-mail outside the allowed domains is
 *       {@code 401}; the sign-in never falls back to another link;</li>
 *   <li>whether roles and groups from the token are kept (dropped for a postings-only profile);</li>
 *   <li>whether multi-factor is required for every tenant.</li>
 * </ol>
 * The IdP's own {@code sub} is kept in the attribute {@value #ATTR_IDP_SUBJECT}; the profile name in
 * {@value #ATTR_PROFILE}.
 */
public final class ClaimsProfileMapper {

    public static final String ATTR_IDP_SUBJECT = "idp_sub";
    public static final String ATTR_PROFILE = "claims_profile";

    private final KeycloakClaimsMapper base;
    private final SecurityProperties props;
    private final ClaimsProfile profile;

    public ClaimsProfileMapper(SecurityProperties props, ClaimsProfile profile) {
        this.base = new KeycloakClaimsMapper(props);
        this.props = props;
        this.profile = profile == null ? ClaimsProfile.DEFAULT : profile;
    }

    public ClaimsProfile profile() {
        return profile;
    }

    public SecurityIdentity map(Map<String, Object> claims, String clientIp) {
        SecurityIdentity raw = base.map(claims, clientIp);
        String link = profile.linkKey(claims, raw.subject());
        Map<String, String> attrs = new LinkedHashMap<>(raw.attributes());
        attrs.put(ATTR_IDP_SUBJECT, raw.subject());
        attrs.put(ATTR_PROFILE, profile.name());
        Object email = claims.get(profile.emailClaim());
        if (email != null && !email.toString().isBlank()) {
            attrs.put("email", email.toString());
        }
        Object name = claims.get(profile.nameClaim());
        if (name != null && !name.toString().isBlank()) {
            attrs.put("name", name.toString());
        }
        SecurityIdentity id = new SecurityIdentity(raw.tenantId(), link, link,
                profile.idpGroups() ? raw.groups() : Set.of(), profile.idpRoles() ? raw.roles() : Set.of(), Set.of(), Set.of(),
                raw.authMethod(), clientIp, null, false, attrs);
        if (profile.requireMfa() && !KeycloakClaimsMapper.mfaShown(id, props.getMfaAcrValues())) {
            throw new SangamSecurityException.Unauthenticated("MFA_REQUIRED", "this sign-in requires multi-factor authentication");
        }
        base.requireMfaIfConfigured(id);
        return id;
    }
}
