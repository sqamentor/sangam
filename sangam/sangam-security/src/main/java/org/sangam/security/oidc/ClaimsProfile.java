// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.oidc;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.sangam.core.security.SangamSecurityException;

/**
 * How the verified claims (OIDC) or attributes (SAML, after {@code saml.SamlAttributeMapper}) of one identity provider
 * become a token identity (M-077, R50). A profile is configuration, never code per provider: the claim names of some
 * IdPs are not public (PK-04), so a deployment names them here.
 *
 * <ul>
 *   <li>{@code linkBy}: what links the sign-in to a Sangam person ({@code SEC_PERSON.external_subject}):
 *       {@link LinkBy#SUBJECT} the {@code sub} claim, {@link LinkBy#EMAIL} the e-mail claim (lower-cased; it must be
 *       verified when {@code requireVerifiedEmail}), {@link LinkBy#CLAIM} the claim {@code linkClaim} (an IdP person
 *       id);</li>
 *   <li>{@code emailDomains}: when not empty, the e-mail must be in one of these domains or a sub-domain of one;</li>
 *   <li>{@code idpRoles}/{@code idpGroups}: whether roles and groups are taken from the token. A profile that sets both
 *       false takes authority only from Sangam postings and memberships (AJ-05 step 3: "seats from postings, not IdP
 *       groups");</li>
 *   <li>{@code requireMfa}: the sign-in must show multi-factor ({@code acr} in {@code mfaAcrValues}, or an MFA
 *       {@code amr} value) for every tenant, not only the R67 MFA tenants.</li>
 * </ul>
 */
