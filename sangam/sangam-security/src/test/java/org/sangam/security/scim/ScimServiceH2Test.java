// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.scim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Schema;
import org.sangam.core.security.SangamSecurityException;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.core.security.SecurityPorts;
import org.sangam.security.admin.ApiTokenService;
import org.sangam.security.admin.GroupAdminService;
import org.sangam.security.audit.SecurityAuditService;
import org.sangam.security.dialect.SecurityDialect;
import org.sangam.security.org.PersonService;
import org.sangam.security.policy.AuthEffectiveMaintainer;
import org.sangam.security.policy.AuthorizationService;
import org.sangam.security.policy.MembershipIndex;
import org.sangam.security.policy.Permission;
import org.sangam.security.policy.PolicyCompiler;
import org.sangam.security.policy.ResourceRegistry;

/**
 * M-099, owner decision 154: SCIM provisioning of persons, groups and memberships through the security services on H2
 * (PostgreSQL mode), with the SEC_SCIM_RESOURCE shape requested from the W4-G generator. Postings come from a fake seat
 * directory and are never written. Written for the test phase, not run here; Oracle and SQL Server run through the
 * acceptance lane (NOT_RUN).
 */
class ScimServiceH2Test {

    static final String T = "t";
    static final ObjectMapper JSON = new ObjectMapper();
    static final String EXT = ScimResources.SANGAM_USER;

    private Connection c;
    private ScimService scim;
    private final List<SecurityPorts.SeatHolder> postings = new ArrayList<>();
    private final SecurityIdentity idp = new SecurityIdentity(T, "idp-client", "idp-client", Set.of(), Set.of("scim-client"),
            Set.of(), Set.of(), "OIDC", null, null, false, Map.of());

