// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.scim;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.core.security.SecurityPorts;
import org.sangam.security.admin.ApiTokenService;
import org.sangam.security.admin.GroupAdminService;
import org.sangam.security.dialect.SecurityDialect;
import org.sangam.security.org.OrgModel;
import org.sangam.security.org.PersonService;
import org.sangam.security.policy.AuthorizationService;
import org.sangam.security.policy.Permission;
import org.sangam.security.policy.ResourceRegistry;
import org.sangam.security.support.Sql;

import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * SCIM 2.0 provisioning (M-099, RFC 7643 / 7644) of Sangam persons, groups and group memberships, owner decision 154
 * (ADR-D-154): postings and seats are read-only; a request that changes them is 400 {@code mutability} and changes
 * nothing, while an unchanged echo of the current postings is accepted.
 *
 * <p>Runs in the caller's transaction with the caller's {@link SecurityIdentity} (the identity provider's service
 * principal, permission {@code scim.provision}). Every write goes through {@link PersonService},
 * {@link GroupAdminService} and {@link ApiTokenService}, so their authorization checks and audit events
 * (USER_PROVISIONED, USER_UPDATED, USER_DISABLED, MEMBERSHIP_CHANGED) apply unchanged. SCIM's own state (uniqueness keys,
 * kept attributes, ETag) is in {@link ScimStore}. Framework-free (decision 137(4)); the web adapter is thin.
 *
 * <p>The person's OIDC subject comes from {@code externalId} by default, or from {@code userName} by configuration, as
 * {@code LdapDirectorySync} takes it from its id attribute; an existing person with that subject that SCIM does not
 * manage yet is adopted, not duplicated.
 */
public final class ScimService {

    /** Largest page; RFC 7644 lets the server return fewer than asked. */
    public static final int MAX_COUNT = 200;
    public static final int DEFAULT_COUNT = 100;
    /** {@code source} of the persons, groups and memberships SCIM creates (like {@code LDAP} for directory sync). */
    public static final String SOURCE = "SCIM";

    public enum SubjectFrom { EXTERNAL_ID, USER_NAME }

    public record Config(SubjectFrom subjectFrom) {
        public Config {
            Objects.requireNonNull(subjectFrom, "subjectFrom");
        }

        public static Config defaults() {
            return new Config(SubjectFrom.EXTERNAL_ID);
        }
    }

    private final SecurityDialect dialect;
    private final AuthorizationService authz;
    private final PersonService persons;
    private final GroupAdminService groups;
    private final ApiTokenService tokens;
    private final SecurityPorts.SeatDirectory seats;
    private final ScimStore store;
    private final Config config;

    public ScimService(SecurityDialect dialect, AuthorizationService authz, PersonService persons, GroupAdminService groups,
                       ApiTokenService tokens, SecurityPorts.SeatDirectory seats, ScimStore store, Config config) {
        this.dialect = dialect;
        this.authz = authz;
        this.persons = persons;
        this.groups = groups;
        this.tokens = tokens;
        this.seats = seats;
        this.store = store;
        this.config = config;
    }

    // ------------------------------------------------------------------ users

    public ObjectNode createUser(Connection c, SecurityIdentity actor, JsonNode body) {
        requireUsers(c, actor, Permission.ADMIN);
        String t = actor.tenantId();
        ScimResources.UserInput in = ScimResources.user(body);
        if (in.postings() != null && !in.postings().isEmpty()) {
            throw ScimException.mutability("postings are read-only in SCIM (decision 154); assign seats through the admin API");
        }
        unique(c, t, ScimStore.USER, null, in.userName(), in.externalId());
        String subject = subject(in);
        Instant now = Sql.dbNow(c, dialect);
        Optional<OrgModel.Person> existing = persons.findBySubject(c, t, subject);
        String personId;
        if (existing.isPresent()) {
            personId = existing.get().personId();
            if (store.find(c, t, ScimStore.USER, personId).isPresent()) {
                throw ScimException.uniqueness("the person with this subject is already provisioned");
            }
            persons.updateUser(c, actor, personId, in.personName(), in.email());
        } else {
            personId = Sql.newId();
            persons.createUser(c, actor, personId, in.personName(), subject, in.email(), SOURCE);
        }
        if (!store.insert(c, t, ScimStore.USER, personId, in.userName(), in.displayName(), in.externalId(), in.attributes(), now)) {
            throw ScimException.uniqueness("userName or externalId is already taken");
        }
        OrgModel.Person person = persons.find(c, t, personId).orElseThrow();
        applyActive(c, actor, person, in.active());
        return user(c, t, store.find(c, t, ScimStore.USER, personId).orElseThrow());
    }

