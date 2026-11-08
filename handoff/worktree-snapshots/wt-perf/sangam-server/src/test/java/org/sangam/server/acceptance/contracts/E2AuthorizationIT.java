// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.acceptance.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.operaton.bpm.engine.task.NativeTaskQuery;
import org.operaton.bpm.engine.task.Task;
import org.sangam.core.layer.AuthorizationPolicy;
import org.sangam.core.layer.AuthorizationPolicy.Action;
import org.sangam.core.layer.AuthorizationPolicy.Principal;
import org.sangam.core.layer.AuthorizationPolicy.Resource;
import org.sangam.core.layer.SqlPredicate;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.security.SangamSecurity;
import org.sangam.security.policy.Permission;
import org.sangam.security.policy.PolicyCompiler;
import org.sangam.security.policy.ResourceRegistry;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * E2 (SANGAM.md XI.4, VI.4, A-3, A-13, A-16) on Sangam: the authorization predicate applied to the engine's own task
 * table {@code ACT_RU_TASK} holding real engine tasks (2,000 starts over 4 tenants, 45/25/20/10 %), with random
 * {@code AUTH_EFFECTIVE} policies over 300 groups, through plain SQL list/count and Operaton's native task query.
 *
 * <p>Two compiled forms are held to the same oracles: sangam-core's {@link AuthorizationPolicy} (the Stage B form) and
 * sangam-security's {@link PolicyCompiler} (the server's production read path: split EXISTS branches, A-16, and the
 * {@code AUTH_MEMBERSHIP} join above 200 principal ids, VI.4 rule 2). Oracles: (1) the object check of the same form
 * per task; (2) an independent in-memory model of A-3 (tenant match, a matching unconditional grant, no matching deny
 * of any kind). Principal ids are Sangam's prefixed ids ({@code user:}, {@code group:}).
 *
 * <p>Isolation: run-unique tenants; this test deletes and rewrites only its own tenants' policy rows (Stage B
 * truncated the table).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class E2AuthorizationIT extends ContractSupport {

    static final double[] TENANT_WEIGHTS = {0.45, 0.25, 0.20, 0.10};
    static final int GROUPS = 300;
    static final String TYPE = ResourceRegistry.TASK;
    static final long BIT = Action.READ_TASK.bit();
    static final long[] SEEDS = {11L, 23L, 47L};
    static final int[] PRINCIPAL_GROUP_COUNTS = {0, 1, 3, 10, 30, 50, 100, 300};
    static final int TASK_TARGET = Integer.getInteger("e2.tasks", 2000);

    final String[] tenants = {key("tn-a"), key("tn-b"), key("tn-c"), key("tn-d")};

    @Autowired
    SangamSecurity security;

    AuthorizationPolicy policy;
    PolicyCompiler compiler;
    final List<TaskRow> tasks = new ArrayList<>();
    final Map<String, List<TaskRow>> tasksByTenant = new HashMap<>();

    record TaskRow(String id, String tenant) {
    }

    record Grant(String tenant, String principal, String resource, boolean deny, long perms, String condition) {
    }

    /** One test principal in both shapes (prefixed ids). */
    record P(String tenant, String user, Set<String> groups) {
        Principal core() {
            return identity().toPrincipal();
        }

        SecurityIdentity identity() {
            return new SecurityIdentity(tenant, user, user, groups, Set.of(), Set.of(), Set.of(), "TEST", null, null,
                    false, Map.of());
        }

        List<String> ids() {
            return core().ids();
        }
    }

    static String group(int i) {
        return String.format("G%03d", i);
    }

    static String gid(String g) {
        return SecurityIdentity.GROUP + g;
    }

    static String uid(String u) {
        return SecurityIdentity.USER + u;
    }

    // ------------------------------------------------------------------ setup: real engine tasks

    @BeforeAll
    void setUp() throws Exception {
        policy = new AuthorizationPolicy(dialect(), TYPE, new AuthorizationPolicy.ResourceColumns("TENANT_ID_", "ID_"));
        compiler = security.compiler();
        String wf1 = key("e2-wf1");
        String wf2 = key("e2-wf2");
        for (String t : tenants) {
            deploy(t, "e2-wf1.bpmn", Models.singleTask(wf1, "checkerReview"));
            deploy(t, "e2-wf2.bpmn", Models.singleTask(wf2, "l1Review"));
        }
        Random r = new Random(7);
        List<String[]> starts = new ArrayList<>();
        for (int i = 0; i < TASK_TARGET; i++) {
            starts.add(new String[]{weighted(r), i % 2 == 0 ? wf1 : wf2, key("e2-" + i)});
        }
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<?>> fs = new ArrayList<>();
        for (String[] s : starts) {
            fs.add(pool.submit(() -> engine.startProcess(s[0], s[1], s[2], Map.of("billId", s[2]))));
        }
        for (Future<?> f : fs) {
            f.get(10, TimeUnit.MINUTES);
        }
        pool.shutdown();
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT ID_, TENANT_ID_ FROM ACT_RU_TASK WHERE TENANT_ID_ IN (?,?,?,?) ORDER BY ID_")) {
            for (int i = 0; i < tenants.length; i++) {
                ps.setString(i + 1, tenants[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    TaskRow tr = new TaskRow(rs.getString(1), rs.getString(2));
                    tasks.add(tr);
                    tasksByTenant.computeIfAbsent(tr.tenant(), k -> new ArrayList<>()).add(tr);
                }
            }
        }
        assertEquals(TASK_TARGET, tasks.size(), "one open task per started instance");
    }

    private String weighted(Random r) {
        double x = r.nextDouble();
        double acc = 0;
        for (int i = 0; i < tenants.length; i++) {
            acc += TENANT_WEIGHTS[i];
            if (x < acc) {
                return tenants[i];
            }
        }
        return tenants[tenants.length - 1];
    }

    // ------------------------------------------------------------------ policy generation

    /** Replaces this test's AUTH_EFFECTIVE rows with a random policy; membership rows for every principal. */
    List<Grant> loadRandomPolicy(long seed, List<P> principals) throws SQLException {
        Random r = new Random(seed);
        Map<String, Grant> rows = new LinkedHashMap<>();
        for (String tenant : tenants) {
            List<TaskRow> tt = tasksByTenant.getOrDefault(tenant, List.of());
            if (tt.isEmpty()) {
                continue;
            }
            for (int g = 0; g < GROUPS; g++) {
                String gid = gid(group(g));
                if (r.nextDouble() < 0.03) {
                    put(rows, new Grant(tenant, gid, "*", false, Action.mask(Action.READ_TASK, Action.CLAIM), null));
                }
                int k = r.nextInt(8);
                for (int i = 0; i < k; i++) {
                    long perms = r.nextDouble() < 0.75 ? Action.mask(Action.READ_TASK, Action.CLAIM) : Action.mask(Action.CLAIM, Action.COMPLETE);
                    put(rows, new Grant(tenant, gid, pick(r, tt).id(), false, perms, null));
                }
                if (r.nextDouble() < 0.25) {
                    int d = 1 + r.nextInt(3);
                    for (int i = 0; i < d; i++) {
                        long perms = r.nextDouble() < 0.8 ? Action.READ_TASK.bit() : Action.COMPLETE.bit();
                        put(rows, new Grant(tenant, gid, pick(r, tt).id(), true, perms, null));
                    }
                }
                if (r.nextDouble() < 0.05) {
                    put(rows, new Grant(tenant, gid, pick(r, tt).id(), false, BIT, "cond-amount-below-1-lakh"));
                }
                if (r.nextDouble() < 0.02) {
                    put(rows, new Grant(tenant, gid, pick(r, tt).id(), true, BIT, "cond-other-district"));
                }
            }
            if (tenant.equals(tenants[1])) {
                put(rows, new Grant(tenant, gid(group(GROUPS - 1)), "*", true, BIT, null));   // one blocking group
            }
        }
        for (P p : principals) {
            List<TaskRow> tt = tasksByTenant.get(p.tenant());
            for (int i = 0; i < 5; i++) {
                put(rows, new Grant(p.tenant(), uid(p.user()), pick(r, tt).id(), false, BIT, null));
            }
            for (int i = 0; i < 2; i++) {
                put(rows, new Grant(p.tenant(), uid(p.user()), pick(r, tt).id(), true, BIT, null));
            }
        }
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            clearMyRows(c);
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO AUTH_EFFECTIVE(tenant_id, principal_id, resource_type,"
                    + " resource_id, deny_flag, perms, condition_id, cond_key, policy_version) VALUES (?,?,?,?,?,?,?,?,1)")) {
                for (Grant g : rows.values()) {
                    ps.setString(1, g.tenant());
                    ps.setString(2, g.principal());
                    ps.setString(3, TYPE);
                    ps.setString(4, g.resource());
                    ps.setInt(5, g.deny() ? 1 : 0);
                    ps.setLong(6, g.perms());
                    ps.setString(7, g.condition());
                    ps.setString(8, g.condition() == null ? "-" : g.condition());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO AUTH_MEMBERSHIP(tenant_id, member_id, principal_id,"
                    + " source_id, valid_from, valid_to) VALUES (?,?,?,?,CURRENT_TIMESTAMP - INTERVAL '1 day',NULL)")) {
                for (P p : principals) {
                    for (String g : p.groups()) {
                        ps.setString(1, p.tenant());
                        ps.setString(2, uid(p.user()));
                        ps.setString(3, gid(g));
                        ps.setString(4, key("e2"));
                        ps.addBatch();
                    }
                }
                ps.executeBatch();
            }
            st.execute("ANALYZE AUTH_EFFECTIVE");
            st.execute("ANALYZE AUTH_MEMBERSHIP");
        }
        return new ArrayList<>(rows.values());
    }

    private void clearMyRows(Connection c) throws SQLException {
        try (PreparedStatement a = c.prepareStatement("DELETE FROM AUTH_EFFECTIVE WHERE tenant_id IN (?,?,?,?)");
             PreparedStatement m = c.prepareStatement("DELETE FROM AUTH_MEMBERSHIP WHERE tenant_id IN (?,?,?,?)")) {
            for (int i = 0; i < tenants.length; i++) {
                a.setString(i + 1, tenants[i]);
                m.setString(i + 1, tenants[i]);
            }
            a.executeUpdate();
            m.executeUpdate();
        }
    }

    private static void put(Map<String, Grant> rows, Grant g) {
        String k = g.tenant() + "|" + g.principal() + "|" + g.resource() + "|" + g.deny() + "|" + g.condition();
        Grant prev = rows.get(k);
        rows.put(k, prev == null ? g : new Grant(g.tenant(), g.principal(), g.resource(), g.deny(), prev.perms() | g.perms(),
                g.condition()));
    }

    private static <T> T pick(Random r, List<T> l) {
        return l.get(r.nextInt(l.size()));
    }

    List<P> randomPrincipals(long seed) {
        Random r = new Random(seed * 31 + 5);
        List<P> out = new ArrayList<>();
        int i = 0;
        for (int n : PRINCIPAL_GROUP_COUNTS) {
            String tenant = n == GROUPS ? tenants[0] : weighted(r);
            List<Integer> all = new ArrayList<>();
            for (int g = 0; g < GROUPS; g++) {
                all.add(g);
            }
            Collections.shuffle(all, r);
            Set<String> groups = new LinkedHashSet<>();
            for (int g : all.subList(0, n)) {
                groups.add(group(g));
            }
            out.add(new P(tenant, "u-" + seed + "-" + (i++), groups));
        }
        return out;
    }

    // ------------------------------------------------------------------ the forms

    enum Form { CORE, COMPILER }

    SqlPredicate predicate(Form f, P p, String alias) {
        if (f == Form.CORE) {
            return policy.visible(p.core(), Action.READ_TASK, alias);
        }
        try (Connection c = dataSource.getConnection()) {
            return compiler.visible(c, p.identity(), TYPE, Permission.READ_TASK, alias);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    List<String> sqlList(Form f, P p, String extraWhere, Object... extraParams) {
        SqlPredicate pred = predicate(f, p, "r");
        List<String> ids = new ArrayList<>(query("SELECT r.ID_ FROM ACT_RU_TASK r WHERE " + pred.sql()
                + (extraWhere == null ? "" : " AND " + extraWhere) + " ORDER BY r.ID_", pred, extraParams));
        Collections.sort(ids);
        return ids;
    }

    long sqlCount(Form f, P p) {
        SqlPredicate pred = predicate(f, p, "r");
        return Long.parseLong(query("SELECT count(*) FROM ACT_RU_TASK r WHERE " + pred.sql(), pred).get(0));
    }

    List<String> query(String sql, SqlPredicate pred, Object... extra) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            int next = pred.bind(ps, 1);
            for (Object o : extra) {
                ps.setObject(next++, o);
            }
            List<String> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Oracle 1: the object check of the form, task by task. The compiler's object check is a query per task; it runs
     * for every task of the principal's tenant and for a sample of 50 tasks of the other tenants (must be false).
     */
    List<String> allowList(Form f, P p) {
        try (Connection c = dataSource.getConnection()) {
            List<String> out = new ArrayList<>();
            Random sample = new Random(p.user().hashCode());
            for (TaskRow t : tasks) {
                boolean same = t.tenant().equals(p.tenant());
                boolean allowed;
                if (f == Form.CORE) {
                    allowed = policy.allow(c, p.core(), Action.READ_TASK, new Resource(t.tenant(), t.id()));
                } else if (same || sample.nextInt(Math.max(1, tasks.size() / 50)) == 0) {
                    allowed = compiler.allow(c, p.identity(), TYPE, Permission.READ_TASK, t.id());
                } else {
                    continue;
                }
                if (allowed) {
                    assertTrue(same, "cross-tenant object check allowed " + t + " for " + p.user());
                    out.add(t.id());
                }
            }
            Collections.sort(out);
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Oracle 2: independent in-memory evaluation of the A-3 semantics. */
    static List<String> modelList(List<Grant> rows, P p, List<TaskRow> tasks) {
        Set<String> ids = new HashSet<>(p.ids());
        List<Grant> mine = rows.stream().filter(g -> g.tenant().equals(p.tenant()) && ids.contains(g.principal())
                && (g.perms() & BIT) != 0).toList();
        List<String> out = new ArrayList<>();
        for (TaskRow t : tasks) {
            if (!t.tenant().equals(p.tenant())) {
                continue;
            }
            boolean granted = false;
            boolean denied = false;
            for (Grant g : mine) {
                if (!g.resource().equals("*") && !g.resource().equals(t.id())) {
                    continue;
                }
                if (g.deny()) {
                    denied = true;
                } else if (g.condition() == null) {
                    granted = true;
                }
            }
            if (granted && !denied) {
                out.add(t.id());
            }
        }
        Collections.sort(out);
        return out;
    }

    List<Grant> currentRows() {
        List<Grant> out = new ArrayList<>();
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT tenant_id, principal_id, resource_id, deny_flag, perms, condition_id FROM AUTH_EFFECTIVE"
                        + " WHERE resource_type = ? AND tenant_id IN (?,?,?,?)")) {
            ps.setString(1, TYPE);
            for (int i = 0; i < tenants.length; i++) {
                ps.setString(i + 2, tenants[i]);
            }
            try (ResultSet r = ps.executeQuery()) {
                while (r.next()) {
                    out.add(new Grant(r.getString(1), r.getString(2), r.getString(3), r.getInt(4) == 1, r.getLong(5),
                            r.getString(6)));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    // ------------------------------------------------------------------ scenarios

    @Test
    @Order(1)
    void e2_1_randomPolicyEquivalence() throws Exception {
        List<String> mismatches = new ArrayList<>();
        long visibleTotal = 0;
        for (long seed : SEEDS) {
            List<P> principals = randomPrincipals(seed);
            List<Grant> rows = loadRandomPolicy(seed, principals);
            for (P p : principals) {
                List<String> model = modelList(rows, p, tasks);
                for (Form f : Form.values()) {
                    List<String> sql = sqlList(f, p, null);
                    long count = sqlCount(f, p);
                    List<String> allow = allowList(f, p);
                    visibleTotal += sql.size();
                    if (!sql.equals(allow) || !sql.equals(model) || count != allow.size()) {
                        mismatches.add(f + " seed " + seed + " " + p.user() + " groups=" + p.groups().size() + ": sql="
                                + sql.size() + " count=" + count + " allow=" + allow.size() + " model=" + model.size());
                    }
                }
            }
        }
        assertEquals(List.of(), mismatches);
        assertTrue(visibleTotal > 0, "the policies make something visible");
    }

    @Test
    @Order(2)
    void e2_2_pagesAreCompleteWithoutPostFiltering() throws Exception {
        List<P> principals = randomPrincipals(SEEDS[SEEDS.length - 1]);
        loadRandomPolicy(SEEDS[SEEDS.length - 1], principals);
        for (Form f : Form.values()) {
            for (P p : principals) {
                SqlPredicate pred = predicate(f, p, "r");
                List<String> full = query("SELECT r.ID_ FROM ACT_RU_TASK r WHERE " + pred.sql() + " ORDER BY r.CREATE_TIME_, r.ID_", pred);
                List<String> concat = new ArrayList<>();
                for (int offset = 0; ; offset += 50) {
                    List<String> page = query("SELECT r.ID_ FROM ACT_RU_TASK r WHERE " + pred.sql()
                            + " ORDER BY r.CREATE_TIME_, r.ID_ LIMIT 50 OFFSET " + offset, pred);
                    if (offset + 50 < full.size()) {
                        assertEquals(50, page.size(), "a non-last page is full");
                    }
                    concat.addAll(page);
                    if (page.size() < 50) {
                        break;
                    }
                }
                assertEquals(full, concat, f + " " + p.user());
            }
        }
    }

    @Test
    @Order(3)
    void e2_3_denyWins() throws Exception {
        String tenant = tenants[2];
        try (Connection c = dataSource.getConnection()) {
            clearMyRows(c);
        }
        List<TaskRow> tt = tasksByTenant.get(tenant);
        String x = tt.get(0).id();
        String y = tt.get(1).id();
        String z = tt.get(2).id();
        inTx(c -> {
            AuthorizationPolicy.grant(c, dialect(), tenant, gid("dw-A"), TYPE, "*", BIT);
            AuthorizationPolicy.deny(c, dialect(), tenant, gid("dw-B"), TYPE, x, BIT);
            AuthorizationPolicy.grant(c, dialect(), tenant, uid("dw-user2"), TYPE, x, BIT);
            AuthorizationPolicy.grant(c, dialect(), tenant, gid("dw-C"), TYPE, y, BIT);
            AuthorizationPolicy.deny(c, dialect(), tenant, gid("dw-D"), TYPE, "*", BIT);
            AuthorizationPolicy.grant(c, dialect(), tenant, gid("dw-E"), TYPE, z, BIT);
            AuthorizationPolicy.deny(c, dialect(), tenant, gid("dw-F"), TYPE, z, Action.COMPLETE.bit());
            return null;
        });
        exec("INSERT INTO AUTH_EFFECTIVE(tenant_id, principal_id, resource_type, resource_id, deny_flag, perms, condition_id,"
                + " cond_key, policy_version) VALUES (?,?,?,?,0,?,?,?,1)", tenant, gid("dw-G"), TYPE, y, BIT, "cond-x", "cond-x");
        exec("INSERT INTO AUTH_EFFECTIVE(tenant_id, principal_id, resource_type, resource_id, deny_flag, perms, condition_id,"
                + " cond_key, policy_version) VALUES (?,?,?,?,1,?,?,?,1)", tenant, gid("dw-H"), TYPE, z, BIT, "cond-y", "cond-y");
        List<Grant> rows = currentRows();

        // a) group grant '*' + other group's deny on x
        List<String> la = check(new P(tenant, "dw-user1", Set.of("dw-A", "dw-B")), rows);
        assertFalse(la.contains(x));
        assertTrue(la.contains(y));
        assertEquals(tt.size() - 1, la.size());
        // b) user-level grant on x + group deny on x
        assertFalse(check(new P(tenant, "dw-user2", Set.of("dw-B")), rows).contains(x));
        // c) specific grant on y + broader deny '*'
        assertTrue(check(new P(tenant, "dw-user3", Set.of("dw-C", "dw-D")), rows).isEmpty());
        // d) deny of another permission (COMPLETE) does not hide READ_TASK
        assertEquals(List.of(z), check(new P(tenant, "dw-user4", Set.of("dw-E", "dw-F")), rows));
        // e) conditional grant never matches; conditional deny always applies (fail closed)
        assertTrue(check(new P(tenant, "dw-user5", Set.of("dw-G")), rows).isEmpty());
        assertTrue(check(new P(tenant, "dw-user6", Set.of("dw-E", "dw-H")), rows).isEmpty());
    }

    /** Asserts both forms and both oracles agree for one principal and returns the list. */
    private List<String> check(P p, List<Grant> rows) {
        List<String> model = modelList(rows, p, tasks);
        List<String> first = null;
        for (Form f : Form.values()) {
            List<String> sql = sqlList(f, p, null);
            assertEquals(allowList(f, p), sql, f + " object check vs SQL for " + p.user());
            assertEquals(model, sql, f + " model vs SQL for " + p.user());
            assertEquals(sql.size(), sqlCount(f, p), f + " count");
            first = first == null ? sql : first;
        }
        return first;
    }

    @Test
    @Order(4)
    void e2_4_tenantBinding() throws Exception {
        try (Connection c = dataSource.getConnection()) {
            clearMyRows(c);
        }
        String ta = tenants[0];
        String tb = tenants[1];
        inTx(c -> {
            AuthorizationPolicy.grant(c, dialect(), tb, gid("tb-G"), TYPE, "*", BIT);       // other tenant, same group id
            AuthorizationPolicy.grant(c, dialect(), ta, gid("tb-G"), TYPE, tasksByTenant.get(ta).get(0).id(), BIT);
            return null;
        });
        P p = new P(ta, "tb-user", Set.of("tb-G"));
        List<String> visible = check(p, currentRows());
        assertEquals(List.of(tasksByTenant.get(ta).get(0).id()), visible);
        for (Form f : Form.values()) {
            assertTrue(sqlList(f, p, "r.TENANT_ID_ = ?", tb).isEmpty(), f + ": request tenant B gives nothing to a tenant-A principal");
        }
    }

    @Test
    @Order(5)
    void e2_5_revocationIsImmediate() throws Exception {
        try (Connection c = dataSource.getConnection()) {
            clearMyRows(c);
        }
        String tenant = tenants[3];
        String x = tasksByTenant.get(tenant).get(0).id();
        long v0 = inTx(c -> {
            AuthorizationPolicy.grant(c, dialect(), tenant, gid("rv-G"), TYPE, x, BIT);
            return AuthorizationPolicy.policyVersion(c, tenant);
        });
        P p = new P(tenant, "rv-user", Set.of("rv-G"));
        assertEquals(List.of(x), check(p, currentRows()));
        long v1 = inTx(c -> AuthorizationPolicy.deny(c, dialect(), tenant, uid("rv-user"), TYPE, x, BIT));
        assertTrue(v1 > v0);
        assertTrue(check(p, currentRows()).isEmpty());
        inTx(c -> AuthorizationPolicy.revoke(c, dialect(), tenant, uid("rv-user"), TYPE, x, true));
        assertEquals(List.of(x), check(p, currentRows()));
        inTx(c -> AuthorizationPolicy.revoke(c, dialect(), tenant, gid("rv-G"), TYPE, x, false));
        assertTrue(check(p, currentRows()).isEmpty());
    }

    @Test
    @Order(6)
    void e2_6_nativeEngineQueryApi() throws Exception {
        List<P> principals = randomPrincipals(SEEDS[0]);
        List<Grant> rows = loadRandomPolicy(SEEDS[0], principals);
        for (Form f : Form.values()) {
            for (P p : principals) {
                SqlPredicate onRes = predicate(f, p, "RES");
                List<String> nat = new ArrayList<>(nativeList(onRes, Integer.MAX_VALUE));
                Collections.sort(nat);
                assertEquals(modelList(rows, p, tasks), nat, f + " " + p.user());
                assertEquals(nat.size(), nativeCount(onRes), f + " " + p.user());
            }
        }
    }

    @Test
    @Order(7)
    void e2_7_latencyWith300Groups() throws Exception {
        List<P> principals = randomPrincipals(SEEDS[0]);
        loadRandomPolicy(SEEDS[0], principals);
        P big = principals.stream().filter(p -> p.groups().size() == GROUPS).findFirst().orElseThrow();
        List<String> failures = new ArrayList<>();
        StringBuilder report = new StringBuilder();
        for (Form f : Form.values()) {
            SqlPredicate pred = predicate(f, big, "r");
            String page = "SELECT r.ID_, r.NAME_, r.CREATE_TIME_ FROM ACT_RU_TASK r WHERE " + pred.sql()
                    + " ORDER BY r.CREATE_TIME_ DESC, r.ID_ LIMIT 50";
            String count = "SELECT count(*) FROM ACT_RU_TASK r WHERE " + pred.sql();
            Map<String, Object> list = time(() -> query(page, pred).size(), 30, 300);
            Map<String, Object> cnt = time(() -> query(count, pred).size(), 30, 300);
            SqlPredicate onRes = predicate(f, big, "RES");
            Map<String, Object> nat = time(() -> nativeList(onRes, 50).size(), 30, 300);
            report.append(f).append(": list ").append(list).append(" count ").append(cnt).append(" native ").append(nat).append('\n');
            if ((Double) list.get("p95Ms") >= 200.0) {
                failures.add(f + " list p95 " + list.get("p95Ms"));
            }
            if ((Double) cnt.get("p95Ms") >= 200.0) {
                failures.add(f + " count p95 " + cnt.get("p95Ms"));
            }
            // the plan of the count query goes into the report (A-16 evidence for the index plan)
            report.append(f).append(" count plan:\n");
            query("EXPLAIN (ANALYZE, BUFFERS) " + count, pred).forEach(l -> report.append("  ").append(l).append('\n'));
        }
        System.out.println("[E2-7] " + tasks.size() + " tasks, 300 groups, visible " + sqlCount(Form.COMPILER, big) + "\n" + report);
        assertEquals(List.of(), failures, report.toString());
    }

    static Map<String, Object> time(Supplier<Integer> q, int warmup, int runs) {
        for (int i = 0; i < warmup; i++) {
            q.get();
        }
        double[] ms = new double[runs];
        for (int i = 0; i < runs; i++) {
            long t = System.nanoTime();
            q.get();
            ms[i] = (System.nanoTime() - t) / 1e6;
        }
        Arrays.sort(ms);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("p50Ms", round(ms[(int) Math.floor(0.50 * (runs - 1))]));
        out.put("p95Ms", round(ms[(int) Math.floor(0.95 * (runs - 1))]));
        out.put("p99Ms", round(ms[(int) Math.floor(0.99 * (runs - 1))]));
        return out;
    }

    static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }

    // ------------------------------------------------------------------ Operaton native task query (Q2)

    /** The predicate with MyBatis named parameters ({@code #{p0}}, ...), as Operaton's native queries expect. */
    private NativeTaskQuery nativeQuery(String select, SqlPredicate p) {
        StringBuilder sb = new StringBuilder();
        Map<String, Object> params = new LinkedHashMap<>();
        int n = 0;
        for (char ch : p.sql().toCharArray()) {
            if (ch == '?') {
                String name = "p" + n;
                sb.append("#{").append(name).append('}');
                params.put(name, p.params().get(n));
                n++;
            } else {
                sb.append(ch);
            }
        }
        NativeTaskQuery q = processEngine.getTaskService().createNativeTaskQuery()
                .sql(select + " FROM ACT_RU_TASK RES WHERE " + sb);
        params.forEach(q::parameter);
        return q;
    }

    List<String> nativeList(SqlPredicate onRes, int maxResults) {
        NativeTaskQuery q = nativeQuery("SELECT RES.*", onRes);
        List<Task> l = maxResults == Integer.MAX_VALUE ? q.list() : q.listPage(0, maxResults);
        return l.stream().map(Task::getId).toList();
    }

    long nativeCount(SqlPredicate onRes) {
        return nativeQuery("SELECT count(*)", onRes).count();
    }
}
