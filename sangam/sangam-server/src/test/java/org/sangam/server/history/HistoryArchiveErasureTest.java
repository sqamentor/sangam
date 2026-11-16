// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.history.HistoryArchiveGate;
import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.AuditCheckpoints;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;
import org.sangam.engine.OperatonEngineAdapter;
import org.sangam.engine.SangamEngineFactory;
import org.sangam.engine.SangamEngineSettings;
import org.sangam.integration.bridge.operaton.OperatonLifecyclePorts;
import org.sangam.integration.connector.ConnectorCallJournal;
import org.sangam.integration.lifecycle.ErasureService;
import org.sangam.integration.lifecycle.LegalHoldStore;
import org.sangam.integration.lifecycle.LifecyclePorts;
import org.sangam.integration.relay.DeadLetterStore;
import org.sangam.ops.archive.HistoryArchives;
import org.sangam.ops.archive.RetentionClassStore;
import org.sangam.ports.content.FileSystemDocumentStore;

/**
 * RM-52 spec section 2 "Tenancy and erasure", on H2 with the Sangam engine: an erasure after the export also reaches
 * the archive. Every stored archive of the tree held the byte variable; after the erasure a new archive without the
 * tree's variable, detail and comment rows and their byte arrays replaces them, its manifest lists the erasure and the
 * previous digests, the superseded archives leave the store, and the gate accepts the new archive. The scrub also
 * reaches a tree whose history was purged after its export, and an archive that is not the one recorded refuses the
 * erasure. The archive store keeps every archive for its retention class.
 */
class HistoryArchiveErasureTest {