    public ObjectNode getUser(Connection c, SecurityIdentity actor, String id) {
        requireUsers(c, actor, Permission.READ);
        return user(c, actor.tenantId(), row(c, actor.tenantId(), ScimStore.USER, id));
    }

    public ObjectNode listUsers(Connection c, SecurityIdentity actor, String filter, Integer startIndex, Integer count) {
        requireUsers(c, actor, Permission.READ);
        return list(c, actor.tenantId(), ScimStore.USER, filter, startIndex, count);
    }

    public ObjectNode replaceUser(Connection c, SecurityIdentity actor, String id, JsonNode body, String ifMatch) {
        requireUsers(c, actor, Permission.ADMIN);
        ScimStore.Row row = versioned(c, actor.tenantId(), ScimStore.USER, id, ifMatch);
        return writeUser(c, actor, row, body);
    }

    public ObjectNode patchUser(Connection c, SecurityIdentity actor, String id, JsonNode patch, String ifMatch) {
        requireUsers(c, actor, Permission.ADMIN);
        ScimStore.Row row = versioned(c, actor.tenantId(), ScimStore.USER, id, ifMatch);
        ObjectNode patched = ScimPatch.apply(user(c, actor.tenantId(), row), patch);
        // after a PATCH the representation is complete, so postings that are gone were removed: compare them as empty
        JsonNode ext = ScimPatch.field(patched, ScimResources.SANGAM_USER);
        ObjectNode extension = ext instanceof ObjectNode o ? o : patched.putObject(ScimResources.SANGAM_USER);
        if (ScimPatch.field(extension, "postings") == null) {
            extension.putArray("postings");
        }
        return writeUser(c, actor, row, patched);
    }

    /**
     * Deactivates the person (the person and its history stay, X.2.3), revokes its API tokens, removes it from the
     * SCIM-managed groups and forgets the SCIM resource. Postings are not touched (decision 154).
     */
    public void deleteUser(Connection c, SecurityIdentity actor, String id, String ifMatch) {
        requireUsers(c, actor, Permission.ADMIN);
        String t = actor.tenantId();
        ScimStore.Row row = versioned(c, t, ScimStore.USER, id, ifMatch);
        OrgModel.Person person = persons.find(c, t, id).orElseThrow(() -> ScimException.notFound("User", id));
        applyActive(c, actor, person, false);
        Instant now = Sql.dbNow(c, dialect);
        for (String g : groups.groupsOf(c, t, id)) {
            Optional<ScimStore.Row> group = store.find(c, t, ScimStore.GROUP, g);
            if (group.isPresent()) {
                groups.removeMember(c, actor, g, id);
                bump(c, t, group.get(), now);
            }
        }
        if (!store.delete(c, t, ScimStore.USER, id, row.version())) {
            throw ScimException.versionMismatch(id);
        }
    }

