// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.adapter.operaton;

import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.WorkPort.FencedLease;

/**
 * The step journal behind {@code context.step(name, fn)} (M-025, R37 / E02): one {@code WORK_STEP} row per
 * {@code (operationId, step name)}, so a later attempt of the same operation (retry, redelivery, lease expiry) sees what
 * an earlier attempt recorded. Every write runs on the connection of the transaction that checked the lease
 * ({@code WorkPort.inFencedWork}), so a stale lease records nothing (VI.3 rule 2).
 *
 * <p>States and transitions (the contract's {@code WorkStepRequest}):
 * <ul>
 *   <li>{@code INTENT} is recorded before the remote call: allowed on a new step, on an {@code INTENT} of an earlier
 *       attempt, and on a {@code FAILED} step (a failure without effect may be run again);</li>
 *   <li>{@code SUCCEEDED} / {@code FAILED} need an {@code INTENT} first ({@code 409 STEP_INTENT_REQUIRED});</li>
 *   <li>{@code SUCCEEDED} is final: an identical repeat is a no-op, anything else is {@code 409 STEP_ALREADY_RECORDED};</li>
 *   <li>an identical repeat of the current state by the same attempt is a no-op.</li>
 * </ul>
 * NOT exactly-once (R37): a crash between the remote call and the outcome leaves {@code INTENT}; the caller then looks
 * the outcome up or reports outcome-unknown, never re-issues blindly. The result is personal data like
 * {@code CONNECTOR_CALL.request_json}; retention and erasure follow R29/R42.
 */
public final class WorkStepJournal {

    /** Step states of the contract ({@code WorkStep.state}). */
    public enum State { INTENT, SUCCEEDED, FAILED }

    /** One recorded step; {@code resultJson} is the JSON text of the recorded result (null when none). */
    public record Step(UUID operationId, String name, String tenantId, String workId, int seq, State state,
                       String resultJson, String providerRef, String error, String attemptId, long lockVersion,
                       Instant recordedAt) {
    }

    /** Longest step name (contract: 1-128 printable ASCII). */
    public static final int MAX_NAME = 128;
    static final int MAX_ERROR = 2000;
    static final int MAX_PROVIDER_REF = 255;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String COLUMNS = "operation_id, step_name, tenant_id, work_id, step_seq, state, result_json, "
            + "provider_ref, error, attempt_id, lock_version, recorded_at";

    private final Dialect dialect;

    public WorkStepJournal(Dialect dialect) {
        this.dialect = Objects.requireNonNull(dialect, "dialect");
    }

