// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.breakglass;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
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
import org.sangam.security.admin.ApiTokenService;
import org.sangam.security.admin.TenantAdminService;
import org.sangam.security.audit.SecurityAuditService;
import org.sangam.security.dialect.SecurityDialect;
import org.sangam.security.policy.AuthorizationService;
import org.sangam.security.policy.Permission;
import org.sangam.security.policy.PolicyCompiler;
import org.sangam.security.policy.ResourceRegistry;

/**
 * M-084 break-glass access (AJ-05 step 7): token classes from tenant config (owner decision 49), dual approval with
 * segregation of duties, a time-boxed token returned once, an alarmed audit event on every use, and the review task.
 * H2 in PostgreSQL mode; written for the test phase (not run here).
 */
class BreakGlassServiceH2Test {

    static final String T = "t";
    static final String CLASS_CONFIG = "{\"tokenClasses\":{\"emergency-admin\":{\"breakGlass\":true,"
            + "\"roles\":[\"tenant-admin\"],\"validMinutes\":60,\"approvals\":2},"
            + "\"ordinary\":{\"roles\":[\"reader\"],\"validMinutes\":60}}}";

    private Connection c;
    private ApiTokenService tokens;
    private BreakGlassService service;
    private final List<Map<String, Object>> alarms = new ArrayList<>();
    /** Roles each person holds (decision 358); the holder holds no break-glass role of its own. */
    private final Map<String, Set<String>> heldRoles = Map.of("holder", Set.of("reader"));