    private ObjectNode writeUser(Connection c, SecurityIdentity actor, ScimStore.Row row, JsonNode body) {
        String t = actor.tenantId();
        String id = row.id();
        OrgModel.Person person = persons.find(c, t, id).orElseThrow(() -> ScimException.notFound("User", id));
        ScimResources.UserInput in = ScimResources.user(body);
        if (in.postings() != null && !in.postings().equals(postings(c, t, id))) {
            throw ScimException.mutability("postings are read-only in SCIM (decision 154); change them through the admin API");
        }
        unique(c, t, ScimStore.USER, id, in.userName(), in.externalId());
        String subject = subject(in);
        if (!subject.equals(person.externalSubject())) {
            Optional<OrgModel.Person> other = persons.findBySubject(c, t, subject);
            if (other.isPresent() && !other.get().personId().equals(id)) {
                throw ScimException.uniqueness("another person already has this subject");
            }
            persons.linkSubject(c, actor, id, subject);
        }
        if (!Objects.equals(in.personName(), person.displayName()) || !Objects.equals(in.email(), person.email())) {
            persons.updateUser(c, actor, id, in.personName(), in.email());
        }
        Instant now = Sql.dbNow(c, dialect);
        if (!store.update(c, t, ScimStore.USER, id, row.version(), in.userName(), in.displayName(), in.externalId(),
                in.attributes(), now)) {
            throw ScimException.versionMismatch(id);
        }
        applyActive(c, actor, persons.find(c, t, id).orElseThrow(), in.active());
        return user(c, t, store.find(c, t, ScimStore.USER, id).orElseThrow());
    }

    /** active=false disables and revokes API tokens; active=true re-enables a disabled person; null leaves it. */
    private void applyActive(Connection c, SecurityIdentity actor, OrgModel.Person person, Boolean active) {
        boolean isActive = "ACTIVE".equals(person.status());
        if (Boolean.FALSE.equals(active) && isActive) {
            persons.disableUser(c, actor, person.personId(), SOURCE);
            tokens.revokeAllOf(c, actor, person.personId());
        } else if (Boolean.TRUE.equals(active) && !isActive) {
            persons.enableUser(c, actor, person.personId());   // USER_UPDATED how=ENABLE; API tokens stay revoked
        }
    }

    private String subject(ScimResources.UserInput in) {
        String s = config.subjectFrom() == SubjectFrom.USER_NAME ? in.userName() : in.externalId();
        if (s == null || s.isBlank()) {
            throw ScimException.invalidValue(config.subjectFrom() == SubjectFrom.USER_NAME ? "userName is required"
                    : "externalId is required: it is the person's identity-provider subject");
        }
        return s;
    }

    private Set<ScimResources.Posting> postings(Connection c, String tenant, String personId) {
        Set<ScimResources.Posting> out = new LinkedHashSet<>();
        for (SecurityPorts.SeatHolder h : seats.seatsOf(c, tenant, personId, Sql.dbNow(c, dialect))) {
            out.add(ScimResources.posting(h));
        }
        return out;
    }

    private ObjectNode user(Connection c, String t, ScimStore.Row row) {
        String id = row.id();
        OrgModel.Person person = persons.find(c, t, id).orElseThrow(() -> ScimException.notFound("User", id));
        ObjectNode u = Sql.JSON.createObjectNode();
        u.set("schemas", ScimResources.schemas(ScimResources.USER_SCHEMA, ScimResources.SANGAM_USER));
        u.put("id", id);
        if (row.externalId() != null) {
            u.put("externalId", row.externalId());
        }
        u.put("userName", row.userName());
        if (row.displayName() != null) {
            u.put("displayName", row.displayName());
        }
        u.put("active", "ACTIVE".equals(person.status()));
        if (row.attributes() != null) {
            try {
                JsonNode kept = Sql.JSON.readTree(row.attributes());
                kept.properties().forEach(e -> u.set(e.getKey(), e.getValue()));
            } catch (java.io.IOException e) {
                throw new IllegalStateException("unreadable SCIM attributes of " + id, e);
            }
        }
        ArrayNode memberOf = u.putArray("groups");
        for (String g : groups.groupsOf(c, t, id)) {
            store.find(c, t, ScimStore.GROUP, g).ifPresent(gr -> memberOf.addObject().put("value", g)
                    .put("display", gr.displayName()).put("$ref", ScimResources.BASE + "Groups/" + g));
        }
        ObjectNode ext = u.putObject(ScimResources.SANGAM_USER);
        ext.put("personId", id);
        ArrayNode p = ext.putArray("postings");
        for (ScimResources.Posting x : postings(c, t, id)) {
            ObjectNode o = p.addObject().put("seatId", x.seatId()).put("kind", x.kind()).put("validFrom", x.validFrom());
            if (x.validTo() != null) {
                o.put("validTo", x.validTo());
            }
        }
        u.set("meta", ScimResources.meta("User", id, row));
        return u;
    }

