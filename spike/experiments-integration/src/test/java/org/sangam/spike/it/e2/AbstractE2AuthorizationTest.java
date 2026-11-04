package org.sangam.spike.it.e2;

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
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.sangam.spike.api.EngineAdapter;
import org.sangam.spike.it.support.Env;
import org.sangam.spike.it.support.ExperimentBase;
import org.sangam.spike.it.support.Scenario;
import org.sangam.spike.it.support.SqlDialects;
import org.sangam.spike.it.support.Workflows;
import org.sangam.spike.layer.AuthorizationPolicy;
import org.sangam.spike.layer.AuthorizationPolicy.Action;
import org.sangam.spike.layer.AuthorizationPolicy.Principal;
import org.sangam.spike.layer.AuthorizationPolicy.Resource;
import org.sangam.spike.layer.SqlPredicate;

/**
 * E2 (SANGAM.md XI.4, VI.4 + A-3): {@link AuthorizationPolicy#visible} applied as a SQL predicate directly to the
 * engine's own task table {@code ACT_RU_TASK} (both engines use that name and the columns {@code ID_}, {@code TENANT_ID_}),
 * through a plain JDBC list/count. Tasks are real engine tasks created by WF-1/WF-2 starts.
 *
 * <p>Oracles: (1) the object check {@link AuthorizationPolicy#allow} per task; (2) an independent in-memory evaluation
 * of the AUTH_EFFECTIVE rows with the A-3 semantics (tenant match, a matching unconditional grant, no matching deny of
 * any kind). {@code list == {t : allow(t)}} and the count must match, exactly.
 */
public abstract class AbstractE2AuthorizationTest extends ExperimentBase {

    static final String[] TENANTS = {"tn-a", "tn-b", "tn-c", "tn-d"};
    static final double[] TENANT_WEIGHTS = {0.45, 0.25, 0.20, 0.10};   // skewed tenants (A-3 benchmark note)
    static final int GROUPS = 300;
    static final String TYPE = "TASK";
    static final long BIT = Action.READ_TASK.bit();
    static final long[] SEEDS = {11L, 23L, 47L};
    static final int[] PRINCIPAL_GROUP_COUNTS = {0, 1, 3, 10, 30, 50, 100, 300};

    final int taskTarget = Integer.getInteger("e2.tasks", 2000);
    AuthorizationPolicy policy;
    final List<TaskRow> tasks = new ArrayList<>();
    final Map<String, List<TaskRow>> tasksByTenant = new HashMap<>();

    record TaskRow(String id, String tenant) {
    }

    record Grant(String tenant, String principal, String resource, boolean deny, long perms, String condition) {
    }

    @Override
    protected String experiment() {
        return "E2";
    }

    /** A run with a non-default -De2.tasks is supplementary and is stored next to the main entry. */
    @Override
    protected String resultKey() {
        return taskTarget == 2000 ? kind().label() : kind().label() + " @" + taskTarget + " tasks (supplementary)";
    }

    static String group(int i) {
        return String.format("G%03d", i);
    }

    // ------------------------------------------------------------------ engine-specific hooks (Q2)

    /** Runs the predicate through the engine's own native task query API; returns task ids. */
    protected abstract List<String> nativeList(SqlPredicate visibleOnRes, int maxResults);

    protected abstract long nativeCount(SqlPredicate visibleOnRes);

    /** Records what the engine's own TaskQuery / authorization offers (Q2). */
    protected abstract Map<String, Object> engineAuthorizationProbe(List<TaskRow> tenantTasks, String tenant);

    // ------------------------------------------------------------------ setup: real engine tasks

