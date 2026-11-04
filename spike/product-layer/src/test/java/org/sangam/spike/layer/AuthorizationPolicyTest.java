package org.sangam.spike.layer;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.spike.layer.AuthorizationPolicy.Action;
import org.sangam.spike.layer.AuthorizationPolicy.Principal;
import org.sangam.spike.layer.AuthorizationPolicy.Resource;
import org.sangam.spike.layer.AuthorizationPolicy.ResourceColumns;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * VI.4 / A-3 / T37-style property test: for random grants and denies, over an engine-like task table with its own
 * column names, {@code list == count-set == pages concatenated == {r : allow(r)} == oracle}.
 */
class AuthorizationPolicyTest extends PgTestBase {

    static final String TYPE = "TASK";
    static final List<String> TENANTS = List.of("t1", "t2");
    static final int TASKS_PER_TENANT = 40;
    static final List<Action> ACTIONS = List.of(Action.READ, Action.READ_TASK, Action.CLAIM);

    final AuthorizationPolicy policy = new AuthorizationPolicy(PG, TYPE, new ResourceColumns("TENANT_ID_", "ID_"));

    record Row(String tenant, String id, String name) {
    }

    static final List<Row> TASKS = new ArrayList<>();

    @BeforeAll
    static void createEngineLikeTaskTable() throws SQLException {
        try (Connection c = DS.getConnection(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS ACT_RU_TASK_SIM (ID_ VARCHAR(64) PRIMARY KEY, TENANT_ID_ VARCHAR(64) NOT NULL, NAME_ VARCHAR(255))");
            st.execute("TRUNCATE ACT_RU_TASK_SIM");
            TASKS.clear();
            for (String t : TENANTS) {
                for (int i = 0; i < TASKS_PER_TENANT; i++) {
                    Row r = new Row(t, t + "-task-" + String.format("%02d", i), (i % 2 == 0 ? "even-" : "odd-") + i);
                    TASKS.add(r);
                    st.execute("INSERT INTO ACT_RU_TASK_SIM VALUES ('" + r.id + "','" + r.tenant + "','" + r.name + "')");
                }
            }
        }
    }

    @BeforeEach
    void clearGrants() {
        run(c -> Jdbc.update(c, "DELETE FROM AUTH_EFFECTIVE"));
    }

    // ------------------------------------------------------------------ property test

    /** Key of the in-memory oracle: mirrors the AUTH_EFFECTIVE primary key. */
    record Key(String tenant, String principal, String resourceId, boolean deny, boolean conditional) {
    }

    @Test
    void listCountPagesAndObjectChecksAgreeForRandomGrantsAndDenies() throws SQLException {
        long seed = 20261104L;
        int iterations = 25;
        int checkedPairs = 0;
        for (int it = 0; it < iterations; it++) {
            Random rnd = new Random(seed + it);
            clearGrants();
            List<String> users = List.of("u0", "u1", "u2", "u3", "u4", "u5");
            List<String> groups = List.of("g0", "g1", "g2", "g3", "g4", "g5", "g6", "g7");
            List<String> allPrincipals = new ArrayList<>(users);
            allPrincipals.addAll(groups);
            Map<Key, Long> oracle = new HashMap<>();

            int grantCount = 15 + rnd.nextInt(30);
            for (int g = 0; g < grantCount; g++) {
                String tenant = rnd.nextInt(5) == 0 ? "t2" : "t1";
                String principal = allPrincipals.get(rnd.nextInt(allPrincipals.size()));
                String resourceId = rnd.nextInt(4) == 0 ? AuthorizationPolicy.ANY
                        : TASKS.get(rnd.nextInt(TASKS.size())).id;   // sometimes a task of the other tenant: must never leak
                long perms = 0;
                while (perms == 0) {
                    for (Action a : ACTIONS) {
                        if (rnd.nextBoolean()) perms |= a.bit();
                    }
                }
                boolean deny = rnd.nextInt(10) < 3;
                boolean conditional = !deny && rnd.nextInt(12) == 0;
                long p = perms;
                if (conditional) {
                    // a grant whose condition cannot be compiled in the spike: both forms must ignore it
                    run(c -> Jdbc.insertIfAbsent(c, PG, "INSERT INTO AUTH_EFFECTIVE(tenant_id, principal_id, resource_type, resource_id,"
                                    + " deny_flag, perms, condition_id, policy_version) VALUES (?,?,?,?,0,?,?,1)",
                            tenant, principal + "#cond", TYPE, resourceId, p, "cond-amount-lt-1000"));
                    // stored under a distinct principal id so it cannot collide with an unconditional row's key
                    oracle.merge(new Key(tenant, principal + "#cond", resourceId, false, true), p, (x, y) -> x | y);
                    continue;
                }
                run(c -> {
                    if (deny) {
                        AuthorizationPolicy.deny(c, PG, tenant, principal, TYPE, resourceId, p);
                    } else {
                        AuthorizationPolicy.grant(c, PG, tenant, principal, TYPE, resourceId, p);
                    }
                });
                oracle.merge(new Key(tenant, principal, resourceId, deny, false), perms, (x, y) -> x | y);
            }

            for (String user : users) {
                Set<String> memberOf = new LinkedHashSet<>();
                for (String g : groups) {
                    if (rnd.nextInt(4) == 0) memberOf.add(g);
                }
                if (rnd.nextInt(6) == 0) memberOf.add(user.replace("u", "g") + "#cond");   // can reach conditional rows
                String tenant = rnd.nextInt(4) == 0 ? "t2" : "t1";
                Principal principal = new Principal(tenant, user, memberOf);
                for (Action action : ACTIONS) {
                    checkOne(principal, action, oracle);
                    checkedPairs++;
                }
            }
        }
        assertTrue(checkedPairs >= iterations * 18);
    }

    private void checkOne(Principal principal, Action action, Map<Key, Long> oracleRows) throws SQLException {
        try (Connection c = DS.getConnection()) {
            SqlPredicate pred = policy.visible(principal, action, "r");
            List<String> list = ids(c, "SELECT r.ID_ FROM ACT_RU_TASK_SIM r WHERE " + pred.sql() + " ORDER BY r.ID_", pred);
            long count = count(c, "SELECT COUNT(*) FROM ACT_RU_TASK_SIM r WHERE " + pred.sql(), pred);

            List<String> paged = new ArrayList<>();
            for (int offset = 0; ; offset += 7) {
                List<String> page = ids(c, "SELECT r.ID_ FROM ACT_RU_TASK_SIM r WHERE " + pred.sql()
                        + " ORDER BY r.ID_ LIMIT 7 OFFSET " + offset, pred);
                paged.addAll(page);
                if (page.size() < 7) break;
            }

            // an arbitrary extra filter AND-ed after the predicate (search)
            List<String> filtered = ids(c, "SELECT r.ID_ FROM ACT_RU_TASK_SIM r WHERE " + pred.sql()
                    + " AND r.NAME_ LIKE 'even%' ORDER BY r.ID_", pred);

            TreeSet<String> byObject = new TreeSet<>();
            TreeSet<String> byOracle = new TreeSet<>();
            TreeSet<String> byObjectEven = new TreeSet<>();
            for (Row r : TASKS) {
                boolean allowed = policy.allow(c, principal, action, new Resource(r.tenant, r.id));
                if (allowed) {
                    byObject.add(r.id);
                    if (r.name.startsWith("even")) byObjectEven.add(r.id);
                }
                if (oracleAllows(oracleRows, principal, action, r)) byOracle.add(r.id);
            }
            String ctx = principal + " " + action;
            assertEquals(byOracle, byObject, "object check vs oracle: " + ctx);
            assertEquals(new ArrayList<>(byObject), list, "list vs {r : allow(r)}: " + ctx);
            assertEquals(byObject.size(), count, "count vs |{r : allow(r)}|: " + ctx);
            assertEquals(list, paged, "pages concatenated vs list: " + ctx);
            assertEquals(new ArrayList<>(byObjectEven), filtered, "filtered list: " + ctx);
        }
    }

    private static boolean oracleAllows(Map<Key, Long> rows, Principal p, Action a, Row r) {
        if (!p.tenantId().equals(r.tenant)) return false;
        Set<String> ids = new HashSet<>(p.ids());
        boolean granted = false;
        for (Map.Entry<Key, Long> e : rows.entrySet()) {
            Key k = e.getKey();
            if (!k.tenant.equals(r.tenant) || !ids.contains(k.principal)) continue;
            if (!(k.resourceId.equals(r.id) || k.resourceId.equals(AuthorizationPolicy.ANY))) continue;
            if ((e.getValue() & a.bit()) == 0) continue;
            if (k.deny) return false;
            if (!k.conditional) granted = true;
        }
        return granted;
    }

    private static List<String> ids(Connection c, String sql, SqlPredicate pred) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            pred.bind(ps, 1);
            try (ResultSet rs = ps.executeQuery()) {
                List<String> out = new ArrayList<>();
                while (rs.next()) out.add(rs.getString(1));
                return out;
            }
        }
    }

    private static long count(Connection c, String sql, SqlPredicate pred) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            pred.bind(ps, 1);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    // ------------------------------------------------------------------ deterministic cases

    @Test
    void explicitDenyWinsAtEveryScopeAndAcrossUserAndGroup() {
        Principal alice = new Principal("t1", "alice", Set.of("clerks"));
        Resource task = new Resource("t1", "t1-task-01");
        run(c -> AuthorizationPolicy.grant(c, PG, "t1", "alice", TYPE, "t1-task-01", Action.READ.bit()));
        assertTrue(txBool(c -> policy.allow(c, alice, Action.READ, task)));

        // group deny on '*' beats the user's specific grant
        run(c -> AuthorizationPolicy.deny(c, PG, "t1", "clerks", TYPE, AuthorizationPolicy.ANY, Action.READ.bit()));
        assertFalse(txBool(c -> policy.allow(c, alice, Action.READ, task)));
        assertEquals(0L, txLong(c -> countVisible(c, alice, Action.READ)));

        // a deny for another permission does not affect READ
        run(c -> AuthorizationPolicy.revoke(c, PG, "t1", "clerks", TYPE, AuthorizationPolicy.ANY, true));
        run(c -> AuthorizationPolicy.deny(c, PG, "t1", "clerks", TYPE, AuthorizationPolicy.ANY, Action.CLAIM.bit()));
        assertTrue(txBool(c -> policy.allow(c, alice, Action.READ, task)));
        assertFalse(txBool(c -> policy.allow(c, alice, Action.CLAIM, task)));
        assertEquals(1L, txLong(c -> countVisible(c, alice, Action.READ)));
    }

    @Test
    void tenantComesFromTheIdentityNeverFromTheGrantTarget() {
        // a t1 grant naming a t2 task id, and a '*' grant in t2 for a t1 principal's user id
        run(c -> AuthorizationPolicy.grant(c, PG, "t1", "bob", TYPE, "t2-task-03", Action.READ.bit()));
        Principal bobT1 = new Principal("t1", "bob", Set.of());
        assertFalse(txBool(c -> policy.allow(c, bobT1, Action.READ, new Resource("t2", "t2-task-03"))));
        assertEquals(0L, txLong(c -> countVisible(c, bobT1, Action.READ)));

        run(c -> AuthorizationPolicy.grant(c, PG, "t2", "bob", TYPE, AuthorizationPolicy.ANY, Action.READ.bit()));
        assertEquals(0L, txLong(c -> countVisible(c, bobT1, Action.READ)));
        assertEquals((long) TASKS_PER_TENANT, txLong(c -> countVisible(c, new Principal("t2", "bob", Set.of()), Action.READ)));
    }

    @Test
    void everyGrantChangeBumpsThePolicyVersion() {
        long v0 = inTx(c -> AuthorizationPolicy.policyVersion(c, "t1"));
        run(c -> AuthorizationPolicy.grant(c, PG, "t1", "x", TYPE, "*", Action.READ.bit()));
        run(c -> AuthorizationPolicy.deny(c, PG, "t1", "x", TYPE, "t1-task-00", Action.READ.bit()));
        run(c -> AuthorizationPolicy.revoke(c, PG, "t1", "x", TYPE, "t1-task-00", true));
        assertEquals(v0 + 3, txLong(c -> AuthorizationPolicy.policyVersion(c, "t1")));
    }

    private long countVisible(Connection c, Principal p, Action a) {
        SqlPredicate pred = policy.visible(p, a, "r");
        return Jdbc.one(c, "SELECT COUNT(*) FROM ACT_RU_TASK_SIM r WHERE " + pred.sql(), rs -> rs.getLong(1), pred.params().toArray())
                .orElseThrow();
    }
}