    // ------------------------------------------------------------------ groups

    public ObjectNode createGroup(Connection c, SecurityIdentity actor, JsonNode body) {
        requireGroups(c, actor, Permission.ADMIN);
        String t = actor.tenantId();
        ScimResources.GroupInput in = ScimResources.group(body);
        unique(c, t, ScimStore.GROUP, null, null, in.externalId());
        requireMembers(c, t, in.members());
        String id = Sql.newId();
        groups.createGroup(c, actor, id, in.displayName(), in.externalId(), SOURCE);
        if (!store.insert(c, t, ScimStore.GROUP, id, null, in.displayName(), in.externalId(), null, Sql.dbNow(c, dialect))) {
            throw ScimException.uniqueness("externalId is already taken");
        }
        for (String m : in.members()) {
            groups.addMember(c, actor, id, m, SOURCE);
        }
        return group(c, t, store.find(c, t, ScimStore.GROUP, id).orElseThrow());
    }

    public ObjectNode getGroup(Connection c, SecurityIdentity actor, String id) {
        requireGroups(c, actor, Permission.READ);
        return group(c, actor.tenantId(), row(c, actor.tenantId(), ScimStore.GROUP, id));
    }

    public ObjectNode listGroups(Connection c, SecurityIdentity actor, String filter, Integer startIndex, Integer count) {
        requireGroups(c, actor, Permission.READ);
        return list(c, actor.tenantId(), ScimStore.GROUP, filter, startIndex, count);
    }

    public ObjectNode replaceGroup(Connection c, SecurityIdentity actor, String id, JsonNode body, String ifMatch) {
        requireGroups(c, actor, Permission.ADMIN);
        ScimStore.Row row = versioned(c, actor.tenantId(), ScimStore.GROUP, id, ifMatch);
        return writeGroup(c, actor, row, body);
    }

    public ObjectNode patchGroup(Connection c, SecurityIdentity actor, String id, JsonNode patch, String ifMatch) {
        requireGroups(c, actor, Permission.ADMIN);
        ScimStore.Row row = versioned(c, actor.tenantId(), ScimStore.GROUP, id, ifMatch);
        return writeGroup(c, actor, row, ScimPatch.apply(group(c, actor.tenantId(), row), patch));
    }

    public void deleteGroup(Connection c, SecurityIdentity actor, String id, String ifMatch) {
        requireGroups(c, actor, Permission.ADMIN);
        String t = actor.tenantId();
        ScimStore.Row row = versioned(c, t, ScimStore.GROUP, id, ifMatch);
        groups.deleteGroup(c, actor, id);
        if (!store.delete(c, t, ScimStore.GROUP, id, row.version())) {
            throw ScimException.versionMismatch(id);
        }
    }

    private ObjectNode writeGroup(Connection c, SecurityIdentity actor, ScimStore.Row row, JsonNode body) {
        String t = actor.tenantId();
        String id = row.id();
        ScimResources.GroupInput in = ScimResources.group(body);
        unique(c, t, ScimStore.GROUP, id, null, in.externalId());
        requireMembers(c, t, in.members());
        if (!Objects.equals(in.displayName(), row.displayName()) || !Objects.equals(in.externalId(), row.externalId())) {
            groups.updateGroup(c, actor, id, in.displayName(), in.externalId());   // MEMBERSHIP_CHANGED updated=true
        }
        List<String> current = groups.members(c, t, id);
        for (String m : in.members()) {
            if (!current.contains(m)) {
                groups.addMember(c, actor, id, m, SOURCE);
            }
        }
        for (String m : current) {
            if (!in.members().contains(m)) {
                groups.removeMember(c, actor, id, m);
            }
        }
        if (!store.update(c, t, ScimStore.GROUP, id, row.version(), null, in.displayName(), in.externalId(), null,
                Sql.dbNow(c, dialect))) {
            throw ScimException.versionMismatch(id);
        }
        return group(c, t, store.find(c, t, ScimStore.GROUP, id).orElseThrow());
    }

