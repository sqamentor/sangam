// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.history;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.bpm.engine.impl.interceptor.Command;
import org.sangam.core.history.HistoryArchiveGate;
import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.AuditCheckpoints;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Transactions;
import org.sangam.engine.OperatonEngineAdapter;
import org.sangam.engine.SangamEngineFactory;
import org.sangam.engine.SangamEngineSettings;
import org.sangam.ops.archive.HistoryArchiveExporter;
import org.sangam.ops.archive.HistoryArchives;
import org.sangam.ops.archive.PartitionDropGuard;
import org.sangam.ops.retention.HierarchyRetention;
import org.sangam.ports.content.FileSystemDocumentStore;

/**
 * RM-52 (decision 386) end to end on H2 with the Sangam engine: a tree with a call activity, a user task, a decision
 * and a byte variable. Before it has an archive every purge path refuses it and deletes nothing (the engine's
 * {@code HistoryService} delete and its removal-time cleanup, the hierarchy-retention purge, a partition drop). Its
 * archive holds every row the engine held, a second export is byte-identical, and a tampered, foreign-key or stale
 * archive is refused. With a verified archive the purge goes through.
 */
class HistoryArchivePurgeTest {

    static final String TENANT = "t-archive";
    static final String MODELS = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:operaton="http://operaton.org/schema/1.0/bpmn" targetNamespace="t">
              <process id="rm52-child" isExecutable="true" operaton:historyTimeToLive="30">
                <startEvent id="cs"/><sequenceFlow id="c1" sourceRef="cs" targetRef="review"/>
                <userTask id="review"/><sequenceFlow id="c2" sourceRef="review" targetRef="fee"/>
                <businessRuleTask id="fee" operaton:decisionRef="rm52-fee" operaton:mapDecisionResult="singleEntry"
                                  operaton:resultVariable="fee" operaton:decisionRefBinding="deployment"/>
                <sequenceFlow id="c3" sourceRef="fee" targetRef="ce"/><endEvent id="ce"/>
              </process>
              <process id="rm52-root" isExecutable="true" operaton:historyTimeToLive="30">
                <startEvent id="s"/><sequenceFlow id="f1" sourceRef="s" targetRef="call"/>
                <callActivity id="call" calledElement="rm52-child" operaton:calledElementBinding="deployment">
                  <extensionElements><operaton:in variables="all"/></extensionElements>
                </callActivity>
                <sequenceFlow id="f2" sourceRef="call" targetRef="e"/><endEvent id="e"/>
              </process>
            </definitions>
            """;
    static final String DMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" xmlns:operaton="http://operaton.org/schema/1.0/dmn"
                         id="d" name="d" namespace="t">
              <decision id="rm52-fee" name="fee" operaton:historyTimeToLive="30">
                <decisionTable id="t" hitPolicy="UNIQUE">
                  <input id="i"><inputExpression id="ie" typeRef="long"><text>amount</text></inputExpression></input>
                  <output id="o" name="fee" typeRef="long"/>
                  <rule id="r1"><inputEntry id="e1"><text>&lt; 1000</text></inputEntry><outputEntry id="o1"><text>10</text></outputEntry></rule>
                  <rule id="r2"><inputEntry id="e2"><text>&gt;= 1000</text></inputEntry><outputEntry id="o2"><text>25</text></outputEntry></rule>
                </decisionTable>
              </decision>
            </definitions>
            """;

    final AtomicReference<HistoryArchiveGate> gate = new AtomicReference<>(HistoryArchiveGate.notConfigured());
    JdbcDataSource ds;
    OperatonEngineAdapter engine;
    Transactions tx;
    AuditChain audit;
    Path store;
    KeyPair key;
    HistoryArchives archives;

    @BeforeEach
    void setUp() throws Exception {
        SangamEngineSettings s = new SangamEngineSettings();
        s.setSystemAuditScope("system:platform");
        s.setAllowMultipleEnginesPerJvm(true);
        s.setEngineSchemaUpdate("true");
        s.setApplyEnvironment(false);
        s.setJobExecutorActivate(false);
        // the gate the engine asks; swapped by the test (decision 386: unset, it refuses every purge)
        s.setHistoryArchiveGate((c, t, r) -> gate.get().refusal(c, t, r));
        ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:rm52" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        engine = SangamEngineFactory.build(ds, s, "h2");
        engine.deploy(TENANT, "rm52", Map.of("rm52.bpmn", MODELS.getBytes(StandardCharsets.UTF_8),
                "rm52.dmn", DMN.getBytes(StandardCharsets.UTF_8)));
        tx = Transactions.of(ds);
        audit = new AuditChain(Dialect.POSTGRESQL);
        store = Files.createTempDirectory("rm52-store");
        KeyPairGenerator g = KeyPairGenerator.getInstance("Ed25519");
        key = g.generateKeyPair();
        archives = archives(key, key);
    }

