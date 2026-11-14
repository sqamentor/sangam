// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.billing;

import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import org.sangam.core.security.SangamSecurityException;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.security.policy.AuthorizationService;
import org.sangam.security.policy.ModuleResourceTypes;
import org.sangam.security.policy.Permission;

/**
 * Decision 141 (RM-03), authorisation of unverified payments on the existing model, framework independent.
 *
 * <p><b>Scope.</b> The actions {@link Permission#ACCEPT_UNVERIFIED} and {@link Permission#RESOLVE_UNVERIFIED} are
 * granted on the virtual type {@link ModuleResourceTypes#BILLING} with the resource id
 * {@code payment-channel:<channel>@<office>} ({@link #resourceId}); the tenant is the grant row's tenant. A counter is a
 * condition attribute ({@value #COUNTER}) of such a grant, so a grant can be narrowed to counters without a new type.
 *
 * <p><b>Why no PAYMENT_CHANNEL resource type.</b> The existing model already expresses every rule of the decision:
 * per-tenant grant rows (tenant), a concrete resource id (channel and office), conditions on attributes (counter),
 * deny-wins and the uncached re-check of sensitive actions inside the command transaction. What the generic model lacked
 * was "a generic billing or channel permission never implies it"; that is the explicit-only rule
 * ({@link Permission#explicitOnly()}): a grant on {@code '*'} (type or id), a "full BILLING" role template and the system
 * identity never confer these two actions, and validation rejects such grants. A separate type would duplicate the
 * channel scope and still need the same rule.
 *
 * <p><b>Callers.</b> Only signed-in staff (decision 201: a person's interactive login, OIDC or SAML) may act: the
 * system identity (packs, jobs, connectors), API keys, service principals, development tokens and an identity of
 * another tenant are refused before the policy is read.
 */
public final class UnverifiedPaymentPermissions {

    /** Resource type of the channel scope. */
    public static final String RESOURCE_TYPE = ModuleResourceTypes.BILLING;

    /** Resource id prefix of a payment channel at an office. */
    public static final String PREFIX = "payment-channel:";

    /** Condition attribute names offered to grant conditions. */
    public static final String TENANT = "tenantId";
    public static final String OFFICE = "officeId";
    public static final String CHANNEL = "channel";
    public static final String COUNTER = "counterId";

    /** Channel and office tokens: no wildcard, separator or whitespace, so a resource id names exactly one scope. */
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}");

    private final AuthorizationService authz;

    public UnverifiedPaymentPermissions(AuthorizationService authz) {
        this.authz = authz;
    }

    /** {@code payment-channel:<channel>@<office>}; refuses a blank, wildcard or malformed channel or office. */
    public static String resourceId(String channel, String office) {
        return PREFIX + token("channel", channel) + "@" + token("office", office);
    }

    /** The permission of a billing action name ({@code ACCEPT_UNVERIFIED} or {@code RESOLVE_UNVERIFIED}), else empty. */
    public static java.util.Optional<Permission> action(String name) {
        if (Permission.ACCEPT_UNVERIFIED.name().equals(name)) {
            return java.util.Optional.of(Permission.ACCEPT_UNVERIFIED);
        }
        if (Permission.RESOLVE_UNVERIFIED.name().equals(name)) {
            return java.util.Optional.of(Permission.RESOLVE_UNVERIFIED);
        }
        return java.util.Optional.empty();
    }

    /**
     * Whether {@code who} holds {@code action} on (tenant, office, channel[, counter]). Evaluated on {@code c} without
     * cache (both actions are sensitive), deny wins. A denial is audited ({@code AUTH_DENIED}) and answered false.
     *
     * @param counterId optional; offered to grant conditions as {@value #COUNTER}
     */
    public boolean permits(Connection c, SecurityIdentity who, Permission action, String tenant, String office,
                           String channel, String counterId) {
        if (action == null || !action.explicitOnly()) {
            return false;
        }
        if (!authenticatedStaff(who) || tenant == null || !tenant.equals(who.tenantId())) {
            return false;
        }
        String id;
        try {
            id = resourceId(channel, office);
        } catch (IllegalArgumentException malformed) {
            return false;
        }
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put(TENANT, tenant);
        attributes.put(OFFICE, office);
        attributes.put(CHANNEL, channel);
        if (counterId != null && !counterId.isBlank()) {
            attributes.put(COUNTER, counterId);
        }
        try {
            authz.require(c, who, RESOURCE_TYPE, action, id, attributes);
            return true;
        } catch (SangamSecurityException.AccessDenied denied) {
            return false;
        }
    }

    /** Decision 201: the authentication methods of signed-in staff (an interactive login through the IdP). */
    public static final java.util.Set<String> STAFF_AUTH_METHODS = java.util.Set.of("OIDC", "SAML");

    /**
     * Decision 201: only signed-in staff. Not the system identity (reconciliation runs as the system job, without this
     * check), not an API key or a service principal, not a development token, not an identity without a method.
     */
    static boolean authenticatedStaff(SecurityIdentity who) {
        return who != null && !who.system() && who.personId() != null && !who.personId().isBlank()
                && who.authMethod() != null && STAFF_AUTH_METHODS.contains(who.authMethod());
    }

    private static String token(String what, String value) {
        if (value == null || !TOKEN.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid payment " + what + " '" + value + "'");
        }
        return value;
    }
}
