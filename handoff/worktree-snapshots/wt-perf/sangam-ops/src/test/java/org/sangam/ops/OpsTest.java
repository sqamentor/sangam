// SPDX-License-Identifier: Apache-2.0
package org.sangam.ops;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.impl.task.TaskDefinition;
import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.AuthorizationPolicy;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Schema;
import org.sangam.core.layer.SqlPredicate;
import org.sangam.core.layer.Transactions;
import org.sangam.core.security.FieldEncryptor;
import org.sangam.ops.listener.GlobalTaskListeners;
import org.sangam.ops.metrics.UsageMetrics;
import org.sangam.ops.migrate.LegacyHistoryMigrator;
import org.sangam.ops.outlier.OutlierAnalysis;
import org.sangam.ops.retention.HierarchyRetention;
import org.sangam.ops.search.GlobalSearch;
import org.sangam.ops.search.SearchIndexMaintenance;
import org.sangam.ops.secrets.SecretAdmin;
import org.sangam.ops.sequence.SequenceAdmin;
import org.sangam.ops.vars.ScopedVariables;

import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class OpsTest {

    Transactions tx;
    JdbcDataSource ds;
    AuditChain audit = new AuditChain(Dialect.POSTGRESQL);
    static final Dialect D = Dialect.POSTGRESQL;

    @BeforeEach
    void db() throws Exception {
        ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=TRUE");
        try (Connection c = ds.getConnection()) {
            Schema.create(c, D);
            OpsSchema.create(c, D);
        }
        tx = Transactions.of(ds);
        tx.inTransaction(c -> {
            Jdbc.update(c, "CREATE TABLE ACT_HI_PROCINST (ID_ VARCHAR(64), ROOT_PROC_INST_ID_ VARCHAR(64), PROC_DEF_KEY_ VARCHAR(64), BUSINESS_KEY_ VARCHAR(64), TENANT_ID_ VARCHAR(64), START_TIME_ TIMESTAMP WITH TIME ZONE, END_TIME_ TIMESTAMP WITH TIME ZONE)");
            Jdbc.update(c, "CREATE TABLE ACT_HI_TASKINST (ID_ VARCHAR(64), TENANT_ID_ VARCHAR(64), ASSIGNEE_ VARCHAR(64), START_TIME_ TIMESTAMP WITH TIME ZONE)");
            Jdbc.update(c, "CREATE TABLE ACT_HI_DECINST (ID_ VARCHAR(64), TENANT_ID_ VARCHAR(64), EVAL_TIME_ TIMESTAMP WITH TIME ZONE)");
            Jdbc.update(c, "CREATE TABLE ACT_ID_USER (ID_ VARCHAR(64), FIRST_ VARCHAR(64), PWD_ VARCHAR(64), SALT_ VARCHAR(64))");
            Jdbc.update(c, "CREATE TABLE ACT_ID_MEMBERSHIP (USER_ID_ VARCHAR(64), GROUP_ID_ VARCHAR(64))");
            return null;
        });
    }

    @Test
    void migratorResumesNeverCopiesPasswordsAndReconciles() throws Exception {
        tx.inTransaction(c -> {
            for (int i = 0; i < 5; i++) {
                Jdbc.update(c, "INSERT INTO ACT_ID_USER VALUES (?,?,?,?)", "u" + i, "n", "hash", "salt");
            }
            Jdbc.update(c, "INSERT INTO ACT_ID_MEMBERSHIP VALUES ('u1','g1'),('u1','g2'),('u2','g1')");
            return null;
        });
        List<LegacyHistoryMigrator.Table> tables = List.of(new LegacyHistoryMigrator.Table("ACT_ID_USER", "ID_"),
                new LegacyHistoryMigrator.Table("ACT_ID_MEMBERSHIP", "USER_ID_", "GROUP_ID_"), new LegacyHistoryMigrator.Table("ACT_HI_MISSING", "ID_"));
        LegacyHistoryMigrator m = new LegacyHistoryMigrator(ds, tx, D, audit, "t");
        assertEquals(5, m.dryRun(tables).get(0).sourceCount());
        assertEquals(0L, (long) tx.<Long>inTransaction(c -> Jdbc.one(c, "SELECT COUNT(*) FROM OPS_MIG_ROW", rs -> rs.getLong(1)).get()));
        var r = m.run("j", tables, 2, "ops");
        assertTrue(r.stream().allMatch(LegacyHistoryMigrator.TableResult::matches), r::toString);
        assertEquals(3, r.get(1).copied());
        String payload = tx.inTransaction(c -> Jdbc.one(c, "SELECT payload FROM OPS_MIG_ROW WHERE source_id='u1'", rs -> rs.getString(1)).get());
        assertFalse(payload.contains("hash") || payload.contains("salt"), payload);
        tx.inTransaction(c -> Jdbc.update(c, "INSERT INTO ACT_ID_USER VALUES ('u9','n','h','s')"));
        assertFalse(m.reconcile("j", tables).get(0).matches());
        assertEquals(6, m.run("j", tables, 2, "ops").get(0).copied());
    }

    @Test
    void usageRollupReplacesTheDay() {
        Instant t = Instant.parse("2026-01-02T10:00:00Z");
        tx.inTransaction(c -> {
            Jdbc.update(c, "INSERT INTO ACT_HI_TASKINST VALUES ('1','a','x',?)", t);
            Jdbc.update(c, "INSERT INTO ACT_HI_TASKINST VALUES ('2','a','x',?)", t);
            Jdbc.update(c, "INSERT INTO ACT_HI_TASKINST VALUES ('3','b','y',?)", t);
            return null;
        });
        UsageMetrics u = new UsageMetrics(D, tx, () -> Map.of("a", 7L));
        LocalDate day = LocalDate.parse("2026-01-02");
        u.rollup(day);
        u.rollup(day);
        var rows = u.read("a", day, day);
        assertTrue(rows.contains(new UsageMetrics.Metric("a", "2026-01-02", "tasks", 2)), rows::toString);
        assertTrue(rows.contains(new UsageMetrics.Metric("a", "2026-01-02", "active_users", 1)));
        assertTrue(rows.contains(new UsageMetrics.Metric("a", "2026-01-02", "storage_bytes", 7)));
        assertEquals(1, u.read("b", day, day).stream().filter(x -> x.metric().equals("tasks")).count());
    }

    @Test
    void searchCapsAndHiddenRowsDoNotCount() {
        tx.inTransaction(c -> {
            Jdbc.update(c, "INSERT INTO ACT_HI_PROCINST(ID_,BUSINESS_KEY_,TENANT_ID_) VALUES ('1','Order 100%','t'),('2','order 200','t'),('3','order 300','t'),('4','order 400','other')");
            return null;
        });
        GlobalSearch s = new GlobalSearch(tx, List.of(new GlobalSearch.Source("instance", "ACT_HI_PROCINST r", "r.ID_", "r.BUSINESS_KEY_", List.of("r.BUSINESS_KEY_"),
                (c, p) -> new SqlPredicate("r.TENANT_ID_ = ? AND r.ID_ <> '3'", List.of(p.tenantId())))));
        var al = who(Set.of());
        assertEquals(2, s.search(al, "ORDER", 5).get("instance").hits().size());
        assertFalse(s.search(al, "order", 2).get("instance").more(), "hidden row 3 must not raise the more flag");
        assertTrue(s.search(al, "order", 1).get("instance").more());
        assertEquals("1", s.search(al, "100%", 5).get("instance").hits().get(0).id());
        assertThrows(IllegalArgumentException.class, () -> s.search(al, " ", 5));
    }

    @Test
    void storesNewSchemaColumns() throws Exception {
        ScopedVariables v = new ScopedVariables(D, tx, audit, 0);
        v.set(ScopedVariables.tenantScope("t"), "limit", 1, "ops", "initial");
        v.set(ScopedVariables.tenantScope("t"), "limit", 2, "ops", "raise");
        v.set(ScopedVariables.CLUSTER, "x", 1, "ops");
        var e = v.entries(ScopedVariables.tenantScope("t")).get(0);
        assertEquals(List.of("TENANT", "t", 2L, "raise"), List.of(e.scopeType(), e.tenantId(), e.version(), e.reason()));
        assertEquals("CLUSTER", v.entries(ScopedVariables.CLUSTER).get(0).scopeType());

        SecretAdmin s = new SecretAdmin(D, tx, audit, REVERSE);
        s.importAll("t", Map.of("k", "v"), "ops", "rotation");
        assertEquals("rotation", s.list("t").get(0).reason());

        SearchIndexMaintenance m = new SearchIndexMaintenance(D, tx);
        assertEquals("drift found", m.rebuild("t", List.of(reader(List.of(new SearchIndexMaintenance.Doc("1", "a", "b")))), 5, "drift found").reason());

        tx.inTransaction(c -> Jdbc.update(c, "INSERT INTO ACT_ID_USER VALUES ('u1','n','h','s')"));
        LegacyHistoryMigrator mig = new LegacyHistoryMigrator(ds, tx, D, audit, "t");
        List<LegacyHistoryMigrator.Table> tables = List.of(new LegacyHistoryMigrator.Table("ACT_ID_USER", "ID_"));
        mig.run("j", tables, 10, "ops");
        assertEquals("DONE", mig.checkpoints("j").get(0).status());
        tx.inTransaction(c -> Jdbc.update(c, "INSERT INTO ACT_ID_USER VALUES ('u2','n','h','s')"));
        mig.reconcile("j", tables);
        assertEquals(List.of("MISMATCHED", 1L), List.of(mig.checkpoints("j").get(0).status(), mig.checkpoints("j").get(0).mismatched()));
        assertThrows(Exception.class, () -> mig.run("j2", List.of(new LegacyHistoryMigrator.Table("ACT_ID_MEMBERSHIP", "NOPE_")), 10, "ops"));
        assertEquals(List.of("FAILED", 1L), List.of(mig.checkpoints("j2").get(0).status(), mig.checkpoints("j2").get(0).failed()));

        HierarchyRetention h = new HierarchyRetention(tx, audit);
        var tree = new HierarchyRetention.Tree("r1", "t", "k", Instant.parse("2020-01-01T00:00:00Z"), List.of("c1", "r1"));
        assertThrows(IllegalArgumentException.class, () -> h.execute(List.of(tree), (t, ids) -> { }, "ops", " "));
        assertEquals(2, h.execute(List.of(tree), (t, ids) -> { }, "ops", "records schedule 4"));
        var run = h.runs("t").get(0);
        assertEquals(List.of("records schedule 4", 2, "ops"), List.of(run.reason(), run.purged(), run.executedBy()));
        assertEquals(64, run.planHash().length());
    }

    static org.sangam.core.security.SecurityIdentity who(Set<String> roles) {
        return new org.sangam.core.security.SecurityIdentity("t", "al", "al", Set.of(), roles, Set.of(), Set.of(), "OIDC", null, null, false, Map.of());
    }

    @Test
    void typeWildcardDenyBlocksSearchAndOutliersThroughThePolicyCompiler() {
        tx.inTransaction(c -> {
            Jdbc.update(c, "ALTER TABLE ACT_HI_PROCINST ADD COLUMN PROC_INST_ID_ VARCHAR(64)");
            Jdbc.update(c, "CREATE TABLE AUTH_CONDITION (tenant_id VARCHAR(64), condition_id VARCHAR(64), resource_type VARCHAR(255), expression TEXT)");
            Jdbc.update(c, "CREATE TABLE ACT_HI_ACTINST (ID_ VARCHAR(64), PROC_INST_ID_ VARCHAR(64), PROC_DEF_KEY_ VARCHAR(64), ACT_ID_ VARCHAR(64), TENANT_ID_ VARCHAR(64), DURATION_ BIGINT)");
            Jdbc.update(c, "INSERT INTO ACT_HI_PROCINST(ID_,ROOT_PROC_INST_ID_,PROC_DEF_KEY_,BUSINESS_KEY_,TENANT_ID_) VALUES ('1','1','k','order 1','t')");
            Jdbc.update(c, "INSERT INTO ACT_HI_ACTINST VALUES ('a1','1','k','task','t',5)");
            long bit = 1L << AuthorizationPolicy.Action.READ.ordinal();
            AuthorizationPolicy.grant(c, D, "t", "user:al", "HISTORIC_PROCESS_INSTANCE", "*", bit);
            return null;
        });
        var compiler = new org.sangam.security.policy.PolicyCompiler(org.sangam.security.dialect.SecurityDialect.H2, new org.sangam.security.policy.ResourceRegistry(), Duration.ZERO);
        GlobalSearch s = new GlobalSearch(tx, List.of(new GlobalSearch.Source("process-instance", "ACT_HI_PROCINST r", "r.ID_", "r.BUSINESS_KEY_", List.of("r.BUSINESS_KEY_"),
                (c, w) -> new org.sangam.core.layer.SqlPredicate(compiler.visible(c, w, "HISTORIC_PROCESS_INSTANCE", org.sangam.security.policy.Permission.READ, "r").sql().replaceAll("BITAND\\(([a-z]+\\.perms), \\?\\)", "BITAND($1, CAST(? AS BIGINT))"), compiler.visible(c, w, "HISTORIC_PROCESS_INSTANCE", org.sangam.security.policy.Permission.READ, "r").params()))));  // H2 only: a cast for BITAND parameters; the compiler SQL is otherwise unchanged
        assertEquals(1, s.search(who(Set.of("ops")), "order", 5).get("process-instance").hits().size());
        assertEquals(1, OutlierAnalysis.readSamples(tx, who(Set.of("ops")), s.visibility("process-instance"), "k", "task", Set.of(), 10).size());
        tx.inTransaction(c -> AuthorizationPolicy.deny(c, D, "t", "role:ops", "*", "*", 1L << AuthorizationPolicy.Action.READ.ordinal()));
        assertTrue(s.search(who(Set.of("ops")), "order", 5).get("process-instance").hits().isEmpty(), "A-13: a type-* deny wins");
        assertTrue(OutlierAnalysis.readSamples(tx, who(Set.of("ops")), s.visibility("process-instance"), "k", "task", Set.of(), 10).isEmpty());
        assertTrue(OutlierAnalysis.readSamples(tx, who(Set.of()), java.util.Optional.empty(), "k", "task", Set.of(), 10).isEmpty(), "no binding fails closed");
    }

    static SearchIndexMaintenance.Reader reader(List<SearchIndexMaintenance.Doc> docs) {
        return new SearchIndexMaintenance.Reader() {
            public String type() {
                return "task";
            }

            public long count(String t) {
                return docs.size();
            }

            public List<SearchIndexMaintenance.Doc> read(String t, String after, int limit) {
                return docs.stream().filter(d -> after == null || d.id().compareTo(after) > 0).limit(limit).toList();
            }
        };
    }

    @Test
    void reindexSwitchesAliasAndDetectsDrift() {
        SearchIndexMaintenance m = new SearchIndexMaintenance(D, tx);
        List<SearchIndexMaintenance.Doc> docs = new ArrayList<>(List.of(new SearchIndexMaintenance.Doc("1", "a", "b"), new SearchIndexMaintenance.Doc("2", "c", "d"), new SearchIndexMaintenance.Doc("3", "e", "f")));
        var r = reader(docs);
        var j1 = m.rebuild("t", List.of(r), 2);
        assertEquals("ACTIVE", j1.state());
        assertEquals(3, j1.indexed());
        assertFalse(m.drift("t", List.of(r)).get(0).drifted());
        docs.add(new SearchIndexMaintenance.Doc("4", "g", "h"));
        assertTrue(m.drift("t", List.of(r)).get(0).drifted());
        var j2 = m.rebuild("t", List.of(r), 2);
        assertEquals(j1.generation() + 1, j2.generation());
        assertFalse(m.drift("t", List.of(r)).get(0).drifted());
        assertEquals(4L, (long) tx.<Long>inTransaction(c -> Jdbc.one(c, "SELECT COUNT(*) FROM OPS_SEARCH_DOC", rs -> rs.getLong(1)).get()));

        // The source changes during a rebuild: the live generation must stay.
        boolean[] changed = {false};
        SearchIndexMaintenance.Reader moving = new SearchIndexMaintenance.Reader() {
            public String type() {
                return "task";
            }

            public long count(String t) {
                return docs.size() + (changed[0] ? 1 : 0);
            }

            public List<SearchIndexMaintenance.Doc> read(String t, String after, int limit) {
                changed[0] = true;
                return r.read(t, after, limit);
            }
        };
        var j3 = m.rebuild("t", List.of(moving), 2);
        assertEquals("NOT_SWITCHED", j3.state());
        assertFalse(m.drift("t", List.of(r)).get(0).drifted(), "the alias still points at the complete generation");
        assertEquals(4L, (long) tx.<Long>inTransaction(c -> Jdbc.one(c, "SELECT COUNT(*) FROM OPS_SEARCH_DOC", rs -> rs.getLong(1)).get()));
    }

    @Test
    void outliersRespectMinSampleAndFindValue() {
        List<OutlierAnalysis.Sample> s = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            s.add(new OutlierAnalysis.Sample("i" + i, 100 + i, Map.of("region", "north", "same", "x")));
        }
        for (int i = 0; i < 6; i++) {
            s.add(new OutlierAnalysis.Sample("s" + i, 100_000, Map.of("region", "south", "same", "x")));
        }
        assertFalse(OutlierAnalysis.analyse("a", s, 100, 1.5, 0.05).enoughData());
        var r = OutlierAnalysis.analyse("a", s, 20, 1.5, 0.05);
        assertEquals(6, r.outliers());
        assertEquals(1, r.variables().size(), r.variables()::toString);
        assertEquals("south", r.variables().get(0).value());
        assertTrue(r.note().startsWith("Correlation"));
    }

    @Test
    void variablesMergeTenantOverCluster() {
        ScopedVariables v = new ScopedVariables(D, tx, audit, 0);
        v.set(ScopedVariables.CLUSTER, "limit", 5, "ops");
        v.set(ScopedVariables.CLUSTER, "name", "c", "ops");
        v.set(ScopedVariables.tenantScope("t"), "limit", 9, "ops");
        assertEquals(9, v.get("t", "limit"));
        assertEquals("c", v.get("t", "name"));
        assertEquals(5, v.get("other", "limit"));
        assertThrows(IllegalArgumentException.class, () -> v.set(ScopedVariables.CLUSTER, "apiToken", "x", "ops"));
    }

    static final FieldEncryptor REVERSE = new FieldEncryptor() {
        public String encrypt(String t, String c, String p) {
            return new StringBuilder(p).reverse().toString();
        }

        public String decrypt(String t, String c, String e) {
            return new StringBuilder(e).reverse().toString();
        }

        public String encryptJson(String t, String c, Map<String, ?> j) {
            throw new UnsupportedOperationException();
        }

        public Map<String, Object> decryptJson(String t, String c, String e) {
            throw new UnsupportedOperationException();
        }

        public String reencrypt(String t, String c, String e) {
            return e;
        }
    };

    @Test
    void secretsRoundTripThroughBundleAndListingHasNoValues() {
        SecretAdmin s = new SecretAdmin(D, tx, audit, REVERSE);
        assertEquals(new SecretAdmin.ImportResult(2, 0), s.importAll("t", Map.of("a/b", "one", "c", "two"), "ops"));
        assertEquals(new SecretAdmin.ImportResult(0, 1), s.importAll("t", Map.of("c", "three"), "ops"));
        assertEquals(2, s.list("t").get(1).version());
        assertFalse(s.list("t").toString().contains("three"));
        assertEquals("three", s.resolver("t").require("secret://c"));
        assertTrue(s.resolver("other").resolve("secret://c").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> s.exportBundle("t", "p".toCharArray(), "ops", " "));
        String bundle = s.exportBundle("t", "p".toCharArray(), "ops", "dr copy");
        assertFalse(bundle.contains("three"));
        assertThrows(IllegalArgumentException.class, () -> s.importBundle("t2", bundle, "bad".toCharArray(), "ops"));
        s.importBundle("t2", bundle, "p".toCharArray(), "ops");
        assertEquals("one", s.resolver("t2").require("secret://a/b"));
    }

    @Test
    void sequenceMovesForwardOnly() {
        tx.inTransaction(c -> {
            Jdbc.update(c, "CREATE TABLE SEQ (T VARCHAR(9), S VARCHAR(9), P VARCHAR(9), V BIGINT)");
            Jdbc.update(c, "INSERT INTO SEQ VALUES ('t','s','p',5)");
            return null;
        });
        SequenceAdmin a = new SequenceAdmin(D, tx, audit, List.of(new SequenceAdmin.Table("SEQ", "T", "S", "P", "V")));
        assertThrows(IllegalArgumentException.class, () -> a.advance("t", "SEQ", "s", "p", 5, "ops", "x"));
        assertThrows(IllegalArgumentException.class, () -> a.advance("t", "SEQ", "s", "p", 9, "ops", ""));
        a.advance("t", "SEQ", "s", "p", 9, "ops", "gap for import");
        assertEquals(9, a.list("t", "SEQ").get(0).value());
        assertTrue(a.list("x", "SEQ").isEmpty());
    }

    @Test
    void retentionTreatsTreeAsOneUnit() {
        Instant old = Instant.parse("2020-01-01T00:00:00Z");
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        tx.inTransaction(c -> {
            String sql = "INSERT INTO ACT_HI_PROCINST(ID_,ROOT_PROC_INST_ID_,PROC_DEF_KEY_,TENANT_ID_,START_TIME_,END_TIME_) VALUES (?,?,?,?,?,?)";
            Jdbc.update(c, sql, "r1", "r1", "k", "t", old, old);
            Jdbc.update(c, sql, "c1", "r1", "child", "t", old.plusSeconds(1), old);
            Jdbc.update(c, sql, "r2", "r2", "k", "t", old, old);
            Jdbc.update(c, sql, "c2", "r2", "child", "t", old.plusSeconds(1), null);
            Jdbc.update(c, sql, "r3", "r3", "k", "t", old, old);
            Jdbc.update(c, sql, "r4", "r4", "k", "t", old, now);
            Jdbc.update(c, sql, "r5", "r5", "unlisted", "t", old, old);
            return null;
        });
        HierarchyRetention h = new HierarchyRetention(tx, audit);
        var plan = h.plan("t", Map.of("k", Duration.ofDays(30)), now, id -> id.equals("r3"), 10);
        assertEquals(List.of("r1"), plan.stream().map(HierarchyRetention.Tree::rootId).toList(), plan::toString);
        assertEquals(List.of("c1", "r1"), plan.get(0).instanceIds());
        List<String> purged = new ArrayList<>();
        assertEquals(2, h.execute(plan, (t, ids) -> purged.addAll(ids), "ops"));
        assertEquals(List.of("c1", "r1"), purged);
    }

    @Test
    void globalListenerAttachesOnlyToMatchingKeysAndValidatesHandler() {
        assertThrows(IllegalArgumentException.class, () -> new GlobalTaskListeners(List.of(new GlobalTaskListeners.Rule("r", List.of("create"), Pattern.compile(".*"), "none", false)), Map.of()));
        assertNotNull(new GlobalTaskListeners(List.of(new GlobalTaskListeners.Rule("r", List.of("create"), Pattern.compile("a.*"), "h", false)),
                Map.of("h", (t, id) -> { })).parseListener());
        assertNotNull(TaskDefinition.class);
    }
}