    @AfterEach
    void tearDown() {
        engine.close();
    }

    HistoryArchives archives(KeyPair signing, KeyPair trusted) throws Exception {
        AuditCheckpoints.Signer signer = AuditCheckpoints.ed25519(
                Base64.getEncoder().encodeToString(signing.getPrivate().getEncoded()),
                Base64.getEncoder().encodeToString(signing.getPublic().getEncoded()));
        return new HistoryArchives(new FileSystemDocumentStore(store), audit, signer,
                AuditCheckpoints.ed25519Keys(List.of(Base64.getEncoder().encodeToString(trusted.getPublic().getEncoded()))),
                new HistoryArchives.Settings("RECORDS_HISTORY", 3650, "test", ""));
    }

    /** Runs one tree to its end: root, child (call activity), user task, decision, byte variable. */
    String endedTree() {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("amount", 1500L);
        vars.put("scan", "a scanned page".getBytes(StandardCharsets.UTF_8));
        String root = engine.startProcess(TENANT, "rm52-root", "RM52-" + UUID.randomUUID(), vars);
        String task = engine.processEngine().getTaskService().createTaskQuery().processDefinitionKey("rm52-child")
                .tenantIdIn(TENANT).singleResult().getId();
        engine.processEngine().getTaskService().complete(task);
        assertNotNull(engine.processEngine().getHistoryService().createHistoricProcessInstanceQuery()
                .processInstanceId(root).singleResult().getEndTime(), "the tree ended");
        return root;
    }