    @Override
    protected void setUp() throws Exception {
        policy = new AuthorizationPolicy(env.dialect, TYPE, new AuthorizationPolicy.ResourceColumns("TENANT_ID_", "ID_"));
        for (String t : TENANTS) {
            env.deploy(t, Workflows.WF1_FILES);
            env.deploy(t, Workflows.WF2_FILES);
        }
        Random r = new Random(7);
        List<String[]> starts = new ArrayList<>();
        for (int i = 0; i < taskTarget; i++) {
            String tenant = weighted(r);
            starts.add(new String[]{tenant, i % 2 == 0 ? "wf1-bill" : "wf2-file", "e2-" + i, group(r.nextInt(GROUPS))});
        }
        long t0 = System.nanoTime();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<?>> fs = new ArrayList<>();
        for (String[] s : starts) {
            fs.add(pool.submit(() -> s[1].equals("wf1-bill")
                    ? env.engine.startProcess(s[0], s[1], s[2], Workflows.wf1Vars(s[2], 50_000L, s[3], "ACC"))
                    : env.engine.startProcess(s[0], s[1], s[2], Workflows.wf2Vars(s[3]))));
        }
        for (Future<?> f : fs) {
            f.get(10, TimeUnit.MINUTES);
        }
        pool.shutdown();
        double startSeconds = (System.nanoTime() - t0) / 1e9;
        for (Object[] row : env.rows("SELECT ID_, TENANT_ID_ FROM ACT_RU_TASK ORDER BY ID_", 2)) {
            TaskRow tr = new TaskRow((String) row[0], (String) row[1]);
            tasks.add(tr);
            tasksByTenant.computeIfAbsent(tr.tenant(), k -> new ArrayList<>()).add(tr);
        }
        notes.put("scale", (tasks.size() >= 10_000 ? "SPEC SCALE: " : "REDUCED: ") + tasks.size() + " engine tasks (target in XI.4: 10,000) x " + GROUPS
                + " groups; 4 tenants with skew 45/25/20/10 %");
        notes.put("taskCreation", Map.of("instancesStarted", taskTarget, "seconds", Math.round(startSeconds * 10) / 10.0,
                "via", "EngineAdapter.startProcess (WF-1 and WF-2, alternating), 8 threads"));
        Map<String, Integer> perTenant = new LinkedHashMap<>();
        tasksByTenant.forEach((k, v) -> perTenant.put(k, v.size()));
        notes.put("tasksPerTenant", perTenant);
        notes.put("table", "ACT_RU_TASK (engine-owned), columns TENANT_ID_ and ID_; predicate from AuthorizationPolicy.visible");
    }

    private static String weighted(Random r) {
        double x = r.nextDouble();
        double acc = 0;
        for (int i = 0; i < TENANTS.length; i++) {
            acc += TENANT_WEIGHTS[i];
            if (x < acc) {
                return TENANTS[i];
            }
        }
        return TENANTS[TENANTS.length - 1];
    }

    // ------------------------------------------------------------------ policy generation

