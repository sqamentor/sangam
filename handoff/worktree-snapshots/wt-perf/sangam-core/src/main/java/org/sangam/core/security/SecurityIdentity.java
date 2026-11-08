// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.security;

import org.sangam.core.layer.AuthorizationPolicy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The authenticated caller, engine-neutral (SANGAM.md VI.4 rules 6 and 7, IX.5 rule 1).
 *
 * <p>The tenant comes only from the authenticated identity or channel binding (VI.4 rule 6). Seats are the seats the
 * person holds <i>at the time of the request</i>, including seats held through additional charge or as a leave
 * substitute ({@link #actingForSeats()}). Seats never come from the IdP (AJ-05 step 3): they are resolved from Sangam
 * postings.
 *
 * <p>Principal ids used in {@code AUTH_EFFECTIVE.principal_id} are prefixed so that a user, a group, a role and a seat
 * with the same raw name never collide: {@code user:}, {@code group:}, {@code role:}, {@code seat:}.
 *
 * @param tenantId       tenant bound to the identity (never from a request parameter)
 * @param personId       Sangam person id (stable; the IdP subject is mapped to it)
 * @param subject        IdP subject ({@code sub}) or worker client id
 * @param groups         group ids (IdP groups plus Sangam group memberships)
 * @param roles          role ids (IdP realm/client roles plus Sangam role bindings)
 * @param seats          seats held now (regular postings, additional charge, leave substitution)
 * @param actingForSeats subset of {@code seats} held by additional charge or substitution ("acted for", IX.5 rule 4)
 * @param authMethod     OIDC, SAML, API_KEY, DEV_STATIC, SYSTEM
 * @param clientIp       caller address for the class-B security chain (X.2.3.2)
 * @param onBehalfOf     for background work run as the system principal: the person it acts for (VI.4 rule 7)
 * @param system         true for the engine/system principal
 * @param attributes     further verified claims (acr, amr, email ...)
 */
public record SecurityIdentity(String tenantId, String personId, String subject, Set<String> groups, Set<String> roles,
                               Set<String> seats, Set<String> actingForSeats, String authMethod, String clientIp,
                               String onBehalfOf, boolean system, Map<String, String> attributes) {

    public static final String USER = "user:";
    public static final String GROUP = "group:";
    public static final String ROLE = "role:";
    public static final String SEAT = "seat:";

    public SecurityIdentity {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(personId, "personId");
        if (tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        groups = immutable(groups);
        roles = immutable(roles);
        seats = immutable(seats);
        actingForSeats = immutable(actingForSeats);
        attributes = attributes == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
        authMethod = authMethod == null ? "UNKNOWN" : authMethod;
    }

    /** The system principal of a tenant (jobs, timers); records {@code onBehalfOf} (VI.4 rule 7). */
    public static SecurityIdentity system(String tenantId, String onBehalfOf) {
        return new SecurityIdentity(tenantId, "system", "system", Set.of(), Set.of("system"), Set.of(), Set.of(),
                "SYSTEM", null, onBehalfOf, true, Map.of());
    }

    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    public boolean holdsSeat(String seatId) {
        return seats.contains(seatId);
    }

    public boolean actsForSeat(String seatId) {
        return actingForSeats.contains(seatId);
    }

    /** Prefixed principal ids: the user first, then groups, roles and seats (input of the VI.4 predicate). */
    public List<String> principalIds() {
        List<String> ids = new ArrayList<>(1 + groups.size() + roles.size() + seats.size());
        ids.add(USER + personId);
        groups.forEach(g -> ids.add(GROUP + g));
        roles.forEach(r -> ids.add(ROLE + r));
        seats.forEach(s -> ids.add(SEAT + s));
        return ids;
    }

    /** Bridge to the spike's {@link AuthorizationPolicy.Principal} (same prefixed ids). */
    public AuthorizationPolicy.Principal toPrincipal() {
        List<String> ids = principalIds();
        return new AuthorizationPolicy.Principal(tenantId, ids.get(0), new LinkedHashSet<>(ids.subList(1, ids.size())));
    }

    public SecurityIdentity withSeats(Set<String> newSeats, Set<String> newActingFor) {
        return new SecurityIdentity(tenantId, personId, subject, groups, roles, newSeats, newActingFor, authMethod,
                clientIp, onBehalfOf, system, attributes);
    }

    public SecurityIdentity withGroupsAndRoles(Set<String> newGroups, Set<String> newRoles) {
        return new SecurityIdentity(tenantId, personId, subject, newGroups, newRoles, seats, actingForSeats, authMethod,
                clientIp, onBehalfOf, system, attributes);
    }

    public SecurityIdentity withPerson(String newPersonId) {
        return new SecurityIdentity(tenantId, newPersonId, subject, groups, roles, seats, actingForSeats, authMethod,
                clientIp, onBehalfOf, system, attributes);
    }

    private static Set<String> immutable(Set<String> s) {
        return s == null ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(s));
    }
}
