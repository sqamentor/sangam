// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.Hashes;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;
import org.sangam.core.security.SangamSecurityException;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.persistence.SangamSchema;
import org.sangam.security.SangamSecurity;
import org.sangam.security.admin.PolicyAdminService;
import org.sangam.security.admin.PolicyAdminService.PolicyDefinition;
import org.sangam.security.dialect.SecurityDialect;
import org.sangam.security.policy.Permission;

/**
 * Decision 392 (one policy id per principal) and its start-time data migration (decision 409), on the real H2 product
 * schema. A legacy multi-principal policy is built the way the code before decision 392 stored it: one SEC_POLICY row
 * whose definition names two subjects, and both subjects' grant rows under the one source {@code policy:<id>}.
 *
 * <p>Before the change: {@code create} stored a two-subject policy under one id, and no split existed (the split and
 * supersede tests do not compile against the old service).
 */
class PolicySplitH2Test {

    private static final String T = "t392";

    private SangamSecurity security;
    private Transactions tx;
    private final SecurityIdentity platform = new SecurityIdentity(T, "requester", "requester", Set.of(),
            Set.of(org.sangam.security.admin.TenantAdminService.PLATFORM_ADMIN_ROLE, "system"), Set.of(), Set.of(), "SYSTEM",
            null, "test", true, Map.of());
    private final SecurityIdentity approver = new SecurityIdentity(T, "approver", "approver", Set.of(),
            Set.of("system"), Set.of(), Set.of(), "SYSTEM", null, "test", true, Map.of());

