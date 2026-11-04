// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.identity;

import org.sangam.core.security.SangamSecurityException;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.core.security.SecurityPorts;
import org.sangam.security.admin.TenantAdminService;
import org.sangam.security.dialect.SecurityDialect;
import org.sangam.security.org.OrgModel;
import org.sangam.security.org.PersonService;
import org.sangam.security.org.PostingService;
import org.sangam.security.policy.MembershipIndex;
import org.sangam.security.support.Sql;

import java.sql.Connection;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Turns a verified token identity into the full Sangam identity of the request (VI.4 rules 2 and 6, IX.5 rules 1 and
 * 4, R67):
 * <ol>
 *   <li>the tenant must exist and not be suspended;</li>
 *   <li>the IdP subject is mapped to a Sangam person (JIT-created if the tenant allows it; else {@code 401 NOT_LINKED});</li>
 *   <li>groups and roles = IdP claims ∪ Sangam memberships (group memberships, role bindings of the user, of its groups
 *       and of its seats), all effective at the database time;</li>
 *   <li>seats = seats the person can act for now (regular, or acting via leave substitution / additional charge).</li>
 * </ol>
 * IdP groups and roles are mirrored into {@code AUTH_MEMBERSHIP} (source {@code idp}) for the large-principal join path.
 */
public final class PrincipalResolver {

    /** Implicit role of every resolved (non-system) person; subject of the self-service templates. */
    public static final String AUTHENTICATED_ROLE = "authenticated";

    private final SecurityDialect dialect;
    private final PersonService persons;
    private final PostingService postings;
    private final MembershipIndex memberships;
    private final TenantAdminService tenants;

    public PrincipalResolver(SecurityDialect dialect, PersonService persons, PostingService postings,
                             MembershipIndex memberships, TenantAdminService tenants) {
        this.dialect = dialect;
        this.persons = persons;
        this.postings = postings;
        this.memberships = memberships;
        this.tenants = tenants;
    }

    /**
     * @param tokenIdentity identity from the token: {@code personId} holds the IdP subject until mapped
     * @param jit           whether just-in-time provisioning is allowed for this tenant
     */
    public SecurityIdentity resolve(Connection c, SecurityIdentity tokenIdentity, boolean jit, String displayName, String email) {
        String tenant = tokenIdentity.tenantId();
        tenants.requireActive(c, tenant);
        String personId;
        if ("API_KEY".equals(tokenIdentity.authMethod())) {
            personId = tokenIdentity.personId();
        } else {
            Optional<OrgModel.Person> p = persons.findBySubject(c, tenant, tokenIdentity.subject());
            if (p.isEmpty() && jit) {
                p = Optional.of(persons.provisionJit(c, tenant, tokenIdentity.subject(), displayName, email));
            }
            OrgModel.Person person = p.orElseThrow(() ->
                    new SangamSecurityException.Unauthenticated("NOT_LINKED", "your account is not linked to this department"));
            if (!"ACTIVE".equals(person.status())) {
                throw new SangamSecurityException.Unauthenticated("ACCOUNT_DISABLED", "your account is disabled");
            }
            personId = person.personId();
        }
        memberships.syncIdp(c, tenant, personId, tokenIdentity.groups(), tokenIdentity.roles());
        return enrich(c, tokenIdentity.withPerson(personId), Sql.dbNow(c, dialect));
    }

    /** Adds Sangam groups, roles and current seats (also used for worker and system identities). */
    public SecurityIdentity enrich(Connection c, SecurityIdentity id, Instant at) {
        String tenant = id.tenantId();
        Set<String> groups = new LinkedHashSet<>(id.groups());
        Set<String> roles = new LinkedHashSet<>(id.roles());
        if (!id.system()) {
            roles.add(AUTHENTICATED_ROLE);
        }
        Set<String> seats = new LinkedHashSet<>();
        Set<String> acting = new LinkedHashSet<>();
        for (SecurityPorts.SeatHolder h : postings.seatsOf(c, tenant, id.personId(), at)) {
            seats.add(h.seatId());
            if (h.actingFor()) {
                acting.add(h.seatId());
            }
        }
        Set<String> members = new LinkedHashSet<>();
        members.add(SecurityIdentity.USER + id.personId());
        for (String p : memberships.principalsOf(c, tenant, SecurityIdentity.USER + id.personId(), at)) {
            if (p.startsWith(SecurityIdentity.GROUP)) {
                groups.add(p.substring(SecurityIdentity.GROUP.length()));
            } else if (p.startsWith(SecurityIdentity.ROLE)) {
                roles.add(p.substring(SecurityIdentity.ROLE.length()));
            }
        }
        // role bindings of the user's groups and seats
        groups.forEach(g -> members.add(SecurityIdentity.GROUP + g));
        seats.forEach(s -> members.add(SecurityIdentity.SEAT + s));
        for (String m : members) {
            if (m.startsWith(SecurityIdentity.USER)) {
                continue;
            }
            for (String p : memberships.principalsOf(c, tenant, m, at)) {
                if (p.startsWith(SecurityIdentity.ROLE)) {
                    roles.add(p.substring(SecurityIdentity.ROLE.length()));
                }
            }
        }
        return id.withGroupsAndRoles(groups, roles).withSeats(seats, acting);
    }
}
