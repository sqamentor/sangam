// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.pipeline;

import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;
import org.sangam.integration.connector.Json;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Run history, step checkpoints and schedules (N11). A run moves QUEUED → RUNNING → SUCCEEDED | FAILED | FAILED_QUALITY.
 * The claim is fenced by {@code epoch}: a node whose heartbeat expired loses the run, and its next write fails with
 * {@link LostClaimException}.
 */
public final class PipelineStore {

    public static final class LostClaimException extends RuntimeException {
        public LostClaimException(String runId) {
            super("pipeline run claim lost: " + runId);
        }
    }

    public record Run(String runId, String tenantId, String pipelineId, String version, String definitionJson, String paramsJson,
                      String state, String trigger, long epoch, long recordsIn, long recordsOut, long rejected, String error,
                      Instant createdAt, Instant startedAt, Instant endedAt) {
    }

    public record StepRun(String runId, String stepId, int stepIndex, String state, long recordsIn, long recordsOut, long rejected,
                          String outputRef, long checkpoint, String error) {
    }

    public record Schedule(String tenantId, String pipelineId, String cron, String zone, String paramsJson, Instant nextRunAt) {
    }

    private final Dialect dialect;
    private final Transactions tx;

    public PipelineStore(Dialect dialect, Transactions tx) {
        this.dialect = dialect;
        this.tx = tx;
    }

    /** Queues a run in the caller's transaction; false when the run id exists (idempotent start). */
    public boolean enqueue(Connection c, String runId, String tenant, PipelineDefinition def, Map<String, Object> params, String trigger) {
        return Jdbc.insertIfAbsent(c, dialect, "INSERT INTO PIPELINE_RUN(run_id, tenant_id, pipeline_id, version, definition_json, params_json,"
                        + " state, trigger_kind, epoch, records_in, records_out, rejected, created_at)"
                        + " VALUES (?,?,?,?,?,?,'QUEUED',?,0,0,0,0," + dialect.now() + ")",
                runId, tenant, def.id(), def.version(), def.toJson(), Json.write(params == null ? Map.of() : params), trigger);
    }

    public boolean enqueue(String runId, String tenant, PipelineDefinition def, Map<String, Object> params, String trigger) {
        return tx.inTransaction(c -> enqueue(c, runId, tenant, def, params, trigger));
    }

    /** Takes a QUEUED run, or a RUNNING one whose heartbeat expired. Returns the new epoch, or -1. */
    public long claim(String runId, String node, Duration ttl) {
        return tx.inTransaction(c -> {
            int n = Jdbc.update(c, "UPDATE PIPELINE_RUN SET state='RUNNING', owner_node=?, epoch=epoch+1, heartbeat_expires_at="
                            + dialect.nowPlusMillisParam() + ", started_at=COALESCE(started_at," + dialect.now() + ") WHERE run_id=?"
                            + " AND (state='QUEUED' OR (state='RUNNING' AND heartbeat_expires_at < " + dialect.now() + "))",
                    node, ttl.toMillis(), runId);
            return n == 0 ? -1L : Jdbc.one(c, "SELECT epoch FROM PIPELINE_RUN WHERE run_id=?", rs -> rs.getLong(1), runId).orElse(-1L);
        });
    }

    public void heartbeat(String runId, long epoch, Duration ttl) {
        int n = tx.inTransaction(c -> Jdbc.update(c, "UPDATE PIPELINE_RUN SET heartbeat_expires_at=" + dialect.nowPlusMillisParam()
                + " WHERE run_id=? AND epoch=? AND state='RUNNING'", ttl.toMillis(), runId, epoch));
        if (n == 0) {
            throw new LostClaimException(runId);
        }
    }

    public void finish(String runId, long epoch, String state, long in, long out, long rejected, String error) {
        int n = tx.inTransaction(c -> Jdbc.update(c, "UPDATE PIPELINE_RUN SET state=?, records_in=?, records_out=?, rejected=?, error=?, ended_at="
                + dialect.now() + " WHERE run_id=? AND epoch=? AND state='RUNNING'", state, in, out, rejected, truncate(error), runId, epoch));
        if (n == 0) {
            throw new LostClaimException(runId);
        }
    }

    /** Operator restart: FAILED → QUEUED; completed steps stay DONE, so the run resumes from the last good step. */
    public boolean requeueFailed(String runId) {
        return tx.inTransaction(c -> Jdbc.update(c, "UPDATE PIPELINE_RUN SET state='QUEUED', error=NULL, ended_at=NULL WHERE run_id=?"
                + " AND state IN ('FAILED','FAILED_QUALITY')", runId)) == 1;
    }

    public Optional<Run> run(String runId) {
        return tx.inTransaction(c -> Jdbc.one(c, "SELECT * FROM PIPELINE_RUN WHERE run_id=?", PipelineStore::mapRun, runId));
    }

    /** Runs to pick up: queued, or running with an expired heartbeat. */
    public List<String> runnable(int limit) {
        return tx.inTransaction(c -> Jdbc.query(c, "SELECT run_id FROM PIPELINE_RUN WHERE state='QUEUED' OR (state='RUNNING' AND"
                + " heartbeat_expires_at < " + dialect.now() + ") ORDER BY created_at", rs -> rs.getString(1))).stream().limit(limit).toList();
    }

