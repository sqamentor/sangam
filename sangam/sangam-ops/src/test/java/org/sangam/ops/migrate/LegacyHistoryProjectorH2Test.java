// SPDX-License-Identifier: Apache-2.0
package org.sangam.ops.migrate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;
import org.sangam.integration.connector.Json;
import org.sangam.ops.migrate.LegacyHistoryProjector.Result;
import org.sangam.ops.migrate.LegacyHistoryProjector.Source;
import org.sangam.persistence.SangamSchema;

/**
 * M-131 (decision 160) on H2 with the product changelog: legacy {@code ACT_HI_*} rows held in {@code OPS_MIG_ROW} become
 * LEGACY read-model rows with their own ids and an audit chain {@code legacy:<job>}; wall clocks are resolved in the named
 * source zone and DST gaps, overlaps and unknown states are recorded unresolved, never guessed. The same flow on Oracle
 * and SQL Server (changesets 0044-05..07 and the insert-if-absent path) is NOT_RUN in this coding phase.
 */
class LegacyHistoryProjectorH2Test {

    static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    static final String JOB = "j1";
    static final String T = "t1";

    Transactions tx;
    AuditChain audit = new AuditChain(Dialect.POSTGRESQL);
    LegacyHistoryProjector projector;

    @BeforeEach
    void db() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=TRUE");
        SangamSchema.apply(ds);
        tx = Transactions.of(ds);
        projector = new LegacyHistoryProjector(tx, Dialect.POSTGRESQL, audit);
    }

    void row(String table, String id, Object... kv) {
        Map<String, Object> p = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            p.put((String) kv[i], kv[i + 1]);
        }
        tx.inTransaction(c -> Jdbc.update(c, "INSERT INTO OPS_MIG_ROW(job_id,source_table,source_id,tenant_id,payload,migrated_at)"
                + " VALUES (?,?,?,?,?,?)", JOB, table, id, T, Json.write(p), Instant.parse("2026-11-01T00:00:00Z")));
    }

    /** One process instance with a root and a canceled activity, a completed and an open task, a case and an operation. */
    void corpus() {
        row("ACT_HI_PROCINST", "p1", "id_", "p1", "proc_inst_id_", "p1", "proc_def_key_", "loan", "proc_def_id_", "loan:1:9",
                "business_key_", "B-1", "start_time_", "2026-01-15T10:00:00Z", "end_time_", "2026-01-15T11:30:00Z",
                "state_", "COMPLETED", "root_proc_inst_id_", "p1", "tenant_id_", T);
        row("ACT_HI_ACTINST", "a1", "id_", "a1", "proc_inst_id_", "p1", "parent_act_inst_id_", "p1", "act_id_", "review",
                "act_type_", "userTask", "start_time_", "2026-01-15T10:00:00Z", "end_time_", "2026-01-15T11:00:00Z", "act_inst_state_", 0);
        row("ACT_HI_ACTINST", "a2", "id_", "a2", "proc_inst_id_", "p1", "parent_act_inst_id_", "a1", "act_id_", "timer",
                "act_type_", "boundaryTimer", "start_time_", "2026-01-15T10:00:00Z", "end_time_", "2026-01-15T11:00:00Z", "act_inst_state_", 2);
        row("ACT_HI_TASKINST", "k1", "id_", "k1", "proc_inst_id_", "p1", "task_def_key_", "review", "name_", "Review",
                "assignee_", "alice", "start_time_", "2026-01-15T10:00:00Z", "end_time_", "2026-01-15T11:00:00Z",
                "delete_reason_", "completed");
        row("ACT_HI_TASKINST", "k2", "id_", "k2", "proc_inst_id_", "p1", "task_def_key_", "sign", "name_", "Sign",
                "assignee_", "bob", "start_time_", "2026-01-16T09:00:00Z");
        row("ACT_HI_CASEINST", "c1", "id_", "c1", "case_inst_id_", "c1", "case_def_id_", "claim:1:3", "state_", 7,
                "create_time_", "2026-02-01T08:00:00Z", "close_time_", "2026-02-02T08:00:00Z");
        row("ACT_HI_OP_LOG", "o1", "id_", "o1", "timestamp_", "2026-01-15T10:30:00Z", "user_id_", "alice",
                "operation_type_", "SetVariable", "entity_type_", "Variable", "property_", "amount", "org_value_", "100",
                "new_value_", "250", "proc_inst_id_", "p1", "task_id_", "k1");
    }

    String one(String sql, Object... params) {
        return tx.inTransaction(c -> Jdbc.one(c, sql, rs -> rs.getString(1), params).orElse(null));
    }

    long count(String sql, Object... params) {
        return tx.inTransaction(c -> Jdbc.one(c, sql, rs -> rs.getLong(1), params).orElse(0L));
    }

    static Result of(List<Result> results, Source s) {
        return results.stream().filter(r -> r.source() == s).findFirst().orElseThrow();
    }

    @Test
    void legacyRowsBecomeMarkedReadModelRowsWithTheirOwnIds() {
        corpus();
        projector.project(JOB, BERLIN, 2, "ops", T);
        String pid = LegacyHistoryProjector.legacyId(JOB, "instance", "p1");
        assertTrue(pid.startsWith("lg-") && pid.length() == 35, pid);
        assertEquals("LEGACY", one("SELECT record_source FROM HRM_PROCESS_INSTANCE WHERE id=?", pid));
        assertEquals(JOB + ":ACT_HI_PROCINST:p1", one("SELECT legacy_ref FROM HRM_PROCESS_INSTANCE WHERE id=?", pid));
        assertEquals("completed", one("SELECT state FROM HRM_PROCESS_INSTANCE WHERE id=?", pid));
        assertEquals(pid, one("SELECT root_id FROM HRM_PROCESS_INSTANCE WHERE id=?", pid), "references use the same mapping");
        // 10:00 wall clock in Berlin (CET, +01:00) is 09:00 UTC
        Instant start = tx.inTransaction(c -> Jdbc.one(c, "SELECT start_time FROM HRM_PROCESS_INSTANCE WHERE id=?",
                rs -> Jdbc.instant(rs, "start_time"), pid).orElseThrow());
        assertEquals(Instant.parse("2026-01-15T09:00:00Z"), start);

        String a1 = LegacyHistoryProjector.legacyId(JOB, "activity", "a1");
        assertEquals(pid, one("SELECT parent_id FROM HRM_ACTIVITY_INSTANCE WHERE id=?", a1), "root activity's parent is the instance");
        assertEquals("completed", one("SELECT state FROM HRM_ACTIVITY_INSTANCE WHERE id=?", a1));
        String a2 = LegacyHistoryProjector.legacyId(JOB, "activity", "a2");
        assertEquals("canceled", one("SELECT state FROM HRM_ACTIVITY_INSTANCE WHERE id=?", a2));
        assertEquals(a1, one("SELECT parent_id FROM HRM_ACTIVITY_INSTANCE WHERE id=?", a2));

        assertEquals("completed", one("SELECT state FROM HRM_TASK WHERE id=?", LegacyHistoryProjector.legacyId(JOB, "task", "k1")));
        assertEquals("assigned", one("SELECT state FROM HRM_TASK WHERE id=?", LegacyHistoryProjector.legacyId(JOB, "task", "k2")));
        String cid = LegacyHistoryProjector.legacyId(JOB, "instance", "c1");
        assertEquals("case", one("SELECT scope_type FROM HRM_PROCESS_INSTANCE WHERE id=?", cid));
        assertEquals("completed", one("SELECT state FROM HRM_PROCESS_INSTANCE WHERE id=?", cid), "CaseExecutionState 7");
        assertEquals(0, count("SELECT COUNT(*) FROM HRM_TASK WHERE record_source <> 'LEGACY'"));
    }

    @Test
    void aLiveRowWithTheSameLegacyIdStaysApart() {
        tx.inTransaction(c -> Jdbc.update(c, "INSERT INTO HRM_PROCESS_INSTANCE(id, tenant_id, scope_type, state, last_seq)"
                + " VALUES ('p1', ?, 'process', 'active', 1)", T));
        corpus();
        projector.project(JOB, BERLIN, 10, "ops", T);
        assertEquals("ENGINE", one("SELECT record_source FROM HRM_PROCESS_INSTANCE WHERE id='p1'"));
        assertEquals("active", one("SELECT state FROM HRM_PROCESS_INSTANCE WHERE id='p1'"), "live row untouched");
        assertEquals(2, count("SELECT COUNT(*) FROM HRM_PROCESS_INSTANCE WHERE record_source='LEGACY'"));
    }

    @Test
    void theAuditChainIsItsOwnAndValuesAreOnlyHashed() {
        corpus();
        projector.project(JOB, BERLIN, 2, "ops", T);
        String chain = LegacyHistoryProjector.CHAIN_PREFIX + JOB;
        assertEquals(1, count("SELECT COUNT(*) FROM AUDIT_EVENT WHERE chain_id=? AND event_type=?", chain,
                LegacyHistoryProjector.EVENT_STARTED));
        assertEquals(1, count("SELECT COUNT(*) FROM AUDIT_EVENT WHERE chain_id=? AND event_type=?", chain,
                LegacyHistoryProjector.EVENT_OPERATION));
        assertTrue(count("SELECT COUNT(*) FROM AUDIT_EVENT WHERE chain_id=? AND event_type=?", chain,
                LegacyHistoryProjector.EVENT_IMPORTED) >= 4, "one per batch and tenant of each projected table");
        assertEquals(1, count("SELECT COUNT(*) FROM AUDIT_EVENT WHERE chain_id=? AND event_type=?", chain,
                LegacyHistoryProjector.EVENT_COMPLETED));
        assertEquals(0, count("SELECT COUNT(*) FROM AUDIT_EVENT WHERE chain_id NOT LIKE 'legacy:%'"), "no live chain touched");
        String payload = one("SELECT p.payload FROM AUDIT_PAYLOAD p JOIN AUDIT_EVENT e ON e.event_id = p.event_id"
                + " WHERE e.event_type=?", LegacyHistoryProjector.EVENT_OPERATION);
        assertTrue(payload.contains("\"operationType\":\"SetVariable\"") && payload.contains("\"userId\":\"alice\""), payload);
        assertTrue(payload.contains("\"legacyAt\":\"2026-01-15T09:30:00Z\""), payload);
        assertFalse(payload.contains("\"250\"") || payload.contains("\"100\""), "old and new values are hashed: " + payload);
        assertTrue(tx.inTransaction(c -> audit.verify(c, T, chain)).ok());
    }

    @Test
    void aSecondRunWritesNothingAndResumesFromTheCheckpoint() {
        corpus();
        List<Result> first = projector.project(JOB, BERLIN, 2, "ops", T);
        assertEquals(2, of(first, Source.ACT_HI_TASKINST).projected());
        long events = count("SELECT COUNT(*) FROM AUDIT_EVENT WHERE event_type <> ? AND event_type <> ?",
                LegacyHistoryProjector.EVENT_STARTED, LegacyHistoryProjector.EVENT_COMPLETED);
        List<Result> second = projector.project(JOB, BERLIN, 2, "ops", T);
        for (Result r : second) {
            assertEquals(0, r.projected(), r.toString());
        }
        assertEquals(events, count("SELECT COUNT(*) FROM AUDIT_EVENT WHERE event_type <> ? AND event_type <> ?",
                LegacyHistoryProjector.EVENT_STARTED, LegacyHistoryProjector.EVENT_COMPLETED), "no operation audited twice");
        assertEquals(2, count("SELECT COUNT(*) FROM HRM_TASK"));
        assertEquals("DONE", one("SELECT status FROM OPS_MIG_CHECKPOINT WHERE job_id=? AND source_table=?", JOB,
                LegacyHistoryProjector.CHECKPOINT_PREFIX + "ACT_HI_TASKINST"));
    }

    @Test
    void aDryRunWritesNothing() {
        corpus();
        List<Result> dry = projector.dryRun(JOB, BERLIN);
        assertEquals(2, of(dry, Source.ACT_HI_ACTINST).projected());
        assertEquals(1, of(dry, Source.ACT_HI_OP_LOG).projected());
        assertEquals(0, count("SELECT COUNT(*) FROM HRM_PROCESS_INSTANCE"));
        assertEquals(0, count("SELECT COUNT(*) FROM AUDIT_EVENT"));
        assertEquals(0, count("SELECT COUNT(*) FROM OPS_MIG_CHECKPOINT"));
        projector.project(JOB, BERLIN, 10, "ops", T);
        List<Result> after = projector.dryRun(JOB, BERLIN);
        assertEquals(2, of(after, Source.ACT_HI_ACTINST).alreadyPresent());
        assertEquals(1, of(after, Source.ACT_HI_OP_LOG).alreadyPresent());
        assertEquals(0, of(after, Source.ACT_HI_OP_LOG).projected());
    }

    @Test
    void daylightSavingGapsOverlapsAndUnknownStatesAreRecordedNotGuessed() {
        // 2026-03-29 02:30 does not exist in Berlin; 2026-10-25 02:30 happens twice
        row("ACT_HI_PROCINST", "gap", "id_", "gap", "proc_inst_id_", "gap", "start_time_", "2026-03-29T02:30:00Z");
        row("ACT_HI_PROCINST", "lap", "id_", "lap", "proc_inst_id_", "lap", "start_time_", "2026-10-25T02:30:00Z");
        row("ACT_HI_PROCINST", "ok", "id_", "ok", "proc_inst_id_", "ok", "start_time_", "2026-10-25T04:30:00Z");
        row("ACT_HI_CASEINST", "c9", "id_", "c9", "case_inst_id_", "c9", "case_def_id_", "x", "state_", 42,
                "create_time_", "2026-02-01T08:00:00Z");
        List<Result> r = projector.project(JOB, BERLIN, 10, "ops", T);
        Result pi = of(r, Source.ACT_HI_PROCINST);
        assertEquals(1, pi.projected());
        assertEquals(2, pi.unresolved());
        assertTrue(pi.unresolvedIds().contains("gap DST_GAP") && pi.unresolvedIds().contains("lap DST_OVERLAP"),
                pi.unresolvedIds().toString());
        assertEquals(List.of("c9 UNKNOWN_STATE"), of(r, Source.ACT_HI_CASEINST).unresolvedIds());
        assertNull(one("SELECT id FROM HRM_PROCESS_INSTANCE WHERE id=?", LegacyHistoryProjector.legacyId(JOB, "instance", "gap")));
        assertEquals(3, count("SELECT COUNT(*) FROM AUDIT_EVENT WHERE event_type=?", LegacyHistoryProjector.EVENT_UNRESOLVED));
        assertEquals("UNRESOLVED", one("SELECT status FROM OPS_MIG_CHECKPOINT WHERE job_id=? AND source_table=?", JOB,
                LegacyHistoryProjector.CHECKPOINT_PREFIX + "ACT_HI_PROCINST"));
        assertEquals("2", one("SELECT mismatched FROM OPS_MIG_CHECKPOINT WHERE job_id=? AND source_table=?", JOB,
                LegacyHistoryProjector.CHECKPOINT_PREFIX + "ACT_HI_PROCINST"));
    }

    @Test
    void aFixedOffsetIsNotASourceZone() {
        assertThrows(IllegalArgumentException.class, () -> projector.project(JOB, ZoneOffset.ofHours(1), 10, "ops", T));
        assertThrows(IllegalArgumentException.class, () -> projector.dryRun(JOB, ZoneOffset.UTC));
        assertThrows(IllegalArgumentException.class, () -> projector.project(JOB, BERLIN, 10, "ops", " "), "audit tenant");
        assertEquals(ZoneId.of("UTC"), LegacyHistoryProjector.requireNamedZone(ZoneId.of("UTC")), "the UTC region is named");
        assertNotEquals(LegacyHistoryProjector.legacyId("j1", "instance", "p1"), LegacyHistoryProjector.legacyId("j2", "instance", "p1"),
                "two import jobs never share ids");
    }
}
