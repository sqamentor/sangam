// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.trace;

import java.sql.Connection;
import java.util.Optional;
import java.util.regex.Pattern;

import org.sangam.core.layer.Jdbc;

/**
 * M-044 (decision 57, contract {@code ProcessInstance.traceId}): the W3C trace id of the command that started a process
 * instance, in the {@code PROCESS_TRACE} side table (W3-F REQUEST 2026-11-12T14:17:20Z: {@code process_instance_id}
 * primary key, {@code tenant_id}, {@code root_process_instance_id}, {@code trace_id CHAR(32)}, {@code created_at_ms}).
 * Plain SQL on the caller's connection, in the caller's transaction; nothing here commits.
 *
 * <p>Only a valid trace id is ever stored or returned (32 lowercase hex, not all zeros); there is no fallback value:
 * no current trace means no row, and a read without a row is empty.
 */
public final class ProcessTraces {

    /** W3C trace-id: 32 lowercase hex characters, all zeros invalid. */
    static final Pattern TRACE_ID = Pattern.compile("[0-9a-f]{32}");
    private static final String INVALID = "00000000000000000000000000000000";

    private ProcessTraces() {
    }

    /** {@code traceId} when it is a valid W3C trace id, else {@code null}. */
    public static String valid(String traceId) {
        return traceId != null && TRACE_ID.matcher(traceId).matches() && !INVALID.equals(traceId) ? traceId : null;
    }

    /** The trace id of a W3C context ({@code 00-<trace id>-<span id>-<flags>}), or {@code null}. */
    public static String traceIdOf(EngineTracing.TraceContext ctx) {
        return ctx == null ? null : valid(ctx.traceparent().substring(3, 35));
    }

    /**
     * Records the starting command's trace id of an instance; does nothing without a valid one. An instance is
     * recorded once (its start), so a second call for the same id is ignored.
     */
    public static void record(Connection c, String processInstanceId, String tenantId, String rootProcessInstanceId,
                              String traceId, long createdAtMs) {
        String id = valid(traceId);
        if (id == null || processInstanceId == null) {
            return;
        }
        if (Jdbc.one(c, "SELECT 1 FROM PROCESS_TRACE WHERE process_instance_id = ?", rs -> 1, processInstanceId)
                .isPresent()) {
            return;
        }
        Jdbc.update(c, "INSERT INTO PROCESS_TRACE (process_instance_id, tenant_id, root_process_instance_id, trace_id,"
                + " created_at_ms) VALUES (?,?,?,?,?)", processInstanceId, tenantId, rootProcessInstanceId, id, createdAtMs);
    }

    /** The recorded trace id of an instance; empty when none was recorded (or the stored value is not valid). */
    public static Optional<String> read(Connection c, String processInstanceId) {
        return Jdbc.one(c, "SELECT trace_id FROM PROCESS_TRACE WHERE process_instance_id = ?",
                rs -> Optional.ofNullable(valid(rs.getString(1))), processInstanceId).flatMap(o -> o);
    }

    /** Removes the rows of a root and every instance under it (the instance's history was removed). */
    public static int deleteRoot(Connection c, String rootProcessInstanceId) {
        return Jdbc.update(c, "DELETE FROM PROCESS_TRACE WHERE root_process_instance_id = ? OR process_instance_id = ?",
                rootProcessInstanceId, rootProcessInstanceId);
    }
}