    @BeforeEach
    void db() throws Exception {
        c = DriverManager.getConnection("jdbc:h2:mem:scim" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=TRUE", "sa", "");
        Schema.create(c, Dialect.POSTGRESQL);   // AUTH_EFFECTIVE, AUTH_POLICY_VERSION, AUDIT_EVENT, AUDIT_PAYLOAD, ROOT_SEQ
        Jdbc.update(c, "CREATE TABLE AUTH_CONDITION (tenant_id VARCHAR(64), condition_id VARCHAR(64),"
                + " resource_type VARCHAR(64), expression TEXT)");
        Jdbc.update(c, "CREATE TABLE AUTH_MEMBERSHIP (tenant_id VARCHAR(64) NOT NULL, member_id VARCHAR(255) NOT NULL,"
                + " principal_id VARCHAR(255) NOT NULL, source_id VARCHAR(255) NOT NULL, valid_from TIMESTAMP WITH TIME ZONE NOT NULL,"
                + " valid_to TIMESTAMP WITH TIME ZONE, PRIMARY KEY (tenant_id, member_id, principal_id, source_id))");
        Jdbc.update(c, "CREATE TABLE SEC_PERSON (tenant_id VARCHAR(64) NOT NULL, person_id VARCHAR(64) NOT NULL,"
                + " display_name VARCHAR(255), external_subject VARCHAR(255), email VARCHAR(255), status VARCHAR(16) NOT NULL,"
                + " source VARCHAR(16) DEFAULT 'LOCAL' NOT NULL, created_at TIMESTAMP WITH TIME ZONE,"
                + " PRIMARY KEY (tenant_id, person_id))");
        Jdbc.update(c, "CREATE UNIQUE INDEX SEC_PERSON_SUBJECT ON SEC_PERSON (tenant_id, external_subject)");
        Jdbc.update(c, "CREATE TABLE SEC_GROUP (tenant_id VARCHAR(64) NOT NULL, group_id VARCHAR(255) NOT NULL, name VARCHAR(255),"
                + " external_ref VARCHAR(255), source VARCHAR(16) NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL,"
                + " PRIMARY KEY (tenant_id, group_id))");
        Jdbc.update(c, "CREATE TABLE SEC_GROUP_MEMBER (tenant_id VARCHAR(64) NOT NULL, group_id VARCHAR(255) NOT NULL,"
                + " person_id VARCHAR(64) NOT NULL, source VARCHAR(16) NOT NULL, added_at TIMESTAMP WITH TIME ZONE NOT NULL,"
                + " PRIMARY KEY (tenant_id, group_id, person_id))");
        Jdbc.update(c, "CREATE TABLE SEC_API_TOKEN (token_id VARCHAR(64) NOT NULL PRIMARY KEY, tenant_id VARCHAR(64) NOT NULL,"
                + " owner_person_id VARCHAR(64) NOT NULL, name VARCHAR(255), secret_hash VARCHAR(64) NOT NULL, scopes TEXT NOT NULL,"
                + " created_at TIMESTAMP WITH TIME ZONE NOT NULL, expires_at TIMESTAMP WITH TIME ZONE NOT NULL,"
                + " last_used_at TIMESTAMP WITH TIME ZONE, revoked_at TIMESTAMP WITH TIME ZONE, created_by VARCHAR(255))");
        // SEC_SCIM_RESOURCE as requested from the W4-G generator (decision 154)
        Jdbc.update(c, "CREATE TABLE SEC_SCIM_RESOURCE (tenant_id VARCHAR(64) NOT NULL, resource_type VARCHAR(16) NOT NULL"
                + " CHECK (resource_type IN ('USER', 'GROUP')), resource_id VARCHAR(255) NOT NULL, user_name VARCHAR(255),"
                + " display_name VARCHAR(255), name_key VARCHAR(255) NOT NULL, external_id VARCHAR(255), external_key VARCHAR(255) NOT NULL,"
                + " attributes TEXT, version BIGINT NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL,"
                + " updated_at TIMESTAMP WITH TIME ZONE NOT NULL, PRIMARY KEY (tenant_id, resource_type, resource_id))");
        Jdbc.update(c, "CREATE UNIQUE INDEX SEC_SCIM_NAME ON SEC_SCIM_RESOURCE (tenant_id, resource_type, name_key)");
        Jdbc.update(c, "CREATE UNIQUE INDEX SEC_SCIM_EXT ON SEC_SCIM_RESOURCE (tenant_id, resource_type, external_key)");
        Jdbc.update(c, "INSERT INTO AUTH_POLICY_VERSION(tenant_id, policy_version) VALUES (?, 1)", T);
        // what scim.provision must grant: USER and GROUP ADMIN and READ, API_TOKEN ADMIN (revocation on disable)
        long perms = Permission.ADMIN.bit() | Permission.READ.bit();
        for (String type : List.of(ResourceRegistry.USER, ResourceRegistry.GROUP, ResourceRegistry.API_TOKEN)) {
            Jdbc.update(c, "INSERT INTO AUTH_EFFECTIVE(tenant_id, principal_id, resource_type, resource_id, deny_flag, perms,"
                    + " condition_id, cond_key, policy_version) VALUES (?, 'role:scim-client', ?, '*', 0, ?, NULL, '-', 0)", T, type, perms);
        }
        SecurityAuditService audit = new SecurityAuditService(SecurityDialect.H2);
        AuthorizationService authz = new AuthorizationService(
                new PolicyCompiler(SecurityDialect.H2, new ResourceRegistry(), Duration.ZERO), audit);
        MembershipIndex memberships = new MembershipIndex(SecurityDialect.H2, new AuthEffectiveMaintainer(SecurityDialect.H2));
        scim = new ScimService(SecurityDialect.H2, authz, new PersonService(SecurityDialect.H2, authz, audit),
                new GroupAdminService(SecurityDialect.H2, authz, memberships, audit), new ApiTokenService(SecurityDialect.H2, authz, audit),
                new FakeSeats(), new ScimStore(SecurityDialect.H2), ScimService.Config.defaults());
    }

    @AfterEach
    void close() throws Exception {
        c.close();
    }

    /** Postings of the test; the service only reads them. */
    final class FakeSeats implements SecurityPorts.SeatDirectory {
        @Override
        public List<SecurityPorts.SeatHolder> holdersAt(Connection c, String tenantId, String seatId, Instant at) {
            return postings.stream().filter(h -> h.seatId().equals(seatId)).toList();
        }

        @Override
        public List<SecurityPorts.SeatHolder> seatsOf(Connection c, String tenantId, String personId, Instant at) {
            return postings.stream().filter(h -> h.personId().equals(personId)).toList();
        }

        @Override
        public Optional<String> escalationSeat(Connection c, String tenantId, String seatId, Instant at) {
            return Optional.empty();
        }
    }

    static JsonNode body(String json) throws Exception {
        return JSON.readTree(json);
    }

    static JsonNode user(String userName, String externalId) throws Exception {
        return body("{\"schemas\":[\"urn:ietf:params:scim:schemas:core:2.0:User\"],\"userName\":\"" + userName
                + "\",\"externalId\":\"" + externalId + "\",\"displayName\":\"" + userName + " D\","
                + "\"emails\":[{\"value\":\"" + userName + "@x.org\",\"primary\":true}]}");
    }

    static JsonNode group(String name, String... members) throws Exception {
        StringBuilder m = new StringBuilder();
        for (String id : members) {
            m.append(m.isEmpty() ? "" : ",").append("{\"value\":\"").append(id).append("\"}");
        }
        return body("{\"schemas\":[\"urn:ietf:params:scim:schemas:core:2.0:Group\"],\"displayName\":\"" + name
                + "\",\"members\":[" + m + "]}");
    }

    static JsonNode patch(String operations) throws Exception {
        return body("{\"schemas\":[\"urn:ietf:params:scim:api:messages:2.0:PatchOp\"],\"Operations\":" + operations + "}");
    }

    private long count(String sql, Object... params) {
        return Jdbc.one(c, sql, rs -> rs.getLong(1), params).orElse(0L);
    }

    private List<String> events(String type) {
        return Jdbc.query(c, "SELECT p.payload FROM AUDIT_EVENT e JOIN AUDIT_PAYLOAD p ON p.event_id = e.event_id"
                + " WHERE e.tenant_id=? AND e.event_type=? ORDER BY e.seq", rs -> rs.getString(1), T, type);
    }

    private void seat(String personId, String kind) {
        postings.add(new SecurityPorts.SeatHolder("seat-1", personId, "po-1", kind, false,
                Instant.parse("2026-04-01T00:00:00Z"), null, null));
    }

    @Test
    void createProvisionsAPersonLinkedToTheExternalIdSubject() throws Exception {
        ObjectNode u = scim.createUser(c, idp, user("alice", "sub-a"));
        String id = u.get("id").asText();
        assertEquals("alice", u.get("userName").asText());
        assertTrue(u.get("active").asBoolean());
        assertEquals("W/\"1\"", u.get("meta").get("version").asText());
        assertEquals("/scim/v2/Users/" + id, u.get("meta").get("location").asText());
        assertEquals(id, u.get(EXT).get("personId").asText());
        assertEquals(0, u.get(EXT).get("postings").size());
        assertEquals("sub-a", Jdbc.one(c, "SELECT external_subject FROM SEC_PERSON WHERE tenant_id=? AND person_id=?",
                rs -> rs.getString(1), T, id).orElseThrow());
        assertEquals("alice@x.org", Jdbc.one(c, "SELECT email FROM SEC_PERSON WHERE person_id=?", rs -> rs.getString(1), id).orElseThrow());
        assertEquals(1, events("USER_PROVISIONED").size());
        assertEquals(u, scim.getUser(c, idp, id), "GET returns what create returned");
    }

    @Test
    void anExistingPersonWithTheSameSubjectIsAdoptedNotDuplicated() throws Exception {
        Jdbc.update(c, "INSERT INTO SEC_PERSON(tenant_id, person_id, display_name, external_subject, status) VALUES (?,?,?,?,'ACTIVE')",
                T, "p-old", "Old", "sub-o");
        ObjectNode u = scim.createUser(c, idp, user("olga", "sub-o"));
        assertEquals("p-old", u.get("id").asText());
        assertEquals(1, count("SELECT COUNT(*) FROM SEC_PERSON WHERE tenant_id=?", T));
        assertEquals("olga D", Jdbc.one(c, "SELECT display_name FROM SEC_PERSON WHERE person_id='p-old'", rs -> rs.getString(1)).orElseThrow());
        ScimException again = assertThrows(ScimException.class, () -> scim.createUser(c, idp, user("olga2", "sub-o")));
        assertEquals(409, again.status());
        assertEquals("uniqueness", again.scimType());
    }

    @Test
    void userNameIsUniqueIgnoringCaseAndExternalIdExactly() throws Exception {
        scim.createUser(c, idp, user("alice", "sub-a"));
        assertEquals("uniqueness", assertThrows(ScimException.class, () -> scim.createUser(c, idp, user("ALICE", "sub-b"))).scimType());
        assertEquals("uniqueness", assertThrows(ScimException.class, () -> scim.createUser(c, idp, user("bob", "sub-a"))).scimType());
        assertEquals(1, count("SELECT COUNT(*) FROM SEC_PERSON WHERE tenant_id=?", T), "a refused create writes nothing");
    }

    @Test
    void theSubjectAttributeIsRequired() throws Exception {
        ScimException e = assertThrows(ScimException.class, () -> scim.createUser(c, idp,
                body("{\"schemas\":[\"urn:ietf:params:scim:schemas:core:2.0:User\"],\"userName\":\"nosub\"}")));
        assertEquals("invalidValue", e.scimType());
    }

    // ------------------------------------------------------------------ decision 154: postings read-only

    @Test
    void createWithPostingsIsMutabilityAndWritesNothing() throws Exception {
        ObjectNode b = (ObjectNode) user("carol", "sub-c");
        b.putObject(EXT).putArray("postings").addObject().put("seatId", "seat-9").put("kind", "SUBSTANTIVE");
        ScimException e = assertThrows(ScimException.class, () -> scim.createUser(c, idp, b));
        assertEquals(400, e.status());
        assertEquals("mutability", e.scimType());
        assertEquals(0, count("SELECT COUNT(*) FROM SEC_PERSON"));
        assertEquals(0, count("SELECT COUNT(*) FROM SEC_SCIM_RESOURCE"));
    }

    @Test
    void anUnchangedEchoOfPostingsIsAcceptedAndAChangeIsMutability() throws Exception {
        String id = scim.createUser(c, idp, user("dev", "sub-d")).get("id").asText();
        seat(id, "REGULAR");
        ObjectNode current = scim.getUser(c, idp, id);
        assertEquals("SUBSTANTIVE", current.get(EXT).get("postings").get(0).get("kind").asText());
        assertEquals("2026-04-01", current.get(EXT).get("postings").get(0).get("validFrom").asText());

        ObjectNode echo = current.deepCopy();
        echo.put("displayName", "Dev Renamed");
        assertEquals("Dev Renamed", scim.replaceUser(c, idp, id, echo, null).get("displayName").asText(), "unchanged postings pass");

        ObjectNode changed = scim.getUser(c, idp, id).deepCopy();
        ((ObjectNode) changed.get(EXT).get("postings").get(0)).put("kind", "ACTING");
        String before = scim.getUser(c, idp, id).get("meta").get("version").asText();
        assertEquals("mutability", assertThrows(ScimException.class, () -> scim.replaceUser(c, idp, id, changed, null)).scimType());
        assertEquals("mutability", assertThrows(ScimException.class, () -> scim.patchUser(c, idp, id, patch(
                "[{\"op\":\"remove\",\"path\":\"" + EXT + ":postings\"}]"), null)).scimType(), "removing them is a change too");
        assertEquals("mutability", assertThrows(ScimException.class, () -> scim.patchUser(c, idp, id, patch(
                "[{\"op\":\"add\",\"path\":\"" + EXT + ":postings\",\"value\":[{\"seatId\":\"seat-2\"}]}]"), null)).scimType());
        assertEquals(before, scim.getUser(c, idp, id).get("meta").get("version").asText(), "nothing written");
        assertEquals(1, postings.size());
    }

    // ------------------------------------------------------------------ lifecycle

    @Test
    void activeFalseDisablesThePersonAndRevokesItsApiTokens() throws Exception {
        String id = scim.createUser(c, idp, user("erin", "sub-e")).get("id").asText();
        Jdbc.update(c, "INSERT INTO SEC_API_TOKEN(token_id, tenant_id, owner_person_id, name, secret_hash, scopes, created_at,"
                + " expires_at) VALUES ('tok1', ?, ?, 'ci', 'h', '[]', ?, ?)", T, id, Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2099-01-01T00:00:00Z"));
        ObjectNode r = scim.patchUser(c, idp, id, patch("[{\"op\":\"replace\",\"path\":\"active\",\"value\":\"False\"}]"), "W/\"1\"");
        assertFalse(r.get("active").asBoolean());
        assertEquals("W/\"2\"", r.get("meta").get("version").asText());
        assertEquals("DISABLED", Jdbc.one(c, "SELECT status FROM SEC_PERSON WHERE person_id=?", rs -> rs.getString(1), id).orElseThrow());
        assertNotNull(Jdbc.one(c, "SELECT revoked_at FROM SEC_API_TOKEN WHERE token_id='tok1'", rs -> rs.getObject(1)).orElse(null));
        assertEquals(1, events("USER_DISABLED").size());
    }

    @Test
    void activeTrueReEnablesThroughThePersonServiceAndTokensStayRevoked() throws Exception {
        String id = scim.createUser(c, idp, user("kiran", "sub-k")).get("id").asText();
        assertEquals("SCIM", Jdbc.one(c, "SELECT source FROM SEC_PERSON WHERE person_id=?", rs -> rs.getString(1), id).orElseThrow());
        Jdbc.update(c, "INSERT INTO SEC_API_TOKEN(token_id, tenant_id, owner_person_id, name, secret_hash, scopes, created_at,"
                + " expires_at) VALUES ('tok2', ?, ?, 'ci', 'h', '[]', ?, ?)", T, id, Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2099-01-01T00:00:00Z"));
        scim.patchUser(c, idp, id, patch("[{\"op\":\"replace\",\"path\":\"active\",\"value\":false}]"), null);
        ObjectNode r = scim.patchUser(c, idp, id, patch("[{\"op\":\"replace\",\"path\":\"active\",\"value\":true}]"), null);
        assertTrue(r.get("active").asBoolean());
        assertEquals("ACTIVE", Jdbc.one(c, "SELECT status FROM SEC_PERSON WHERE person_id=?", rs -> rs.getString(1), id).orElseThrow());
        assertTrue(events("USER_UPDATED").stream().anyMatch(p -> p.contains("ENABLE")), "audited by the person service");
        assertNotNull(Jdbc.one(c, "SELECT revoked_at FROM SEC_API_TOKEN WHERE token_id='tok2'", rs -> rs.getObject(1)).orElse(null),
                "re-enabling does not bring revoked tokens back");
    }

    @Test
    void aGroupRenameAndExternalIdChangeGoThroughTheGroupService() throws Exception {
        ObjectNode g = scim.createGroup(c, idp, group("Old name"));
        String gid = g.get("id").asText();
        ObjectNode other = scim.createGroup(c, idp, body("{\"schemas\":[\"urn:ietf:params:scim:schemas:core:2.0:Group\"],"
                + "\"displayName\":\"Other\",\"externalId\":\"grp-x\"}"));
        assertNotNull(other.get("id"));
        ObjectNode r = scim.replaceGroup(c, idp, gid, body("{\"schemas\":[\"urn:ietf:params:scim:schemas:core:2.0:Group\"],"
                + "\"displayName\":\"New name\",\"externalId\":\"grp-1\"}"), "W/\"1\"");
        assertEquals("New name", r.get("displayName").asText());
        assertEquals("New name", Jdbc.one(c, "SELECT name FROM SEC_GROUP WHERE group_id=?", rs -> rs.getString(1), gid).orElseThrow());
        assertEquals("grp-1", Jdbc.one(c, "SELECT external_ref FROM SEC_GROUP WHERE group_id=?", rs -> rs.getString(1), gid).orElseThrow());
        assertEquals("uniqueness", assertThrows(ScimException.class, () -> scim.patchGroup(c, idp, gid, patch(
                "[{\"op\":\"replace\",\"path\":\"externalId\",\"value\":\"grp-x\"}]"), null)).scimType());
        assertEquals("grp-1", Jdbc.one(c, "SELECT external_ref FROM SEC_GROUP WHERE group_id=?", rs -> rs.getString(1), gid).orElseThrow());
    }

    @Test
    void aStaleIfMatchIs412AndChangesNothing() throws Exception {
        String id = scim.createUser(c, idp, user("finn", "sub-f")).get("id").asText();
        ScimException e = assertThrows(ScimException.class, () -> scim.replaceUser(c, idp, id, user("finn2", "sub-f"), "W/\"7\""));
        assertEquals(412, e.status());
        assertEquals("finn", scim.getUser(c, idp, id).get("userName").asText());
    }

    @Test
    void deleteDeactivatesKeepsThePersonAndLeavesScimGroups() throws Exception {
        String id = scim.createUser(c, idp, user("gita", "sub-g")).get("id").asText();
        String gid = scim.createGroup(c, idp, group("Clerks", id)).get("id").asText();
        Jdbc.update(c, "INSERT INTO SEC_GROUP(tenant_id, group_id, name, source, created_at) VALUES (?, 'local', 'L', 'LOCAL', ?)",
                T, Instant.parse("2026-01-01T00:00:00Z"));
        Jdbc.update(c, "INSERT INTO SEC_GROUP_MEMBER(tenant_id, group_id, person_id, source, added_at) VALUES (?, 'local', ?, 'LOCAL', ?)",
                T, id, Instant.parse("2026-01-01T00:00:00Z"));
        seat(id, "REGULAR");
        scim.deleteUser(c, idp, id, null);
        assertEquals(404, assertThrows(ScimException.class, () -> scim.getUser(c, idp, id)).status());
        assertEquals("DISABLED", Jdbc.one(c, "SELECT status FROM SEC_PERSON WHERE person_id=?", rs -> rs.getString(1), id).orElseThrow());
        assertEquals(0, count("SELECT COUNT(*) FROM SEC_GROUP_MEMBER WHERE group_id=?", gid));
        assertEquals(1, count("SELECT COUNT(*) FROM SEC_GROUP_MEMBER WHERE group_id='local'"), "groups managed elsewhere are kept");
        assertEquals("W/\"2\"", scim.getGroup(c, idp, gid).get("meta").get("version").asText(), "the group's ETag moved");
        assertEquals(1, postings.size(), "postings are not touched");
    }

    // ------------------------------------------------------------------ lists

    @Test
    void listsFilterAndPage() throws Exception {
        scim.createUser(c, idp, user("anna", "ext-1"));
        scim.createUser(c, idp, user("arjun", "ext-2"));
        scim.createUser(c, idp, user("bala", "other-3"));
        ObjectNode all = scim.listUsers(c, idp, null, 2, 1);
        assertEquals(3, all.get("totalResults").asInt());
        assertEquals(2, all.get("startIndex").asInt());
        assertEquals(1, all.get("itemsPerPage").asInt());
        Set<String> seen = new java.util.HashSet<>();
        for (int start = 1; start <= 3; start++) {
            seen.add(scim.listUsers(c, idp, null, start, 1).get("Resources").get(0).get("userName").asText());
        }
        assertEquals(Set.of("anna", "arjun", "bala"), seen, "stable order: every page holds a different user");
        assertEquals("anna", scim.listUsers(c, idp, "userName eq \"ANNA\"", null, null).get("Resources").get(0).get("userName").asText());
        assertEquals(2, scim.listUsers(c, idp, "externalId sw \"ext-\"", null, null).get("totalResults").asInt());
        assertEquals(0, scim.listUsers(c, idp, "externalId eq \"EXT-1\"", null, null).get("totalResults").asInt(), "case-exact");
        assertEquals(1, scim.listUsers(c, idp, "displayName co \"BALA\"", null, null).get("totalResults").asInt());
        assertEquals(0, scim.listUsers(c, idp, "userName co \"%\"", null, null).get("totalResults").asInt(), "% is literal");
        assertEquals(0, scim.listUsers(c, idp, null, 1, 0).get("Resources").size(), "count 0 gives the total only");
        assertEquals("invalidFilter", assertThrows(ScimException.class,
                () -> scim.listGroups(c, idp, "userName eq \"a\"", null, null)).scimType());
    }

    // ------------------------------------------------------------------ groups

    @Test
    void groupsHoldProvisionedUsersOnlyAndPatchChangesMemberships() throws Exception {
        String a = scim.createUser(c, idp, user("hari", "sub-h")).get("id").asText();
        String b = scim.createUser(c, idp, user("isha", "sub-i")).get("id").asText();
        assertEquals("invalidValue", assertThrows(ScimException.class, () -> scim.createGroup(c, idp, group("X", "nobody"))).scimType());
        ObjectNode g = scim.createGroup(c, idp, group("Approvers", a));
        String gid = g.get("id").asText();
        assertEquals("SCIM", Jdbc.one(c, "SELECT source FROM SEC_GROUP WHERE group_id=?", rs -> rs.getString(1), gid).orElseThrow());
        assertEquals(1, count("SELECT COUNT(*) FROM AUTH_MEMBERSHIP WHERE source_id=?", "grp:" + gid + ":" + a));

        ObjectNode r = scim.patchGroup(c, idp, gid, patch("[{\"op\":\"add\",\"path\":\"members\",\"value\":[{\"value\":\"" + b + "\"}]},"
                + "{\"op\":\"remove\",\"path\":\"members[value eq \\\"" + a + "\\\"]\"}]"), null);
        assertEquals(1, r.get("members").size());
        assertEquals(b, r.get("members").get(0).get("value").asText());
        assertEquals(0, count("SELECT COUNT(*) FROM AUTH_MEMBERSHIP WHERE source_id=?", "grp:" + gid + ":" + a));
        assertEquals(gid, scim.getUser(c, idp, b).get("groups").get(0).get("value").asText());
        assertTrue(events("MEMBERSHIP_CHANGED").size() >= 3);

        assertEquals("invalidValue", assertThrows(ScimException.class, () -> scim.patchGroup(c, idp, gid, patch(
                "[{\"op\":\"add\",\"path\":\"members\",\"value\":[{\"value\":\"" + gid + "\",\"type\":\"Group\"}]}]"), null)).scimType());

        scim.deleteGroup(c, idp, gid, null);
        assertEquals(0, count("SELECT COUNT(*) FROM SEC_GROUP WHERE group_id=?", gid));
        assertEquals(404, assertThrows(ScimException.class, () -> scim.getGroup(c, idp, gid)).status());
    }

    @Test
    void aCallerWithoutTheProvisioningGrantIsRefused() throws Exception {
        SecurityIdentity plain = new SecurityIdentity(T, "someone", "someone", Set.of(), Set.of(), Set.of(), Set.of(), "OIDC",
                null, null, false, Map.of());
        assertThrows(SangamSecurityException.class, () -> scim.createUser(c, plain, user("j", "sub-j")));
        assertThrows(SangamSecurityException.class, () -> scim.listGroups(c, plain, null, null, null));
        assertEquals(0, count("SELECT COUNT(*) FROM SEC_PERSON"));
    }
}