    @BeforeEach
    void schema() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:ps" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=30000");
        SangamSchema.apply(ds);
        try (Connection c = ds.getConnection()) {
            security = new SangamSecurity(SecurityDialect.detect(c), null);
        }
        security.roles().usePlatformRoles(SecurityWiring.platformRoles());
        tx = Transactions.of(ds);
        tx.inTransaction(c -> {
            security.tenants().createTenant(c, platform, T, T, null, null, "dev", Map.of());
            Jdbc.update(c, "UPDATE SEC_TENANT SET four_eyes=0 WHERE tenant_id=?", T);
            return null;
        });
    }

    private static PolicyDefinition def(String id, String... subjects) {
        return new PolicyDefinition(id, "p " + id, PolicyAdminService.Effect.ALLOW, List.of(subjects), "*", "*",
                EnumSet.of(Permission.READ), null);
    }

    /** Builds the pre-392 storage of policy {@code id} naming {@code a} and {@code b}. */
    private void legacyTwoPrincipalPolicy(String id, String a, String b) {
        tx.inTransaction(c -> {
            security.policies().create(c, platform, def(id, a), "seed");
            security.policies().create(c, platform, def(id + "-other", b), "seed");
            String src = "policy:" + id;
            Jdbc.update(c, "UPDATE AUTH_GRANT_SOURCE SET source_id=?, source_key=? WHERE tenant_id=? AND source_id=?",
                    src, Hashes.hex(Hashes.sha256(src)), T, "policy:" + id + "-other");
            Jdbc.update(c, "DELETE FROM SEC_POLICY WHERE tenant_id=? AND policy_id=?", T, id + "-other");
            Jdbc.update(c, "UPDATE SEC_POLICY SET definition=? WHERE tenant_id=? AND policy_id=?",
                    legacyJson(id, a, b), T, id);
            return null;
        });
    }

    private static String legacyJson(String id, String a, String b) {
        return "{\"name\":\"p " + id + "\",\"effect\":\"ALLOW\",\"subjects\":[\"" + a + "\",\"" + b
                + "\"],\"resourceType\":\"*\",\"resourceId\":\"*\",\"permissions\":[\"READ\"]}";
    }

    private List<PolicyDefinition> active() {
        return tx.inTransaction(c -> security.policies().list(c, platform, 0, 100));
    }

    private int count(String sql, Object... args) {
        return tx.inTransaction(c -> Jdbc.one(c, sql, rs -> rs.getInt(1), args).orElse(0));
    }

    private int grantEvents() {
        return count("SELECT COUNT(*) FROM AUDIT_EVENT WHERE tenant_id=? AND event_type='GRANT_CHANGED'", T);
    }

    private int effective(String principal) {
        return count("SELECT COUNT(*) FROM AUTH_EFFECTIVE WHERE tenant_id=? AND principal_id=? AND perms<>0", T, principal);
    }

    @Test
    void aPolicyNamingTwoPrincipalsIsRefused() {
        var e = assertThrows(SangamSecurityException.PolicyRejected.class, () -> tx.inTransaction(c ->
                security.policies().create(c, platform, def("two", "user:a", "user:b"), "test")));
        assertTrue(e.getMessage().contains("392"), e.getMessage());
        assertTrue(active().isEmpty());
    }

    @Test
    void theSplitGivesEachPrincipalItsOwnIdKeepsTheGrantsAndAuditsOnce() {
        legacyTwoPrincipalPolicy("p1", "user:a", "group:g");
        int before = grantEvents();
        var result = SecurityWiring.splitPolicies(security, tx);
        List<String> ids = result.get(T).splitPolicies().get("p1");
        assertEquals(2, ids.size());
        assertEquals("p1", ids.get(0), "the first principal keeps the id");
        List<PolicyDefinition> rows = active();
        assertEquals(2, rows.size());
        assertEquals(2, rows.stream().map(PolicyDefinition::policyId).distinct().count(), "no two rows share an id");
        assertTrue(rows.stream().allMatch(d -> d.subjects().size() == 1));
        assertEquals(1, count("SELECT COUNT(*) FROM AUTH_GRANT_SOURCE WHERE tenant_id=? AND source_id='policy:p1'", T));
        assertEquals(1, effective("user:a"));
        assertEquals(1, effective("group:g"));
        assertEquals(before + 1, grantEvents(), "one audit event per split");
        assertTrue(tx.inTransaction(c -> security.maintainer().verify(c, T)).isEmpty(), "no drift");

        // deleting by id now removes only that principal
        tx.inTransaction(c -> security.policies().delete(c, platform, "p1", "test"));
        assertEquals(0, effective("user:a"));
        assertEquals(1, effective("group:g"));
    }

    @Test
    void theSplitIsIdempotent() {
        legacyTwoPrincipalPolicy("p1", "user:a", "group:g");
        SecurityWiring.splitPolicies(security, tx);
        int events = grantEvents();
        assertTrue(SecurityWiring.splitPolicies(security, tx).isEmpty());
        assertEquals(events, grantEvents());
        assertEquals(2, active().size());
    }

    @Test
    void twoNodesStartingTogetherSplitOnceAndAuditOnce() throws Exception {
        legacyTwoPrincipalPolicy("p1", "user:a", "group:g");
        int before = grantEvents();
        CyclicBarrier go = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Map<String, PolicyAdminService.SplitResult>>> runs = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                runs.add(pool.submit(() -> {
                    go.await();
                    return SecurityWiring.splitPolicies(security, tx);
                }));
            }
            int splitting = 0;
            for (var f : runs) {
                splitting += f.get().isEmpty() ? 0 : 1;
            }
            assertEquals(1, splitting, "exactly one node split");
        } finally {
            pool.shutdownNow();
        }
        assertEquals(2, active().size());
        assertEquals(before + 1, grantEvents());
    }

    @Test
    void aFailedAuditStopsTheSplitWithNothingApplied() {
        legacyTwoPrincipalPolicy("p1", "user:a", "group:g");
        tx.inTransaction(c -> Jdbc.update(c, "ALTER TABLE AUDIT_PAYLOAD RENAME TO AUDIT_PAYLOAD_GONE"));
        assertThrows(RuntimeException.class, () -> SecurityWiring.splitPolicies(security, tx));
        tx.inTransaction(c -> Jdbc.update(c, "ALTER TABLE AUDIT_PAYLOAD_GONE RENAME TO AUDIT_PAYLOAD"));
        assertEquals(1, active().size(), "no new policy id");
        assertEquals(2, active().get(0).subjects().size(), "the original definition is untouched");
        assertEquals(2, count("SELECT COUNT(*) FROM AUTH_GRANT_SOURCE WHERE tenant_id=? AND source_id='policy:p1'", T));
        // the next start completes it
        assertEquals(2, SecurityWiring.splitPolicies(security, tx).get(T).splitPolicies().get("p1").size());
    }

    @Test
    void anOldMultiPrincipalApprovalCannotRestoreAGrant() {
        legacyTwoPrincipalPolicy("p1", "user:a", "group:g");
        tx.inTransaction(c -> {
            Jdbc.update(c, "UPDATE SEC_TENANT SET four_eyes=1 WHERE tenant_id=?", T);
            // stored before decision 392: a pending CREATE naming two principals, and a pending DELETE of p1
            insertPending(c, "chg-create", "x1", "CREATE", legacyJson("x1", "user:c", "user:d"));
            insertPending(c, "chg-delete", "p1", "DELETE", legacyJson("p1", "user:a", "group:g"));
            return null;
        });
        var r = SecurityWiring.splitPolicies(security, tx).get(T);
        assertEquals(List.of("chg-create", "chg-delete"), r.supersededChanges());
        for (String id : List.of("chg-create", "chg-delete")) {
            assertEquals(1, count("SELECT COUNT(*) FROM SEC_POLICY_CHANGE WHERE tenant_id=? AND change_id=?"
                    + " AND status='SUPERSEDED'", T, id));
            assertThrows(SangamSecurityException.AdminConflict.class,
                    () -> tx.inTransaction(c -> { security.policies().approve(c, approver, id); return null; }));
        }
        assertEquals(0, effective("user:c"));
        assertEquals(0, effective("user:d"));
        assertEquals(1, effective("user:a"));
        assertEquals(1, effective("group:g"));

        // and one written after the split (never through the service) is refused at approval, nothing applied
        tx.inTransaction(c -> { insertPending(c, "chg-late", "x2", "CREATE", legacyJson("x2", "user:e", "user:f")); return null; });
        assertThrows(SangamSecurityException.PolicyRejected.class,
                () -> tx.inTransaction(c -> { security.policies().approve(c, approver, "chg-late"); return null; }));
        assertEquals(0, effective("user:e"));
        assertEquals(1, count("SELECT COUNT(*) FROM SEC_POLICY_CHANGE WHERE tenant_id=? AND change_id='chg-late'"
                + " AND status='PENDING'", T));
    }

    @Test
    void anOldTwoGroupBootstrapPolicyGivesExactlyOneGrantPerGroupAfterStart() {
        legacyTwoPrincipalPolicy("bootstrap-admin", "group:sangam-admin", "group:sangam-tenant-admin");
        // start order: the development bootstrap (InitializingBean), then the split runner (ApplicationRunner)
        org.sangam.server.bridge.SecurityBootstrap.run(security, tx, List.of(T),
                List.of("sangam-admin", "sangam-tenant-admin"), false);
        assertTrue(SecurityWiring.splitPolicies(security, tx).isEmpty(), "the bootstrap already split it");
        List<PolicyDefinition> rows = active();
        assertEquals(2, rows.size(), rows.toString());
        for (String g : List.of("group:sangam-admin", "group:sangam-tenant-admin")) {
            assertEquals(1, rows.stream().filter(d -> d.subjects().equals(List.of(g))).count(), g);
            assertEquals(1, effective(g), g);
        }
        assertEquals(1, rows.stream().filter(d -> d.policyId().equals("bootstrap-admin")).count());
        // a second start changes nothing
        org.sangam.server.bridge.SecurityBootstrap.run(security, tx, List.of(T),
                List.of("sangam-admin", "sangam-tenant-admin"), false);
        assertEquals(2, active().size());
    }

    private static void insertPending(Connection c, String changeId, String policyId, String op, String json) {
        Jdbc.update(c, "INSERT INTO SEC_POLICY_CHANGE(tenant_id, change_id, policy_id, operation, definition, requested_by,"
                + " requested_at, status) VALUES (?,?,?,?,?,?,CURRENT_TIMESTAMP,'PENDING')", T, changeId, policyId, op, json,
                "requester");
    }
}