    long count(String table, String root) throws Exception {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM " + table + " WHERE ROOT_PROC_INST_ID_=?")) {
            ps.setString(1, root);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    <T> T inCommand(Command<T> command) {
        return ((ProcessEngineConfigurationImpl) engine.processEngine().getProcessEngineConfiguration())
                .getCommandExecutorTxRequired().execute(command);
    }

    void dueNow(String root) throws Exception {
        try (Connection c = ds.getConnection()) {
            for (String t : List.of("ACT_HI_PROCINST", "ACT_HI_ACTINST", "ACT_HI_TASKINST", "ACT_HI_VARINST",
                    "ACT_HI_DECINST", "ACT_HI_DEC_IN", "ACT_HI_DEC_OUT", "ACT_GE_BYTEARRAY")) {
                try (PreparedStatement ps = c.prepareStatement("UPDATE " + t + " SET REMOVAL_TIME_=? WHERE ROOT_PROC_INST_ID_=?")) {
                    ps.setTimestamp(1, Timestamp.from(Instant.now().minusSeconds(3600)));
                    ps.setString(2, root);
                    ps.executeUpdate();
                }
            }
        }
    }

    HierarchyRetention.Tree tree(String root) {
        List<HierarchyRetention.Tree> plan = new HierarchyRetention(tx, audit, gate.get()).plan(TENANT,
                Map.of("rm52-root", Duration.ZERO), Instant.now().plusSeconds(60), id -> false, 10);
        return plan.stream().filter(t -> t.rootId().equals(root)).findFirst().orElseThrow();
    }

    @Test
    void everyPurgePathRefusesATreeWithoutAVerifiedArchive() throws Exception {
        String root = endedTree();
        long acts = count("ACT_HI_ACTINST", root);
        assertTrue(acts > 0 && count("ACT_HI_DECINST", root) == 1 && count("ACT_GE_BYTEARRAY", root) > 0);

        // not configured (no store, no key): refused
        HistoryArchiveGate.PurgeRefusedException e = assertThrows(HistoryArchiveGate.PurgeRefusedException.class,
                () -> engine.processEngine().getHistoryService().deleteHistoricProcessInstance(root));
        assertTrue(e.getMessage().contains(HistoryArchiveGate.NOT_CONFIGURED), e.getMessage());

        // configured, no archive yet: every path refuses and nothing goes
        gate.set(archives);
        assertTrue(assertThrows(HistoryArchiveGate.PurgeRefusedException.class,
                () -> engine.processEngine().getHistoryService().deleteHistoricProcessInstancesIfExists(List.of(root)))
                .getMessage().contains("HISTORY_ARCHIVE_MISSING"));
        dueNow(root);
        assertThrows(HistoryArchiveGate.PurgeRefusedException.class, () -> inCommand(cc ->
                cc.getHistoricProcessInstanceManager().deleteHistoricProcessInstancesByRemovalTime(new Date(), 0, 59, 500)));
        assertThrows(HistoryArchiveGate.PurgeRefusedException.class, () -> inCommand(cc ->
                cc.getHistoricDecisionInstanceManager().deleteHistoricDecisionsByRemovalTime(new Date(), 0, 59, 500)));
        HierarchyRetention retention = new HierarchyRetention(tx, audit, archives);
        assertEquals(0, retention.execute(List.of(tree(root)), (t, ids) -> engine.processEngine().getHistoryService()
                .deleteHistoricProcessInstancesIfExists(ids), "test", "RM-52 refusal"));
        assertTrue(retention.runs(TENANT).get(0).reason().contains("refused without a verified archive: 1"));
        PartitionDropGuard.Verdict v = tx.inTransaction(c -> new PartitionDropGuard(archives, "").check(c,
                Instant.now().minusSeconds(7200), Instant.now()));
        assertFalse(v.allowed());
        assertTrue(v.refused().get(root).startsWith("HISTORY_ARCHIVE_MISSING"), v.toString());
        assertEquals(acts, count("ACT_HI_ACTINST", root), "nothing was deleted");
        assertEquals(1, count("ACT_HI_DECINST", root));
    }

    @Test
    void theArchiveHoldsEveryRowIsDeterministicAndOnlyAVerifiedOneLetsThePurgeThrough() throws Exception {
        String root = endedTree();
        HistoryArchiveExporter exporter = new HistoryArchiveExporter("", "test");
        HistoryArchiveExporter.Archive one = tx.inTransaction(c -> exporter.export(c, TENANT, root, Map.of()));
        HistoryArchiveExporter.Archive two = tx.inTransaction(c -> exporter.export(c, TENANT, root, Map.of()));
        assertArrayEquals(HistoryArchiveExporter.zip(one.files()), HistoryArchiveExporter.zip(two.files()),
                "a second export of the same tree is identical");
        for (String t : List.of("ACT_HI_PROCINST", "ACT_HI_ACTINST", "ACT_HI_TASKINST", "ACT_HI_VARINST",
                "ACT_HI_DECINST", "ACT_HI_DEC_IN", "ACT_HI_DEC_OUT", "ACT_GE_BYTEARRAY")) {
            long rows = count(t, root);
            long archived = one.rowCounts().get(t);
            assertEquals(archived, new String(one.files().get("tables/" + t + ".jsonl"), StandardCharsets.UTF_8)
                    .lines().count(), t);
            if (t.equals("ACT_GE_BYTEARRAY")) {
                assertTrue(rows > 0 && archived >= rows, "the tree's byte arrays and every one its rows reference");
            } else {
                assertEquals(rows, archived, t);
            }
        }
        assertEquals(2, one.rowCounts().get("ACT_HI_PROCINST"), "the root and the called child");
        String bytes = new String(one.files().get("tables/ACT_GE_BYTEARRAY.jsonl"), StandardCharsets.UTF_8);
        assertTrue(bytes.contains(Base64.getEncoder().encodeToString("a scanned page".getBytes(StandardCharsets.UTF_8))),
                "the byte variable is in the archive");
        assertTrue(one.files().keySet().stream().anyMatch(p -> p.startsWith("models/") && p.endsWith("rm52.bpmn")));
        assertTrue(one.files().keySet().stream().anyMatch(p -> p.startsWith("models/") && p.endsWith("rm52.dmn")));

        // decision 386(1): an archive class that keeps the archive no longer than the engine keeps the history is refused
        HistoryArchives tooShort = new HistoryArchives(new FileSystemDocumentStore(store), audit,
                AuditCheckpoints.ed25519(Base64.getEncoder().encodeToString(key.getPrivate().getEncoded()),
                        Base64.getEncoder().encodeToString(key.getPublic().getEncoded())),
                AuditCheckpoints.ed25519Keys(List.of(Base64.getEncoder().encodeToString(key.getPublic().getEncoded()))),
                new HistoryArchives.Settings("RECORDS_HISTORY", 30, "test", ""));
        HistoryArchiveExporter.ExportRefusedException shortClass = assertThrows(
                HistoryArchiveExporter.ExportRefusedException.class,
                () -> tx.inTransaction(c -> tooShort.archive(c, TENANT, root, Map.of())));
        assertEquals("HISTORY_ARCHIVE_RETENTION_TOO_SHORT", shortClass.code());

        // archived and verified: every check passes
        gate.set(archives);
        HistoryArchives.Stored stored = tx.inTransaction(c -> archives.archive(c, TENANT, root, Map.of()));
        assertNull(tx.inTransaction(c -> archives.refusal(c, TENANT, root)));
        assertTrue(tx.inTransaction(c -> archives.archive(c, TENANT, root, Map.of())).reused(), "same tree, same ZIP");
        byte[] zip = Files.readAllBytes(store.resolve(stored.key()));
        java.util.Set<String> entries = new java.util.TreeSet<>();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry en; (en = z.getNextEntry()) != null; ) {
                entries.add(en.getName());
            }
        }
        assertTrue(entries.containsAll(List.of("manifest.json", "manifest.sig", "tables/ACT_HI_PROCINST.jsonl")), entries.toString());