    /** Members are SCIM users of the tenant (RFC 7644 section 3.5.2: an unknown member is 400 invalidValue). */
    private void requireMembers(Connection c, String t, Set<String> members) {
        for (String m : members) {
            if (store.find(c, t, ScimStore.USER, m).isEmpty()) {
                throw ScimException.invalidValue("member " + m + " is not a provisioned User");
            }
        }
    }

    private ObjectNode group(Connection c, String t, ScimStore.Row row) {
        String id = row.id();
        ObjectNode g = Sql.JSON.createObjectNode();
        g.set("schemas", ScimResources.schemas(ScimResources.GROUP_SCHEMA));
        g.put("id", id);
        if (row.externalId() != null) {
            g.put("externalId", row.externalId());
        }
        g.put("displayName", row.displayName());
        ArrayNode members = g.putArray("members");
        for (String m : groups.members(c, t, id)) {
            ObjectNode o = members.addObject().put("value", m).put("type", "User");
            Optional<ScimStore.Row> u = store.find(c, t, ScimStore.USER, m);
            if (u.isPresent()) {
                o.put("display", u.get().displayName() != null ? u.get().displayName() : u.get().userName());
                o.put("$ref", ScimResources.BASE + "Users/" + m);
            }
        }
        g.set("meta", ScimResources.meta("Group", id, row));
        return g;
    }

    // ------------------------------------------------------------------ shared

    private ObjectNode list(Connection c, String t, String type, String filter, Integer startIndex, Integer count) {
        List<ScimFilter.Clause> clauses = ScimFilter.parse(filter);
        int start = startIndex == null || startIndex < 1 ? 1 : startIndex;
        int size = count == null ? DEFAULT_COUNT : Math.max(0, Math.min(count, MAX_COUNT));
        ScimStore.Page page = store.list(c, t, type, clauses, start, size);
        List<ObjectNode> out = new ArrayList<>();
        for (ScimStore.Row r : page.rows()) {
            out.add(ScimStore.USER.equals(type) ? user(c, t, r) : group(c, t, r));
        }
        return ScimResources.listResponse(page.total(), start, out);
    }

    private void unique(Connection c, String t, String type, String exceptId, String userName, String externalId) {
        if (store.holderOf(c, t, type, exceptId, userName, externalId).isPresent()) {
            throw ScimException.uniqueness((ScimStore.USER.equals(type) ? "userName or externalId" : "externalId")
                    + " is already taken");
        }
    }

    private ScimStore.Row row(Connection c, String t, String type, String id) {
        return store.find(c, t, type, id)
                .orElseThrow(() -> ScimException.notFound(ScimStore.USER.equals(type) ? "User" : "Group", id));
    }

    private ScimStore.Row versioned(Connection c, String t, String type, String id, String ifMatch) {
        ScimStore.Row row = row(c, t, type, id);
        if (!ScimResources.matches(ifMatch, row.version())) {
            throw ScimException.versionMismatch(id);
        }
        return row;
    }

    /** A membership change made from the User side changes the group's representation, so its ETag moves. */
    private void bump(Connection c, String t, ScimStore.Row group, Instant now) {
        if (!store.touch(c, t, ScimStore.GROUP, group.id(), group.version(), now)) {
            throw ScimException.versionMismatch(group.id());
        }
    }

    private void requireUsers(Connection c, SecurityIdentity actor, Permission p) {
        authz.require(c, actor, ResourceRegistry.USER, p, ResourceRegistry.ANY_ID);
    }

    private void requireGroups(Connection c, SecurityIdentity actor, Permission p) {
        authz.require(c, actor, ResourceRegistry.GROUP, p, ResourceRegistry.ANY_ID);
    }
}