    @BeforeEach
    void db() throws Exception {
        c = DriverManager.getConnection("jdbc:h2:mem:bg" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=TRUE", "sa", "");
        Schema.create(c, Dialect.POSTGRESQL);   // AUTH_EFFECTIVE, AUTH_POLICY_VERSION, AUDIT_EVENT, AUDIT_PAYLOAD, ROOT_SEQ
        Jdbc.update(c, "CREATE TABLE AUTH_CONDITION (tenant_id VARCHAR(64), condition_id VARCHAR(64),"
                + " resource_type VARCHAR(64), expression TEXT)");
        Jdbc.update(c, "CREATE TABLE SEC_TENANT (tenant_id VARCHAR(64) PRIMARY KEY, name_en VARCHAR(255), name_hi VARCHAR(255),"
                + " name_local VARCHAR(255), hosting_profile VARCHAR(64), status VARCHAR(16), config TEXT, quotas TEXT,"
                + " four_eyes SMALLINT)");
        Jdbc.update(c, "CREATE TABLE SEC_PERSON (tenant_id VARCHAR(64) NOT NULL, person_id VARCHAR(64) NOT NULL,"
                + " display_name VARCHAR(255), external_subject VARCHAR(255), email VARCHAR(255), status VARCHAR(16) NOT NULL,"
                + " source VARCHAR(16) DEFAULT 'LOCAL' NOT NULL, created_at TIMESTAMP WITH TIME ZONE,"
                + " PRIMARY KEY (tenant_id, person_id))");
        Jdbc.update(c, "CREATE TABLE SEC_API_TOKEN (token_id VARCHAR(64) NOT NULL PRIMARY KEY, tenant_id VARCHAR(64) NOT NULL,"
                + " owner_person_id VARCHAR(64) NOT NULL, name VARCHAR(255), secret_hash VARCHAR(64) NOT NULL, scopes TEXT NOT NULL,"
                + " created_at TIMESTAMP WITH TIME ZONE NOT NULL, expires_at TIMESTAMP WITH TIME ZONE NOT NULL,"
                + " last_used_at TIMESTAMP WITH TIME ZONE, revoked_at TIMESTAMP WITH TIME ZONE, created_by VARCHAR(255))");
        Jdbc.update(c, "CREATE TABLE SEC_BREAK_GLASS (tenant_id VARCHAR(64) NOT NULL, grant_id VARCHAR(64) NOT NULL,"
                + " token_class VARCHAR(64) NOT NULL, holder_person_id VARCHAR(64) NOT NULL, requested_by VARCHAR(255) NOT NULL,"
                + " reason TEXT NOT NULL, status VARCHAR(16) NOT NULL, required_approvals INTEGER NOT NULL, approvals TEXT NOT NULL,"
                + " valid_minutes INTEGER NOT NULL, token_id VARCHAR(64), requested_at TIMESTAMP WITH TIME ZONE NOT NULL,"
                + " issued_at TIMESTAMP WITH TIME ZONE, expires_at TIMESTAMP WITH TIME ZONE, first_used_at TIMESTAMP WITH TIME ZONE,"
                + " last_used_at TIMESTAMP WITH TIME ZONE, use_count BIGINT NOT NULL, review_status VARCHAR(16) NOT NULL,"
                + " reviewed_by VARCHAR(255), review_outcome VARCHAR(16), review_notes TEXT, reviewed_at TIMESTAMP WITH TIME ZONE,"
                + " PRIMARY KEY (tenant_id, grant_id))");
        Jdbc.update(c, "INSERT INTO SEC_TENANT VALUES (?, 'T', NULL, NULL, NULL, 'ACTIVE', ?, '{}', 1)", T, CLASS_CONFIG);
        Jdbc.update(c, "INSERT INTO SEC_TENANT VALUES ('u', 'U', NULL, NULL, NULL, 'ACTIVE', '{}', '{}', 1)");
        // changeset 0043-11 (decision 97): the isolation column, SHARED for the rows above
        Jdbc.update(c, "ALTER TABLE SEC_TENANT ADD COLUMN isolation VARCHAR(16) DEFAULT 'SHARED' NOT NULL");
        for (String p : List.of("req", "ap1", "ap2", "holder")) {
            Jdbc.update(c, "INSERT INTO SEC_PERSON(tenant_id, person_id, status) VALUES (?,?,'ACTIVE')", T, p);
        }
        Jdbc.update(c, "INSERT INTO SEC_PERSON(tenant_id, person_id, status) VALUES (?, 'gone', 'DISABLED')", T);
        Jdbc.update(c, "INSERT INTO AUTH_POLICY_VERSION(tenant_id, policy_version) VALUES (?, 1)", T);
        Jdbc.update(c, "INSERT INTO AUTH_POLICY_VERSION(tenant_id, policy_version) VALUES ('u', 1)");
        Jdbc.update(c, "INSERT INTO AUTH_EFFECTIVE(tenant_id, principal_id, resource_type, resource_id, deny_flag, perms,"
                        + " condition_id, cond_key, policy_version) VALUES (?, 'role:tenant-admin', ?, '*', 0, ?, NULL, '-', 0)",
                T, ResourceRegistry.API_TOKEN, Permission.ADMIN.bit() | Permission.READ.bit());

        SecurityAuditService audit = new SecurityAuditService(SecurityDialect.H2);
        AuthorizationService authz = new AuthorizationService(
                new PolicyCompiler(SecurityDialect.H2, new ResourceRegistry(), Duration.ZERO), audit);
        // decision 358: the roles each person holds (an ordinary token's scopes must be among them)
        tokens = new ApiTokenService(SecurityDialect.H2, authz, audit, (conn, tenant, person) -> heldRoles.getOrDefault(person, Set.of()));
        service = new BreakGlassService(SecurityDialect.H2, authz, audit, new TenantAdminService(SecurityDialect.H2, authz, audit),
                tokens);
        tokens.useBreakGlass(service::used);
        service.useAlarm((tenant, details) -> alarms.add(Map.copyOf(details)));
    }

    @AfterEach
    void close() throws Exception {
        c.close();
    }

    static SecurityIdentity admin(String person) {
        return new SecurityIdentity(T, person, person, Set.of(), Set.of("tenant-admin"), Set.of(), Set.of(), "OIDC",
                "10.0.0.1", null, false, Map.of());
    }

    static SecurityIdentity plain(String person) {
        return new SecurityIdentity(T, person, person, Set.of(), Set.of(), Set.of(), Set.of(), "OIDC", null, null, false,
                Map.of());
    }

    private List<String> events(String type) {
        return Jdbc.query(c, "SELECT p.payload FROM AUDIT_EVENT e JOIN AUDIT_PAYLOAD p ON p.event_id = e.event_id"
                + " WHERE e.tenant_id=? AND e.event_type=? ORDER BY e.seq", rs -> rs.getString(1), T, type);
    }

    private BreakGlassService.Grant requested() {
        return service.request(c, admin("req"), "emergency-admin", "holder", "payment run blocked, IdP down");
    }

    @Test
    void tokenClassesComeFromTenantConfigAndOnlyBreakGlassClassesCount() {
        Map<String, BreakGlassService.TokenClass> classes = service.tokenClasses(c, T);
        assertEquals(Set.of("emergency-admin"), classes.keySet(), "a class without breakGlass:true is not a break-glass class");
        BreakGlassService.TokenClass k = classes.get("emergency-admin");
        assertEquals(Set.of("tenant-admin"), k.roles());
        assertEquals(60, k.validMinutes());
        assertEquals(2, k.approvals());
        assertTrue(service.tokenClasses(c, "u").isEmpty());
    }

    @Test
    void aTenantWithoutBreakGlassConfigGets404() {
        SecurityIdentity other = new SecurityIdentity("u", "x", "x", Set.of(), Set.of(), Set.of(), Set.of(), "OIDC", null,
                null, false, Map.of());
        Jdbc.update(c, "INSERT INTO AUTH_EFFECTIVE(tenant_id, principal_id, resource_type, resource_id, deny_flag, perms,"
                        + " condition_id, cond_key, policy_version) VALUES ('u', 'user:x', ?, '*', 0, ?, NULL, '-', 0)",
                ResourceRegistry.API_TOKEN, Permission.ADMIN.bit() | Permission.READ.bit());
        SangamSecurityException e = assertThrows(SangamSecurityException.class,
                () -> service.request(c, other, "emergency-admin", "x", "reason"));
        assertEquals("BREAK_GLASS_NOT_CONFIGURED", e.code());
        assertEquals(404, e.httpStatus());
        for (Runnable op : List.<Runnable>of(() -> service.list(c, other, null, null, 0, 10),
                () -> service.count(c, other, null, null), () -> service.reject(c, other, "g", "r"),
                () -> service.review(c, other, "g", "ACCEPTED", "n"), () -> service.approve(c, other, "g"))) {
            assertEquals(404, assertThrows(SangamSecurityException.class, op::run).httpStatus(),
                    "every operation is off without a break-glass class");
        }
    }

    @Test
    void anInvalidClassIsRefused() {
        assertThrows(SangamSecurityException.AdminConflict.class, () -> BreakGlassService.parse("k",
                Map.of("breakGlass", true, "roles", List.of("a"), "validMinutes", 60, "approvals", 1)),
                "one approver is not dual control");
        assertThrows(SangamSecurityException.AdminConflict.class, () -> BreakGlassService.parse("k",
                Map.of("breakGlass", true, "roles", List.of(), "validMinutes", 60)));
        assertThrows(SangamSecurityException.AdminConflict.class, () -> BreakGlassService.parse("k",
                Map.of("breakGlass", true, "roles", List.of("a"), "validMinutes", BreakGlassService.MAX_VALID_MINUTES + 1)));
        assertThrows(SangamSecurityException.AdminConflict.class, () -> BreakGlassService.parse("k",
                Map.of("breakGlass", true, "roles", List.of("a"))), "validMinutes is required");
    }

    @Test
    void aRequestNeedsAdminAReasonAndAnActiveHolder() {
        assertThrows(SangamSecurityException.AccessDenied.class,
                () -> service.request(c, plain("req"), "emergency-admin", "holder", "reason"));
        assertThrows(SangamSecurityException.AdminConflict.class,
                () -> service.request(c, admin("req"), "emergency-admin", "holder", " "));
        assertThrows(SangamSecurityException.AdminConflict.class,
                () -> service.request(c, admin("req"), "emergency-admin", "gone", "reason"));
        assertThrows(SangamSecurityException.AdminConflict.class,
                () -> service.request(c, admin("req"), "ordinary", "holder", "reason"));

        BreakGlassService.Grant g = requested();
        assertEquals("REQUESTED", g.status());
        assertEquals(2, g.requiredApprovals());
        assertEquals(List.of(), g.approvals());
        assertEquals("NONE", g.reviewStatus());
        assertEquals(1, events("BREAK_GLASS_REQUESTED").size());
    }

    @Test
    void theRequesterAndTheHolderCannotApproveAndNobodyApprovesTwice() {
        BreakGlassService.Grant g = requested();
        assertThrows(SangamSecurityException.SodViolation.class, () -> service.approve(c, admin("req"), g.grantId()));
        assertThrows(SangamSecurityException.SodViolation.class, () -> service.approve(c, admin("holder"), g.grantId()));
        assertTrue(service.approve(c, admin("ap1"), g.grantId()).token().isEmpty());
        assertThrows(SangamSecurityException.AdminConflict.class, () -> service.approve(c, admin("ap1"), g.grantId()));
        assertEquals("REQUESTED", service.find(c, T, g.grantId()).orElseThrow().status());
        assertEquals(0, Jdbc.one(c, "SELECT COUNT(*) FROM SEC_API_TOKEN", rs -> rs.getInt(1)).orElseThrow(),
                "one approval issues nothing");
    }

    @Test
    void theSecondApprovalIssuesATimeBoxedTokenOnce() {
        BreakGlassService.Grant g = requested();
        service.approve(c, admin("ap1"), g.grantId());
        BreakGlassService.Approval a = service.approve(c, admin("ap2"), g.grantId());

        assertTrue(a.token().isPresent());
        ApiTokenService.IssuedToken token = a.token().get();
        assertTrue(token.plaintext().startsWith(ApiTokenService.PREFIX));
        BreakGlassService.Grant issued = a.grant();
        assertEquals("ISSUED", issued.status());
        assertEquals(List.of("ap1", "ap2"), issued.approvals());
        assertEquals(token.tokenId(), issued.tokenId());
        assertEquals(Duration.ofMinutes(60), Duration.between(issued.issuedAt(), issued.expiresAt()));
        assertEquals("break-glass:emergency-admin", Jdbc.one(c, "SELECT name FROM SEC_API_TOKEN WHERE token_id=?",
                rs -> rs.getString(1), token.tokenId()).orElseThrow());
        assertEquals("holder", Jdbc.one(c, "SELECT owner_person_id FROM SEC_API_TOKEN WHERE token_id=?",
                rs -> rs.getString(1), token.tokenId()).orElseThrow());
        assertEquals(2, events("BREAK_GLASS_APPROVED").size());
        assertEquals(1, events("BREAK_GLASS_ISSUED").size());
        assertTrue(events("BREAK_GLASS_ISSUED").get(0).indexOf(token.plaintext()) < 0, "the secret is never audited");
        assertThrows(SangamSecurityException.AdminConflict.class, () -> service.approve(c, admin("ap1"), g.grantId()),
                "an issued grant takes no more approvals");
    }

    @Test
    void everyUseIsAuditedWithAnAlarmAndOpensTheReview() {
        BreakGlassService.Grant g = requested();
        service.approve(c, admin("ap1"), g.grantId());
        String plaintext = service.approve(c, admin("ap2"), g.grantId()).token().orElseThrow().plaintext();

        Optional<SecurityIdentity> first = tokens.authenticate(c, plaintext, "192.0.2.7");
        assertTrue(first.isPresent());
        SecurityIdentity who = first.get();
        assertEquals("holder", who.personId());
        assertEquals("API_KEY", who.authMethod());
        assertEquals(Set.of("tenant-admin"), who.roles());
        assertEquals(g.grantId(), who.attributes().get(BreakGlassService.ATTR_GRANT));
        assertEquals("emergency-admin", who.attributes().get(BreakGlassService.ATTR_TOKEN_CLASS));
        tokens.authenticate(c, plaintext, "192.0.2.7");

        BreakGlassService.Grant used = service.find(c, T, g.grantId()).orElseThrow();
        assertEquals(2, used.useCount());
        assertEquals("PENDING", used.reviewStatus());
        assertNotNull(used.firstUsedAt());
        List<String> events = events("BREAK_GLASS_USED");
        assertEquals(2, events.size());
        assertTrue(java.util.regex.Pattern.compile("\"alarm\"\\s*:\\s*true").matcher(events.get(0)).find(), events.get(0));
        assertEquals(2, alarms.size());
        assertEquals(true, alarms.get(0).get("alarm"));
        assertEquals("192.0.2.7", alarms.get(0).get("client_ip"));
        assertEquals(2L, alarms.get(1).get("use_count"));
        assertEquals(List.of(g.grantId()), service.list(c, admin("ap1"), null, "PENDING", 0, 50).stream()
                .map(BreakGlassService.Grant::grantId).toList(), "the pending reviews are the review task list");
    }

    @Test
    void aFailingAlarmSinkNeverBlocksTheAccess() {
        service.useAlarm((tenant, details) -> {
            throw new IllegalStateException("sink down");
        });
        BreakGlassService.Grant g = requested();
        service.approve(c, admin("ap1"), g.grantId());
        String plaintext = service.approve(c, admin("ap2"), g.grantId()).token().orElseThrow().plaintext();
        assertTrue(tokens.authenticate(c, plaintext, null).isPresent());
        assertEquals(1, events("BREAK_GLASS_USED").size(), "the durable audit record is written regardless");
    }

    @Test
    void anOrdinaryTokenNamedLikeBreakGlassIsNotABreakGlassUse() {
        ApiTokenService.IssuedToken t = tokens.issue(c, admin("holder"), "holder", "break-glass:fake", Set.of("reader"),
                java.time.Instant.now().plusSeconds(600));
        SecurityIdentity who = tokens.authenticate(c, t.plaintext(), null).orElseThrow();
        assertNull(who.attributes().get(BreakGlassService.ATTR_GRANT), "the grant row decides, not the name");
        assertEquals(0, events("BREAK_GLASS_USED").size());
        assertTrue(alarms.isEmpty());
    }

    @Test
    void breakGlassRolesComeFromTheTokenClassNotFromTheHoldersRoles() {
        // decision 358: the holder holds no tenant-admin role, so an ordinary token with it is refused by name ...
        SangamSecurityException.AdminConflict refused = assertThrows(SangamSecurityException.AdminConflict.class,
                () -> tokens.issue(c, admin("ap1"), "holder", "ordinary", Set.of("tenant-admin"),
                        java.time.Instant.now().plusSeconds(600)));
        assertEquals(ApiTokenService.SCOPE_NOT_HELD, refused.code());
        assertTrue(refused.getMessage().contains("tenant-admin"), refused.getMessage());
        // ... while the break-glass path, after dual approval, issues the class roles
        BreakGlassService.Grant g = requested();
        service.approve(c, admin("ap1"), g.grantId());
        String plaintext = service.approve(c, admin("ap2"), g.grantId()).token().orElseThrow().plaintext();
        assertEquals(Set.of("tenant-admin"), tokens.authenticate(c, plaintext, null).orElseThrow().roles());
    }

    @Test
    void theBreakGlassIssuerIsTakenOnceByTheBreakGlassService() {
        // decision 358: no caller other than BreakGlassService reaches the break-glass issuer
        assertThrows(IllegalStateException.class, () -> tokens.breakGlassIssuer());
    }

    @Test
    void theReviewIsClosedByAnAdministratorOtherThanTheHolder() {
        BreakGlassService.Grant g = requested();
        service.approve(c, admin("ap1"), g.grantId());
        String plaintext = service.approve(c, admin("ap2"), g.grantId()).token().orElseThrow().plaintext();
        assertThrows(SangamSecurityException.AdminConflict.class,
                () -> service.review(c, admin("ap1"), g.grantId(), "ACCEPTED", "ok"), "no review before the first use");
        tokens.authenticate(c, plaintext, null);

        assertThrows(SangamSecurityException.SodViolation.class,
                () -> service.review(c, admin("holder"), g.grantId(), "ACCEPTED", "fine"));
        assertThrows(SangamSecurityException.AdminConflict.class,
                () -> service.review(c, admin("ap1"), g.grantId(), "MAYBE", "notes"));
        assertThrows(SangamSecurityException.AdminConflict.class,
                () -> service.review(c, admin("ap1"), g.grantId(), "ACCEPTED", " "));
        assertThrows(SangamSecurityException.AccessDenied.class,
                () -> service.review(c, plain("ap1"), g.grantId(), "ACCEPTED", "notes"));

        BreakGlassService.Grant done = service.review(c, admin("ap1"), g.grantId(), "ESCALATED", "used outside the incident");
        assertEquals("DONE", done.reviewStatus());
        assertEquals("ESCALATED", done.reviewOutcome());
        assertEquals("ap1", done.reviewedBy());
        assertEquals(1, events("BREAK_GLASS_REVIEWED").size());
        tokens.authenticate(c, plaintext, null);
        assertEquals("DONE", service.find(c, T, g.grantId()).orElseThrow().reviewStatus(),
                "a later use is still alarmed but does not reopen a closed review");
        assertEquals(2, events("BREAK_GLASS_USED").size());
    }

    @Test
    void aRejectedRequestCannotBeApproved() {
        BreakGlassService.Grant g = requested();
        assertEquals("REJECTED", service.reject(c, admin("ap1"), g.grantId(), "not an emergency").status());
        assertThrows(SangamSecurityException.AdminConflict.class, () -> service.approve(c, admin("ap2"), g.grantId()));
        assertEquals(1, events("BREAK_GLASS_REJECTED").size());
    }

    @Test
    void aClassRemovedFromConfigAfterTheRequestIssuesNothing() {
        BreakGlassService.Grant g = requested();
        service.approve(c, admin("ap1"), g.grantId());
        Jdbc.update(c, "UPDATE SEC_TENANT SET config='{}' WHERE tenant_id=?", T);
        assertThrows(SangamSecurityException.class, () -> service.approve(c, admin("ap2"), g.grantId()));
        assertEquals(0, Jdbc.one(c, "SELECT COUNT(*) FROM SEC_API_TOKEN", rs -> rs.getInt(1)).orElseThrow());
    }
}