    public List<Run> history(String tenant, String pipelineId, int limit) {
        return tx.inTransaction(c -> Jdbc.query(c, "SELECT * FROM PIPELINE_RUN WHERE tenant_id=? AND pipeline_id=? ORDER BY created_at DESC",
                PipelineStore::mapRun, tenant, pipelineId)).stream().limit(limit).toList();
    }

    public List<StepRun> steps(String runId) {
        return tx.inTransaction(c -> Jdbc.query(c, "SELECT * FROM PIPELINE_STEP_RUN WHERE run_id=? ORDER BY step_index", PipelineStore::mapStep, runId));
    }

    public Optional<StepRun> step(String runId, String stepId) {
        return tx.inTransaction(c -> step(c, runId, stepId));
    }

    private Optional<StepRun> step(Connection c, String runId, String stepId) {
        return Jdbc.one(c, "SELECT * FROM PIPELINE_STEP_RUN WHERE run_id=? AND step_id=?", PipelineStore::mapStep, runId, stepId);
    }

    public void stepStarted(String runId, String stepId, int index) {
        tx.inTransaction(c -> {
            Jdbc.insertIfAbsent(c, dialect, "INSERT INTO PIPELINE_STEP_RUN(run_id, step_id, step_index, state, records_in, records_out, rejected,"
                    + " checkpoint_no, started_at) VALUES (?,?,?,'RUNNING',0,0,0,0," + dialect.now() + ")", runId, stepId, index);
            return Jdbc.update(c, "UPDATE PIPELINE_STEP_RUN SET state='RUNNING', error=NULL WHERE run_id=? AND step_id=?", runId, stepId);
        });
    }

    /** Checkpoint in the caller's transaction, fenced by the run epoch (atomic with an outbox load). */
    public void checkpoint(Connection c, String runId, long epoch, String stepId, long chunk, long in, long out, long rejected) {
        fence(c, runId, epoch);
        Jdbc.update(c, "UPDATE PIPELINE_STEP_RUN SET checkpoint_no=?, records_in=?, records_out=?, rejected=? WHERE run_id=? AND step_id=?",
                chunk, in, out, rejected, runId, stepId);
    }

    public void checkpoint(String runId, long epoch, String stepId, long chunk, long in, long out, long rejected) {
        tx.inTransaction(c -> {
            checkpoint(c, runId, epoch, stepId, chunk, in, out, rejected);
            return null;
        });
    }

    public void stepDone(String runId, long epoch, String stepId, long in, long out, long rejected, String outputRef) {
        tx.inTransaction(c -> {
            fence(c, runId, epoch);
            return Jdbc.update(c, "UPDATE PIPELINE_STEP_RUN SET state='DONE', records_in=?, records_out=?, rejected=?, output_ref=?, ended_at="
                    + dialect.now() + " WHERE run_id=? AND step_id=?", in, out, rejected, outputRef, runId, stepId);
        });
    }

    public void stepFailed(String runId, String stepId, String error) {
        tx.inTransaction(c -> Jdbc.update(c, "UPDATE PIPELINE_STEP_RUN SET state='FAILED', error=?, ended_at=" + dialect.now()
                + " WHERE run_id=? AND step_id=?", truncate(error), runId, stepId));
    }

    private void fence(Connection c, String runId, long epoch) {
        if (Jdbc.one(c, "SELECT 1 FROM PIPELINE_RUN WHERE run_id=? AND epoch=? AND state='RUNNING'", rs -> 1, runId, epoch).isEmpty()) {
            throw new LostClaimException(runId);
        }
    }

    /** Metrics: run counts per state and record totals for a tenant, from the run history (all nodes). */
    public Map<String, Long> metrics(String tenant, String pipelineIdOrNull) {
        return tx.inTransaction(c -> {
            Map<String, Long> out = new LinkedHashMap<>();
            String where = " WHERE tenant_id=?" + (pipelineIdOrNull == null ? "" : " AND pipeline_id=?");
            Object[] args = pipelineIdOrNull == null ? new Object[]{tenant} : new Object[]{tenant, pipelineIdOrNull};
            Jdbc.query(c, "SELECT state, COUNT(*), SUM(records_in), SUM(records_out), SUM(rejected) FROM PIPELINE_RUN" + where + " GROUP BY state",
                    rs -> {
                        String s = rs.getString(1);
                        out.put("pipeline_runs_total{state=" + s + "}", rs.getLong(2));
                        out.merge("pipeline_records_in_total", rs.getLong(3), Long::sum);
                        out.merge("pipeline_records_out_total", rs.getLong(4), Long::sum);
                        out.merge("pipeline_records_rejected_total", rs.getLong(5), Long::sum);
                        return 1;
                    }, args);
            return out;
        });
    }

    // ------------------------------------------------------------------ schedules