    /** Replaces AUTH_EFFECTIVE with a random policy (grants, denies, wildcards, conditional rows, user rows). */
    List<Grant> loadRandomPolicy(long seed, List<Principal> principals) throws SQLException {
        Random r = new Random(seed);
        Map<String, Grant> rows = new LinkedHashMap<>();
        for (String tenant : TENANTS) {
            List<TaskRow> tt = tasksByTenant.getOrDefault(tenant, List.of());
            if (tt.isEmpty()) {
                continue;
            }
            for (int g = 0; g < GROUPS; g++) {
                String gid = group(g);
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
            if (tenant.equals("tn-b")) {
                put(rows, new Grant(tenant, group(GROUPS - 1), "*", true, BIT, null));   // one blocking group
            }
        }
        for (Principal p : principals) {
            List<TaskRow> tt = tasksByTenant.get(p.tenantId());
            for (int i = 0; i < 5; i++) {
                put(rows, new Grant(p.tenantId(), p.userId(), pick(r, tt).id(), false, BIT, null));
            }
            for (int i = 0; i < 2; i++) {
                put(rows, new Grant(p.tenantId(), p.userId(), pick(r, tt).id(), true, BIT, null));
            }
        }
        try (Connection c = env.reads.getConnection(); Statement st = c.createStatement()) {
            st.execute("TRUNCATE TABLE AUTH_EFFECTIVE");          // one table per statement: Oracle / SQL Server
            st.execute("TRUNCATE TABLE AUTH_POLICY_VERSION");
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO AUTH_EFFECTIVE(tenant_id, principal_id, resource_type,"
                    + " resource_id, deny_flag, perms, condition_id, policy_version) VALUES (?,?,?,?,?,?,?,1)")) {
                for (Grant g : rows.values()) {
                    ps.setString(1, g.tenant());
                    ps.setString(2, g.principal());
                    ps.setString(3, TYPE);
                    ps.setString(4, g.resource());
                    ps.setInt(5, g.deny() ? 1 : 0);
                    ps.setLong(6, g.perms());
                    ps.setString(7, g.condition());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            st.execute(SqlDialects.analyze(env.dialect, "AUTH_EFFECTIVE"));
        }
        return new ArrayList<>(rows.values());
    }

    private static void put(Map<String, Grant> rows, Grant g) {
        String k = g.tenant() + "|" + g.principal() + "|" + g.resource() + "|" + g.deny();
        Grant prev = rows.get(k);
        rows.put(k, prev == null ? g : new Grant(g.tenant(), g.principal(), g.resource(), g.deny(), prev.perms() | g.perms(),
                prev.condition() != null ? prev.condition() : g.condition()));
    }

    private static <T> T pick(Random r, List<T> l) {
        return l.get(r.nextInt(l.size()));
    }

    List<Principal> randomPrincipals(long seed) {
        Random r = new Random(seed * 31 + 5);
        List<Principal> out = new ArrayList<>();
        int i = 0;
        for (int n : PRINCIPAL_GROUP_COUNTS) {
            String tenant = n == GROUPS ? "tn-a" : weighted(r);
            List<Integer> all = new ArrayList<>();
            for (int g = 0; g < GROUPS; g++) {
                all.add(g);
            }
            Collections.shuffle(all, r);
            Set<String> groups = new LinkedHashSet<>();
            for (int g : all.subList(0, n)) {
                groups.add(group(g));
            }
            out.add(new Principal(tenant, "u-" + seed + "-" + (i++), groups));
        }
        return out;
    }

    // ------------------------------------------------------------------ the three forms

    List<String> sqlList(Principal p, String extraWhere, Object... extraParams) {
        SqlPredicate pred = policy.visible(p, Action.READ_TASK, "r");
        // sorted in Java as well: the oracles are sorted in Java, and SQL Server's default collation orders ids differently
        List<String> ids = new ArrayList<>(query("SELECT r.ID_ FROM ACT_RU_TASK r WHERE " + pred.sql()
                + (extraWhere == null ? "" : " AND " + extraWhere) + " ORDER BY r.ID_", pred, extraParams));
        Collections.sort(ids);
        return ids;
    }

    long sqlCount(Principal p) {
        SqlPredicate pred = policy.visible(p, Action.READ_TASK, "r");
        return Long.parseLong(query("SELECT count(*) FROM ACT_RU_TASK r WHERE " + pred.sql(), pred).get(0));
    }

    List<String> query(String sql, SqlPredicate pred, Object... extra) {
        try (Connection c = env.reads.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
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

    /** Oracle 1: the compiled object check, task by task (only same-tenant tasks need a query). */
    List<String> allowList(Principal p) {
        try (Connection c = env.reads.getConnection()) {
            List<String> out = new ArrayList<>();
            for (TaskRow t : tasks) {
                if (policy.allow(c, p, Action.READ_TASK, new Resource(t.tenant(), t.id()))) {
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
    static List<String> modelList(List<Grant> rows, Principal p, List<TaskRow> tasks) {
        Set<String> ids = new HashSet<>(p.ids());
        List<Grant> mine = rows.stream().filter(g -> g.tenant().equals(p.tenantId()) && ids.contains(g.principal())
                && (g.perms() & BIT) != 0).toList();
        List<String> out = new ArrayList<>();
        for (TaskRow t : tasks) {
            if (!t.tenant().equals(p.tenantId())) {
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
        for (Object[] r : env.rows("SELECT tenant_id, principal_id, resource_id, deny_flag, perms, condition_id FROM AUTH_EFFECTIVE"
                + " WHERE resource_type = ?", 6, TYPE)) {
            out.add(new Grant((String) r[0], (String) r[1], (String) r[2], ((Number) r[3]).intValue() == 1,
                    ((Number) r[4]).longValue(), (String) r[5]));
        }
        return out;
    }

    // ------------------------------------------------------------------ scenarios

    @Test
    @Order(1)
    @Scenario(id = "E2-1", name = "Random policies: SQL list == {t : allow(t)} == in-memory model, count equal, "
            + "for 3 seeds x 8 principals (0..300 groups)", tests = {"T14", "T37"})
    void randomPolicyEquivalence() throws Exception {
        int checked = 0;
        int mismatches = 0;
        long visibleTotal = 0;
        List<String> mismatchDetails = new ArrayList<>();
        Map<String, Object> perSeed = new LinkedHashMap<>();
        for (long seed : SEEDS) {
            List<Principal> principals = randomPrincipals(seed);
            List<Grant> rows = loadRandomPolicy(seed, principals);
            List<Integer> sizes = new ArrayList<>();
            for (Principal p : principals) {
                List<String> sql = sqlList(p, null);
                long count = sqlCount(p);
                List<String> allow = allowList(p);
                List<String> model = modelList(rows, p, tasks);
                checked++;
                visibleTotal += sql.size();
                sizes.add(sql.size());
                if (!sql.equals(allow) || !sql.equals(model) || count != allow.size()) {
                    mismatches++;
                    mismatchDetails.add("seed " + seed + " " + p.userId() + " groups=" + p.groups().size() + ": sql="
                            + sql.size() + " count=" + count + " allow=" + allow.size() + " model=" + model.size());
                }
            }
            perSeed.put("seed " + seed, Map.of("grantRows", rows.size(), "visiblePerPrincipal", sizes));
        }
        m.put("principalsChecked", checked);
        m.put("tasks", tasks.size());
        m.put("visibleTasksTotal", visibleTotal);
        m.put("mismatches", mismatches);
        m.put("perSeed", perSeed);
        if (!mismatchDetails.isEmpty()) {
            m.put("mismatchDetails", mismatchDetails);
        }
        assertEquals(0, mismatches, String.join("; ", mismatchDetails));
        assertTrue(visibleTotal > 0, "the policies make something visible");
    }

    @Test
    @Order(2)
    @Scenario(id = "E2-2", name = "No post-filtering: LIMIT/OFFSET pages of the predicate query are full and their "
            + "concatenation equals the full list", tests = {"T37"})
    void pagesAreCompleteWithoutPostFiltering() {
        int pagesChecked = 0;
        for (Principal p : randomPrincipals(SEEDS[SEEDS.length - 1])) {
            SqlPredicate pred = policy.visible(p, Action.READ_TASK, "r");
            List<String> full = query("SELECT r.ID_ FROM ACT_RU_TASK r WHERE " + pred.sql() + " ORDER BY r.CREATE_TIME_, r.ID_", pred);
            List<String> concat = new ArrayList<>();
            for (int offset = 0; ; offset += 50) {
                List<String> page = query("SELECT r.ID_ FROM ACT_RU_TASK r WHERE " + pred.sql()
                        + " ORDER BY r.CREATE_TIME_, r.ID_" + SqlDialects.page(env.dialect, offset, 50), pred);
                pagesChecked++;
                if (offset + 50 < full.size()) {
                    assertEquals(50, page.size(), "a non-last page is full");
                }
                concat.addAll(page);
                if (page.size() < 50) {
                    break;
                }
            }
            assertEquals(full, concat);
        }
        m.put("pagesChecked", pagesChecked);
        m.put("pageSize", 50);
    }

    @Test
    @Order(3)
    @Scenario(id = "E2-3", name = "Explicit deny wins (A-3): over a broader grant, over a user-level grant, a broader "
            + "deny over a specific grant; conditional rows fail closed; a deny of another permission does not hide",
            tests = {"T14", "T37"})
    void denyWins() {
        String tenant = "tn-c";
        List<TaskRow> tt = tasksByTenant.get(tenant);
        String x = tt.get(0).id();
        String y = tt.get(1).id();
        String z = tt.get(2).id();
        Map<String, Object> cases = new LinkedHashMap<>();
        env.run(c -> {
            AuthorizationPolicy.grant(c, env.dialect, tenant, "dw-A", TYPE, "*", BIT);
            AuthorizationPolicy.deny(c, env.dialect, tenant, "dw-B", TYPE, x, BIT);
            AuthorizationPolicy.grant(c, env.dialect, tenant, "dw-user2", TYPE, x, BIT);
            AuthorizationPolicy.grant(c, env.dialect, tenant, "dw-C", TYPE, y, BIT);
            AuthorizationPolicy.deny(c, env.dialect, tenant, "dw-D", TYPE, "*", BIT);
            AuthorizationPolicy.grant(c, env.dialect, tenant, "dw-E", TYPE, z, BIT);
            AuthorizationPolicy.deny(c, env.dialect, tenant, "dw-F", TYPE, z, Action.COMPLETE.bit());
        });
        env.exec("INSERT INTO AUTH_EFFECTIVE VALUES (?,?,?,?,0,?,?,1)", tenant, "dw-G", TYPE, y, BIT, "cond-x");
        env.exec("INSERT INTO AUTH_EFFECTIVE VALUES (?,?,?,?,1,?,?,1)", tenant, "dw-H", TYPE, z, BIT, "cond-y");
        List<Grant> rows = currentRows();

        // a) group grant '*' + other group's deny on x
        Principal a = new Principal(tenant, "dw-user1", Set.of("dw-A", "dw-B"));
        List<String> la = check(a, rows);
        assertFalse(la.contains(x));
        assertTrue(la.contains(y));
        assertEquals(tt.size() - 1, la.size());
        cases.put("groupGrantAll+groupDenyX", "visible " + la.size() + "/" + tt.size() + ", x hidden");
        // b) user-level grant on x + group deny on x
        Principal b = new Principal(tenant, "dw-user2", Set.of("dw-B"));
        assertFalse(check(b, rows).contains(x));
        cases.put("userGrantX+groupDenyX", "x hidden");
        // c) specific grant on y + broader deny '*'
        Principal cc = new Principal(tenant, "dw-user3", Set.of("dw-C", "dw-D"));
        assertTrue(check(cc, rows).isEmpty());
        cases.put("groupGrantY+groupDenyAll", "nothing visible");
        // d) deny of another permission (COMPLETE) does not hide READ_TASK
        Principal d = new Principal(tenant, "dw-user4", Set.of("dw-E", "dw-F"));
        assertEquals(List.of(z), check(d, rows));
        cases.put("grantZ+denyCompleteZ", "z visible");
        // e) conditional grant never matches; conditional deny always applies (fail closed)
        Principal e = new Principal(tenant, "dw-user5", Set.of("dw-G"));
        assertTrue(check(e, rows).isEmpty());
        Principal f = new Principal(tenant, "dw-user6", Set.of("dw-E", "dw-H"));
        assertTrue(check(f, rows).isEmpty());
        cases.put("conditionalGrant", "not visible (fail closed)");
        cases.put("conditionalDeny", "applies (fail closed)");
        m.put("cases", cases);
    }

    /** Asserts the three forms agree for one principal and returns the list. */
    private List<String> check(Principal p, List<Grant> rows) {
        List<String> sql = sqlList(p, null);
        assertEquals(allowList(p), sql, "object check vs SQL for " + p.userId());
        assertEquals(modelList(rows, p, tasks), sql, "model vs SQL for " + p.userId());
        assertEquals(sql.size(), sqlCount(p));
        return sql;
    }

    @Test
    @Order(4)
    @Scenario(id = "E2-4", name = "Tenant binding: grant rows of another tenant never leak; a request tenant id only "
            + "filters inside the allowed set", tests = {"T13"})
    void tenantBinding() {
        env.run(c -> {
            AuthorizationPolicy.grant(c, env.dialect, "tn-b", "tb-G", TYPE, "*", BIT);       // other tenant, same group id
            AuthorizationPolicy.grant(c, env.dialect, "tn-a", "tb-G", TYPE, tasksByTenant.get("tn-a").get(0).id(), BIT);
        });
        Principal p = new Principal("tn-a", "tb-user", Set.of("tb-G"));
        List<String> visible = check(p, currentRows());
        assertEquals(List.of(tasksByTenant.get("tn-a").get(0).id()), visible);
        List<String> spoofed = sqlList(p, "r.TENANT_ID_ = ?", "tn-b");
        assertTrue(spoofed.isEmpty(), "request tenant tn-b gives nothing to a tn-a principal");
        m.put("visibleForTnA", visible.size());
        m.put("withRequestTenantTnB", spoofed.size());
        m.put("tnBTasksCoveredByOtherTenantGrant", tasksByTenant.get("tn-b").size());
    }

    @Test
    @Order(5)
    @Scenario(id = "E2-5", name = "Revocation: a new deny (policy_version bump) applies to the very next list and "
            + "object check (no cache)", tests = {"T15"})
    void revocationIsImmediate() {
        String tenant = "tn-d";
        String x = tasksByTenant.get(tenant).get(0).id();
        long v0 = env.inTx(c -> {
            AuthorizationPolicy.grant(c, env.dialect, tenant, "rv-G", TYPE, x, BIT);
            return AuthorizationPolicy.policyVersion(c, tenant);
        });
        Principal p = new Principal(tenant, "rv-user", Set.of("rv-G"));
        assertEquals(List.of(x), check(p, currentRows()));
        long v1 = env.inTx(c -> AuthorizationPolicy.deny(c, env.dialect, tenant, "rv-user", TYPE, x, BIT));
        assertTrue(v1 > v0);
        assertTrue(check(p, currentRows()).isEmpty());
        long v2 = env.inTx(c -> AuthorizationPolicy.revoke(c, env.dialect, tenant, "rv-user", TYPE, x, true));
        assertEquals(List.of(x), check(p, currentRows()));
        long v3 = env.inTx(c -> AuthorizationPolicy.revoke(c, env.dialect, tenant, "rv-G", TYPE, x, false));
        assertTrue(check(p, currentRows()).isEmpty());
        m.put("policyVersions", List.of(v0, v1, v2, v3));
    }

    @Test
    @Order(6)
    @Scenario(id = "E2-6", name = "Q2: the same predicate through the engine's own native task query API returns the "
            + "same list and count, without engine patches", tests = {"T37"})
    void nativeEngineQueryApi() throws Exception {
        List<Principal> principals = randomPrincipals(SEEDS[0]);
        List<Grant> rows = loadRandomPolicy(SEEDS[0], principals);
        int checked = 0;
        for (Principal p : principals) {
            SqlPredicate onRes = policy.visible(p, Action.READ_TASK, "RES");
            List<String> nat = new ArrayList<>(nativeList(onRes, Integer.MAX_VALUE));
            Collections.sort(nat);
            assertEquals(modelList(rows, p, tasks), nat, p.userId());
            assertEquals(nat.size(), nativeCount(onRes));
            checked++;
        }
        m.put("principalsChecked", checked);
        m.put("api", kind() == org.sangam.spike.it.support.EngineKind.FLOWABLE
                ? "TaskService.createNativeTaskQuery().sql(..).parameter(..)  (Flowable 8.0.0)"
                : "TaskService.createNativeTaskQuery().sql(..).parameter(..)  (Operaton 2.2.0)");
    }

    @Test
    @Order(7)
    @Scenario(id = "E2-7", name = "p95 list latency with a 300-group principal (plain SQL and engine native query), "
            + "reduced scale; pass criterion p95 < 200 ms", tests = {"T38"})
    void latencyWith300Groups() throws Exception {
        List<Principal> principals = randomPrincipals(SEEDS[0]);
        loadRandomPolicy(SEEDS[0], principals);
        Principal big = principals.stream().filter(p -> p.groups().size() == GROUPS).findFirst().orElseThrow();
        SqlPredicate pred = policy.visible(big, Action.READ_TASK, "r");
        String page = "SELECT r.ID_, r.NAME_, r.CREATE_TIME_ FROM ACT_RU_TASK r WHERE " + pred.sql()
                + " ORDER BY r.CREATE_TIME_ DESC, r.ID_" + SqlDialects.page(env.dialect, 0, 50);
        String count = "SELECT count(*) FROM ACT_RU_TASK r WHERE " + pred.sql();
        Map<String, Object> list = time(() -> query(page, pred).size(), 30, 300);
        Map<String, Object> cnt = time(() -> query(count, pred).size(), 30, 300);
        SqlPredicate onRes = policy.visible(big, Action.READ_TASK, "RES");
        Map<String, Object> nat = time(() -> nativeList(onRes, 50).size(), 30, 300);
        String plan;
        String explain = SqlDialects.explainAnalyzePrefix(env.dialect);
        if (explain == null) {
            plan = "not captured on " + env.dialect;
        } else {
            try (Connection c = env.reads.getConnection(); PreparedStatement ps = c.prepareStatement(explain + page)) {
                pred.bind(ps, 1);
                StringBuilder sb = new StringBuilder();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        sb.append(rs.getString(1)).append('\n');
                    }
                }
                plan = sb.toString();
            }
        }
        long grantRows = env.count("SELECT count(*) FROM AUTH_EFFECTIVE");
        m.put("principalIds", big.ids().size());
        m.put("visibleTasks", sqlCount(big));
        m.put("tasks", tasks.size());
        m.put("authEffectiveRows", grantRows);
        m.put("listPage50", list);
        m.put("count", cnt);
        m.put("nativeQueryPage50", nat);
        m.put("explainAnalyzeListPage", plan.length() > 3000 ? plan.substring(0, 3000) : plan);
        m.put("scaleNote", (tasks.size() >= 10_000 ? "SPEC SCALE: " : "REDUCED: ") + tasks.size() + " tasks (spec 10,000), 300 groups, warm cache, single client");
        assertTrue((Double) list.get("p95Ms") < 200.0, "list p95 " + list.get("p95Ms"));
        assertTrue((Double) cnt.get("p95Ms") < 200.0, "count p95 " + cnt.get("p95Ms"));
    }

    static Map<String, Object> time(Supplier<Integer> q, int warmup, int runs) {
        for (int i = 0; i < warmup; i++) {
            q.get();
        }
        double[] ms = new double[runs];
        int rows = 0;
        for (int i = 0; i < runs; i++) {
            long t = System.nanoTime();
            rows = q.get();
            ms[i] = (System.nanoTime() - t) / 1e6;
        }
        Arrays.sort(ms);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runs", runs);
        out.put("rows", rows);
        out.put("p50Ms", round(ms[(int) Math.floor(0.50 * (runs - 1))]));
        out.put("p95Ms", round(ms[(int) Math.floor(0.95 * (runs - 1))]));
        out.put("p99Ms", round(ms[(int) Math.floor(0.99 * (runs - 1))]));
        out.put("maxMs", round(ms[runs - 1]));
        return out;
    }

    static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }

    @Test
    @Order(8)
    @Scenario(id = "E2-8", name = "Q2 probe: what the engine's own TaskQuery / authorization offers for this filter",
            counted = false)
    void engineOwnAuthorizationProbe() throws Exception {
        String tenant = "tn-d";
        // the engine's candidate-link filter for the same 300 groups, compared with the policy (a different model)
        List<Principal> principals = randomPrincipals(SEEDS[0]);
        loadRandomPolicy(SEEDS[0], principals);
        Principal big = principals.stream().filter(p -> p.groups().size() == GROUPS).findFirst().orElseThrow();
        long candidate = env.engine.countTasks(new EngineAdapter.TaskQuery(big.tenantId(), null, new ArrayList<>(big.groups()),
                null, null, 0, 0));
        m.put("engineCandidateGroupFilterCount", candidate);
        m.put("policyVisibleCount", sqlCount(big));
        m.put("engineTablesNamedLikeAuth", env.strings(SqlDialects.tablesLike(env.dialect, "ACT%AUTH%")));
        m.putAll(engineAuthorizationProbe(new ArrayList<>(tasksByTenant.get(tenant)), tenant));
    }

    static List<String> sortedCopy(List<String> l) {
        return new ArrayList<>(new TreeSet<>(l));
    }

    /** The predicate with MyBatis named parameters ({@code #{p0}}, ...), as both engines' native queries expect. */
    record NamedSql(String sql, Map<String, Object> params) {
    }

    protected static NamedSql named(SqlPredicate p) {
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
        return new NamedSql(sb.toString(), params);
    }
}