    /** {@code 400 VALIDATION_FAILED} unless {@code name} is 1-128 printable ASCII characters. */
    public static String requireName(String name) {
        if (name == null || name.isEmpty() || name.length() > MAX_NAME) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "stepName must be 1-" + MAX_NAME + " characters");
        }
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            if (ch < 0x20 || ch > 0x7e) {
                throw ApiProblem.badRequest("VALIDATION_FAILED", "stepName must be printable ASCII");
            }
        }
        return name;
    }

    /** The step as recorded under the lease's operation, or empty. */
    public Optional<Step> find(Connection c, UUID operationId, String name) {
        return Jdbc.one(c, "SELECT " + COLUMNS + " FROM WORK_STEP WHERE operation_id = ? AND step_name = ?",
                WorkStepJournal::row, operationId.toString(), name);
    }

    /**
     * Records {@code state} of step {@code name} under {@code lease.operationId()} (see the class comment for the
     * transitions). {@code result} is any JSON value; it is stored as JSON text and replayed as is.
     *
     * @return the step as it stands after the call (unchanged on an identical repeat)
     */
    public Step record(Connection c, FencedLease lease, String name, State state, Object result, String providerRef,
                       String error) {
        Objects.requireNonNull(lease.operationId(), "operationId");
        Objects.requireNonNull(state, "state");
        String json = toJson(result);
        String ref = truncate(providerRef, MAX_PROVIDER_REF);
        String err = truncate(error, MAX_ERROR);
        Optional<Step> current = find(c, lease.operationId(), name);
        if (current.isEmpty()) {
            if (state != State.INTENT) {
                throw intentRequired(name, null);
            }
            insert(c, lease, name, json, ref, err);
            return find(c, lease.operationId(), name).orElseThrow();
        }
        Step s = current.get();
        if (identical(s, lease, state, json, ref, err)) {
            return s;   // an identical repeat (a retried request) is a no-op
        }
        if (s.state() == State.SUCCEEDED) {
            throw ApiProblem.conflict("STEP_ALREADY_RECORDED", "step " + name + " already SUCCEEDED for operation "
                    + lease.operationId() + "; its recorded result is final");
        }
        if (state != State.INTENT && s.state() != State.INTENT) {
            throw intentRequired(name, s.state());
        }
        Jdbc.update(c, "UPDATE WORK_STEP SET state = ?, result_json = ?, provider_ref = ?, error = ?, attempt_id = ?, "
                        + "lock_version = ?, work_id = ?, recorded_at = " + dialect.now()
                        + " WHERE operation_id = ? AND step_name = ?",
                state.name(), json, ref, err, lease.attemptId(), lease.lockVersion(), lease.workId(),
                lease.operationId().toString(), name);
        return find(c, lease.operationId(), name).orElseThrow();
    }

    /** Every step of {@code operationId} in recording order; {@code tenantId} null = no tenant filter. */
    public List<Step> listByOperation(Connection c, String tenantId, UUID operationId) {
        return tenantId == null
                ? Jdbc.query(c, "SELECT " + COLUMNS + " FROM WORK_STEP WHERE operation_id = ? ORDER BY step_seq",
                        WorkStepJournal::row, operationId.toString())
                : Jdbc.query(c, "SELECT " + COLUMNS + " FROM WORK_STEP WHERE operation_id = ? AND tenant_id = ? "
                        + "ORDER BY step_seq", WorkStepJournal::row, operationId.toString(), tenantId);
    }

    /**
     * Every step recorded by the work item {@code workId} (when its operation can no longer be resolved, e.g. after
     * completion), in recording order; {@code tenantId} null = no tenant filter.
     */
    public List<Step> listByWork(Connection c, String tenantId, String workId) {
        return tenantId == null
                ? Jdbc.query(c, "SELECT " + COLUMNS + " FROM WORK_STEP WHERE work_id = ? ORDER BY operation_id, step_seq",
                        WorkStepJournal::row, workId)
                : Jdbc.query(c, "SELECT " + COLUMNS + " FROM WORK_STEP WHERE work_id = ? AND tenant_id = ? "
                        + "ORDER BY operation_id, step_seq", WorkStepJournal::row, workId, tenantId);
    }

    /** The recorded result as a JSON value (maps, lists, strings, numbers, booleans), or null. */
    public static Object result(Step s) {
        if (s.resultJson() == null) {
            return null;
        }
        try {
            return JSON.readValue(s.resultJson(), Object.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("WORK_STEP " + s.operationId() + "/" + s.name() + ": result is not JSON", e);
        }
    }

    // ------------------------------------------------------------------ helpers

    private void insert(Connection c, FencedLease lease, String name, String json, String ref, String err) {
        // the lease row lock serialises the steps of one work item, so MAX + 1 is the recording order
        int seq = Jdbc.one(c, "SELECT COALESCE(MAX(step_seq), 0) FROM WORK_STEP WHERE operation_id = ?",
                rs -> rs.getInt(1), lease.operationId().toString()).orElse(0) + 1;
        boolean inserted = Jdbc.insertIfAbsent(c, dialect, "INSERT INTO WORK_STEP (" + COLUMNS + ", created_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?," + dialect.now() + "," + dialect.now() + ")",
                lease.operationId().toString(), name, lease.tenantId(), lease.workId(), seq, State.INTENT.name(), json,
                ref, err, lease.attemptId(), lease.lockVersion());
        if (!inserted) {
            // another work item of the same operation recorded it first (the lease fences one work item only)
            throw ApiProblem.conflict("STEP_CONCURRENTLY_RECORDED", "step " + name + " of operation "
                    + lease.operationId() + " was recorded concurrently; read it again");
        }
    }

    private static boolean identical(Step s, FencedLease lease, State state, String json, String ref, String err) {
        if (s.state() != state) {
            return false;
        }
        if (state == State.SUCCEEDED) {   // final: the same outcome again, by any attempt
            return sameJson(s.resultJson(), json) && Objects.equals(s.providerRef(), ref);
        }
        return Objects.equals(s.attemptId(), lease.attemptId()) && sameJson(s.resultJson(), json)
                && Objects.equals(s.providerRef(), ref) && Objects.equals(s.error(), err);
    }

    /** JSON equality that ignores object key order and formatting. */
    static boolean sameJson(String a, String b) {
        if (a == null || b == null) {
            return a == null && b == null;
        }
        try {
            return JSON.readTree(a).equals(JSON.readTree(b));
        } catch (JsonProcessingException e) {
            return a.equals(b);
        }
    }

    private static String toJson(Object result) {
        if (result == null) {
            return null;
        }
        try {
            return JSON.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "result is not serialisable as JSON");
        }
    }

    private static ApiProblem intentRequired(String name, State current) {
        return ApiProblem.conflict("STEP_INTENT_REQUIRED", "step " + name + " has no INTENT"
                + (current == null ? "" : " (it is " + current + ")") + "; record INTENT before its outcome");
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    private static Step row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Step(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getInt(5), State.valueOf(rs.getString(6)), rs.getString(7), rs.getString(8), rs.getString(9),
                rs.getString(10), rs.getLong(11), Jdbc.instant(rs, "recorded_at"));
    }
}