public record ClaimsProfile(String name, LinkBy linkBy, String linkClaim, String emailClaim, String emailVerifiedClaim,
                            boolean requireVerifiedEmail, List<String> emailDomains, String nameClaim, boolean idpRoles,
                            boolean idpGroups, boolean requireMfa) {

    public enum LinkBy { SUBJECT, EMAIL, CLAIM }

    public ClaimsProfile {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(linkBy, "linkBy");
        if (linkBy == LinkBy.CLAIM && (linkClaim == null || linkClaim.isBlank())) {
            throw new IllegalArgumentException("claims profile " + name + ": linkBy CLAIM needs linkClaim");
        }
        emailClaim = blankTo(emailClaim, "email");
        emailVerifiedClaim = blankTo(emailVerifiedClaim, "email_verified");
        nameClaim = blankTo(nameClaim, "name");
        List<String> domains = new ArrayList<>();
        for (String d : emailDomains == null ? List.<String>of() : emailDomains) {
            if (d != null && !d.isBlank()) {
                domains.add(d.trim().toLowerCase(Locale.ROOT).replaceFirst("^[@.]+", ""));
            }
        }
        emailDomains = List.copyOf(domains);
    }

    /** The behaviour before profiles existed: linked by {@code sub}, roles and groups from the token. */
    public static final ClaimsProfile DEFAULT = new ClaimsProfile("default", LinkBy.SUBJECT, null, null, null, false,
            List.of(), null, true, true, false);

    /**
     * Preset for an organisation-wide single sign-on whose authority lives in Sangam (AJ-05 steps 3 and 7): linked by
     * the verified e-mail, no roles or groups from the IdP, multi-factor required. The allowed e-mail domains are
     * deployment configuration ({@code emailDomains}), never a product default (SL-104). A deployment that receives a
     * stable IdP person id instead names that claim with {@code linkBy=CLAIM}.
     */
    public static final ClaimsProfile VERIFIED_EMAIL = new ClaimsProfile("verified-email", LinkBy.EMAIL, null, null, null,
            true, List.of(), null, false, false, true);

    /**
     * Reads a profile from configuration ({@code sangam.security.claims-profile.*} or a tenant config entry): keys
     * {@code preset} (default | verified-email, the base), then any of {@code linkBy}, {@code linkClaim}, {@code emailClaim},
     * {@code emailVerifiedClaim}, {@code requireVerifiedEmail}, {@code emailDomains}, {@code nameClaim},
     * {@code idpRoles}, {@code idpGroups}, {@code requireMfa} overriding it. Keys match in camel or kebab case
     * ({@code emailDomains} or {@code email-domains}), as a Spring map binding keeps the property's own spelling.
     */
    public static ClaimsProfile of(Map<String, ?> raw) {
        if (raw == null || raw.isEmpty()) {
            return DEFAULT;
        }
        Map<String, Object> config = new java.util.HashMap<>();
        raw.forEach((k, v) -> {
            if (k != null) {
                config.put(canonicalKey(k), v);
            }
        });
        String preset = text(config.get("preset"), "default");
        ClaimsProfile base = switch (preset.toLowerCase(Locale.ROOT)) {
            case "default" -> DEFAULT;
            case "verified-email" -> VERIFIED_EMAIL;
            default -> throw new IllegalArgumentException("unknown claims profile preset " + preset);
        };
        return new ClaimsProfile(text(config.get("name"), base.name()),
                config.get("linkBy") == null ? base.linkBy()
                        : LinkBy.valueOf(config.get("linkBy").toString().trim().toUpperCase(Locale.ROOT)),
                text(config.get("linkClaim"), base.linkClaim()), text(config.get("emailClaim"), base.emailClaim()),
                text(config.get("emailVerifiedClaim"), base.emailVerifiedClaim()),
                bool(config.get("requireVerifiedEmail"), base.requireVerifiedEmail()),
                config.get("emailDomains") == null ? base.emailDomains() : list(config.get("emailDomains")),
                text(config.get("nameClaim"), base.nameClaim()), bool(config.get("idpRoles"), base.idpRoles()),
                bool(config.get("idpGroups"), base.idpGroups()), bool(config.get("requireMfa"), base.requireMfa()));
    }

    /** {@code email-domains} or {@code email_domains} to {@code emailDomains}; a camel-case key is unchanged. */
    static String canonicalKey(String key) {
        String[] parts = key.trim().split("[-_]");
        StringBuilder b = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            if (!parts[i].isEmpty()) {
                b.append(Character.toUpperCase(parts[i].charAt(0))).append(parts[i].substring(1));
            }
        }
        return b.toString();
    }

    /**
     * The link key of a sign-in: the IdP {@code sub}, the named claim, or the lower-cased verified e-mail in an allowed
     * domain. It becomes the identity's subject and person id. A missing link claim, an unverified e-mail or an e-mail
     * outside the allowed domains is refused ({@code 401}); there is never a fallback to another link.
     */
    public String linkKey(Map<String, ?> claims, String idpSubject) {
        return switch (linkBy) {
            case SUBJECT -> idpSubject;
            case CLAIM -> {
                String v = single(claims.get(linkClaim));
                if (v == null) {
                    throw new SangamSecurityException.Unauthenticated("LINK_CLAIM_MISSING", "the sign-in carries no " + linkClaim);
                }
                yield v;
            }
            case EMAIL -> {
                String email = single(claims.get(emailClaim));
                if (email == null || email.indexOf('@') < 1) {
                    throw new SangamSecurityException.Unauthenticated("EMAIL_CLAIM_MISSING", "the sign-in carries no e-mail");
                }
                if (requireVerifiedEmail) {
                    String verified = single(claims.get(emailVerifiedClaim));
                    if (verified == null || !verified.equalsIgnoreCase("true")) {
                        throw new SangamSecurityException.Unauthenticated("EMAIL_NOT_VERIFIED", "the e-mail is not verified");
                    }
                }
                String normalised = email.trim().toLowerCase(Locale.ROOT);
                if (!domainAllowed(normalised)) {
                    throw new SangamSecurityException.Unauthenticated("EMAIL_DOMAIN_REFUSED",
                            "the e-mail domain is not accepted for this sign-in");
                }
                yield normalised;
            }
        };
    }

    /** One non-blank value; a multi-valued claim with more than one value is ambiguous and refused (null). */
    private static String single(Object v) {
        if (v instanceof java.util.Collection<?> c) {
            if (c.size() != 1 || c.iterator().next() == null) {
                return null;
            }
            v = c.iterator().next();
        }
        return v == null || v.toString().isBlank() ? null : v.toString().trim();
    }

    /** Whether {@code email} is in an allowed domain (always true when no domain is configured). */
    public boolean domainAllowed(String email) {
        if (emailDomains.isEmpty()) {
            return true;
        }
        int at = email.lastIndexOf('@');
        if (at < 0) {
            return false;
        }
        String domain = email.substring(at + 1).toLowerCase(Locale.ROOT);
        return emailDomains.stream().anyMatch(d -> domain.equals(d) || domain.endsWith("." + d));
    }

    private static String blankTo(String v, String dflt) {
        return v == null || v.isBlank() ? dflt : v;
    }

    private static String text(Object v, String dflt) {
        return v == null || v.toString().isBlank() ? dflt : v.toString().trim();
    }

    private static boolean bool(Object v, boolean dflt) {
        if (v == null) {
            return dflt;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        String s = v.toString().trim();
        if (s.equalsIgnoreCase("true") || s.equalsIgnoreCase("false")) {
            return Boolean.parseBoolean(s);
        }
        throw new IllegalArgumentException("not a boolean: " + s);
    }

    private static List<String> list(Object v) {
        if (v instanceof Collection<?> c) {
            return c.stream().filter(Objects::nonNull).map(Object::toString).toList();
        }
        return List.of(v.toString().split("\\s*,\\s*"));
    }
}