    static final String TENANT = HistoryArchivePurgeTest.TENANT;
    static final String SCAN = Base64.getEncoder().encodeToString("a scanned page".getBytes(StandardCharsets.UTF_8));
    static final ObjectMapper JSON = new ObjectMapper();

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
        s.setHistoryArchiveGate((c, t, r) -> gate.get().refusal(c, t, r));
        ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:rm52e" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        engine = SangamEngineFactory.build(ds, s, "h2");
        engine.deploy(TENANT, "rm52", Map.of("rm52.bpmn", HistoryArchivePurgeTest.MODELS.getBytes(StandardCharsets.UTF_8),
                "rm52.dmn", HistoryArchivePurgeTest.DMN.getBytes(StandardCharsets.UTF_8)));
        tx = Transactions.of(ds);
        audit = new AuditChain(Dialect.POSTGRESQL);
        store = Files.createTempDirectory("rm52-erasure");
        key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        archives = archives(key);
        gate.set(archives);
    }

    @AfterEach
    void tearDown() {
        engine.close();
    }

    HistoryArchives archives(KeyPair signing) throws Exception {
        return new HistoryArchives(new FileSystemDocumentStore(store), audit, AuditCheckpoints.ed25519(
                Base64.getEncoder().encodeToString(signing.getPrivate().getEncoded()),
                Base64.getEncoder().encodeToString(signing.getPublic().getEncoded())),
                AuditCheckpoints.ed25519Keys(List.of(Base64.getEncoder().encodeToString(key.getPublic().getEncoded()))),
                new HistoryArchives.Settings("RECORDS_HISTORY", 3650, "test", ""));
    }

    String endedTree() {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("amount", 1500L);
        vars.put("scan", "a scanned page".getBytes(StandardCharsets.UTF_8));
        String root = engine.startProcess(TENANT, "rm52-root", "RM52-" + UUID.randomUUID(), vars);
        String task = engine.processEngine().getTaskService().createTaskQuery().processDefinitionKey("rm52-child")
                .tenantIdIn(TENANT).singleResult().getId();
        engine.processEngine().getTaskService().complete(task);
        return root;
    }

    ErasureService erasure(List<LifecyclePorts.ErasureFollower> followers) {
        OperatonLifecyclePorts ports = new OperatonLifecyclePorts(engine.processEngine());
        Dialect d = Dialect.POSTGRESQL;
        return new ErasureService(d, tx, audit, new LegalHoldStore(d), new ConnectorCallJournal(d),
                new DeadLetterStore(d, tx), ports, ports, null, (c, t, p, perm, i) -> { },
                LifecyclePorts.RelatedAuditChains.NONE, followers);
    }

    Map<String, byte[]> unzip(String archiveKey) throws Exception {
        Map<String, byte[]> files = new TreeMap<>();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(Files.readAllBytes(store.resolve(archiveKey))))) {
            for (ZipEntry en; (en = z.getNextEntry()) != null; ) {
                files.put(en.getName(), z.readAllBytes());
            }
        }
        return files;
    }

    /** The payload of the latest event of a type on the root's chain. */
    @SuppressWarnings("unchecked")
    Map<String, Object> latest(String root, String type) {
        return tx.inTransaction(c -> Jdbc.query(c, "SELECT p.payload FROM AUDIT_EVENT e JOIN AUDIT_PAYLOAD p ON"
                        + " p.event_id=e.event_id WHERE e.tenant_id=? AND e.chain_id=? AND e.event_type=? ORDER BY e.seq DESC",
                rs -> rs.getString(1), TENANT, root, type).stream().findFirst().map(j -> {
                    try {
                        return (Map<String, Object>) JSON.readValue(j, Map.class);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }).orElse(null));
    }

    String eventId(String root, String type) {
        return tx.inTransaction(c -> Jdbc.query(c, "SELECT event_id FROM AUDIT_EVENT WHERE tenant_id=? AND chain_id=?"
                + " AND event_type=? ORDER BY seq DESC", rs -> rs.getString(1), TENANT, root, type).get(0));
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

    /** Purges the history of the whole tree (root and child), as the retention run does. */
    void purgeTree(String root) {
        List<String> ids = engine.processEngine().getHistoryService().createHistoricProcessInstanceQuery()
                .rootProcessInstanceId(root).list().stream().map(h -> h.getId()).toList();
        engine.processEngine().getHistoryService().deleteHistoricProcessInstancesIfExists(ids);
    }

    static String text(Map<String, byte[]> files, String path) {
        return new String(files.get(path), StandardCharsets.UTF_8);
    }

    @Test
    void anErasureReplacesEveryArchiveOfTheTreeByOneWithoutTheErasedData() throws Exception {
        String root = endedTree();
        assertTrue(count("ACT_HI_VARINST", root) > 0);
        // two stored archives of the tree: one signed with another key, then the verified one
        HistoryArchives.Stored foreign = tx.inTransaction(c -> {
            try {
                return archives(KeyPairGenerator.getInstance("Ed25519").generateKeyPair()).archive(c, TENANT, root, Map.of());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        HistoryArchives.Stored stored = tx.inTransaction(c -> archives.archive(c, TENANT, root, Map.of()));
        assertNotEquals(foreign.key(), stored.key());
        for (String k : List.of(foreign.key(), stored.key())) {
            assertTrue(text(unzip(k), "tables/ACT_GE_BYTEARRAY.jsonl").contains(SCAN), "before: " + k + " holds the scan");
        }
        assertNull(tx.inTransaction(c -> archives.refusal(c, TENANT, root)));

        ErasureService.Result r = erasure(List.of(archives)).erase(TENANT, root, List.of(), "dpo-1", "DSR erasure");

        assertEquals(1, r.scrubbed().get("historyArchives"), r.scrubbed().toString());
        assertEquals(2, r.scrubbed().get("historyArchivesRemoved"), r.scrubbed().toString());
        assertEquals(0, count("ACT_HI_VARINST", root), "the engine erased the variables");
        for (String k : List.of(foreign.key(), stored.key())) {
            assertFalse(Files.exists(store.resolve(k)), "the superseded archive " + k + " left the store");
            assertFalse(Files.exists(store.resolve(k + RetentionClassStore.MARKER)), "with its retention marker");
        }
        Map<String, Object> event = latest(root, HistoryArchives.ARCHIVED);
        String now = String.valueOf(event.get("key"));
        assertEquals(eventId(root, "DATA_ERASED"), event.get("dataErased"));
        assertEquals(List.of(stored.key(), foreign.key()), event.get("supersedes"));
        Map<String, byte[]> files = unzip(now);
        assertFalse(text(files, "tables/ACT_GE_BYTEARRAY.jsonl").contains(SCAN), "the scan is not in the new archive");
        for (String t : HistoryArchives.ERASED_TABLES) {
            assertEquals("", text(files, "tables/" + t + ".jsonl"), t + " rows are removed");
        }
        assertEquals(2, text(files, "tables/ACT_HI_PROCINST.jsonl").lines().count(), "the other history is kept");
        assertTrue(text(files, "tables/ACT_HI_ACTINST.jsonl").lines().count() > 0);
        Map<?, ?> manifest = JSON.readValue(files.get(HistoryArchives.MANIFEST), Map.class);
        List<?> erasures = (List<?>) manifest.get("erasures");
        assertEquals(1, erasures.size());
        Map<?, ?> e = (Map<?, ?>) erasures.get(0);
        assertEquals(eventId(root, "DATA_ERASED"), e.get("dataErased"));
        assertEquals(stored.zipSha256(), e.get("previousZipSha256"));
        assertEquals(stored.manifestSha256(), e.get("previousManifestSha256"));
        assertTrue(((Number) e.get("removedByteArrays")).longValue() > 0, e.toString());

        // the new archive is signed and verified, so the purge goes through; it is kept for its class
        assertNull(tx.inTransaction(c -> archives.refusal(c, TENANT, root)));
        Instant until = archives.store().retainUntil(now).orElseThrow();
        assertTrue(until.isAfter(Instant.now().plus(3649, ChronoUnit.DAYS)), until.toString());
        RetentionClassStore.RetentionRefusedException kept = assertThrows(
                RetentionClassStore.RetentionRefusedException.class, () -> archives.store().delete(now));
        assertEquals("RETENTION_ACTIVE", kept.code());
        purgeTree(root);
        assertEquals(0, count("ACT_HI_PROCINST", root));
        assertTrue(Files.exists(store.resolve(now)), "the archive outlives the history");
    }

    @Test
    void anErasureAfterThePurgeScrubsTheArchiveThatIsTheOnlyCopy() throws Exception {
        String root = endedTree();
        HistoryArchives.Stored stored = tx.inTransaction(c -> archives.archive(c, TENANT, root, Map.of()));
        purgeTree(root);
        assertEquals(0, count("ACT_HI_PROCINST", root), "purged with a verified archive");

        ErasureService.Result r = erasure(List.of(archives)).erase(TENANT, root, List.of(), "dpo-1", "DSR erasure");

        assertEquals(1, r.scrubbed().get("historyArchives"));
        assertFalse(Files.exists(store.resolve(stored.key())));
        Map<String, byte[]> files = unzip(String.valueOf(latest(root, HistoryArchives.ARCHIVED).get("key")));
        assertFalse(text(files, "tables/ACT_GE_BYTEARRAY.jsonl").contains(SCAN));
        assertEquals(2, text(files, "tables/ACT_HI_PROCINST.jsonl").lines().count(), "the archived tree is kept");
    }

    @Test
    void anArchiveThatIsNotTheOneRecordedRefusesTheErasure() throws Exception {
        String root = endedTree();
        HistoryArchives.Stored stored = tx.inTransaction(c -> archives.archive(c, TENANT, root, Map.of()));
        Path file = store.resolve(stored.key());
        byte[] bad = Files.readAllBytes(file);
        bad[bad.length / 2] ^= 1;
        Files.write(file, bad);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> erasure(List.of(archives)).erase(TENANT, root, List.of(), "dpo-1", "DSR erasure"));
        assertTrue(e.getMessage().startsWith("HISTORY_ARCHIVE_ERASURE_REFUSED"), e.getMessage());
        assertNotNull(latest(root, HistoryArchives.ARCHIVED), "nothing was erased: the archive event is readable");
        assertTrue(count("ACT_HI_VARINST", root) > 0, "nothing was erased: the variables are there");
    }
}