        // an archive signed by a key this node does not trust is refused
        HistoryArchives foreign = archives(KeyPairGenerator.getInstance("Ed25519").generateKeyPair(), key);
        assertFalse(tx.inTransaction(c -> foreign.archive(c, TENANT, root, Map.of())).reused());
        assertTrue(tx.inTransaction(c -> archives.refusal(c, TENANT, root)).startsWith("HISTORY_ARCHIVE_SIGNATURE_INVALID"));
        tx.inTransaction(c -> archives.archive(c, TENANT, root, Map.of()));
        assertNull(tx.inTransaction(c -> archives.refusal(c, TENANT, root)));

        // a stored archive altered after the fact is refused
        Path file = store.resolve(stored.key());
        byte[] good = Files.readAllBytes(file);
        byte[] bad = good.clone();
        bad[bad.length / 2] ^= 1;
        Files.write(file, bad);
        assertTrue(tx.inTransaction(c -> archives.refusal(c, TENANT, root)).startsWith("HISTORY_ARCHIVE_DIGEST_MISMATCH"));
        Files.write(file, good);

        // a row added to the tree's history after the archive makes it stale
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO ACT_HI_ACTINST (ID_, PARENT_ACT_INST_ID_, PROC_DEF_KEY_, PROC_DEF_ID_, ROOT_PROC_INST_ID_,"
                        + " PROC_INST_ID_, EXECUTION_ID_, ACT_ID_, ACT_TYPE_, START_TIME_, TENANT_ID_, SEQUENCE_COUNTER_)"
                        + " SELECT 'rm52-extra', PARENT_ACT_INST_ID_, PROC_DEF_KEY_, PROC_DEF_ID_, ROOT_PROC_INST_ID_,"
                        + " PROC_INST_ID_, EXECUTION_ID_, ACT_ID_, ACT_TYPE_, START_TIME_, TENANT_ID_, SEQUENCE_COUNTER_"
                        + " FROM ACT_HI_ACTINST WHERE ROOT_PROC_INST_ID_=? FETCH FIRST 1 ROWS ONLY")) {
            ps.setString(1, root);
            assertEquals(1, ps.executeUpdate());
        }
        assertTrue(tx.inTransaction(c -> archives.refusal(c, TENANT, root)).startsWith("HISTORY_ARCHIVE_STALE"));
        tx.inTransaction(c -> archives.archive(c, TENANT, root, Map.of()));
        assertNull(tx.inTransaction(c -> archives.refusal(c, TENANT, root)));

        // the partition drop is allowed for this range now, and the retention purge goes through
        dueNow(root);
        assertTrue(tx.inTransaction(c -> new PartitionDropGuard(archives, "").check(c, Instant.now().minusSeconds(7200),
                Instant.now())).allowed());
        HierarchyRetention retention = new HierarchyRetention(tx, audit, archives);
        assertEquals(2, retention.execute(List.of(tree(root)), (t, ids) -> engine.processEngine().getHistoryService()
                .deleteHistoricProcessInstancesIfExists(ids), "test", "RM-52 purge"));
        assertEquals(0, count("ACT_HI_PROCINST", root));
        assertEquals(0, count("ACT_HI_ACTINST", root));
        assertTrue(Files.exists(store.resolve(stored.key())), "the archive outlives the history");
    }

    @Test
    void theRetentionRunExportsTheArchiveBeforeItPurges() throws Exception {
        String root = endedTree();
        gate.set(archives);
        HierarchyRetention retention = new HierarchyRetention(tx, audit, archives);
        assertEquals(2, retention.execute(List.of(tree(root)), (c, t) -> archives.archive(c, t.tenantId(), t.rootId(),
                Map.of()), (t, ids) -> engine.processEngine().getHistoryService().deleteHistoricProcessInstancesIfExists(ids),
                "test", "RM-52 archive then purge"));
        assertEquals(0, count("ACT_HI_PROCINST", root));
        try (var files = Files.walk(store)) {
            assertEquals(1, files.filter(p -> p.toString().endsWith(".zip")).count());
        }
    }
}