    public void saveSchedule(String tenant, String pipelineId, String cron, String zone, Map<String, Object> params, Instant nextRunAt) {
        tx.inTransaction(c -> {
            boolean inserted = Jdbc.insertIfAbsent(c, dialect, "INSERT INTO PIPELINE_SCHEDULE(tenant_id, pipeline_id, cron, zone_id, params_json,"
                            + " next_run_at, enabled) VALUES (?,?,?,?,?,?,1)", tenant, pipelineId, cron, zone, Json.write(params == null ? Map.of() : params),
                    nextRunAt);
            if (!inserted) {
                Jdbc.update(c, "UPDATE PIPELINE_SCHEDULE SET cron=?, zone_id=?, params_json=?, next_run_at=?, enabled=1 WHERE tenant_id=? AND pipeline_id=?",
                        cron, zone, Json.write(params == null ? Map.of() : params), nextRunAt, tenant, pipelineId);
            }
            return null;
        });
    }

    public void disableSchedule(String tenant, String pipelineId) {
        tx.inTransaction(c -> Jdbc.update(c, "UPDATE PIPELINE_SCHEDULE SET enabled=0 WHERE tenant_id=? AND pipeline_id=?", tenant, pipelineId));
    }

    List<Schedule> dueSchedules(Connection c, Instant now) {
        return Jdbc.query(c, "SELECT tenant_id, pipeline_id, cron, zone_id, params_json, next_run_at FROM PIPELINE_SCHEDULE WHERE enabled=1 AND next_run_at <= ?",
                rs -> new Schedule(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), Jdbc.instant(rs, "next_run_at")), now);
    }

    void advanceSchedule(Connection c, Schedule s, Instant next) {
        Jdbc.update(c, "UPDATE PIPELINE_SCHEDULE SET next_run_at=? WHERE tenant_id=? AND pipeline_id=?", next, s.tenantId(), s.pipelineId());
    }

    private static Run mapRun(ResultSet rs) throws SQLException {
        return new Run(rs.getString("run_id"), rs.getString("tenant_id"), rs.getString("pipeline_id"), rs.getString("version"),
                rs.getString("definition_json"), rs.getString("params_json"), rs.getString("state"), rs.getString("trigger_kind"),
                rs.getLong("epoch"), rs.getLong("records_in"), rs.getLong("records_out"), rs.getLong("rejected"), rs.getString("error"),
                Jdbc.instant(rs, "created_at"), Jdbc.instant(rs, "started_at"), Jdbc.instant(rs, "ended_at"));
    }

    private static StepRun mapStep(ResultSet rs) throws SQLException {
        return new StepRun(rs.getString("run_id"), rs.getString("step_id"), rs.getInt("step_index"), rs.getString("state"),
                rs.getLong("records_in"), rs.getLong("records_out"), rs.getLong("rejected"), rs.getString("output_ref"),
                rs.getLong("checkpoint_no"), rs.getString("error"));
    }

    private static String truncate(String s) {
        return s == null || s.length() <= 2000 ? s : s.substring(0, 2000);
    }

    /** Reference DDL for sangam-persistence (WS-ENGINE). */
    public static final List<String> DDL = List.of(
            """
            CREATE TABLE PIPELINE_RUN (
              run_id               {str64} NOT NULL PRIMARY KEY,
              tenant_id            {str64} NOT NULL,
              pipeline_id          {str64} NOT NULL,
              version              {str64},
              definition_json      {text} NOT NULL,
              params_json          {text},
              state                {str64} NOT NULL,
              trigger_kind         {str64},
              owner_node           {str64},
              epoch                {long} NOT NULL,
              heartbeat_expires_at {ts},
              records_in           {long} NOT NULL,
              records_out          {long} NOT NULL,
              rejected             {long} NOT NULL,
              error                {text},
              created_at           {ts} NOT NULL,
              started_at           {ts},
              ended_at             {ts}
            )""",
            "CREATE INDEX PIPELINE_RUN_STATE ON PIPELINE_RUN (state, created_at)",
            "CREATE INDEX PIPELINE_RUN_HISTORY ON PIPELINE_RUN (tenant_id, pipeline_id, created_at)",
            """
            CREATE TABLE PIPELINE_STEP_RUN (
              run_id        {str64} NOT NULL,
              step_id       {str64} NOT NULL,
              step_index    {int} NOT NULL,
              state         {str64} NOT NULL,
              records_in    {long} NOT NULL,
              records_out   {long} NOT NULL,
              rejected      {long} NOT NULL,
              output_ref    {str},
              checkpoint_no {long} NOT NULL,
              error         {text},
              started_at    {ts},
              ended_at      {ts},
              PRIMARY KEY (run_id, step_id)
            )""",
            """
            CREATE TABLE PIPELINE_SCHEDULE (
              tenant_id    {str64} NOT NULL,
              pipeline_id  {str64} NOT NULL,
              cron         {str64} NOT NULL,
              zone_id      {str64} NOT NULL,
              params_json  {text},
              next_run_at  {ts} NOT NULL,
              enabled      {small} NOT NULL,
              PRIMARY KEY (tenant_id, pipeline_id)
            )""");
}
