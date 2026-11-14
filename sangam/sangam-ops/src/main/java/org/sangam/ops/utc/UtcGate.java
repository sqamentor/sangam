// SPDX-License-Identifier: Apache-2.0
package org.sangam.ops.utc;

import java.sql.Connection;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;

/**
 * Integrator ruling 2026-11-13T04:07:40Z items 3 and 4 (UTC conversion race, option C): the engine-work gate, row
 * {@value #GATE_ID} of {@code OPS_UTC_GATE} (changesets 0045-13 and 0045-14). The UTC tool closes it in its own committed
 * transaction before a run moves to APPLYING or ROLLING_BACK, and reopens it in the transaction that moves the run to
 * APPLIED or ROLLED_BACK. Engine job acquisition and external-task fetch ({@code sangam-engine job/UtcConversionHardStop})
 * read the row with a lock held to commit in the transaction that writes their locks and do no work while it is CLOSED.
 * The close is an UPDATE: its exclusive row lock waits for every acquisition or fetch in flight, so each lock they write
 * is committed, and visible to {@link EngineLocks}, before the close commits; none can commit a lock after it.
 * <p>
 * One gate for all tables: one UTC run at a time changes rows. A close succeeds on an OPEN gate, or on a gate already
 * closed by the same run (a resumed apply, or the rollback of an interrupted apply); any other run is refused.
 */
final class UtcGate {

    static final String GATE_ID = "ENGINE_WORK";
    static final String OPEN = "OPEN";
    static final String CLOSED = "CLOSED";
    /**
     * Audit events on the gate's own chain {@value #CHAIN} (one history of the shared gate across runs; the payload
     * names the run). Names proposed to INT-H2 2026-11-13T04:11:54Z.
     */
    static final String CHAIN = "utc-conversion-gate:" + GATE_ID;
    static final String EVENT_CLOSED = "UTC_CONVERSION_GATE_CLOSED";
    static final String EVENT_OPENED = "UTC_CONVERSION_GATE_OPENED";

    /** The gate as stored: state, the run that closed it (null when OPEN) and the row version. */
    record Gate(String state, String runId, long version) {
    }

    private UtcGate() {
    }

    static Optional<Gate> read(Connection c) {
        return Jdbc.one(c, "SELECT state, run_id, version FROM OPS_UTC_GATE WHERE gate_id = ?",
                rs -> new Gate(rs.getString(1), rs.getString(2), rs.getLong(3)), GATE_ID);
    }

    /**
     * Closes the gate for {@code runId} in the caller's transaction, which the caller commits on its own before the run
     * changes state; audited. Throws {@link IllegalStateException} when another run holds the gate or the row is missing.
     */
    static void close(Connection c, Dialect dialect, AuditChain audit, String auditTenant, String runId, String actor,
                      String purpose) {
        Instant now = Jdbc.dbNow(c, dialect);
        int n = Jdbc.update(c, "UPDATE OPS_UTC_GATE SET state = '" + CLOSED + "', run_id = ?, changed_by = ?, changed_at = ?,"
                + " version = version + 1 WHERE gate_id = ? AND (state = '" + OPEN + "' OR (state = '" + CLOSED
                + "' AND run_id = ?))", runId, actor, now, GATE_ID, runId);
        if (n != 1) {
            throw new IllegalStateException(read(c).map(g -> "the engine-work gate is " + g.state() + " by run "
                    + g.runId() + ": one UTC run changes rows at a time; finish or roll back that run first")
                    .orElse("the engine-work gate row " + GATE_ID + " is missing from OPS_UTC_GATE (changesets 0045-13"
                            + " and 0045-14): apply the schema first"));
        }
        audit.append(c, auditTenant, CHAIN, EVENT_CLOSED, payload(c, runId, actor, purpose));
    }

    /**
     * Reopens the gate closed by {@code runId} in the caller's transaction; audited. Throws {@link IllegalStateException}
     * when the gate is not closed by that run (nothing is reopened for another run).
     */
    static void open(Connection c, Dialect dialect, AuditChain audit, String auditTenant, String runId, String actor,
                     String purpose) {
        Instant now = Jdbc.dbNow(c, dialect);
        int n = Jdbc.update(c, "UPDATE OPS_UTC_GATE SET state = '" + OPEN + "', run_id = NULL, changed_by = ?, changed_at = ?,"
                + " version = version + 1 WHERE gate_id = ? AND state = '" + CLOSED + "' AND run_id = ?",
                actor, now, GATE_ID, runId);
        if (n != 1) {
            throw new IllegalStateException("the engine-work gate is not closed by run " + runId + ": "
                    + read(c).map(Gate::toString).orElse("row missing"));
        }
        audit.append(c, auditTenant, CHAIN, EVENT_OPENED, payload(c, runId, actor, purpose));
    }

    private static Map<String, Object> payload(Connection c, String runId, String actor, String purpose) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("runId", runId);
        p.put("actor", actor);
        p.put("gateId", GATE_ID);
        p.put("purpose", purpose);
        p.put("version", read(c).map(Gate::version).orElse(-1L));
        return p;
    }
}
